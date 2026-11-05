package org.churchpresenter.signage.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
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
import kotlinx.serialization.json.JsonObject
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
 * - A page with an [ScreenItem.idleUrl] shows that page first, under a cover whose first tap opens the
 *   interactive page; its idle reset starts over on the idle page. One opened from its idle playlist
 *   ([ScreenState.woken]) hands the screen back to that playlist on its idle reset ([onIdle]).
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
    /**
     * A page opened from its idle playlist went idle: the playlist comes back
     * ([PlayerEngine.IdleScreen.backToIdle]).
     */
    private val onIdle: () -> Unit = {},
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

    private data class Wanted(
        val page: ScreenItem?,
        val orientation: Orientation,
        val restartToken: Int,
        val woken: Boolean = false,
    )

    /** The page in the browser now, for [touched]; null while the kiosk does not have the screen. */
    @Volatile
    private var current: OnePage? = null

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
        current?.touched("a touch on the screen")
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
        if (state.kioskWanted) Wanted(state.kiosk, state.orientation, state.restartToken, state.woken) else null

    /** Shows [wanted] until cancelled, starting it over after each idle reset or browser exit. */
    private suspend fun keepShowing(wanted: Wanted) {
        while (true) {
            try {
                wipe()
                browser.open(profileDir, wanted.orientation) { page -> OnePage(KioskPage(page), page, wanted).drive() }
                if (wanted.woken) {
                    onIdle()
                    awaitCancellation()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.warn("The interactive page stopped: ${e.message}")
                wanted.page?.url?.let(onFailed)
                delay(timing.retryMs)
            } finally {
                current = null
                showing = null
                wipe()
            }
        }
    }

    /**
     * One browser's page, from its start to its idle reset: on its idle page ([idle]) until a tap there
     * wakes it, then the interactive page.
     */
    private inner class OnePage(private val kiosk: KioskPage, private val page: WebRenderer.Page, wanted: Wanted) {
        private val item = wanted.page
        private val idleUrl = item?.idleUrl?.takeIf { !wanted.woken }
        private val pageNavigation = item?.url?.let { KioskNavigation(it, item.allowedHosts) }
        private val idleNavigation = idleUrl?.let { KioskNavigation(it, emptyList()) }
        private val timer = IdleTimer(item?.idleResetMs ?: 0, startedAtMs = timing.now())

        /** The church's own CSS and JavaScript for the page: added once the idle page is left, not to it. */
        private val code = item?.pageCode

        @Volatile
        private var idle = idleUrl != null

        /**
         * Taps on the idle page, woken from outside the loop that answers the page: opening the link
         * waits for its first request, which that loop has to let through.
         */
        private val wakes = Channel<Unit>(Channel.CONFLATED)

        /** Held while the idle page is woken or reloaded, so a reload never lands on the woken page. */
        private val opening = Mutex()

        /** What first started the idle time, for the log. */
        @Volatile
        private var firstTouch: String? = null

        init {
            if (wanted.woken) touched("a touch on its idle playlist")
        }

        private val navigation get() = if (idle) idleNavigation else pageNavigation

        /** Someone used the page: its idle time starts again. Nothing counts on the idle page. */
        fun touched(what: String) {
            if (idle || item == null) return
            if (firstTouch == null) {
                firstTouch = what
                Log.info("The interactive page is in use ($what): it starts over after a while without a touch")
            }
            timer.touched(timing.now())
        }

        /** Runs until the page has gone idle (returns) or the browser closed (throws). */
        suspend fun drive() = coroutineScope {
            kiosk.prepare(navigation)
            showing = kiosk
            current = this@OnePage
            // Events first: the page's first request waits at Fetch until it is answered, and navigating
            // waits for that request.
            val closed = async { handleEvents() }
            if (!idle) page.addPageCode(code)
            kiosk.open(idleUrl ?: item?.url ?: BLANK_PAGE)
            // The PIN pad alone has nothing to clear: it closes with the prompt.
            val expired = async { if (item != null) untilIdle() else awaitCancellation() }
            launch { state.map { it.pinPrompt }.distinctUntilChanged().collect { kiosk.evaluate("pinPad($it)") } }
            launch { wakes.consumeEach { wake() } }
            launch { reloadWhileUnused() }
            select {
                expired.onAwait { Log.info("Starting the interactive page over: ${resetReason()}") }
                closed.onAwait {}
            }
            coroutineContext.cancelChildren()
        }

        /** A tap on the idle page: the interactive page opens in its place, its idle time started. */
        private suspend fun wake() = opening.withLock {
            val url = item?.url
            if (!idle || url == null) return@withLock
            idle = false
            touched("a tap on its idle page")
            kiosk.evaluate("idleScreen(false)")
            page.addPageCode(code)
            kiosk.open(url)
        }

        /**
         * Opens what shows again while nobody uses it, so a slideshow picks up new pictures: the idle
         * page every `idleRefreshMs` while it shows, the page itself every `refreshMs` until it is
         * first touched. Stops for good once someone uses the page; its idle reset starts it over.
         */
        private suspend fun reloadWhileUnused() {
            while (true) {
                val wasIdle = idle
                val reload = reloadOf(item, idleUrl, wasIdle, touched = firstTouch != null) ?: return
                delay(reload.everyMs)
                opening.withLock { if (idle == wasIdle && firstTouch == null) kiosk.open(reload.url) }
            }
        }

        /** Why the page starts over, for the log: how long without a touch, what touched it first, where it was. */
        private suspend fun resetReason(): String {
            val seconds = (item?.idleResetMs ?: 0) / MS_PER_SECOND
            val where = kiosk.location()?.let { " It was on $it." }.orEmpty()
            return "no touch for $seconds s since it was put in use by ${firstTouch ?: "nothing"}.$where"
        }

        /** Shows the countdown over the page's last seconds, and returns once it has run out. */
        private suspend fun untilIdle() {
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
        private suspend fun handleEvents() {
            for (event in page.events) {
                when (event.method) {
                    "Runtime.bindingCalled" -> if (event.params.string("name") == KioskPage.BINDING) {
                        message(event.params.string("payload").orEmpty())
                    }
                    "Fetch.requestPaused" -> kiosk.request(event.params, navigation)
                }
            }
            throw IOException("the browser closed")
        }

        private suspend fun message(payload: String) {
            val message = runCatching { SignageJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
            when (message.string("t")) {
                "ready" -> {
                    kiosk.evaluate("pinPad(${state.value.pinPrompt})")
                    if (idle) kiosk.evaluate("idleScreen(true, ${JsonPrimitive(item?.idlePrompt.orEmpty())})")
                }
                "act" -> touched("a press on the page")
                "wake" -> wakes.trySend(Unit)
                "key" -> message.string("k")?.let { kiosk.key(it) }
                "pin" -> kiosk.evaluate("pinResult(${pins.entered(message.string("v").orEmpty())})")
                "pinCancel" -> pins.cancelled()
                "cursor" -> cursor(message)
            }
        }

        private suspend fun cursor(message: JsonObject) {
            val x = (message["x"] as? JsonPrimitive)?.doubleOrNull
            val y = (message["y"] as? JsonPrimitive)?.doubleOrNull
            if (x != null && y != null) mouseAt = x to y
            val middleX = (message["cx"] as? JsonPrimitive)?.doubleOrNull
            val middleY = (message["cy"] as? JsonPrimitive)?.doubleOrNull
            val at = mouseAt ?: if (middleX != null && middleY != null) middleX to middleY else null
            at?.let { (atX, atY) -> kiosk.refreshCursor(atX, atY) }
        }
    }

    private fun wipe() {
        if (!profileDir.toFile().deleteRecursively()) Log.warn("The kiosk browser's profile could not be deleted")
    }

    companion object {
        const val TICK_MS = 1_000L
        const val RETRY_MS = 5_000L
        private const val MS_PER_SECOND = 1_000L

        /** What the PIN pad opens over when no interactive page is on screen. */
        const val BLANK_PAGE = "data:text/html,<html><body style=\"margin:0;background:#000\"></body></html>"
    }
}

/** What a touch page reloads, and how often: [url] every [everyMs]. */
internal data class Reload(val everyMs: Long, val url: String)

/**
 * What [item]'s screen reloads now: its idle page ([idleUrl]) while that shows, the page itself while
 * nobody has [touched] it; nothing once someone has, or when no time is set.
 */
internal fun reloadOf(item: ScreenItem?, idleUrl: String?, idle: Boolean, touched: Boolean): Reload? {
    if (item == null || touched) return null
    val every = (if (idle) item.idleRefreshMs else item.refreshMs)?.takeIf { it > 0 } ?: return null
    val url = (if (idle) idleUrl else item.url) ?: return null
    return Reload(every, url)
}
