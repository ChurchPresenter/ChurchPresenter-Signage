package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.KioskPin
import org.churchpresenter.signage.core.Manifest
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.OverrideAction
import org.churchpresenter.signage.core.Playlist
import org.churchpresenter.signage.core.ScreenOverride
import org.churchpresenter.signage.core.Until
import java.time.Instant

/** Whether the PIN pad is open: until a PIN or a cancel closes it, or [PROMPT_MS] goes by. */
class PinPrompt {
    @Volatile
    private var until: Instant? = null

    fun open(now: Instant) {
        until = now.plusMillis(PROMPT_MS)
    }

    fun close() {
        until = null
    }

    fun isOpen(now: Instant): Boolean = until?.let { now < it } == true

    companion object {
        const val PROMPT_MS = 30_000L
    }
}

/**
 * The switch someone makes on the screen itself, by holding its top-left corner ([TouchInput]):
 * between the playlist and the interactive page, at once when the screen has no PIN, or once the PIN
 * pad is given the right one. The switch is stored as an override until the schedule next changes, as
 * a manual switch made in ChurchPresenter would be.
 */
class KioskControl(
    private val store: PlayerStore,
    private val engine: PlayerEngine,
    private val prompt: PinPrompt,
    private val clock: () -> Instant = Instant::now,
) : KioskSession.Pins {
    private var wrongPins = 0
    private var lockedUntil: Instant = Instant.EPOCH

    @Synchronized
    fun gesture() {
        val manifest = store.manifest()?.manifest?.takeIf { it.screen.kioskGesture } ?: return
        if (manifest.screen.kioskPinHash == null) {
            switch(manifest)
        } else {
            prompt.open(clock())
            engine.tick()
        }
    }

    /**
     * [pin] was typed on the pad: the right one switches and closes it. Five wrong ones in a row close
     * the pad and keep it shut for a minute. Returns whether it was right.
     */
    @Synchronized
    override fun entered(pin: String): Boolean {
        val now = clock()
        val manifest = store.manifest()?.manifest
        val hash = manifest?.screen?.kioskPinHash
        val right = prompt.isOpen(now) && now >= lockedUntil && hash != null && KioskPin.matches(pin, hash)
        if (right && manifest != null) {
            wrongPins = 0
            prompt.close()
            switch(manifest)
        } else if (++wrongPins >= MAX_WRONG_PINS) {
            Log.warn("Wrong PIN $MAX_WRONG_PINS times on the screen; the PIN pad is locked for a minute")
            wrongPins = 0
            lockedUntil = now.plusMillis(LOCK_MS)
            cancelled()
        }
        return right
    }

    /** The pad was closed without a PIN. */
    @Synchronized
    override fun cancelled() {
        prompt.close()
        engine.tick()
    }

    private fun switch(manifest: Manifest) {
        val leaving = engine.state.value.kiosk != null
        val action = target(manifest, leaving)
        if (action == null) {
            Log.info("The screen's gesture found no interactive page to show")
            engine.tick()
            return
        }
        Log.info("${if (leaving) "Leaving" else "Showing"} the interactive page by the screen's gesture")
        store.saveOverride(ScreenOverride(action, Until.NextChange, clock().toEpochMilli()))
        engine.reloadFromStore()
    }

    companion object {
        const val MAX_WRONG_PINS = 5
        const val LOCK_MS = 60_000L

        /**
         * Where the gesture goes: from an interactive page to the default playlist (or the first
         * without one; dark when there is none), from anything else to the screen's chosen playlist
         * with one, or the first (null when there is none).
         */
        fun target(manifest: Manifest, leaving: Boolean): OverrideAction? {
            val hasPage = { playlist: Playlist -> playlist.items.any { it.interactive && it.kind == MediaKind.WEB } }
            return if (leaving) {
                val default = manifest.playlist(manifest.defaultPlaylistId)?.takeUnless(hasPage)
                val other = default ?: manifest.playlists.firstOrNull { it.items.isNotEmpty() && !hasPage(it) }
                other?.let { OverrideAction.Play(it.id) } ?: OverrideAction.TvOff
            } else {
                val chosen = manifest.playlist(manifest.screen.kioskGesturePlaylistId)?.takeIf(hasPage)
                (chosen ?: manifest.playlists.firstOrNull(hasPage))?.let { OverrideAction.Play(it.id) }
            }
        }
    }
}
