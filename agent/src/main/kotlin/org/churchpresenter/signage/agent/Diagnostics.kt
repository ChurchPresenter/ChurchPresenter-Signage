package org.churchpresenter.signage.agent

import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.User
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties

/**
 * Crash reports to Sentry (the church-projector / church-presenter-signage project), as ChurchPresenter
 * sends its own: what crashed, every error the agent logs, and a few faults worth a look of their
 * own (an update rolled back, mpv that keeps exiting); warnings ride along as breadcrumbs.
 *
 * Nothing is sent without a DSN in `sentry.properties`, and nothing while the owning ChurchPresenter's
 * analytics setting is off ([Manifest.diagnostics][org.churchpresenter.signage.core.Manifest]), asked
 * again before each event. No host name, and network addresses are masked before anything leaves.
 */
object Diagnostics {
    /** Where reports go and what sent them: the build ([release], a [production] one or not) and the screen. */
    data class Setup(
        val dsn: String,
        val release: String,
        val production: Boolean,
        val platform: String,
        /** The player's id; only its hash ([anonymous]) is sent. */
        val installId: String,
    )

    @Volatile
    private var started = false

    /** Starts reporting as [setup] says; [allowed] is asked before each event. Does nothing with a blank DSN. */
    fun start(setup: Setup, allowed: () -> Boolean) {
        if (setup.dsn.isBlank()) {
            Log.info("Crash reporting off: this build has no Sentry DSN")
            return
        }
        Sentry.init { options -> configure(options, setup, allowed) }
        Sentry.setTag("platform", setup.platform)
        Sentry.setTag("os.family", System.getProperty("os.name").orEmpty())
        Sentry.setTag("os.arch", System.getProperty("os.arch").orEmpty())
        Sentry.setUser(User().apply { id = anonymous(setup.installId) })
        started = true
    }

    /** What every event is sent with; separate from [start] so a test can read it without a DSN. */
    internal fun configure(options: SentryOptions, setup: Setup, allowed: () -> Boolean) {
        options.dsn = setup.dsn
        options.release = "churchpresenter-signage@${setup.release}"
        options.environment = if (setup.production) "production" else "development"
        options.isSendDefaultPii = false
        options.isAttachServerName = false
        options.maxBreadcrumbs = MAX_BREADCRUMBS
        options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> if (allowed()) scrub(event) else null }
        options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { crumb, _ ->
            crumb.apply { message = message?.let(::mask) }
        }
    }

    /** A tag on every later event, such as the mpv version; a blank one is left off. */
    fun tag(key: String, value: String) {
        if (started && value.isNotBlank()) Sentry.setTag(key, value)
    }

    /** An error the agent logged, sent as one: with its exception's stack when it has one. */
    fun error(message: String, cause: Throwable?) {
        if (!started) return
        if (cause != null) {
            Sentry.captureException(cause) { scope -> scope.setExtra("log", mask(message)) }
        } else {
            Sentry.captureMessage(message, SentryLevel.ERROR)
        }
    }

    /** A warning, kept to go with the next event. */
    fun breadcrumb(message: String) {
        if (started) Sentry.addBreadcrumb(Breadcrumb.info(message).apply { level = SentryLevel.WARNING })
    }

    /**
     * A fault worth an issue of its own though nothing threw: [title] stays the same every time, so
     * they group; what varies goes in [tags] and [details].
     */
    fun report(title: String, tags: Map<String, String>, details: List<String> = emptyList()) {
        if (!started) return
        Sentry.captureMessage(title, SentryLevel.ERROR) { scope ->
            tags.filterValues { it.isNotBlank() }.forEach { (key, value) -> scope.setTag(key, value) }
            if (details.isNotEmpty()) scope.setExtra("details", details.joinToString("\n") { mask(it) })
        }
    }

    /** The `dsn` of a `sentry.properties`, or "" when there is none. */
    fun dsnFrom(stream: InputStream?): String = try {
        stream?.use { input -> Properties().apply { load(input) }.getProperty("dsn", "").trim() }.orEmpty()
    } catch (_: IOException) {
        ""
    }

    /** IPv4 addresses replaced: a screen's LAN and ChurchPresenter's address say nothing a fix needs. */
    fun mask(text: String): String = IPV4.replace(text, "<address>")

    /** The install id as Sentry sees it: a hash, so events group by screen without naming it. */
    fun anonymous(installId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(installId.toByteArray()).take(ID_BYTES).joinToString("") { "%02x".format(it) }

    private fun scrub(event: SentryEvent): SentryEvent = event.apply {
        message?.let { it.formatted = it.formatted?.let(::mask); it.message = it.message?.let(::mask) }
        exceptions?.forEach { it.value = it.value?.let(::mask) }
    }

    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private const val MAX_BREADCRUMBS = 50
    private const val ID_BYTES = 8
}

/**
 * Notices mpv exiting again and again: [exited] answers true once [limit] exits have come within
 * [windowMs], then not again for [quietMs], so a screen stuck that way files one report an hour.
 */
class ExitWatch(
    private val clock: () -> Long = System::currentTimeMillis,
    private val limit: Int = DEFAULT_LIMIT,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val quietMs: Long = DEFAULT_QUIET_MS,
) {
    private val exits = ArrayDeque<Long>()
    private var reportedAt: Long? = null

    fun exited(): Boolean {
        val now = clock()
        exits.addLast(now)
        while (exits.isNotEmpty() && now - exits.first() > windowMs) exits.removeFirst()
        val quiet = reportedAt?.let { now - it < quietMs } ?: false
        if (exits.size < limit || quiet) return false
        reportedAt = now
        exits.clear()
        return true
    }

    companion object {
        const val DEFAULT_LIMIT = 3
        const val DEFAULT_WINDOW_MS = 10 * 60_000L
        const val DEFAULT_QUIET_MS = 60 * 60_000L
    }
}
