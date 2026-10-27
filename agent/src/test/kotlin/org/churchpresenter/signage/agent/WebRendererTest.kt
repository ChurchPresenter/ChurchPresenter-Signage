package org.churchpresenter.signage.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.Orientation
import java.util.Base64
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The web renderer against a fake browser: what it asks of a page, how its frames reach the local
 * stream, and that a page that cannot load goes to onFailed and never to the screen.
 */
class WebRendererTest {
    private val server = MjpegServer()

    @AfterTest
    fun close() = server.close()

    /** A page that loads on navigate unless told otherwise, and records every call. */
    private class FakePage(val errorText: String? = null, val loads: Boolean = true) : WebRenderer.Page {
        override val events = Channel<WebRenderer.Event>(Channel.UNLIMITED)
        val calls: MutableList<Pair<String, JsonObject>> = Collections.synchronizedList(mutableListOf())
        val navigations = Channel<Unit>(Channel.UNLIMITED)
        val screencast = CompletableDeferred<JsonObject>()

        override suspend fun call(method: String, params: JsonObject): JsonObject {
            calls += method to params
            when (method) {
                "Page.navigate" -> {
                    navigations.send(Unit)
                    if (errorText != null) return buildJsonObject { put("errorText", errorText) }
                    if (loads) events.send(WebRenderer.Event("Page.loadEventFired", JsonObject(emptyMap())))
                }
                "Page.startScreencast" -> screencast.complete(params)
            }
            return JsonObject(emptyMap())
        }

        override suspend fun send(method: String, params: JsonObject) {
            calls += method to params
        }

        fun frame(bytes: ByteArray, session: Int) = events.trySend(
            WebRenderer.Event(
                "Page.screencastFrame",
                buildJsonObject {
                    put("data", Base64.getEncoder().encodeToString(bytes))
                    put("sessionId", session)
                },
            ),
        )
    }

    private class FakeBrowser(val page: FakePage) : WebRenderer.Browser {
        var stops = 0

        override suspend fun <T> withPage(block: suspend (WebRenderer.Page) -> T): T = block(page)

        override fun stop() {
            stops++
        }
    }

    private fun state(vararg items: ScreenItem, orientation: Orientation = Orientation.LANDSCAPE) =
        MutableStateFlow(ScreenState(ScreenMode.PLAYLIST, "links", items.toList(), orientation))

    private fun page(url: String, refreshMs: Long? = null, fps: Int? = null) =
        ScreenItem(null, MediaKind.WEB, null, url, refreshMs, fps)

    private fun renderer(
        browser: FakeBrowser,
        failed: CompletableDeferred<String> = CompletableDeferred(),
        reachable: Boolean = true,
        loadTimeoutMs: Long = WebRenderer.LOAD_TIMEOUT_MS,
    ) = WebRenderer(browser, server, 1920, 1080, { failed.complete(it) }, { reachable }, loadTimeoutMs)

    @Test
    fun aPageIsSizedToTheScreenAndItsFramesReachTheStream() = runBlocking {
        val fake = FakePage()
        val renderer = renderer(FakeBrowser(fake))
        val job = async { renderer.run(state(page("https://example.org"), orientation = Orientation.PORTRAIT)) }
        val screencast = withTimeout(WAIT_MS) { fake.screencast.await() }
        // Portrait: the page is drawn upright, as tall as the panel is wide.
        val size = screencast["maxWidth"]!!.jsonPrimitive.int to screencast["maxHeight"]!!.jsonPrimitive.int
        assertEquals(1080 to 1920, size)
        assertEquals(4, screencast["everyNthFrame"]!!.jsonPrimitive.int)
        fake.frame(byteArrayOf(5, 6), session = 7)
        withTimeout(WAIT_MS) {
            while (fake.calls.none { it.first == "Page.screencastFrameAck" }) yield()
        }
        val ack = fake.calls.first { it.first == "Page.screencastFrameAck" }.second
        assertEquals(JsonPrimitive(7), ack["sessionId"])
        assertTrue(renderer.streamUrl("https://example.org").endsWith("/web/" + WebRenderer.key("https://example.org")))
        job.cancel()
    }

    @Test
    fun smoothMotionLetsTwiceTheFramesThrough() = runBlocking {
        val fake = FakePage()
        val job = async { renderer(FakeBrowser(fake)).run(state(page("https://example.org", fps = 30))) }
        assertEquals(2, withTimeout(WAIT_MS) { fake.screencast.await() }["everyNthFrame"]!!.jsonPrimitive.int)
        assertEquals(listOf(4, 2, 1, 60), listOf(15, 30, 60, 0).map(WebRenderer::everyNthFrame))
        job.cancel()
    }

    @Test
    fun aPageReloadsEveryRefresh() = runBlocking {
        val fake = FakePage()
        val job = async { renderer(FakeBrowser(fake)).run(state(page("https://example.org", refreshMs = 10))) }
        withTimeout(WAIT_MS) { repeat(2) { fake.navigations.receive() } }
        // The last frame stays up while it reloads: the screencast stops, then starts again.
        assertTrue(fake.calls.any { it.first == "Page.stopScreencast" })
        job.cancel()
    }

    @Test
    fun aPageThatAnswersWithAnErrorFails() = runBlocking {
        val failed = CompletableDeferred<String>()
        val job =
            async {
                val browser = FakeBrowser(FakePage(errorText = "net::ERR_NAME_NOT_RESOLVED"))
                renderer(browser, failed).run(state(page("https://x.invalid")))
            }
        assertEquals("https://x.invalid", withTimeout(WAIT_MS) { failed.await() })
        job.cancel()
    }

    @Test
    fun aPageThatNeverLoadsFailsAtTheTimeout() = runBlocking {
        val failed = CompletableDeferred<String>()
        val fake = FakePage(loads = false)
        val job =
            async { renderer(FakeBrowser(fake), failed, loadTimeoutMs = 20).run(state(page("https://slow.example"))) }
        assertEquals("https://slow.example", withTimeout(WAIT_MS) { failed.await() })
        assertTrue(fake.calls.none { it.first == "Page.startScreencast" })
        job.cancel()
    }

    @Test
    fun aSiteThatDoesNotAnswerIsNotNavigatedTo() = runBlocking {
        val failed = CompletableDeferred<String>()
        val fake = FakePage()
        val job =
            async { renderer(FakeBrowser(fake), failed, reachable = false).run(state(page("https://down.example"))) }
        withTimeout(WAIT_MS) { failed.await() }
        // Chromium's own error page is never drawn: there was no navigation to draw it.
        assertTrue(fake.calls.none { it.first == "Page.navigate" })
        job.cancel()
    }

    @Test
    fun withNoPageOnScreenTheBrowserStops() = runBlocking {
        val browser = FakeBrowser(FakePage())
        val screen = MutableStateFlow(ScreenState(ScreenMode.IDLE))
        val job = async { renderer(browser).run(screen) }
        withTimeout(WAIT_MS) { while (browser.stops == 0) yield() }
        job.cancel()
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
