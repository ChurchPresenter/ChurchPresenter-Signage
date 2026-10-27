package org.churchpresenter.signage.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.PlaylistItem
import org.churchpresenter.signage.core.sha256Hex
import java.io.IOException
import java.net.URI
import java.util.Base64

/**
 * Draws the web pages on screen off screen and hands their frames to mpv through [server]: a page is
 * open only while its playlist is on screen, reloads every `refreshMs`, and closes when it goes off.
 * One that cannot load within [loadTimeoutMs], or reloads to an error, goes to [onFailed], so the
 * screen shows something else and the page is tried again later; an error page never reaches the screen.
 */
class WebRenderer(
    private val browser: Browser,
    private val server: MjpegServer,
    /** The panel's size as it is mounted in landscape; a portrait screen's page is drawn upright. */
    private val panelWidth: Int,
    private val panelHeight: Int,
    private val onFailed: (url: String) -> Unit,
    /** Whether the site answers at all before it is loaded, so an offline reload never draws Chromium's error page. */
    private val reachable: suspend (url: String) -> Boolean = { true },
    private val loadTimeoutMs: Long = LOAD_TIMEOUT_MS,
) {
    /** Where pages are drawn: [Chromium], or a fake in tests. */
    interface Browser {
        /** Opens a page, runs [block] on it, and closes it; throws [IOException] when it cannot. */
        suspend fun <T> withPage(block: suspend (Page) -> T): T

        /** Ends the browser, when no page is wanted. */
        fun stop()
    }

    /** One DevTools page. */
    interface Page {
        /** Everything the page sends that is not an answer; closed with an [IOException] when the page goes. */
        val events: ReceiveChannel<Event>

        /** Calls [method] and returns its result; throws [IOException] when the page answers with an error. */
        suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject

        /** Sends [method] without waiting for the answer. */
        suspend fun send(method: String, params: JsonObject = JsonObject(emptyMap()))
    }

    data class Event(val method: String, val params: JsonObject)

    private data class Wanted(val url: String, val refreshMs: Long?, val width: Int, val height: Int, val fps: Int)

    /** The stream mpv plays for the page at [url]. */
    fun streamUrl(url: String): String = "http://127.0.0.1:${server.port}${MjpegServer.PATH_PREFIX}${key(url)}"

    /** Follows [state] until cancelled, keeping open exactly the pages it shows. */
    suspend fun run(state: StateFlow<ScreenState>) {
        state.map(::wanted).distinctUntilChanged().collectLatest { pages ->
            if (pages.isEmpty()) {
                browser.stop()
                return@collectLatest
            }
            coroutineScope { pages.forEach { launch { render(it) } } }
        }
    }

    private fun wanted(state: ScreenState): Set<Wanted> {
        val upright = state.orientation.clockwiseDegrees % HALF_TURN != 0
        val (width, height) = if (upright) panelHeight to panelWidth else panelWidth to panelHeight
        return state.items.filter { it.kind == MediaKind.WEB && it.url != null }
            .map { Wanted(it.url.orEmpty(), it.refreshMs, width, height, it.maxFps ?: PlaylistItem.DEFAULT_WEB_FPS) }
            .toSet()
    }

    private suspend fun render(wanted: Wanted) {
        val key = key(wanted.url)
        try {
            browser.withPage { page -> show(page, wanted, key) }
        } catch (e: IOException) {
            Log.warn("Web page failed: ${e.message}; trying it again in a minute")
            onFailed(wanted.url)
        } finally {
            server.remove(key)
        }
    }

    /** Loads the page, then passes its frames on until cancelled, reloading it every refreshMs. */
    private suspend fun show(page: Page, wanted: Wanted, key: String): Unit = coroutineScope {
        page.call("Page.enable")
        page.call(
            "Emulation.setDeviceMetricsOverride",
            buildJsonObject {
                put("width", wanted.width)
                put("height", wanted.height)
                put("deviceScaleFactor", 1)
                put("mobile", false)
            },
        )
        var loaded = CompletableDeferred<Unit>()
        launch {
            try {
                for (event in page.events) {
                    when (event.method) {
                        "Page.loadEventFired" -> loaded.complete(Unit)
                        "Page.screencastFrame" -> {
                            val data = event.params["data"]?.jsonPrimitive?.contentOrNull
                            data?.let { server.publish(key, Base64.getDecoder().decode(it)) }
                            page.send("Page.screencastFrameAck", ack(event.params))
                        }
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // The page went; withPage reports it.
            }
        }
        while (true) {
            loaded = CompletableDeferred()
            load(page, wanted, loaded)
            page.call("Page.startScreencast", screencast(wanted))
            val refreshMs = wanted.refreshMs ?: break
            delay(refreshMs)
            // The last frame stays up while the page reloads behind it.
            page.call("Page.stopScreencast")
        }
        awaitCancellation()
    }

    /** Loads [wanted]'s page; throws [IOException] when it cannot within [loadTimeoutMs]. */
    private suspend fun load(page: Page, wanted: Wanted, loaded: CompletableDeferred<Unit>) {
        val host = host(wanted.url)
        val error = try {
            withTimeout(loadTimeoutMs) { navigate(page, wanted.url, loaded) }
        } catch (_: TimeoutCancellationException) {
            "took longer than ${loadTimeoutMs / MS_PER_SECOND} s to load"
        }
        error?.let { throw IOException("$host: $it") }
    }

    /** Null once the page has loaded, or why it did not. */
    private suspend fun navigate(page: Page, url: String, loaded: CompletableDeferred<Unit>): String? {
        if (!reachable(url)) return "does not answer"
        val navigation = page.call("Page.navigate", buildJsonObject { put("url", url) })
        navigation["errorText"]?.jsonPrimitive?.contentOrNull?.let { return it }
        loaded.await()
        return null
    }

    private fun screencast(wanted: Wanted) = buildJsonObject {
        put("format", "jpeg")
        put("quality", JPEG_QUALITY)
        put("maxWidth", wanted.width)
        put("maxHeight", wanted.height)
        put("everyNthFrame", everyNthFrame(wanted.fps))
    }

    private fun ack(params: JsonObject) = buildJsonObject {
        params["sessionId"]?.let { put("sessionId", it) }
    }

    companion object {
        const val LOAD_TIMEOUT_MS = 15_000L

        /** How often Chromium draws a page that moves. */
        private const val CHROMIUM_FPS = 60

        /** Which of Chromium's frames reach the screen for at most [fps] a second: every fourth for 15. */
        fun everyNthFrame(fps: Int): Int = (CHROMIUM_FPS / fps.coerceIn(1, CHROMIUM_FPS)).coerceAtLeast(1)
        private const val JPEG_QUALITY = 80
        private const val HALF_TURN = 180
        private const val KEY_CHARS = 16
        private const val MS_PER_SECOND = 1000

        /** A page's name in the stream's path: no URL characters, and the same for the same URL. */
        fun key(url: String): String = sha256Hex(url.toByteArray()).take(KEY_CHARS)

        /** Only the host goes into the log, never the whole address with whatever it carries. */
        private fun host(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "the page"
    }
}
