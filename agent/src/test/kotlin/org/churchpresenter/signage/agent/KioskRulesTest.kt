package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.Orientation
import org.churchpresenter.signage.core.PlaylistItem
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The kiosk's rules on their own: when a page starts over, which pages it may open, how its browser
 * is started, and how touches become the switch gesture.
 */
class KioskRulesTest {
    @Test
    fun aPageNobodyTouchedNeverStartsOver() {
        val timer = IdleTimer(idleMs = 30_000, startedAtMs = 0)
        assertEquals(IdleTimer.State.Running, timer.state(10_000_000))
    }

    @Test
    fun aTouchedPageCountsDownItsLastSecondsThenStartsOver() {
        val timer = IdleTimer(idleMs = 30_000, countdownMs = 10_000, startedAtMs = 0)
        timer.touched(1_000)
        assertEquals(IdleTimer.State.Running, timer.state(20_000))
        assertEquals(IdleTimer.State.Counting(10), timer.state(21_000))
        assertEquals(IdleTimer.State.Counting(1), timer.state(30_500))
        assertEquals(IdleTimer.State.Expired, timer.state(31_000))
        // Another touch starts the wait again; an older one does not move it back.
        timer.touched(40_000)
        timer.touched(35_000)
        assertEquals(IdleTimer.State.Running, timer.state(50_000))
        assertEquals(IdleTimer.State.Expired, timer.state(70_000))
    }

    @Test
    fun aPageOpensItsOwnSiteAndTheOnesAllowedWithTheirSubdomains() {
        val nav = KioskNavigation("https://www.signup.example.org/retreat", listOf(" https://Pay.Example.com/x ", ""))
        listOf(
            "https://signup.example.org/thanks",
            "https://forms.signup.example.org/",
            "http://www.signup.example.org",
            "https://pay.example.com/checkout",
            "https://eu.pay.example.com.",
        ).forEach { assertTrue(nav.allows(it), it) }
        listOf(
            "https://example.org/",
            "https://evil-signup.example.org.attacker.com/",
            "https://pay.example.com.attacker.com/",
            "file:///etc/passwd",
            "chrome://settings",
            "javascript:alert(1)",
            "not a url",
        ).forEach { assertFalse(nav.allows(it), it) }
    }

    @Test
    fun anyHostOpensEveryWebPageButNothingElse() {
        val nav = KioskNavigation("https://signup.example.org", listOf(PlaylistItem.ANY_HOST))
        assertTrue(nav.allows("https://anything.example.net/page"))
        assertFalse(nav.allows("file:///etc/passwd"))
        assertFalse(nav.allows("chrome://settings"))
    }

    @Test
    fun theKioskBrowserIsPrivateAndForgetful() {
        val command = KioskCommand.command("/usr/bin/cage", "/usr/bin/chromium", Path("/run/user/1/kiosk"))
        assertEquals(listOf("/usr/bin/cage", "-d", "--", "/usr/bin/chromium"), command.take(4))
        listOf(
            "--kiosk", "--incognito", "--user-data-dir=/run/user/1/kiosk", "--password-store=basic",
            "--disable-crash-reporter", "--disable-breakpad", "--remote-debugging-address=127.0.0.1",
        ).forEach { assertTrue(it in command, it) }
        assertEquals("about:blank", command.last())
    }

    @Test
    fun libinputsTouchesAndAbsolutePointerAreRead() {
        val events = LibinputEvents()
        assertEquals(
            TouchEvent.Down(0, 3.5, 4.25),
            events.parse("-event5   TOUCH_DOWN       +2.345s\t0 (0)  3.50/ 4.25 (9.31/9.21mm)"),
        )
        assertEquals(
            TouchEvent.Move(1, 50.0, 60.0),
            events.parse(" event5   TOUCH_MOTION     +2.355s\t1 (1) 50.00/60.00 (1/1mm)"),
        )
        assertEquals(TouchEvent.Up(0), events.parse(" event5   TOUCH_UP         +2.500s\t0 (0)"))
        assertEquals(null, events.parse(" event5   TOUCH_FRAME      +2.500s"))
        assertEquals(null, events.parse(" event2   KEYBOARD_KEY     +3.000s\t*** (-1) pressed"))
        // A touchscreen that reports itself as an absolute pointer: its button is the finger.
        assertEquals(null, events.parse(" event7   POINTER_BUTTON   +1.000s\tBTN_LEFT (272) pressed, seat count: 1"))
        assertEquals(
            TouchEvent.Move(-1, 10.0, 20.0),
            events.parse(" event7   POINTER_MOTION_ABSOLUTE +1.1s\t10.00/20.00"),
        )
        assertEquals(
            TouchEvent.Down(-1, 10.0, 20.0),
            events.parse(" event7   POINTER_BUTTON   +1.2s\tBTN_LEFT (272) pressed, seat count: 1"),
        )
        assertEquals(
            TouchEvent.Up(-1),
            events.parse(" event7   POINTER_BUTTON   +1.3s\tBTN_LEFT (272) released, seat count: 0"),
        )
    }

    @Test
    fun aFingerHeldInTheCornerFiresOncePerHold() {
        val hold = CornerHold(holdMs = 5_000)
        hold.down(0, 3.0 to 4.0, nowMs = 0)
        assertFalse(hold.check(4_999))
        assertTrue(hold.check(5_000))
        assertFalse(hold.check(9_000), "once per hold")
        hold.up(0)
        hold.down(0, 3.0 to 4.0, nowMs = 10_000)
        assertTrue(hold.check(15_000))
    }

    @Test
    fun liftingOrLeavingTheCornerOrStartingElsewhereCancelsIt() {
        val hold = CornerHold(holdMs = 5_000)
        hold.down(0, 3.0 to 4.0, nowMs = 0)
        hold.up(0)
        assertFalse(hold.check(6_000))
        hold.down(1, 3.0 to 4.0, nowMs = 0)
        hold.move(1, 40.0 to 4.0)
        assertFalse(hold.check(6_000))
        hold.down(2, 50.0 to 50.0, nowMs = 0)
        assertFalse(hold.check(6_000))
    }

    @Test
    fun theCornerPeopleSeeFollowsHowTheScreenIsMounted() {
        // The panel's corner that shows the picture's top left, for each way the screen is mounted.
        val topLeft = mapOf(
            Orientation.LANDSCAPE to (0.0 to 0.0),
            Orientation.PORTRAIT to (100.0 to 0.0),
            Orientation.LANDSCAPE_FLIPPED to (100.0 to 100.0),
            Orientation.PORTRAIT_FLIPPED to (0.0 to 100.0),
        )
        topLeft.forEach { (mounted, panel) ->
            assertEquals(0.0 to 0.0, TouchInput.upright(mounted, panel.first, panel.second), mounted.name)
        }
    }
}
