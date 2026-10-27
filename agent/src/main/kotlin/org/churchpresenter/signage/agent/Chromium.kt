package org.churchpresenter.signage.agent

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.churchpresenter.signage.core.SignageJson
import java.io.File
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.io.path.Path

/**
 * One headless Chromium for the player, started when a web page is first wanted and stopped when
 * none is, driven through the DevTools protocol on a port of its own choosing on 127.0.0.1. Its
 * profile lives in [profile], under the player's data folder.
 */
class Chromium(
    private val executable: String,
    private val profile: Path,
    private val client: HttpClient,
    /** Present once the GPU failed on this player: from then on pages are drawn in software. */
    private val gpuOff: Path = profile.resolveSibling(GPU_OFF_MARKER),
    private val arm: Boolean = System.getProperty("os.arch") in ARM_ARCHES,
    /** The machine's memory in KiB, which sizes Chromium's JavaScript heap. */
    private val totalMemoryKb: Long? = Memory.read()?.totalKb,
) : WebRenderer.Browser {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var port = 0
    private val starting = Mutex()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { process?.destroy() })
    }

    /** The GPU draws on an ARM player until it fails there once; x86 players stay in software. */
    override val gpu: Boolean get() = arm && !gpuOff.exists()

    override fun useSoftware() {
        if (!gpu) return
        Log.warn("Web pages did not draw with the GPU; drawing them in software from now on")
        runCatching { gpuOff.writeText("The GPU did not draw web pages on this player.\n") }
        stop()
    }

    /**
     * What Chromium and every process it started use now, in KiB; null while it is not running. Each
     * process's proportional share (PSS): its processes share most of their memory, and adding up
     * what each has resident counts that several times over (on the test VM 1.2 GB for 425 MB).
     */
    override fun memoryKb(): Long? {
        val running = process?.takeIf { it.isAlive } ?: return null
        val processes = listOf(running.toHandle()) + running.descendants().toList()
        return processes.sumOf { handle -> proportionalKb(handle.pid()) }
    }

    override suspend fun <T> withPage(block: suspend (WebRenderer.Page) -> T): T {
        val devtools = ensureStarted()
        val target = SignageJson.parseToJsonElement(
            client.put("http://127.0.0.1:$devtools/json/new?about:blank").bodyAsText(),
        ).jsonObject
        val id = target.string("id") ?: throw IOException("Chromium opened no page")
        val socketUrl = target.string("webSocketDebuggerUrl") ?: throw IOException("Chromium's page has no socket")
        try {
            var result: T? = null
            var done = false
            client.webSocket(socketUrl) {
                coroutineScope {
                    val page = DevToolsPage(this@webSocket)
                    val reader = launch { page.readLoop() }
                    try {
                        result = block(page)
                        done = true
                    } finally {
                        reader.cancel()
                    }
                }
            }
            if (!done) throw IOException("Chromium closed the page")
            @Suppress("UNCHECKED_CAST")
            return result as T
        } finally {
            runCatching { client.get("http://127.0.0.1:$devtools/json/close/$id") }
        }
    }

    override fun stop() {
        process?.let { running ->
            running.destroy()
            Log.info("Chromium stopped: no web page on screen")
        }
        process = null
    }

    /**
     * The DevTools port of a running Chromium, starting one if none is; one at a time. One that cannot
     * start with the GPU is started again in software at once, and stays there.
     */
    private suspend fun ensureStarted(): Int = starting.withLock {
        process?.takeIf { it.isAlive }?.let { return port }
        val withGpu = gpu
        try {
            start(withGpu)
        } catch (e: IOException) {
            if (!withGpu) throw e
            useSoftware()
            start(withGpu = false)
        }
    }

    private suspend fun start(withGpu: Boolean): Int {
        val portFile = profile.resolve("DevToolsActivePort")
        portFile.deleteIfExists()
        val heapMb = if ((totalMemoryKb ?: 0) < SMALL_MACHINE_KB) SMALL_HEAP_MB else HEAP_MB
        val command = listOf(executable) + args(profile, PAGE_WINDOW_WIDTH, PAGE_WINDOW_HEIGHT, withGpu, heapMb)
        val started = launcher(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process = started
        var waited = 0L
        while (waited < START_TIMEOUT_MS) {
            if (!started.isAlive) throw IOException("Chromium exited with ${started.exitValue()}")
            val first = if (portFile.exists()) portFile.readText().lineSequence().firstOrNull()?.toIntOrNull() else null
            if (first != null) {
                port = first
                Log.info("Chromium started (pid ${started.pid()}, ${if (withGpu) "GPU" else "software"})")
                return first
            }
            delay(START_POLL_MS)
            waited += START_POLL_MS
        }
        started.destroyForcibly()
        throw IOException("Chromium did not open DevTools")
    }

    /** One page's DevTools socket: calls answered by id, and everything else as events. */
    private class DevToolsPage(private val socket: DefaultClientWebSocketSession) : WebRenderer.Page {
        private val ids = AtomicInteger(1)
        private val waiting = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        override val events = Channel<WebRenderer.Event>(Channel.UNLIMITED)

        suspend fun readLoop() {
            for (frame in socket.incoming) {
                val message = (frame as? Frame.Text)
                    ?.let { runCatching { SignageJson.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
                val id = message?.get("id")?.jsonPrimitive?.intOrNull
                val method = message?.string("method")
                when {
                    id != null -> waiting.remove(id)?.complete(message)
                    method != null -> events.send(WebRenderer.Event(method, message.params()))
                }
            }
            val closed = IOException("Chromium closed the page")
            events.close(closed)
            waiting.values.forEach { it.completeExceptionally(closed) }
        }

        override suspend fun call(method: String, params: JsonObject): JsonObject {
            val answer = CompletableDeferred<JsonObject>()
            val id = ids.getAndIncrement()
            waiting[id] = answer
            socket.send(Frame.Text(request(id, method, params)))
            val message = answer.await()
            message["error"]?.let { throw IOException("$method: ${it.jsonObject.string("message")}") }
            return message["result"]?.jsonObject ?: JsonObject(emptyMap())
        }

        override suspend fun send(method: String, params: JsonObject) {
            socket.send(Frame.Text(request(ids.getAndIncrement(), method, params)))
        }

        private fun request(id: Int, method: String, params: JsonObject): String = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
    }

    companion object {
        private val NAMES = listOf("chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "msedge")
        private const val START_TIMEOUT_MS = 15_000L
        private const val START_POLL_MS = 100L

        /** The browser's window; each page is sized to the screen with device metrics. */
        private const val PAGE_WINDOW_WIDTH = 1920
        private const val PAGE_WINDOW_HEIGHT = 1080

        /**
         * The first Chromium on [path], or null. Never as root: Chromium refuses to run there with its
         * sandbox, and a page from the internet is not drawn without one.
         */
        fun find(path: String? = System.getenv("PATH"), user: String = System.getProperty("user.name")): String? {
            if (user == "root") return null
            val dirs = path.orEmpty().split(File.pathSeparator).filter(String::isNotEmpty)
            return NAMES.firstNotNullOfOrNull { name ->
                dirs.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.path
            }
        }

        /** Where the marker that turns the GPU off lives, beside the browser profile. */
        const val GPU_OFF_MARKER = "web-gpu-off"
        private val ARM_ARCHES = setOf("aarch64", "arm64")
        private const val SMALL_MACHINE_KB = 2L * 1024 * 1024
        private const val SMALL_HEAP_MB = 512
        private const val HEAP_MB = 1024

        /**
         * Starts [command] without the login session's D-Bus. Raspberry Pi OS starts Chromium with
         * `--force-renderer-accessibility`, which on a session bus held each start's first page for
         * D-Bus's 25 s timeout, past the load timeout; a headless page needs nothing on it.
         */
        fun launcher(command: List<String>): ProcessBuilder = ProcessBuilder(command).apply {
            environment()["DBUS_SESSION_BUS_ADDRESS"] = "disabled:"
        }

        /**
         * Headless, silent, no scroll bars, nothing of a first run; DevTools on a free port. Drawn
         * with the GPU through ANGLE on EGL when [gpu], in software otherwise; JavaScript's heap
         * capped at [heapMb], so one page cannot take the whole machine.
         */
        fun args(
            profile: Path,
            width: Int,
            height: Int,
            gpu: Boolean = false,
            heapMb: Int = HEAP_MB,
        ): List<String> = listOf(
            "--headless=new",
            "--remote-debugging-port=0",
            "--remote-debugging-address=127.0.0.1",
            "--user-data-dir=$profile",
            "--window-size=$width,$height",
            "--hide-scrollbars",
            "--mute-audio",
            "--autoplay-policy=no-user-gesture-required",
            "--no-first-run",
            "--no-default-browser-check",
            "--noerrdialogs",
            "--js-flags=--max-old-space-size=$heapMb",
        ) + (if (gpu) GPU_ARGS else listOf("--disable-gpu")) + "about:blank"

        private val GPU_ARGS = listOf(
            "--use-gl=angle",
            "--use-angle=gles-egl",
            "--enable-gpu-rasterization",
            "--ignore-gpu-blocklist",
        )

        /**
         * How much memory Chromium may use on a machine with [totalKb] before it is restarted, as
         * Kiosk-URL sizes it: most of a large machine, a little over half of a small one.
         */
        fun memoryLimitKb(totalKb: Long?): Long = when {
            totalKb == null -> LIMITS.last().second
            else -> LIMITS.firstOrNull { (above, _) -> totalKb > above }?.second ?: LIMITS.last().second
        }

        /** Machine memory above which (KiB) Chromium may use how much (KiB). */
        private val LIMITS = listOf(
            8_388_608L to 6_291_456L,
            4_194_304L to 4_194_304L,
            2_097_152L to 2_621_440L,
            1_048_576L to 1_228_800L,
            0L to 716_800L,
        )

        /**
         * A process's proportional share of memory in KiB, from `/proc/<pid>/smaps_rollup`, or what it
         * has resident where the kernel is too old for that; 0 where neither can be read.
         */
        private fun proportionalKb(pid: Long): Long =
            kibField(Path("/proc/$pid/smaps_rollup"), "Pss:") ?: kibField(Path("/proc/$pid/status"), "VmRSS:") ?: 0

        private fun kibField(file: Path, name: String): Long? = try {
            file.readLines().firstOrNull { it.startsWith(name) }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
        } catch (_: IOException) {
            null
        }
    }
}

/**
 * Whether the site at [url] answers at all within [timeoutMs]: any reply counts, an error status
 * too, since some sites turn away a client that is not a browser; no answer, or no such host, does
 * not. Addresses that are not http(s) are taken as answering.
 */
suspend fun HttpClient.answers(url: String, timeoutMs: Long): Boolean {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return true
    return try {
        prepareGet(url) { timeout { requestTimeoutMillis = timeoutMs } }.execute { }
        true
    } catch (_: IOException) {
        false
    } catch (_: UnresolvedAddressException) {
        false
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.params(): JsonObject = this["params"] as? JsonObject ?: JsonObject(emptyMap())
