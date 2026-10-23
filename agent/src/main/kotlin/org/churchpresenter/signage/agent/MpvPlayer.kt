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
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * Keeps mpv on the screen showing [state]: starts it, sends it each change, and starts it again when
 * it exits or a refresh asks for it. A fresh mpv is sent the whole state; after that only the changes.
 */
class MpvPlayer(
    private val state: StateFlow<ScreenState>,
    private val launcher: Launcher,
    private val restartDelayMs: Long = RESTART_DELAY_MS,
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
                Log.error("mpv failed: ${e.message}")
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
                session.send(MpvScene.commands(shown, next))
                shown = next
            }
        }
        select {
            exited.onAwait { Log.warn("mpv exited; starting it again") }
            restartAsked.onAwait { Log.info("Restarting mpv on request") }
        }
        coroutineContext.cancelChildren()
    }

    companion object {
        const val RESTART_DELAY_MS = 2_000L
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
) : MpvPlayer.Launcher {
    /** The options whose names differ between the mpv versions the player runs on, read from mpv once. */
    private val versionArgs: List<String> by lazy {
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
        Thread({ process.inputReader().forEachLine { Log.warn("mpv: $it") } }, "mpv-output")
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

    private fun connect(process: Process): MpvIpc {
        var waited = 0L
        while (true) {
            try {
                return MpvIpc(if (windows) MpvIpc.NamedPipe(ipcPath) else MpvIpc.UnixSocket(ipcPath))
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
            // ffmpeg warns on every video while it looks for hardware the machine does not have.
            "--msg-level=all=warn,input=error,ffmpeg=error",
            "--screenshot-format=jpg",
            "--input-ipc-server=$ipcPath",
        )

        /**
         * What [baseArgs] leaves to the version: stay with hardware decoding through a live feed's
         * first frames (renamed in mpv 0.39), and draw a picture's see-through parts black rather
         * than as a checkerboard (mpv 0.38 moved that from `--alpha` to `--background`). [names] is
         * what `mpv --list-options` lists; with none, the older names, which later versions still take.
         */
        fun versionArgs(names: Set<String>): List<String> = listOfNotNull(
            if ("--hwdec-software-fallback" in names) "--hwdec-software-fallback=600"
            else "--vd-lavc-software-fallback=600",
            "--alpha=blend",
            "--background=color".takeIf { "--background-color" in names },
        )
    }
}
