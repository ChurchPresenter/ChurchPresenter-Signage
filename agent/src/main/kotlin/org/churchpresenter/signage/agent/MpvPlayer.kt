package org.churchpresenter.signage.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.future.await
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists

/**
 * Keeps mpv on the screen showing [state]: starts it, sends it each change, and starts it again when
 * it exits or a refresh asks for it. A fresh mpv is sent the whole state; after that only the changes.
 */
class MpvPlayer(
    private val state: StateFlow<ScreenState>,
    private val launcher: Launcher,
    /** A black picture, shown when there is nothing else; see [MpvScene.commands]. */
    private val blank: Path,
    /** Told each time mpv exits by itself or fails to start, not on a refresh. */
    private val onExit: () -> Unit = {},
    private val restartDelayMs: Long = RESTART_DELAY_MS,
    /** The local stream a web page is drawn into, from its URL; see [WebRenderer.streamUrl]. */
    private val webStream: (String) -> String = { it },
) {
    /** A running mpv the player can drive. */
    interface Session : Closeable {
        fun send(commands: List<JsonElement>)

        /** Writes what mpv draws now to [file] as a JPEG; false if it could not. */
        suspend fun screenshot(file: Path): Boolean

        /** Returns when mpv has exited. */
        suspend fun awaitExit()
    }

    fun interface Launcher {
        /** Starts mpv and connects to it; throws [IOException] if it cannot. */
        fun start(): Session
    }

    @Volatile
    private var current: Session? = null

    /** What the screen shows now, as a JPEG in [file]; false when mpv is not up or could not. */
    suspend fun screenshot(file: Path): Boolean = current?.screenshot(file) ?: false

    /** Runs until cancelled. */
    suspend fun run() {
        while (true) {
            try {
                launcher.start().use { session -> driveAsCurrent(session) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Retried every few seconds: a warning, counted as an exit, so a screen that cannot
                // start mpv files one report an hour rather than one each try.
                Log.warn("mpv failed: ${e.message}")
                onExit()
            }
            delay(restartDelayMs)
        }
    }

    private suspend fun driveAsCurrent(session: Session) {
        current = session
        try {
            drive(session)
        } finally {
            current = null
        }
    }

    private suspend fun drive(session: Session) = coroutineScope {
        val token = state.value.restartToken
        var shown: ScreenState? = null
        val exited = async { session.awaitExit() }
        val restartAsked = async {
            state.takeWhile { it.restartToken == token }.collect { next ->
                session.send(MpvScene.commands(shown, next, blank, webStream))
                shown = next
            }
        }
        select {
            exited.onAwait {
                Log.warn("mpv exited; starting it again")
                onExit()
            }
            restartAsked.onAwait { Log.info("Restarting mpv on request") }
        }
        coroutineContext.cancelChildren()
    }

    companion object {
        const val RESTART_DELAY_MS = 2_000L

        /** Writes [MpvPlayer]'s black picture to [file]: 16×9 pixels, which mpv scales to the screen. */
        fun writeBlank(file: Path): Path = file.also {
            ImageIO.write(BufferedImage(BLANK_WIDTH, BLANK_HEIGHT, BufferedImage.TYPE_INT_RGB), "png", it.toFile())
        }

        private const val BLANK_WIDTH = 16
        private const val BLANK_HEIGHT = 9
    }
}

/**
 * Starts the mpv at [executable] with the player's settings and [extraArgs] (the install's, e.g. how
 * to reach the screen), and connects to its IPC at [ipcPath].
 */
class MpvProcessLauncher(
    private val executable: String,
    private val extraArgs: List<String>,
    private val ipcPath: Path,
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
    /** Told when mpv could not play something it was given, such as a stream that does not answer. */
    private val onFileError: () -> Unit = {},
    /** Told when mpv played something to its end. */
    private val onFileEnded: () -> Unit = {},
) : MpvPlayer.Launcher {
    /** The options whose names differ between the mpv versions the player runs on, read from mpv once. */
    private val versionArgs: List<String> by lazy {
        version = try {
            ProcessBuilder(executable, "--version").redirectErrorStream(true).start()
                .inputReader().useLines { it.firstOrNull().orEmpty() }.substringBefore(" Copyright").trim()
        } catch (_: IOException) {
            ""
        }
        val names = try {
            val probe = ProcessBuilder(executable, "--list-options").redirectErrorStream(true).start()
            probe.inputReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.startsWith("--") }.map { it.substringBefore(' ') }.toSet()
            }.also { probe.waitFor() }
        } catch (_: IOException) {
            emptySet()
        }
        versionArgs(names)
    }

    private val output = ArrayDeque<String>()

    /** mpv's last lines of output, to go with a report that it keeps exiting. */
    fun recentOutput(): List<String> = synchronized(output) { output.toList() }

    /** mpv's version line, e.g. `mpv 0.37.0`, once it has been asked; "" until then. */
    @Volatile
    var version: String = ""
        private set

    /** The mpv running now, closed with the agent so no window outlives it. */
    @Volatile
    private var running: Process? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread { running?.destroy() })
    }

    override fun start(): MpvPlayer.Session {
        if (!windows) ipcPath.deleteIfExists()
        val process = ProcessBuilder(listOf(executable) + baseArgs(ipcPath) + versionArgs + extraArgs)
            .redirectErrorStream(true)
            .start()
        running = process
        Thread({ process.inputReader().forEachLine { line -> remember(line); Log.warn("mpv: $line") } }, "mpv-output")
            .apply { isDaemon = true }
            .start()
        val ipc = try {
            connect(process)
        } catch (e: IOException) {
            process.destroyForcibly()
            throw e
        }
        Log.info("mpv started (pid ${process.pid()})")
        return ProcessSession(process, ipc)
    }

    private fun remember(line: String) = synchronized(output) {
        output.addLast(line)
        while (output.size > KEPT_OUTPUT_LINES) output.removeFirst()
    }

    private fun connect(process: Process): MpvIpc {
        var waited = 0L
        while (true) {
            try {
                val transport = if (windows) MpvIpc.NamedPipe(ipcPath) else MpvIpc.UnixSocket(ipcPath)
                return MpvIpc(transport, onFileError, onFileEnded)
            } catch (e: IOException) {
                if (!process.isAlive) throw IOException("mpv exited with ${process.exitValue()}", e)
                if (waited >= CONNECT_TIMEOUT_MS) throw IOException("mpv's IPC did not open", e)
            }
            Thread.sleep(CONNECT_POLL_MS)
            waited += CONNECT_POLL_MS
        }
    }

    private class ProcessSession(private val process: Process, private val ipc: MpvIpc) : MpvPlayer.Session {
        override fun send(commands: List<JsonElement>) = ipc.send(commands)

        /** The window as drawn, text included; an output that cannot give that gives the picture alone. */
        override suspend fun screenshot(file: Path): Boolean = listOf("window", "video").any { flags ->
            ipc.request(
                buildJsonObject {
                    put("name", "screenshot-to-file")
                    put("filename", file.toString())
                    put("flags", flags)
                },
            )
        }

        override suspend fun awaitExit() {
            process.onExit().await()
        }

        override fun close() {
            ipc.close()
            process.destroy()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val CONNECT_POLL_MS = 100L
        private const val KEPT_OUTPUT_LINES = 30

        /**
         * Full screen, silent, nothing of mpv's own on screen and no keys: the agent is the only
         * thing that drives it. A live feed joined between keyframes would otherwise leave hardware
         * decoding for good after three failed frames; display-resample keeps a 60 fps video from
         * dropping frames on a 59.94 Hz screen.
         */
        fun baseArgs(ipcPath: Path): List<String> = listOf(
            "--no-config",
            "--idle=yes",
            "--force-window=yes",
            "--fullscreen",
            "--no-audio",
            "--osc=no",
            "--osd-level=0",
            "--no-input-default-bindings",
            "--input-vo-keyboard=no",
            "--cursor-autohide=always",
            "--stop-screensaver=yes",
            "--loop-playlist=inf",
            "--hwdec=auto-safe",
            "--video-sync=display-resample",
            // ffmpeg warns on every video while it looks for hardware the machine does not have, and
            // lavf on every web page's stream, which carries no timestamps by design.
            "--msg-level=all=warn,input=error,ffmpeg=error,lavf=error",
            "--screenshot-format=jpg",
            // A stream opened through yt-dlp (YouTube and the like): no larger than the screen.
            "--ytdl-format=bestvideo[height<=?1080]/best[height<=?1080]/best",
            "--input-ipc-server=$ipcPath",
        )

        /**
         * What [baseArgs] leaves to the version: stay with hardware decoding through a live feed's
         * first frames (renamed in mpv 0.39), and draw a picture's see-through parts black rather
         * than as a checkerboard (mpv 0.38 moved that from `--alpha` to `--background`, and 0.40 removed
         * `--alpha`, which it refuses to start with). [names] is what `mpv --list-options` lists; with
         * none, the older names.
         */
        fun versionArgs(names: Set<String>): List<String> = listOfNotNull(
            if ("--hwdec-software-fallback" in names) "--hwdec-software-fallback=600"
            else "--vd-lavc-software-fallback=600",
            "--alpha=blend".takeIf { names.isEmpty() || "--alpha" in names },
            "--background=color".takeIf { "--background-color" in names },
        )
    }
}
