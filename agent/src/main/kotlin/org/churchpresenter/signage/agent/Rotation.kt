package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.Playlist
import org.churchpresenter.signage.core.PlaylistItem
import java.time.Instant

/**
 * Steps through a rotation — a playlist with a link and more than that — one item at a time: each
 * stays for its `durationMs`, a video without one until mpv says it ended, and an item that cannot
 * play now (a link set aside after failing) is skipped. Coming back to a rotation starts it again
 * from its first item. Playlists of files alone are left to mpv's own playlist.
 */
class Rotation(private val items: PlayableItems) {
    private var playlistId: String? = null
    private var index = -1
    private var stepStartedAt: Instant = Instant.EPOCH
    private var ended = false

    /** The item to show now in [playlist], moving on when the one showing is done; null when none can play. */
    fun current(playlist: Playlist, now: Instant): PlaylistItem? {
        if (playlist.id != playlistId) {
            playlistId = playlist.id
            index = -1
        }
        val showing = playlist.items.getOrNull(index)
        if (showing == null || !stays(showing, now)) {
            ended = false
            stepStartedAt = now
            index = nextPlayable(playlist, now) ?: return null
        }
        return playlist.items[index]
    }

    /** mpv played the item on screen to its end: a video without a time moves on at the next tick. */
    fun itemEnded() {
        if (playlistId != null) ended = true
    }

    /** No rotation on screen; the next one starts from its first item. */
    fun reset() {
        playlistId = null
        ended = false
    }

    /** Whether [item], on screen, stays: it has not ended, can still play, and its time is not up. */
    private fun stays(item: PlaylistItem, now: Instant): Boolean {
        val duration = stepMs(item)
        val timeUp = duration != null && now >= stepStartedAt.plusMillis(duration)
        return !ended && !timeUp && items.canPlay(item, now)
    }

    private fun nextPlayable(playlist: Playlist, now: Instant): Int? = (1..playlist.items.size)
        .map { (index + it).mod(playlist.items.size) }
        .firstOrNull { items.canPlay(playlist.items[it], now) }

    companion object {
        /** A link with no time stays this long when it is one step of several. */
        const val DEFAULT_LINK_STEP_MS = 60_000L

        /** Shown one item at a time: it has a link and more than that. */
        fun isRotation(playlist: Playlist): Boolean = playlist.items.size > 1 && playlist.items.any { it.kind.isLink }

        /**
         * How long [item] stays as a step; null for a video that plays to its end, and for an
         * interactive page, which stays for as long as its playlist plays.
         */
        fun stepMs(item: PlaylistItem): Long? = if (item.interactive) null else item.durationMs ?: when (item.kind) {
            MediaKind.VIDEO -> null
            MediaKind.IMAGE -> ScreenItem.DEFAULT_PICTURE_MS
            MediaKind.STREAM, MediaKind.WEB -> DEFAULT_LINK_STEP_MS
        }
    }
}
