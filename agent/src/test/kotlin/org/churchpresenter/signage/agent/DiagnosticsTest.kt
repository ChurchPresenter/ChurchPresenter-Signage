package org.churchpresenter.signage.agent

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsTest {
    private val setup = Diagnostics.Setup("x", "26.0.9", production = true, platform = "linux_x64", installId = "p")
    private var allowed = true
    private val options = SentryOptions().also {
        Diagnostics.configure(it, setup.copy(dsn = "https://key@example.ingest.sentry.io/1")) { allowed }
    }

    private fun event(text: String) = SentryEvent().apply {
        message = Message().apply { formatted = text }
        exceptions = listOf(SentryException().apply { value = "refused: /$text" })
    }

    @Test
    fun eventsGoAsTheReleaseWithNoHostName() {
        assertEquals("churchpresenter-signage@26.0.9", options.release)
        assertEquals("production", options.environment)
        assertFalse(options.isSendDefaultPii)
        assertFalse(options.isAttachServerName)
        val dev = SentryOptions().also { Diagnostics.configure(it, setup.copy(production = false)) { true } }
        assertEquals("development", dev.environment)
    }

    @Test
    fun nothingIsSentWhileChurchPresenterSaysNo() {
        allowed = false
        assertNull(options.beforeSend?.execute(event("x"), Hint()))
        allowed = true
        assertTrue(options.beforeSend?.execute(event("x"), Hint()) != null)
    }

    @Test
    fun addressesAreMaskedInEventsAndBreadcrumbs() {
        val sent = options.beforeSend?.execute(event("ChurchPresenter at 192.168.2.52:8765"), Hint())
        assertEquals("ChurchPresenter at <address>:8765", sent?.message?.formatted)
        assertEquals("refused: /ChurchPresenter at <address>:8765", sent?.exceptions?.single()?.value)
        val crumb = options.beforeBreadcrumb?.execute(Breadcrumb.info("not reachable at 10.0.0.5"), Hint())
        assertEquals("not reachable at <address>", crumb?.message)
        assertEquals("version 26.0.9 stays", Diagnostics.mask("version 26.0.9 stays"))
    }

    @Test
    fun theDsnComesFromSentryProperties() {
        assertEquals("https://k@h/1", Diagnostics.dsnFrom("dsn = https://k@h/1\n".byteInputStream()))
        assertEquals("", Diagnostics.dsnFrom("dsn=\n".byteInputStream()))
        assertEquals("", Diagnostics.dsnFrom(null))
    }

    @Test
    fun theInstallIdIsSentOnlyAsAHash() {
        val id = Diagnostics.anonymous("player-1")
        assertEquals(id, Diagnostics.anonymous("player-1"))
        assertNotEquals(id, Diagnostics.anonymous("player-2"))
        assertFalse("player" in id)
        assertEquals(16, id.length)
    }

    @Test
    fun withoutADsnNothingStartsAndCallsAreHarmless() {
        Diagnostics.start(setup.copy(dsn = "")) { true }
        Diagnostics.error("boom", IllegalStateException("x"))
        Diagnostics.report("title", mapOf("a" to "b"), listOf("line"))
        Diagnostics.breadcrumb("crumb")
        Diagnostics.tag("k", "v")
    }
}

class ExitWatchTest {
    private var now = 0L
    private val watch = ExitWatch(clock = { now }, limit = 3, windowMs = 600_000, quietMs = 3_600_000)

    private fun exitAt(minute: Long): Boolean {
        now = minute * 60_000
        return watch.exited()
    }

    @Test
    fun threeExitsWithinTenMinutesAreAReport() {
        assertFalse(exitAt(0))
        assertFalse(exitAt(1))
        assertTrue(exitAt(2))
    }

    @Test
    fun exitsFarApartAreNot() {
        assertFalse(exitAt(0))
        assertFalse(exitAt(20))
        assertFalse(exitAt(40))
    }

    @Test
    fun aScreenStuckThatWayReportsOnceAnHour() {
        exitAt(0)
        exitAt(1)
        assertTrue(exitAt(2))
        assertFalse(exitAt(3))
        assertFalse(exitAt(4))
        assertFalse(exitAt(5), "still within the quiet hour")
        exitAt(70)
        exitAt(71)
        assertTrue(exitAt(72))
    }
}
