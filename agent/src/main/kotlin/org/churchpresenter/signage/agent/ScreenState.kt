package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.Orientation
import org.churchpresenter.signage.core.PlaylistItem
import java.nio.file.Path

/** What the screen shows right now. [PlayerEngine] works it out; [MpvPlayer] puts it on the screen. */
data class ScreenState(
    val mode: ScreenMode,
    val playlistId: String? = null,
    val items: List<ScreenItem> = emptyList(),
    val orientation: Orientation = Orientation.LANDSCAPE,
    val idleTitle: String = "",
    val idleLogo: Path? = null,
    /** A line under the idle card, e.g. the pairing code while waiting for approval. */
    val message: String? = null,
    /** The screen's name, shown full screen while someone identifies it. */
    val identify: String? = null,
    /** Bumped by a refresh request; the player restarts mpv when it changes. */
    val restartToken: Int = 0,
    /** One step of a rotation: [items] is the one on screen, and the engine, not mpv, moves on. */
    val stepped: Boolean = false,
    /** The touch gesture asked for the screen's PIN: the kiosk browser shows its PIN pad. */
    val pinPrompt: Boolean = false,
    /**
     * [items] are an interactive page's idle playlist, and a touch on the screen opens this page
     * ([PlayerEngine.IdleScreen.touched]); null for anything else.
     */
    val touchOpens: ScreenItem? = null,
    /** The interactive page on screen was opened by a touch on its idle playlist: its idle time has started. */
    val woken: Boolean = false,
) {
    /** The interactive page on screen, which the kiosk browser shows instead of mpv; null for anything else. */
    val kiosk: ScreenItem?
        get() = items.singleOrNull()?.takeIf { mode == ScreenMode.PLAYLIST && it.interactive && it.url != null }

    /** Whether the kiosk browser has the screen: an interactive page, or the PIN pad over nothing. */
    val kioskWanted: Boolean get() = kiosk != null || pinPrompt
}

enum class ScreenMode { PLAYLIST, IDLE, BLANK }

/** How far the picture turns clockwise for a screen mounted this way, as mpv's `video-rotate`. */
val Orientation.clockwiseDegrees: Int
    get() = turnsByOrientation.getValue(this)

private val turnsByOrientation = mapOf(
    Orientation.LANDSCAPE to 0,
    Orientation.PORTRAIT to 90,
    Orientation.LANDSCAPE_FLIPPED to 180,
    Orientation.PORTRAIT_FLIPPED to 270,
)

/**
 * One playlist entry: a [file] on disk, or for a link its [url]. A picture with no [durationMs] stays
 * [DEFAULT_PICTURE_MS]; a link with none stays up for as long as its playlist plays.
 */
data class ScreenItem(
    val file: Path?,
    val kind: MediaKind,
    val durationMs: Long?,
    val url: String? = null,
    /** How often a web page reloads; never when null. */
    val refreshMs: Long? = null,
    /** At most how many frames a second a web page is drawn at; the protocol's default when null. */
    val maxFps: Int? = null,
    /** A web page people use by touch, shown by [KioskSession] rather than mpv. */
    val interactive: Boolean = false,
    /** How long an interactive page waits without a touch before it starts over. */
    val idleResetMs: Long = PlaylistItem.DEFAULT_IDLE_RESET_MS,
    /** Other sites an interactive page may go to besides its own. */
    val allowedHosts: List<String> = emptyList(),
    /** The playlist an interactive page's screen plays while nobody is using it. */
    val idlePlaylistId: String? = null,
    /** The web page an interactive page's screen shows while nobody is using it. */
    val idleUrl: String? = null,
    /** The line written over the idle playlist or page, so people know to touch it; none when null. */
    val idlePrompt: String? = null,
) {
    companion object {
        const val DEFAULT_PICTURE_MS = 8_000L
    }
}
