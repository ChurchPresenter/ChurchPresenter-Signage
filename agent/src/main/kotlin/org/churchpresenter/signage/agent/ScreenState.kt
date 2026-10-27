package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.Orientation
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
)

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
) {
    companion object {
        const val DEFAULT_PICTURE_MS = 8_000L
    }
}
