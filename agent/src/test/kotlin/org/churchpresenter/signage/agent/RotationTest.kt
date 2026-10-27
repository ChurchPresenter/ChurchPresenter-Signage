package org.churchpresenter.signage.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.churchpresenter.signage.core.Manifest
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.Playlist
import org.churchpresenter.signage.core.PlaylistItem
import org.churchpresenter.signage.core.ScreenSettings
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A rotation — a playlist with a link and more — shown one item at a time: each for its time, a
 * video without one to its end, a link that fails skipped, and from the top when it comes back.
 */
@OptIn(ExperimentalPathApi::class)
class RotationTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second
    private val clock = MutableClock(instant("2026-10-19T08:00"))

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private val weather = PlaylistItem(kind = MediaKind.WEB, url = "https://weather.example.org", durationMs = 20_000)
    private val picture = PlaylistItem(pictureA.sha, MediaKind.IMAGE, durationMs = 10_000)
    private val clip = PlaylistItem(pictureB.sha, MediaKind.VIDEO)
    private val live = PlaylistItem(kind = MediaKind.STREAM, url = "https://example.org/live.m3u8", durationMs = 30_000)

    private fun install(vararg items: PlaylistItem) {
        store.files.put(pictureA.sha, pictureA.bytes.inputStream())
        store.files.put(pictureB.sha, pictureB.bytes.inputStream())
        val manifest = Manifest(
            ownerId = OWNER,
            screen = ScreenSettings("Lobby"),
            playlists = listOf(
                Playlist("rotation", "Rotation", items.toList()),
                Playlist("welcome", "Welcome", listOf(image(pictureA))),
            ),
            defaultPlaylistId = "rotation",
            timeZone = zone.id,
        )
        store.commit(StoredManifest("v", manifest))
    }

    private fun engine() = PlayerEngine(store, FakeCec(), clock::read, LinkHealth(), web = true)

    private fun PlayerEngine.showing(): Pair<MediaKind, Boolean> =
        state.value.items.single().kind to state.value.stepped

    private fun after(seconds: Long, engine: PlayerEngine) {
        clock.now = clock.now.plusSeconds(seconds)
        engine.tick()
    }

    @Test
    fun eachStepStaysForItsTimeAndTheRotationGoesRound() {
        install(weather, picture, live)
        val engine = engine()
        engine.tick()
        assertEquals(MediaKind.WEB to true, engine.showing())
        after(19, engine)
        assertEquals(MediaKind.WEB, engine.showing().first)
        after(1, engine)
        assertEquals(MediaKind.IMAGE, engine.showing().first)
        after(10, engine)
        assertEquals(MediaKind.STREAM, engine.showing().first)
        after(30, engine)
        assertEquals(MediaKind.WEB, engine.showing().first)
    }

    @Test
    fun aVideoWithoutATimeMovesOnWhenItEnds() {
        install(weather, clip)
        val engine = engine()
        engine.tick()
        after(20, engine)
        assertEquals(MediaKind.VIDEO, engine.showing().first)
        after(600, engine)
        assertEquals(MediaKind.VIDEO, engine.showing().first)
        engine.itemEnded()
        assertEquals(MediaKind.WEB, engine.showing().first)
    }

    @Test
    fun aLinkThatFailsIsSkippedNotTheWholeRotation() {
        install(weather, picture)
        val engine = engine()
        engine.tick()
        engine.linkFailed(listOf(weather.url!!))
        assertEquals("rotation" to MediaKind.IMAGE, engine.state.value.playlistId to engine.showing().first)
        // Ten seconds on, the picture's time is up, but the page is still set aside: the picture again.
        after(10, engine)
        assertEquals(MediaKind.IMAGE, engine.showing().first)
        after(50, engine)
        assertEquals(MediaKind.WEB, engine.showing().first)
    }

    @Test
    fun aRotationStartsAgainFromTheTopWhenItComesBack() {
        install(weather, picture)
        val engine = engine()
        engine.tick()
        after(20, engine)
        assertEquals(MediaKind.IMAGE, engine.showing().first)
        engine.testing = ScreenItem(null, MediaKind.WEB, null, "data:text/html,", maxFps = 15)
        engine.testing = null
        // The test took the screen but not the rotation: it carries on where it was.
        assertEquals(MediaKind.IMAGE, engine.showing().first)
        val rotation = Rotation(PlayableItems(store.files, LinkHealth(), web = true))
        val playlist = Playlist("r", "R", listOf(weather, picture))
        assertEquals(weather, rotation.current(playlist, clock.now))
        rotation.reset()
        assertEquals(weather, rotation.current(playlist, clock.now.plusSeconds(25)))
    }

    @Test
    fun aPlaylistOfFilesIsLeftToMpv() {
        install(picture, clip)
        val engine = engine()
        engine.tick()
        assertEquals(2 to false, engine.state.value.items.size to engine.state.value.stepped)
        assertTrue(Rotation.isRotation(Playlist("r", "R", listOf(weather, picture))))
        assertEquals(listOf(Rotation.DEFAULT_LINK_STEP_MS, ScreenItem.DEFAULT_PICTURE_MS, null),
            listOf(PlaylistItem(kind = MediaKind.WEB), PlaylistItem("a", MediaKind.IMAGE), clip).map(Rotation::stepMs))
    }

    @Test
    fun aStepHasNoTimeOfItsOwnInMpv() {
        fun options(item: ScreenItem): String? {
            val step = ScreenState(ScreenMode.PLAYLIST, "r", listOf(item), stepped = true)
            val loadfile = MpvScene.commands(null, step, Path("/b")).filterIsInstance<JsonObject>()
                .single { it["name"]?.jsonPrimitive?.content == "loadfile" }
            return loadfile["options"]?.jsonPrimitive?.content
        }
        assertEquals("image-display-duration=inf", options(ScreenItem(Path("/p"), MediaKind.IMAGE, 10_000)))
        assertEquals("loop-file=inf", options(ScreenItem(Path("/v"), MediaKind.VIDEO, 30_000)))
        assertEquals(null, options(ScreenItem(Path("/v"), MediaKind.VIDEO, null)))
        val stream = ScreenItem(null, MediaKind.STREAM, 30_000, "rtsp://c")
        assertEquals("network-timeout=15,loop-file=inf", options(stream))
    }
}
