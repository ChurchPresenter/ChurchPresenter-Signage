package org.churchpresenter.signage.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.churchpresenter.signage.core.CecState
import org.churchpresenter.signage.core.Manifest
import org.churchpresenter.signage.core.ScreenDecision
import org.churchpresenter.signage.core.ScreenOverride
import org.churchpresenter.signage.core.Show
import org.churchpresenter.signage.core.decide
import java.time.Instant

/**
 * The player's heart: once a second, and whenever something changes, it decides what to show from
 * what is on disk, publishes that for [MpvPlayer], and sends CEC when the wanted TV power changes.
 * It needs nothing from ChurchPresenter to run.
 */
class PlayerEngine(
    private val store: PlayerStore,
    private val cec: Cec,
    private val clock: () -> Instant = Instant::now,
    /** Links that failed lately, which do not play until they are tried again. */
    private val links: LinkHealth = LinkHealth(),
    /** Whether this player can draw web pages: it has a Chromium. */
    private val web: Boolean = false,
    /** Whether this player can show interactive pages: it has a Chromium and cage. */
    private val interactive: Boolean = false,
    /** The PIN pad the screen's gesture opens ([KioskControl]); shown while it is open. */
    val pinPrompt: PinPrompt = PinPrompt(),
) {
    private val policy = TvPowerPolicy()
    private val items = PlayableItems(store.files, links, web, interactive)
    private val rotation = Rotation(items)
    private var manifest: StoredManifest? = store.manifest()
    private var override: ScreenOverride? = store.override()
    private var message: String? = null
    private var identifyUntil: Instant? = null
    private var restartToken = 0

    /** An interactive page's idle playlist, which a touch leaves for the page. */
    val idleScreen = IdleScreen()

    private val _state = MutableStateFlow(ScreenState(ScreenMode.IDLE))
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    var decision: ScreenDecision? = null
        private set

    var cecState: CecState = CecState.UNKNOWN
        private set

    /** What the screen shows instead of its playlist while a [WebTest] runs; null otherwise. */
    @Volatile
    var testing: ScreenItem? = null
        set(value) {
            field = value
            tick()
        }

    /** Re-reads the manifest and override after a sync or a switch. */
    @Synchronized
    fun reloadFromStore() {
        manifest = store.manifest()
        override = store.override()
        tick()
    }

    @Synchronized
    fun setMessage(text: String?) {
        message = text
        tick()
    }

    @Synchronized
    fun identify(seconds: Int) {
        identifyUntil = clock().plusSeconds(seconds.toLong())
        tick()
    }

    @Synchronized
    fun restartPlayer() {
        restartToken++
        tick()
    }

    /** [urls] could not be opened; their playlists give way until they are tried again. */
    @Synchronized
    fun linkFailed(urls: Collection<String>) {
        val now = clock()
        urls.forEach { links.failed(it, now) }
        tick()
    }

    fun probeCec() {
        cecState = if (cec.probe()) CecState.WORKING else CecState.UNAVAILABLE
    }

    @Synchronized
    fun tick() {
        val now = clock()
        val stored = manifest
        val identifying = identifyUntil?.takeIf { now < it }?.let { stored?.manifest?.screen?.name ?: "" }
        if (stored == null) {
            decision = null
            _state.value = ScreenState(
                ScreenMode.IDLE, message = message, identify = identifying, restartToken = restartToken,
            ).showing(testing)
            return
        }
        val next = decide(stored.manifest, override, now) { items.canPlay(it, now) }
        decision = next
        policy.next(next.tv)?.let { command -> if (cecState != CecState.UNAVAILABLE) cec.send(command) }
        val prompt = pinPrompt.isOpen(now) && stored.manifest.screen.kioskPinHash != null
        _state.update { stateFor(stored.manifest, next, identifying, now).copy(pinPrompt = prompt).showing(testing) }
    }

    /** Ticks once a second until cancelled. */
    suspend fun run() {
        while (true) {
            tick()
            delay(TICK_MS)
        }
    }

    /** mpv played the item on screen to its end; a rotation moves on from a video without a time. */
    @Synchronized
    fun itemEnded() {
        rotation.itemEnded()
        tick()
    }

    private fun stateFor(
        manifest: Manifest,
        decision: ScreenDecision,
        identifying: String?,
        now: Instant,
    ): ScreenState {
        val base = ScreenState(
            mode = ScreenMode.IDLE,
            orientation = manifest.screen.orientation,
            idleTitle = manifest.idleCard.title,
            idleLogo = manifest.idleCard.logoSha256?.let(store.files::path),
            message = message,
            notice = manifest.screen.message?.takeIf { it.showsAt(now.toEpochMilli()) },
            identify = identifying,
            restartToken = restartToken,
        )
        val playlist = (decision.show as? Show.PlaylistShow)?.let { manifest.playlist(it.playlistId) }
        if (playlist == null || !Rotation.isRotation(playlist)) rotation.reset()
        idleScreen.playlistOnScreen(playlist?.id)
        return when {
            decision.show == Show.Blank -> base.copy(mode = ScreenMode.BLANK)
            playlist == null -> base
            Rotation.isRotation(playlist) -> {
                val step = rotation.current(playlist, now)?.let(items::screenItem) ?: return base
                base.copy(mode = ScreenMode.PLAYLIST, playlistId = playlist.id, items = listOf(step), stepped = true)
            }
            // canPlay() has checked each item can play, so none is dropped.
            else -> idleScreen.shown(
                manifest,
                base.copy(
                    mode = ScreenMode.PLAYLIST,
                    playlistId = playlist.id,
                    items = playlist.items.mapNotNull(items::screenItem),
                ),
                now,
            )
        }
    }

    /**
     * An interactive page with an idle playlist plays that playlist until a touch opens it ([touched]),
     * and again after its idle reset ([backToIdle]). The page itself shows when the idle playlist cannot
     * play, is a rotation or itself interactive, or when no touch could leave it ([touchWakes]).
     */
    inner class IdleScreen {
        /** The interactive playlist whose idle playlist is on screen, waiting for a touch; null otherwise. */
        private var idleFor: String? = null

        /** The interactive playlist a touch opened from its idle playlist, until its idle reset. */
        private var wokenFor: String? = null

        /** Whether a touch on the screen reaches the player ([TouchInput] runs). */
        @Volatile
        var touchWakes: Boolean = false

        /** Someone touched the screen: an idle playlist on screen gives way to its page. */
        fun touched() = synchronized(this@PlayerEngine) {
            val waiting = idleFor ?: return
            Log.info("The screen was touched: opening the interactive page over its idle playlist")
            wokenFor = waiting
            tick()
        }

        /** The page a touch opened has gone idle: its idle playlist comes back. */
        fun backToIdle() = synchronized(this@PlayerEngine) {
            if (wokenFor == null) return
            wokenFor = null
            tick()
        }

        internal fun playlistOnScreen(id: String?) {
            if (id != wokenFor) wokenFor = null
            idleFor = null
        }

        /** [shown] as the screen shows it, with the page's idle playlist in its place until a touch. */
        internal fun shown(manifest: Manifest, shown: ScreenState, now: Instant): ScreenState {
            val page = shown.kiosk ?: return shown
            val pageList = shown.playlistId ?: return shown
            val idle = page.idlePlaylistId?.takeIf { touchWakes && it != pageList }?.let(manifest::playlist)
            val playable = idle != null && !Rotation.isRotation(idle) && idle.items.none { it.interactive } &&
                items.canPlay(idle, now)
            return when {
                idle == null || !playable -> shown
                wokenFor == pageList -> shown.copy(woken = true)
                else -> {
                    idleFor = pageList
                    val idleItems = idle.items.mapNotNull(items::screenItem)
                    shown.copy(playlistId = idle.id, items = idleItems, touchOpens = page)
                }
            }
        }
    }

    companion object {
        const val TICK_MS = 1_000L
    }
}

/** This state with [test] on screen instead, while a [WebTest] runs; unchanged when there is none. */
private fun ScreenState.showing(test: ScreenItem?): ScreenState =
    test?.let { copy(mode = ScreenMode.PLAYLIST, playlistId = null, items = listOf(it), message = null) } ?: this
