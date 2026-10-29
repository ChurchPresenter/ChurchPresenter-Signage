package org.churchpresenter.signage.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.churchpresenter.signage.core.Orientation
import org.churchpresenter.signage.core.SignageJson
import java.io.IOException
import java.nio.file.Path

/**
 * Shows an interactive page (an event sign-up, say) on the touchscreen in a visible browser, and
 * keeps nothing anyone types into it.
 *
 * - The browser takes the screen from mpv ([screen]) while the state wants it, and gives it back.
 * - Each browser starts on an empty profile in [profileDir] (in RAM where the system has one), which
 *   is deleted when it closes: when the page has gone [ScreenItem.idleResetMs] without a touch, when
 *   the page goes off screen, and on a restart.
 * - Only the page's own site and its allowed ones open ([KioskNavigation]); anything else shows a
 *   short notice and goes back.
 * - It brings its own on-screen keyboard, with no suggestions and nothing learned, and the PIN pad
 *   the corner gesture asks for ([Pins]).
 */
class KioskSession(
    private val state: StateFlow<ScreenState>,
    private val screen: Mutex,
    private val browser: Browser,
    private val profileDir: Path,
    /** The page could not be shown: it is set aside for a while and the playlist falls back. */
    private val onFailed: (url: String) -> Unit,
    private val pins: Pins,
    private val timing: Timing = Timing(),
) {
    /** Where the kiosk browser runs: [CageKiosk], or a fake in tests. */
    interface Browser {
        /**
         * Starts a browser on [profile], runs [block] on its page, and closes it; throws [IOException]
         * when it cannot.
         */
        suspend fun <T> open(profile: Path, orientation: Orientation, block: suspend (WebRenderer.Page) -> T): T
    }

    /** What the PIN pad answers to: [KioskControl]. */
    interface Pins {
        /** Whether [pin] was right. */
        fun entered(pin: String): Boolean

        fun cancelled()
    }

    /** The clock, how often the countdown is looked at, and how long a page that stopped waits to start again. */
    data class Timing(
        val now: () -> Long = System::currentTimeMillis,
        val tickMs: Long = TICK_MS,
        val retryMs: Long = RETRY_MS,
    )

    private data class Wanted(val page: ScreenItem?, val orientation: Orientation, val restartToken: Int)

    @Volatile
    private var idle: IdleTimer? = null

    /** The page on screen now, for [screenshot]; null while the kiosk does not have the screen. */
    @Volatile
    private var showing: KioskPage? = null

    /**
     * Where the mouse last was, for a new page that hides its pointer before the mouse has moved on it;
     * until the mouse has moved, the page's middle, where cage starts the pointer.
     */
    private var mouseAt: Pair<Double, Double>? = null

    /** Someone touched the screen (from [TouchInput]): the page's idle time starts again. */
    fun touched() {
        idle?.touched(timing.now())
    }

    /**
     * Writes what the kiosk shows to [file] for the Signage tab's preview: whatever anyone is typing
     * too, as the church asked, to check it works. Null while the kiosk does not have the screen.
     */
    suspend fun screenshot(file: Path): Boolean? = showing?.screenshot(file)

    /** Follows [state] until cancelled. */
    suspend fun run() {
        state.map { wantedOf(it) }.distinctUntilChanged().collectLatest { wanted ->
            if (wanted == null) return@collectLatest
            screen.withLock { keepShowing(wanted) }
        }
    }

    private fun wantedOf(state: ScreenState): Wanted? =
        if (state.kioskWanted) Wanted(state.kiosk, state.orientation, state.restartToken) else null

    /** Shows [wanted] until cancelled, starting it over after each idle reset or browser exit. */
    private suspend fun keepShowing(wanted: Wanted) {
        while (true) {
            try {
                wipe()
                browser.open(profileDir, wanted.orientation) { page -> drive(KioskPage(page), page, wanted.page) }
                Log.info("Starting the interactive page over: nobody has touched it for a while")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.warn("The interactive page stopped: ${e.message}")
                wanted.page?.url?.let(onFailed)
                delay(timing.retryMs)
            } finally {
                idle = null
                showing = null
                wipe()
            }
        }
    }

    /** Runs one browser's page until it has gone idle (returns) or closed (throws). */
    private suspend fun drive(kiosk: KioskPage, page: WebRenderer.Page, item: ScreenItem?) = coroutineScope {
        val navigation = item?.url?.let { KioskNavigation(it, item.allowedHosts) }
        kiosk.prepare(navigation)
        showing = kiosk
        val timer = IdleTimer(item?.idleResetMs ?: 0, startedAtMs = timing.now())
        idle = timer.takeIf { item != null }
        // Events first: the page's first request waits at Fetch until it is answered, and navigating
        // waits for that request.
        val closed = async { handleEvents(kiosk, page, timer, navigation) }
        kiosk.open(item?.url ?: BLANK_PAGE)
        // The PIN pad alone has nothing to clear: it closes with the prompt.
        val expired = async { if (item != null) untilIdle(kiosk, timer) else awaitCancellation() }
        launch { state.map { it.pinPrompt }.distinctUntilChanged().collect { kiosk.evaluate("pinPad($it)") } }
        select {
            expired.onAwait {}
            closed.onAwait {}
        }
        coroutineContext.cancelChildren()
    }

    /** Shows the countdown over the page's last seconds, and returns once it has run out. */
    private suspend fun untilIdle(kiosk: KioskPage, timer: IdleTimer) {
        var shown = 0
        while (true) {
            val seconds = when (val now = timer.state(timing.now())) {
                IdleTimer.State.Expired -> return
                is IdleTimer.State.Counting -> now.secondsLeft
                IdleTimer.State.Running -> 0
            }
            if (seconds != shown) {
                kiosk.evaluate("countdown($seconds)")
                shown = seconds
            }
            delay(timing.tickMs)
        }
    }

    /** Answers the page's messages and its requests to open pages, until it closes. */
    private suspend fun handleEvents(
        kiosk: KioskPage,
        page: WebRenderer.Page,
        timer: IdleTimer,
        navigation: KioskNavigation?,
    ) {
        for (event in page.events) {
            when (event.method) {
                "Runtime.bindingCalled" -> if (event.params.string("name") == KioskPage.BINDING) {
                    message(kiosk, timer, event.params.string("payload").orEmpty())
                }
                "Fetch.requestPaused" -> kiosk.request(event.params, navigation)
            }
        }
        throw IOException("the browser closed")
    }

    private suspend fun message(kiosk: KioskPage, timer: IdleTimer, payload: String) {
        val message = runCatching { SignageJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
        when (message.string("t")) {
            "ready" -> kiosk.evaluate("pinPad(${state.value.pinPrompt})")
            "act" -> timer.touched(timing.now())
            "key" -> message.string("k")?.let { kiosk.key(it) }
            "pin" -> kiosk.evaluate("pinResult(${pins.entered(message.string("v").orEmpty())})")
            "pinCancel" -> pins.cancelled()
            "cursor" -> {
                val x = (message["x"] as? JsonPrimitive)?.doubleOrNull
                val y = (message["y"] as? JsonPrimitive)?.doubleOrNull
                if (x != null && y != null) mouseAt = x to y
                val middleX = (message["cx"] as? JsonPrimitive)?.doubleOrNull
                val middleY = (message["cy"] as? JsonPrimitive)?.doubleOrNull
                val at = mouseAt ?: if (middleX != null && middleY != null) middleX to middleY else null
                at?.let { (atX, atY) -> kiosk.refreshCursor(atX, atY) }
            }
        }
    }

    private fun wipe() {
        if (!profileDir.toFile().deleteRecursively()) Log.warn("The kiosk browser's profile could not be deleted")
    }

    companion object {
        const val TICK_MS = 1_000L
        const val RETRY_MS = 5_000L

        /** What the PIN pad opens over when no interactive page is on screen. */
        const val BLANK_PAGE = "data:text/html,<html><body style=\"margin:0;background:#000\"></body></html>"
    }
}
