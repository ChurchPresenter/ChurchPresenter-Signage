package org.churchpresenter.signage.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.churchpresenter.signage.core.Manifest
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.Playlist
import org.churchpresenter.signage.core.PlaylistItem
import org.churchpresenter.signage.core.ScreenSettings
import java.time.Duration
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Links on the screen: what mpv is told to open for a stream and a web page, which items count as
 * playable, and how a link that fails gives way to the idle card and is tried again.
 */
@OptIn(ExperimentalPathApi::class)
class LinkPlaybackTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second
    private val clock = MutableClock(instant("2026-10-19T08:00"))
    private val blank = Path("/store/blank.png")

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private val stream = PlaylistItem(kind = MediaKind.STREAM, url = "https://example.org/live.m3u8")
    private val web =
        PlaylistItem(kind = MediaKind.WEB, url = "https://example.org/weather", refreshMs = 60_000, maxFps = 30)

    /**
     * The screen plays [links] as its default playlist, with Welcome (a picture) beside it; with no
     * links, Welcome. A link that cannot play has no other default to fall back to, so it is the idle card.
     */
    private fun install(links: PlaylistItem? = null) {
        store.files.put(pictureA.sha, pictureA.bytes.inputStream())
        val playlists = listOf(Playlist("welcome", "Welcome", listOf(image(pictureA)))) +
            listOfNotNull(links?.let { Playlist("links", "Links", listOf(it)) })
        val manifest = Manifest(
            ownerId = OWNER,
            screen = ScreenSettings("Lobby"),
            playlists = playlists,
            defaultPlaylistId = if (links != null) "links" else "welcome",
            timeZone = zone.id,
        )
        store.commit(StoredManifest("v-${links?.kind}", manifest))
    }

    private fun engine(web: Boolean = true) =
        PlayerEngine(store, FakeCec(), clock::read, LinkHealth(Duration.ofMinutes(1)), web)

    private fun loadfiles(state: ScreenState, webStream: (String) -> String = { "local:$it" }) =
        MpvScene.commands(null, state, blank, webStream).filterIsInstance<JsonObject>()
            .filter { it["name"]?.jsonPrimitive?.content == "loadfile" }
            .map { it["url"]?.jsonPrimitive?.content to it["options"]?.jsonPrimitive?.content }

    @Test
    fun aStreamIsOpenedFromItsOwnAddressAndGivesUpWhenItStopsAnswering() {
        val item = ScreenItem(null, MediaKind.STREAM, null, "rtsp://camera/live")
        val (url, options) = loadfiles(ScreenState(ScreenMode.PLAYLIST, "links", listOf(item))).single()
        assertEquals("rtsp://camera/live", url)
        assertEquals("network-timeout=15,loop-file=inf", options)
        val timed = item.copy(durationMs = 30_000)
        val timedOptions = loadfiles(ScreenState(ScreenMode.PLAYLIST, "links", listOf(timed))).single().second
        assertEquals("network-timeout=15,end=30.000", timedOptions)
    }

    @Test
    fun aWebPageIsOpenedFromTheLocalStreamItIsDrawnInto() {
        val item = ScreenItem(null, MediaKind.WEB, null, "https://example.org", 60_000, 30)
        val (url, options) = loadfiles(ScreenState(ScreenMode.PLAYLIST, "links", listOf(item))).single()
        assertEquals("local:https://example.org", url)
        assertEquals("demuxer-lavf-format=mpjpeg,untimed=yes,cache=no,demuxer-readahead-secs=0", options)
    }

    @Test
    fun aLinkPlaysWithNoFileAndCarriesItsSettingsToTheScreen() {
        install(web)
        val engine = engine()
        engine.tick()
        val state = engine.state.value
        assertEquals("links", state.playlistId)
        assertEquals(ScreenItem(null, MediaKind.WEB, null, web.url, 60_000, 30), state.items.single())
    }

    @Test
    fun aWebPageNeedsAChromium() {
        install(web)
        val engine = engine(web = false)
        engine.tick()
        assertEquals(ScreenMode.IDLE, engine.state.value.mode)
    }

    @Test
    fun aLinkThatFailsShowsTheIdleCardForAMinuteAndIsTriedAgain() {
        install(web)
        val engine = engine()
        engine.tick()
        engine.linkFailed(listOf(web.url!!))
        assertEquals(ScreenMode.IDLE, engine.state.value.mode)
        clock.now = clock.now.plusSeconds(59)
        engine.tick()
        assertEquals(ScreenMode.IDLE, engine.state.value.mode)
        clock.now = clock.now.plusSeconds(1)
        engine.tick()
        assertEquals("links", engine.state.value.playlistId)
    }

    @Test
    fun mpvFailingSetsAsideTheStreamOnScreenAndNothingElse() {
        install(stream)
        val engine = engine()
        engine.tick()
        engine.streamFailed()
        assertEquals(ScreenMode.IDLE, engine.state.value.mode)
        // On the idle card now, another failure has no stream to set aside, and changes nothing.
        engine.streamFailed()
        assertEquals(ScreenMode.IDLE, engine.state.value.mode)
        clock.now = clock.now.plusSeconds(60)
        engine.tick()
        assertEquals("links", engine.state.value.playlistId)
    }

    @Test
    fun aTestTakesTheScreenAndGivesItBack() {
        install()
        val engine = engine()
        engine.tick()
        val page = ScreenItem(null, MediaKind.WEB, null, "data:text/html,", maxFps = 15)
        engine.testing = page
        assertEquals(ScreenMode.PLAYLIST to listOf(page), engine.state.value.mode to engine.state.value.items)
        assertNull(engine.state.value.playlistId)
        engine.testing = null
        assertEquals("welcome", engine.state.value.playlistId)
    }

    @Test
    fun onlyFilesOnDiskAndLinksWithAnAddressArePlayable() {
        val links = LinkHealth()
        val items = PlayableItems(store.files, links, web = true)
        val now = clock.now
        assertFalse(items.canPlay(image(pictureA), now))
        store.files.put(pictureA.sha, pictureA.bytes.inputStream())
        assertTrue(items.canPlay(image(pictureA), now))
        assertTrue(items.canPlay(stream, now))
        assertFalse(items.canPlay(PlaylistItem(kind = MediaKind.STREAM), now))
        assertNull(items.screenItem(PlaylistItem(kind = MediaKind.WEB)))
        links.failed(stream.url!!, now)
        assertFalse(items.canPlay(stream, now))
        assertTrue(items.canPlay(stream, now.plusSeconds(60)))
        assertFalse(PlayableItems(store.files, links, web = false).canPlay(web, now))
    }
}
