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
import kotlin.io.path.readText

/**
 * One headless Chromium for the player, started when a web page is first wanted and stopped when
 * none is, driven through the DevTools protocol on a port of its own choosing on 127.0.0.1. Its
 * profile lives in [profile], under the player's data folder.
 */
class Chromium(
    private val executable: String,
    private val profile: Path,
    private val client: HttpClient,
) : WebRenderer.Browser {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var port = 0
    private val starting = Mutex()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { process?.destroy() })
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

    /** The DevTools port of a running Chromium, starting one if none is; one at a time. */
    private suspend fun ensureStarted(): Int = starting.withLock {
        process?.takeIf { it.isAlive }?.let { return port }
        val portFile = profile.resolve("DevToolsActivePort")
        portFile.deleteIfExists()
        val started = ProcessBuilder(listOf(executable) + args(profile, PAGE_WINDOW_WIDTH, PAGE_WINDOW_HEIGHT))
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
                Log.info("Chromium started (pid ${started.pid()})")
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

        /** Headless, silent, no scroll bars, nothing of a first run; DevTools on a free port. */
        fun args(profile: Path, width: Int, height: Int): List<String> = listOf(
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
            "--disable-gpu",
            "--noerrdialogs",
            "about:blank",
        )
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
