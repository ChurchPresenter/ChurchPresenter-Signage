package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.PlaylistItem
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Links that failed to open, each set aside for [retryAfter]: while it is, its playlist counts as one
 * that cannot play, so the screen falls back to the default playlist or the idle card, and then tries
 * the link again. A link that cannot load never puts an error on the screen.
 */
class LinkHealth(private val retryAfter: Duration = RETRY_AFTER) {
    private val failedAt = ConcurrentHashMap<String, Instant>()

    fun failed(url: String, now: Instant) {
        failedAt[url] = now
    }

    /** False while [url] failed less than [retryAfter] ago. */
    fun isUp(url: String, now: Instant): Boolean {
        val at = failedAt[url] ?: return true
        if (now < at + retryAfter) return false
        failedAt.remove(url, at)
        return true
    }

    companion object {
        val RETRY_AFTER: Duration = Duration.ofMinutes(1)
    }
}

/** Which playlist items can play now on this player, and each as the screen shows it. */
class PlayableItems(private val files: ContentFiles, private val links: LinkHealth, private val web: Boolean) {
    /** A file that is on disk; a link with its URL that has not failed lately, a web page only with a Chromium. */
    fun canPlay(item: PlaylistItem, now: Instant): Boolean = when (item.kind) {
        MediaKind.IMAGE, MediaKind.VIDEO -> files.has(item.sha256)
        MediaKind.STREAM -> item.url?.let { links.isUp(it, now) } == true
        MediaKind.WEB -> web && item.url?.let { links.isUp(it, now) } == true
    }

    /** [item] as the screen shows it; null for a file not on disk or a link with no URL. */
    fun screenItem(item: PlaylistItem): ScreenItem? = if (item.kind.isLink) {
        item.url?.let { ScreenItem(null, item.kind, item.durationMs, it, item.refreshMs) }
    } else {
        files.path(item.sha256)?.let { ScreenItem(it, item.kind, item.durationMs) }
    }
}

/** mpv could not play what it was given: a stream on screen is set aside for a while. */
fun PlayerEngine.streamFailed() {
    val streams = state.value.items.filter { it.kind == MediaKind.STREAM }.mapNotNull { it.url }
    if (streams.isEmpty()) return
    Log.warn("A stream could not be opened; trying it again in a minute")
    linkFailed(streams)
}
