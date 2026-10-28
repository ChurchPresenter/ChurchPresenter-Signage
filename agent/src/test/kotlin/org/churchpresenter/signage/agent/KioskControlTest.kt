package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.KioskPin
import org.churchpresenter.signage.core.Manifest
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.OverrideAction
import org.churchpresenter.signage.core.Playlist
import org.churchpresenter.signage.core.PlaylistItem
import org.churchpresenter.signage.core.ScreenSettings
import org.churchpresenter.signage.core.Until
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The switch made on the screen itself: holding its corner goes to a touch page and back, as a
 * manual switch until the schedule next changes; with a PIN, only once it is typed; and not at all
 * when the screen's gesture is turned off.
 */
@OptIn(ExperimentalPathApi::class)
class KioskControlTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second
    private val clock = MutableClock(instant("2026-10-19T08:00"))
    private val prompt = PinPrompt()
    private val engine =
        PlayerEngine(store, FakeCec(), clock::read, LinkHealth(), web = true, interactive = true, pinPrompt = prompt)
    private val control = KioskControl(store, engine, prompt, clock::read)

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private fun page(url: String) = PlaylistItem(kind = MediaKind.WEB, url = url, interactive = true)

    private fun manifest(screen: ScreenSettings = ScreenSettings("Lobby")) = Manifest(
        ownerId = OWNER,
        screen = screen,
        playlists = listOf(
            Playlist("welcome", "Welcome", listOf(image(pictureA))),
            Playlist("signup", "Sign-up", listOf(page("https://signup.example.org"))),
            Playlist("checkin", "Check-in", listOf(page("https://checkin.example.org"))),
        ),
        defaultPlaylistId = "welcome",
        timeZone = zone.id,
    )

    private fun install(screen: ScreenSettings = ScreenSettings("Lobby")) {
        store.files.put(pictureA.sha, pictureA.bytes.inputStream())
        store.commit(StoredManifest("v", manifest(screen)))
        engine.reloadFromStore()
    }

    private fun showing(): String? = engine.state.value.playlistId

    @Test
    fun theCornerGoesToTheFirstTouchPageAndBack() {
        install()
        assertEquals("welcome", showing())
        control.gesture()
        assertEquals("signup", showing())
        assertTrue(engine.state.value.kioskWanted)
        val override = store.override()!!
        assertEquals(OverrideAction.Play("signup"), override.action)
        assertEquals(Until.NextChange, override.until)
        control.gesture()
        assertEquals("welcome", showing())
    }

    @Test
    fun itGoesToTheChosenTouchPageAndNowhereWhenTurnedOff() {
        install(ScreenSettings("Lobby", kioskGesturePlaylistId = "checkin"))
        control.gesture()
        assertEquals("checkin", showing())
        install(ScreenSettings("Lobby", kioskGesture = false))
        control.gesture()
        assertEquals("checkin", showing(), "the override from before stands; the gesture did nothing")
    }

    @Test
    fun withAPinThePadOpensAndOnlyTheRightPinSwitches() {
        install(ScreenSettings("Lobby", kioskPinHash = KioskPin.hash("2468")))
        control.gesture()
        assertTrue(engine.state.value.pinPrompt)
        assertEquals("welcome", showing())
        assertFalse(control.entered("1111"))
        assertTrue(engine.state.value.pinPrompt)
        assertTrue(control.entered("2468"))
        assertFalse(engine.state.value.pinPrompt)
        assertEquals("signup", showing())
    }

    @Test
    fun thePadClosesOnCancelOrAfterItsTime() {
        install(ScreenSettings("Lobby", kioskPinHash = KioskPin.hash("2468")))
        control.gesture()
        control.cancelled()
        assertFalse(engine.state.value.pinPrompt)
        assertFalse(control.entered("2468"), "a PIN with no pad open does nothing")
        control.gesture()
        clock.now = clock.now.plusMillis(PinPrompt.PROMPT_MS)
        engine.tick()
        assertFalse(engine.state.value.pinPrompt)
    }

    @Test
    fun fiveWrongPinsCloseThePadForAMinute() {
        install(ScreenSettings("Lobby", kioskPinHash = KioskPin.hash("2468")))
        control.gesture()
        repeat(KioskControl.MAX_WRONG_PINS) { assertFalse(control.entered("0000")) }
        assertFalse(engine.state.value.pinPrompt)
        control.gesture()
        assertFalse(control.entered("2468"), "locked")
        clock.now = clock.now.plusMillis(KioskControl.LOCK_MS)
        control.gesture()
        assertTrue(control.entered("2468"))
    }

    @Test
    fun whereTheGestureGoesWhenThereIsLittleToChoose() {
        val onlyPage = manifest().copy(
            playlists = listOf(Playlist("signup", "Sign-up", listOf(page("https://s.example")))),
        )
        assertEquals(OverrideAction.TvOff, KioskControl.target(onlyPage, leaving = true), "nowhere else: dark")
        val noPage = manifest().copy(playlists = listOf(Playlist("welcome", "Welcome", listOf(image(pictureA)))))
        assertNull(KioskControl.target(noPage, leaving = false))
        // The default playlist is a touch page itself: leaving goes to the first that is not.
        val defaultIsPage = manifest().copy(defaultPlaylistId = "signup")
        assertEquals(OverrideAction.Play("welcome"), KioskControl.target(defaultIsPage, leaving = true))
    }

    @Test
    fun noManifestNoGesture() {
        control.gesture()
        assertNull(store.override())
    }
}
