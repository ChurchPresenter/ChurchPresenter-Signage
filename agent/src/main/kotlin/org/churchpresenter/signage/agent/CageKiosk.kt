package org.churchpresenter.signage.agent

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.churchpresenter.signage.core.Orientation
import org.churchpresenter.signage.core.SignageJson
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * The kiosk browser: Chromium full screen in cage, which takes the screen and the touchscreen from the
 * console session the agent runs in, as mpv does. A rotated screen is turned with wlr-randr, inside
 * cage, before Chromium starts; touches follow the output's turn.
 */
class CageKiosk(
    private val cage: String,
    private val chromium: String,
    private val client: HttpClient,
    /** wlr-randr, which turns the picture for a rotated screen; null where it is not installed. */
    private val wlrRandr: String? = find("wlr-randr"),
) : KioskSession.Browser {
    override suspend fun <T> open(
        profile: Path,
        orientation: Orientation,
        block: suspend (WebRenderer.Page) -> T,
    ): T {
        profile.createDirectories()
        val command = command(profile, orientation)
        val cursors = BlankCursor.write(profile.resolveSibling("churchpresenter-kiosk-cursor"))
        val process = Chromium.launcher(command)
            .apply {
                environment()["WLR_LIBINPUT_NO_DEVICES"] = "1"
                environment()["XCURSOR_PATH"] = cursors.toString()
            }
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        Log.info("Kiosk browser started (pid ${process.pid()})")
        try {
            val port = awaitPort(process, profile)
            val socketUrl = pageSocket(port)
            var result: Result<T>? = null
            client.webSocket(socketUrl) {
                coroutineScope {
                    val page = Chromium.DevToolsPage(this@webSocket)
                    val reader = launch { page.readLoop() }
                    try {
                        result = Result.success(block(page))
                    } finally {
                        reader.cancel()
                    }
                }
            }
            return result?.getOrThrow() ?: throw IOException("the kiosk browser closed its page")
        } finally {
            stop(process)
        }
    }

    /** cage with Chromium, through a shell that turns the output first when the screen is rotated. */
    private fun command(profile: Path, orientation: Orientation): List<String> {
        val degrees = orientation.clockwiseDegrees
        if (degrees == 0 || wlrRandr == null) {
            if (degrees != 0) Log.warn("wlr-randr is not installed: the interactive page is not turned")
            return KioskCommand.command(cage, chromium, profile)
        }
        val turn = "out=\$('$wlrRandr' | awk 'NR==1{print \$1}'); " +
            "'$wlrRandr' --output \"\$out\" --transform $degrees; exec \"\$@\""
        return listOf(cage) + KioskCommand.CAGE_ARGS + listOf("--", "/bin/sh", "-c", turn, "kiosk") +
            KioskCommand.CHROMIUM_ENV + chromium +
            KioskCommand.chromiumArgs(profile)
    }

    private suspend fun awaitPort(process: Process, profile: Path): Int {
        val portFile = profile.resolve("DevToolsActivePort")
        var waited = 0L
        while (waited < START_TIMEOUT_MS) {
            if (!process.isAlive) throw IOException("the kiosk browser exited with ${process.exitValue()}")
            val port = if (portFile.exists()) portFile.readText().lineSequence().firstOrNull()?.toIntOrNull() else null
            if (port != null) return port
            delay(POLL_MS)
            waited += POLL_MS
        }
        throw IOException("the kiosk browser did not open DevTools")
    }

    private suspend fun pageSocket(port: Int): String {
        val targets = SignageJson.parseToJsonElement(client.get("http://127.0.0.1:$port/json/list").bodyAsText())
        return targets.jsonArray.map { it.jsonObject }
            .firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "page" }
            ?.let { (it["webSocketDebuggerUrl"] as? JsonPrimitive)?.contentOrNull }
            ?: throw IOException("the kiosk browser has no page")
    }

    /** Ends cage and everything it started, and waits, so mpv can take the screen back. */
    private fun stop(process: Process) {
        val children = process.descendants().toList()
        process.destroy()
        children.forEach { it.destroy() }
        if (!process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            children.forEach { it.destroyForcibly() }
        }
        Log.info("Kiosk browser stopped")
    }

    companion object {
        private const val START_TIMEOUT_MS = 30_000L
        private const val POLL_MS = 100L
        private const val EXIT_WAIT_MS = 5_000L

        /** The first [name] on [path], or null. */
        fun find(name: String, path: String? = System.getenv("PATH")): String? =
            path.orEmpty().split(File.pathSeparator).filter(String::isNotEmpty)
                .map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.path

        /**
         * Where the kiosk browser's profile lives: the login session's runtime folder, which is in
         * RAM and emptied at every boot, or the temp folder where there is none.
         */
        fun profileDir(runtimeDir: String? = System.getenv("XDG_RUNTIME_DIR")): Path {
            val base = runtimeDir?.takeIf { File(it).isDirectory }?.let(::Path)
                ?: Path(System.getProperty("java.io.tmpdir")).also {
                    Log.warn("No runtime folder: the kiosk browser's profile is on disk, deleted after each use")
                }
            return base.resolve("churchpresenter-kiosk")
        }
    }
}
