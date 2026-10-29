package org.churchpresenter.signage.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.Orientation
import java.io.IOException
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The kiosk session against a fake browser: it opens the page with its script and checks where it
 * goes, types the keyboard's keys, starts over on an empty profile after the idle time, answers the
 * PIN pad, and gives the screen back when the page goes off it. Waits are on what the page is sent.
 */
@OptIn(ExperimentalPathApi::class)
class KioskSessionTest {
    private val root = createTempDirectory("signage-kiosk")
    private val profile = root.resolve("profile")
    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var nowMs = 0L

    /**
     * Stops the session and waits for it, so its own last wipe is over before the folder goes: on
     * Windows a file still open cannot be deleted.
     */
    @AfterTest
    fun cleanUp() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        root.deleteRecursively()
    }

    /** One page: records what it is sent, and answers the frame tree and a screenshot. */
    private class FakePage : WebRenderer.Page {
        override val events = Channel<WebRenderer.Event>(Channel.UNLIMITED)
        val sent = Channel<Pair<String, JsonObject>>(Channel.UNLIMITED)

        override suspend fun call(method: String, params: JsonObject): JsonObject {
            sent.send(method to params)
            return when (method) {
                "Page.getFrameTree" -> buildJsonObject {
                    putJsonObject("frameTree") { putJsonObject("frame") { put("id", MAIN_FRAME) } }
                }
                "Page.captureScreenshot" -> buildJsonObject { put("data", Base64.getEncoder().encodeToString(PICTURE)) }
                else -> JsonObject(emptyMap())
            }
        }

        override suspend fun send(method: String, params: JsonObject) {
            sent.send(method to params)
        }

        /** The next call or send to [method], skipping others; fails after a while. */
        suspend fun await(method: String): JsonObject = withTimeout(WAIT_MS) {
            var found: JsonObject? = null
            while (found == null) {
                val (name, params) = sent.receive()
                if (name == method) found = params
            }
            found
        }

        /** The next script call whose expression has [text] in it, skipping the others. */
        suspend fun awaitEvaluate(text: String): String = withTimeout(WAIT_MS) {
            var found: String? = null
            while (found == null) {
                val expression = await("Runtime.evaluate")["expression"]!!.jsonPrimitive.content
                if (text in expression) found = expression
            }
            found
        }

        fun binding(payload: String) = events.trySend(
            WebRenderer.Event(
                "Runtime.bindingCalled",
                buildJsonObject {
                    put("name", KioskPage.BINDING)
                    put("payload", payload)
                },
            ),
        )
    }

    /** Opens a fresh page each time, after writing into the profile as Chromium would. */
    private inner class FakeBrowser(private val failing: Boolean = false) : KioskSession.Browser {
        val pages = Channel<FakePage>(Channel.UNLIMITED)

        /** Whether the profile already held something when each browser started: it never should. */
        val profileWasEmpty = mutableListOf<Boolean>()

        override suspend fun <T> open(
            profile: Path,
            orientation: Orientation,
            block: suspend (WebRenderer.Page) -> T,
        ): T {
            if (failing) throw IOException("cage did not start")
            profileWasEmpty += !profile.exists()
            profile.createDirectories()
            profile.resolve("History").writeText("someone's name")
            val page = FakePage()
            pages.send(page)
            return block(page)
        }

        suspend fun next(): FakePage = withTimeout(WAIT_MS) { pages.receive() }
    }

    private class FakePins : KioskSession.Pins {
        val typed = mutableListOf<String>()
        var cancels = 0

        override fun entered(pin: String): Boolean {
            typed += pin
            return pin == "2468"
        }

        override fun cancelled() {
            cancels++
        }
    }

    private val page = ScreenItem(
        null, MediaKind.WEB, null, "https://signup.example.org/form",
        interactive = true, idleResetMs = 30_000, allowedHosts = listOf("pay.example.com"),
    )

    private fun kioskState(item: ScreenItem? = page, pinPrompt: Boolean = false) =
        ScreenState(ScreenMode.PLAYLIST, "signup", listOfNotNull(item), pinPrompt = pinPrompt)

    private class Run(val session: KioskSession, val state: MutableStateFlow<ScreenState>, val screen: Mutex)

    private fun start(
        browser: KioskSession.Browser,
        pins: KioskSession.Pins = FakePins(),
        state: ScreenState = kioskState(),
        failed: (String) -> Unit = {},
    ): Run {
        val flow = MutableStateFlow(state)
        val screen = Mutex()
        val session = KioskSession(
            flow, screen, browser, profile, failed, pins,
            KioskSession.Timing(now = { nowMs }, tickMs = 1, retryMs = 1),
        )
        scope.launch { session.run() }
        return Run(session, flow, screen)
    }

    private fun requestPaused(id: String, url: String, frame: String) = WebRenderer.Event(
        "Fetch.requestPaused",
        buildJsonObject {
            put("requestId", id)
            put("frameId", frame)
            putJsonObject("request") { put("url", url) }
        },
    )

    @Test
    fun thePageOpensWithItsScriptAndGoesOnlyWhereItMay() = runBlocking<Unit> {
        val browser = FakeBrowser()
        start(browser)
        val fake = browser.next()
        val script = fake.await("Page.addScriptToEvaluateOnNewDocument")["source"]!!.jsonPrimitive.content
        assertTrue("__cpKiosk" in script)
        fake.await("Fetch.enable")
        assertEquals(page.url, fake.await("Page.navigate")["url"]!!.jsonPrimitive.content)
        fake.events.send(requestPaused("1", "https://attacker.example.net/", MAIN_FRAME))
        assertEquals("1", fake.await("Fetch.fulfillRequest")["requestId"]!!.jsonPrimitive.content)
        fake.events.send(requestPaused("2", "https://pay.example.com/checkout", MAIN_FRAME))
        assertEquals("2", fake.await("Fetch.continueRequest")["requestId"]!!.jsonPrimitive.content)
        // What another frame opens (a card field from the payment site, say) is not the page's to stop.
        fake.events.send(requestPaused("3", "https://attacker.example.net/frame", "card-frame"))
        assertEquals("3", fake.await("Fetch.continueRequest")["requestId"]!!.jsonPrimitive.content)
    }

    @Test
    fun theKeyboardsKeysAreTyped() = runBlocking<Unit> {
        val browser = FakeBrowser()
        start(browser)
        val fake = browser.next()
        fake.await("Page.navigate")
        fake.binding("""{"t":"key","k":"a"}""")
        assertEquals("a", fake.await("Input.insertText")["text"]!!.jsonPrimitive.content)
        fake.binding("""{"t":"key","k":"Enter"}""")
        val down = fake.await("Input.dispatchKeyEvent")
        assertEquals("keyDown" to "\r", down["type"]!!.jsonPrimitive.content to down["text"]!!.jsonPrimitive.content)
        assertEquals("keyUp", fake.await("Input.dispatchKeyEvent")["type"]!!.jsonPrimitive.content)
        fake.binding("""{"t":"key","k":"Backspace"}""")
        assertEquals("rawKeyDown", fake.await("Input.dispatchKeyEvent")["type"]!!.jsonPrimitive.content)
        fake.binding("not json")
        fake.binding("""{"t":"key","k":"b"}""")
        assertEquals("b", fake.await("Input.insertText")["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun aHiddenPointerIsRedrawnWhereTheMouseIs() = runBlocking<Unit> {
        val browser = FakeBrowser()
        start(browser)
        val fake = browser.next()
        fake.await("Page.navigate")
        // Before the mouse has been anywhere there is nowhere to move it, but the page's middle.
        fake.binding("""{"t":"cursor"}""")
        fake.binding("""{"t":"cursor","x":"far"}""")
        fake.binding("""{"t":"cursor","cx":960,"cy":540}""")
        val middle = fake.await("Input.dispatchMouseEvent")
        assertEquals(960.0 to 540.0, middle["x"]!!.jsonPrimitive.double to middle["y"]!!.jsonPrimitive.double)
        fake.binding("""{"t":"cursor","x":120.5,"y":40}""")
        val move = fake.await("Input.dispatchMouseEvent")
        assertEquals("mouseMoved", move["type"]!!.jsonPrimitive.content)
        assertEquals(120.5 to 40.0, move["x"]!!.jsonPrimitive.double to move["y"]!!.jsonPrimitive.double)
        // A new page, where the mouse has not moved yet: where it last was.
        fake.binding("""{"t":"cursor","cx":960,"cy":540}""")
        val again = fake.await("Input.dispatchMouseEvent")
        assertEquals(120.5 to 40.0, again["x"]!!.jsonPrimitive.double to again["y"]!!.jsonPrimitive.double)
    }

    @Test
    fun aTouchedPageCountsDownAndStartsOverOnAnEmptyProfile() = runBlocking<Unit> {
        val browser = FakeBrowser()
        val run = start(browser)
        val first = browser.next()
        first.await("Page.navigate")
        run.session.touched()
        nowMs = 25_000
        first.awaitEvaluate("countdown(5)")
        nowMs = 30_000
        val second = browser.next()
        second.await("Page.navigate")
        assertEquals(listOf(true, true), browser.profileWasEmpty)
    }

    @Test
    fun thePinPadOpensOverNothingAndAnswersEachPin() = runBlocking<Unit> {
        val browser = FakeBrowser()
        val pins = FakePins()
        start(browser, pins, kioskState(item = null, pinPrompt = true))
        val fake = browser.next()
        assertEquals(KioskSession.BLANK_PAGE, fake.await("Page.navigate")["url"]!!.jsonPrimitive.content)
        fake.binding("""{"t":"ready"}""")
        fake.awaitEvaluate("pinPad(true)")
        fake.binding("""{"t":"pin","v":"1111"}""")
        fake.awaitEvaluate("pinResult(false)")
        fake.binding("""{"t":"pin","v":"2468"}""")
        fake.awaitEvaluate("pinResult(true)")
        fake.binding("""{"t":"pinCancel"}""")
        // Messages are answered in order: one after the cancel shows it was handled.
        fake.binding("""{"t":"key","k":"x"}""")
        fake.await("Input.insertText")
        assertEquals(listOf("1111", "2468"), pins.typed)
        assertEquals(1, pins.cancels)
    }

    @Test
    fun leavingThePageWipesItAndGivesTheScreenBack() = runBlocking<Unit> {
        val browser = FakeBrowser()
        val run = start(browser)
        val fake = browser.next()
        fake.await("Page.navigate")
        val file = root.resolve("shot.jpg")
        assertEquals(true, run.session.screenshot(file))
        assertContentEquals(PICTURE, file.readBytes())
        run.state.value = ScreenState(ScreenMode.PLAYLIST, "welcome", emptyList())
        withTimeout(WAIT_MS) {
            while (!run.screen.tryLock()) yield()
        }
        assertFalse(profile.exists(), "the profile is deleted")
        assertNull(run.session.screenshot(file), "no kiosk, no picture of it")
    }

    @Test
    fun aBrowserThatCannotStartSetsThePageAside() = runBlocking<Unit> {
        val failed = Channel<String>(Channel.UNLIMITED)
        start(FakeBrowser(failing = true), failed = { failed.trySend(it) })
        assertEquals(page.url, withTimeout(WAIT_MS) { failed.receive() })
    }

    @Test
    fun thePageEndsWhenTheBrowserClosesAndOpensAgain() = runBlocking<Unit> {
        val browser = FakeBrowser()
        start(browser)
        val first = browser.next()
        first.await("Page.navigate")
        first.events.close(IOException("Chromium closed the page"))
        browser.next().await("Page.navigate")
    }

    private companion object {
        const val MAIN_FRAME = "main"
        const val WAIT_MS = 5_000L
        val PICTURE = byteArrayOf(1, 2, 3)
    }
}
