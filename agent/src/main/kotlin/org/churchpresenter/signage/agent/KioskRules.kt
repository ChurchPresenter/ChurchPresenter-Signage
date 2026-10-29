package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.PlaylistItem
import java.net.URI
import java.nio.file.Path

/**
 * When an interactive page starts over: [idleMs] after its last touch, with the last
 * [countdownMs] of that shown on screen so whoever is still there can tap to keep going. A page nobody
 * has touched since it opened is left as it is, since there is nothing of anyone's to clear.
 */
class IdleTimer(private val idleMs: Long, private val countdownMs: Long = COUNTDOWN_MS, startedAtMs: Long) {
    private var lastTouchMs = startedAtMs
    private var touched = false

    sealed interface State {
        data object Running : State

        /** Starting over in [secondsLeft] unless someone touches the screen. */
        data class Counting(val secondsLeft: Int) : State

        /** Time to start over. */
        data object Expired : State
    }

    fun touched(nowMs: Long) {
        lastTouchMs = maxOf(lastTouchMs, nowMs)
        touched = true
    }

    fun state(nowMs: Long): State {
        if (!touched) return State.Running
        val left = lastTouchMs + idleMs - nowMs
        return when {
            left <= 0 -> State.Expired
            left <= countdownMs -> State.Counting(((left + MS_PER_SECOND - 1) / MS_PER_SECOND).toInt())
            else -> State.Running
        }
    }

    companion object {
        const val COUNTDOWN_MS = 10_000L
        private const val MS_PER_SECOND = 1_000L
    }
}

/**
 * Which pages an interactive page may go to: http(s) on its own site or one of [allowedHosts], each
 * with its subdomains, or any http(s) page when they hold [PlaylistItem.ANY_HOST]. Anything else (a
 * file, a browser page, another site) is not opened.
 */
class KioskNavigation(home: String, allowedHosts: List<String>) {
    private val anySite = PlaylistItem.ANY_HOST in allowedHosts
    private val hosts: Set<String> = (listOfNotNull(siteOf(home)) + allowedHosts.mapNotNull(::cleanHost)).toSet()

    fun allows(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in WEB_SCHEMES) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return anySite || hosts.any { host == it || host.endsWith(".$it") }
    }

    private companion object {
        val WEB_SCHEMES = setOf("http", "https")

        /** A host name as typed in ChurchPresenter: no scheme, path or `www.`, lower case. */
        fun cleanHost(typed: String): String? = typed.trim().lowercase()
            .substringAfter("://").substringBefore('/').substringBefore(':').trimEnd('.')
            .removePrefix("www.").takeIf { it.isNotEmpty() }

        /** A page's site: its host without `www.`, so `www.example.org` also allows `forms.example.org`. */
        fun siteOf(url: String): String? = runCatching { URI(url).host }.getOrNull()?.let(::cleanHost)
    }
}

/** How the kiosk browser is started: Chromium full screen inside cage, a Wayland compositor that shows one app. */
object KioskCommand {
    /** cage running [chromium] with [profile], opening a blank page that the session then sends where it should go. */
    fun command(cage: String, chromium: String, profile: Path): List<String> =
        listOf(cage) + CAGE_ARGS + "--" + CHROMIUM_ENV + chromium + chromiumArgs(profile)

    /** No window decorations; Ctrl+Alt+F2 still reaches a console login, as it does under mpv. */
    val CAGE_ARGS = listOf("-d", "-s")

    /**
     * Chromium draws its pointer from the system's cursor theme, not cage's blank one ([BlankCursor]):
     * nothing shows on a touch, and a mouse's pointer appears once the mouse moves.
     */
    val CHROMIUM_ENV = listOf("/usr/bin/env", "-u", "XCURSOR_PATH")

    /**
     * Full screen, private and forgetful: an incognito window with nothing of a first run, no
     * translate bar, no pinch zoom or swipe back, no crash dumps, no saved passwords (the managed
     * policy install.sh writes turns the rest off). DevTools on a free port of 127.0.0.1 for the session.
     */
    fun chromiumArgs(profile: Path): List<String> = listOf(
        "--ozone-platform=wayland",
        "--kiosk",
        "--incognito",
        "--user-data-dir=$profile",
        "--remote-debugging-port=0",
        "--remote-debugging-address=127.0.0.1",
        "--no-first-run",
        "--no-default-browser-check",
        "--noerrdialogs",
        "--disable-infobars",
        "--disable-session-crashed-bubble",
        "--disable-translate",
        "--disable-sync",
        "--password-store=basic",
        "--disable-pinch",
        "--overscroll-history-navigation=0",
        "--disable-features=Translate,TouchpadOverscrollHistoryNavigation",
        "--autoplay-policy=no-user-gesture-required",
        "--check-for-update-interval=31536000",
        // A crash dump could hold what someone typed.
        "--disable-crash-reporter",
        "--disable-breakpad",
        "about:blank",
    )
}
