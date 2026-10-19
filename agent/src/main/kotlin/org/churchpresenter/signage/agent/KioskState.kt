package org.churchpresenter.signage.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.Orientation
import org.churchpresenter.signage.core.Transition

/** What the kiosk page draws. The page holds no state of its own: it asks for this every second. */
@Serializable
data class KioskState(
    val mode: KioskMode,
    val playlistId: String? = null,
    val items: List<KioskItem> = emptyList(),
    val orientation: Orientation = Orientation.LANDSCAPE,
    val idleTitle: String = "",
    val idleLogoUrl: String? = null,
    /** A line under the idle card, e.g. the pairing code while waiting for approval. */
    val message: String? = null,
    /** The screen's name, shown full screen while someone identifies it. */
    val identify: String? = null,
    /** Bumped by a refresh request; the page reloads when it changes. */
    val reloadToken: Int = 0,
)

@Serializable
enum class KioskMode {
    @SerialName("playlist")
    PLAYLIST,

    @SerialName("idle")
    IDLE,

    @SerialName("blank")
    BLANK,
}

@Serializable
data class KioskItem(
    val url: String,
    val kind: MediaKind,
    val durationMs: Long?,
    val transition: Transition,
    val transitionMs: Long,
)
