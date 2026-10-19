package org.churchpresenter.signage.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.churchpresenter.signage.core.CecState
import org.churchpresenter.signage.core.Manifest
import org.churchpresenter.signage.core.Playlist
import org.churchpresenter.signage.core.ScreenDecision
import org.churchpresenter.signage.core.ScreenOverride
import org.churchpresenter.signage.core.Show
import org.churchpresenter.signage.core.decide
import java.time.Instant

/**
 * The player's heart: once a second, and whenever something changes, it decides what to show from
 * what is on disk, publishes that to the kiosk page, and sends CEC when the wanted TV power changes.
 * It needs nothing from ChurchPresenter to run.
 */
class PlayerEngine(
    private val store: PlayerStore,
    private val cec: Cec,
    private val clock: () -> Instant = Instant::now,
) {
    private val policy = TvPowerPolicy()
    private var manifest: StoredManifest? = store.manifest()
    private var override: ScreenOverride? = store.override()
    private var message: String? = null
    private var identifyUntil: Instant? = null
    private var reloadToken = 0

    private val _state = MutableStateFlow(KioskState(KioskMode.IDLE))
    val state: StateFlow<KioskState> = _state.asStateFlow()

    var decision: ScreenDecision? = null
        private set

    var cecState: CecState = CecState.UNKNOWN
        private set

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
    fun reloadPage() {
        reloadToken++
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
            _state.value = KioskState(
                KioskMode.IDLE, message = message, identify = identifying, reloadToken = reloadToken,
            )
            return
        }
        val next = decide(stored.manifest, override, now, ::isPlayable)
        decision = next
        policy.next(next.tv)?.let { command -> if (cecState != CecState.UNAVAILABLE) cec.send(command) }
        _state.update { stateFor(stored.manifest, next, identifying) }
    }

    /** Ticks once a second until cancelled. */
    suspend fun run() {
        while (true) {
            tick()
            delay(TICK_MS)
        }
    }

    private fun isPlayable(playlist: Playlist): Boolean =
        playlist.items.isNotEmpty() && playlist.items.all { store.files.has(it.sha256) }

    private fun stateFor(manifest: Manifest, decision: ScreenDecision, identifying: String?): KioskState {
        val base = KioskState(
            mode = KioskMode.IDLE,
            orientation = manifest.screen.orientation,
            idleTitle = manifest.idleCard.title,
            idleLogoUrl = manifest.idleCard.logoSha256?.takeIf(store.files::has)?.let(::fileUrl),
            message = message,
            identify = identifying,
            reloadToken = reloadToken,
        )
        return when (val show = decision.show) {
            Show.Blank -> base.copy(mode = KioskMode.BLANK)
            Show.Idle -> base
            is Show.PlaylistShow -> {
                val playlist = manifest.playlist(show.playlistId) ?: return base
                base.copy(
                    mode = KioskMode.PLAYLIST,
                    playlistId = playlist.id,
                    items = playlist.items.map {
                        KioskItem(fileUrl(it.sha256), it.kind, it.durationMs, it.transition, it.transitionMs)
                    },
                )
            }
        }
    }

    companion object {
        const val TICK_MS = 1_000L

        fun fileUrl(sha256: String): String = "/files/$sha256"
    }
}
