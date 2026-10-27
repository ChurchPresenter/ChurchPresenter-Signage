package org.churchpresenter.signage.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
import java.io.IOException
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

    /**
     * A page that loads on navigate unless told otherwise, gives its picture when asked for one unless
     * [draws] is false (then never answers, as a page nothing can draw), and records every call.
     * Like a real still page, it sends no screencast frame of its own.
     */
    private class FakePage(
        val errorText: String? = null,
        val loads: Boolean = true,
        val draws: Boolean = true,
    ) : WebRenderer.Page {
        override var events = Channel<WebRenderer.Event>(Channel.UNLIMITED)
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
                "Page.captureScreenshot" -> {
                    if (!draws) awaitCancellation()
                    return buildJsonObject { put("data", Base64.getEncoder().encodeToString(byteArrayOf(1))) }
                }
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

    private class FakeBrowser(val page: FakePage, override var gpu: Boolean = false) : WebRenderer.Browser {
        var stops = 0
        var memory: Long? = null

        override suspend fun <T> withPage(block: suspend (WebRenderer.Page) -> T): T = block(page)

        /** As a real browser: the page's socket closes, and the next page starts afresh. */
        override fun stop() {
            stops++
            page.events.close(IOException("the browser stopped"))
            page.events = Channel(Channel.UNLIMITED)
        }

        override fun useSoftware() {
            gpu = false
        }

        override fun memoryKb(): Long? = memory
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
        // Every frame Chromium draws is sent; the renderer paces them itself.
        assertEquals(null, screencast["everyNthFrame"])
        fake.frame(byteArrayOf(5, 6), session = 7)
        // Each frame is acknowledged with its own session, or Chromium sends no more.
        val acked = {
            fake.calls.any { it.first == "Page.screencastFrameAck" && it.second["sessionId"] == JsonPrimitive(7) }
        }
        withTimeout(WAIT_MS) { while (!acked()) yield() }
        assertTrue(renderer.streamUrl("https://example.org").endsWith("/web/" + WebRenderer.key("https://example.org")))
        job.cancel()
    }

    @Test
    fun aStillPageIsShownByItsPictureBeforeAnyFrame() = runBlocking {
        val fake = FakePage()
        val job = async { renderer(FakeBrowser(fake)).run(state(page("https://example.org"))) }
        withTimeout(WAIT_MS) { fake.screencast.await() }
        val methods = fake.calls.map { it.first }
        // Asked for outright after the load: the screencast alone sends nothing for a page that never changes.
        val picture = methods.indexOf("Page.captureScreenshot")
        assertTrue(picture > methods.indexOf("Page.navigate") && picture < methods.indexOf("Page.startScreencast"))
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

    @Test
    fun overItsMemoryTheBrowserRestartsAndThePageComesBackNotFailed() = runBlocking {
        val failed = CompletableDeferred<String>()
        val fake = FakePage()
        val browser = FakeBrowser(fake).also { it.memory = 10 }
        val renderer =
            WebRenderer(browser, server, 1920, 1080, { failed.complete(it) }, memoryLimitKb = 5, watchEveryMs = 1)
        val job = async { renderer.run(state(page("https://example.org"))) }
        withTimeout(WAIT_MS) { while (browser.stops == 0) yield() }
        browser.memory = 1
        // The page is opened again: a second navigation, and no failure.
        withTimeout(WAIT_MS) { repeat(2) { fake.navigations.receive() } }
        assertTrue(!failed.isCompleted)
        job.cancel()
    }

    @Test
    fun aPageTheGpuDrawsNothingOfIsDrawnInSoftware() = runBlocking {
        val failed = CompletableDeferred<String>()
        val fake = FakePage(draws = false)
        val browser = FakeBrowser(fake, gpu = true)
        val job = async { renderer(browser, failed, loadTimeoutMs = 20).run(state(page("https://example.org"))) }
        // In software the fake still draws nothing: the page fails then, not before.
        assertEquals("https://example.org", withTimeout(WAIT_MS) { failed.await() })
        assertTrue(!browser.gpu)
        // Asked for its picture with the GPU, then again in software.
        assertTrue(fake.calls.count { it.first == "Page.captureScreenshot" } >= 2)
        job.cancel()
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
