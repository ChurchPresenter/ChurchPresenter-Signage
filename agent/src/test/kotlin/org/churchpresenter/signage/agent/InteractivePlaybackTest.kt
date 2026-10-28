package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.Manifest
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.Playlist
import org.churchpresenter.signage.core.PlaylistItem
import org.churchpresenter.signage.core.ScreenSettings
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An interactive page in a playlist: it plays only where the player has the kiosk browser, is handed
 * to it rather than drawn for mpv, and stops a rotation on itself for as long as the playlist plays.
 */
@OptIn(ExperimentalPathApi::class)
class InteractivePlaybackTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second
    private val clock = MutableClock(instant("2026-10-19T08:00"))

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private val signup = PlaylistItem(
        kind = MediaKind.WEB, url = "https://signup.example.org", durationMs = 20_000,
        interactive = true, idleResetMs = 45_000, allowedHosts = listOf("pay.example.com"),
    )

    private fun install(vararg items: PlaylistItem) {
        store.files.put(pictureA.sha, pictureA.bytes.inputStream())
        store.commit(
            StoredManifest(
                "v",
                Manifest(
                    ownerId = OWNER,
                    screen = ScreenSettings("Lobby"),
                    playlists = listOf(
                        Playlist("signup", "Sign-up", items.toList()),
                        Playlist("welcome", "Welcome", listOf(image(pictureA))),
                    ),
                    defaultPlaylistId = "signup",
                    timeZone = zone.id,
                ),
            ),
        )
    }

    private fun engine(interactive: Boolean = true) =
        PlayerEngine(store, FakeCec(), clock::read, LinkHealth(), web = true, interactive = interactive)

    @Test
    fun aTouchPageIsTheKiosksWithItsSettings() {
        install(signup)
        val engine = engine().apply { tick() }
        val kiosk = engine.state.value.kiosk!!
        assertEquals("https://signup.example.org", kiosk.url)
        assertEquals(45_000L to listOf("pay.example.com"), kiosk.idleResetMs to kiosk.allowedHosts)
        assertTrue(engine.state.value.kioskWanted)
    }

    @Test
    fun aPlayerWithoutTheKioskShowsItsIdleCardInstead() {
        install(signup)
        val engine = engine(interactive = false).apply { tick() }
        assertNull(engine.state.value.kiosk)
        assertEquals(ScreenMode.IDLE, engine.state.value.mode, "the page is the default, so nothing else plays")
    }

    @Test
    fun aRotationStopsOnItsTouchPage() {
        install(image(pictureA), signup)
        val engine = engine().apply { tick() }
        assertEquals(MediaKind.IMAGE, engine.state.value.items.single().kind)
        clock.now = clock.now.plusSeconds(5)
        engine.tick()
        assertTrue(engine.state.value.kioskWanted)
        clock.now = clock.now.plusSeconds(3_600)
        engine.tick()
        assertTrue(engine.state.value.kioskWanted, "its time does not move it on")
        assertEquals(null, Rotation.stepMs(signup))
    }
}
