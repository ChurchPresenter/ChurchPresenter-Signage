package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.Orientation
import org.churchpresenter.signage.core.ScreenMessage
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ChurchPresenter's message on the screen: kept from the manifest until it runs out, and how it is drawn. */
@OptIn(ExperimentalPathApi::class)
class ScreenNoticeTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second
    private val clock = MutableClock(instant("2026-10-19T08:00"))

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private fun withMessage(message: ScreenMessage?) {
        store.files.put(pictureA.sha, pictureA.bytes.inputStream())
        val manifest = manifestOf(pictureA)
        store.commit(StoredManifest("v", manifest.copy(screen = manifest.screen.copy(message = message))))
    }

    @Test
    fun theManifestsMessageShowsUntilItRunsOut() {
        val until = clock.now.toEpochMilli() + 60_000
        withMessage(ScreenMessage("Parking lot B is full", untilMs = until))
        val engine = PlayerEngine(store, FakeCec(), clock::read).apply { tick() }
        assertEquals("Parking lot B is full", engine.state.value.notice?.text)
        clock.now = clock.now.plusSeconds(60)
        engine.tick()
        assertNull(engine.state.value.notice)
    }

    @Test
    fun aBandSitsAcrossTheTopAndAFullScreenMessageHidesWhatPlays() {
        val band = OsdText.notice(ScreenState(ScreenMode.PLAYLIST, notice = ScreenMessage("Welcome"))).orEmpty().lines()
        assertEquals(2, band.size)
        // A sheet 14% of the height across the top, see-through a little; the words in its middle.
        assertTrue("\\p1" in band[0] && "l 1920 0 1920 151 0 151" in band[0] && "\\1a&H1A&" in band[0], band[0])
        assertTrue(band[1].endsWith("}Welcome") && "\\pos(960,76)" in band[1] && "\\q0" in band[1], band[1])
        val full = OsdText.notice(
            ScreenState(ScreenMode.PLAYLIST, notice = ScreenMessage("Evacuate", fullScreen = true)),
        ).orEmpty().lines()
        assertTrue("l 1920 0 1920 1080 0 1080" in full[0] && "\\1a&H00&" in full[0], full[0])
        assertTrue("\\pos(960,540)" in full[1], full[1])
        assertNull(OsdText.notice(ScreenState(ScreenMode.PLAYLIST)))
    }

    @Test
    fun onAPortraitScreenTheBandIsAcrossItsOwnTop() {
        val portrait = ScreenState(
            ScreenMode.PLAYLIST, orientation = Orientation.PORTRAIT, notice = ScreenMessage("Hi"),
        )
        val band = OsdText.notice(portrait).orEmpty().lines()
        // 1080 across and 1920 down as mounted, centred on the panel and turned with it.
        assertTrue("\\pos(420,-420)" in band[0] && "l 1080 0 1080 268 0 268" in band[0], band[0])
        assertTrue("\\frz-90" in band[0], band[0])
    }

    @Test
    fun mpvIsToldOnlyWhenTheMessageChanges() {
        val none = ScreenState(ScreenMode.IDLE)
        val shown = none.copy(notice = ScreenMessage("Hi"))
        fun overlays(previous: ScreenState?, next: ScreenState) =
            MpvScene.commands(previous, next, Path("/blank.png")).map { it.toString() }.filter { "\"id\":4" in it }
        assertEquals(1, overlays(none, shown).size)
        assertTrue(overlays(shown, shown).isEmpty())
        assertTrue("\"format\":\"none\"" in overlays(shown, none).single())
    }

    @Test
    fun aTouchPageIsToldTheMessageAsAScriptCall() {
        val quoted = ScreenMessage("Doors \"open\"", fullScreen = true)
        assertEquals("notice(\"Doors \\\"open\\\"\", true)", noticeCall(quoted))
        assertEquals("notice(\"\", false)", noticeCall(null))
    }
}
