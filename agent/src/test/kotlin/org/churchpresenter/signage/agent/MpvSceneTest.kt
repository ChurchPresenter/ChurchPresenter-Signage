package org.churchpresenter.signage.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.Orientation
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvSceneTest {
    private val picA = ScreenItem(Path("/store/a"), MediaKind.IMAGE, 5_000)
    private val picB = ScreenItem(Path("/store/b"), MediaKind.IMAGE, null)
    private val video = ScreenItem(Path("/store/v"), MediaKind.VIDEO, null)
    private val playlist = ScreenState(ScreenMode.PLAYLIST, "welcome", listOf(picA, picB, video))

    /** Each command as a short line: `loadfile /store/a replace image-display-duration=5.000`. */
    private fun lines(commands: List<JsonElement>): List<String> = commands.map { command ->
        when (command) {
            is JsonArray -> command.joinToString(" ") { it.jsonPrimitive.content }
            is JsonObject -> when (command["name"]?.jsonPrimitive?.content) {
                "osd-overlay" -> "overlay ${command["id"]} ${command["format"]?.jsonPrimitive?.content}"
                else -> listOfNotNull("url", "flags", "options").mapNotNull { command[it]?.jsonPrimitive?.content }
                    .joinToString(" ", prefix = "loadfile ")
            }
            else -> error("unexpected $command")
        }
    }

    @Test
    fun aFreshMpvGetsTheWholePlaylist() {
        assertEquals(
            listOf(
                "set_property video-rotate 0",
                "set_property video-margin-ratio-top 0.0",
                "set_property video-margin-ratio-right 0.0",
                "set_property video-margin-ratio-bottom 0.0",
                "set_property video-margin-ratio-left 0.0",
                "loadfile /store/a replace image-display-duration=5.000",
                "loadfile /store/b append image-display-duration=8.000",
                "loadfile /store/v append",
                "overlay 1 none",
                "overlay 2 none",
            ),
            lines(MpvScene.commands(null, playlist)),
        )
    }

    @Test
    fun nothingChangedSendsNothing() {
        assertEquals(emptyList(), MpvScene.commands(playlist, playlist.copy()))
    }

    @Test
    fun aNewPairingCodeOnlyRedrawsTheText() {
        val idle = ScreenState(ScreenMode.IDLE, message = "code 1234")
        assertEquals(listOf("overlay 1 ass-events"), lines(MpvScene.commands(idle, idle.copy(message = "code 5678"))))
    }

    @Test
    fun aLoneItemStaysUpOrLoopsInPlace() {
        val one = { item: ScreenItem -> lines(MpvScene.commands(playlist, playlist.copy(items = listOf(item)))) }
        assertEquals(listOf("loadfile /store/a replace image-display-duration=inf"), one(picA))
        assertEquals(listOf("loadfile /store/v replace loop-file=inf"), one(video))
        assertEquals(
            listOf("loadfile /store/v replace end=12.500"),
            one(video.copy(durationMs = 12_500)),
        )
    }

    @Test
    fun blankStopsThePlaylistAndClearsTheText() {
        assertEquals(listOf("stop"), lines(MpvScene.commands(playlist, playlist.copy(mode = ScreenMode.BLANK))))
        val idle = ScreenState(ScreenMode.IDLE, idleTitle = "Grace Church")
        assertEquals(listOf("overlay 1 none"), lines(MpvScene.commands(idle, idle.copy(mode = ScreenMode.BLANK))))
    }

    @Test
    fun theIdleLogoSitsInItsBoxTurnedWithAPortraitScreen() {
        val idle = ScreenState(ScreenMode.IDLE, orientation = Orientation.PORTRAIT, idleLogo = Path("/store/logo"))
        val sent = lines(MpvScene.commands(playlist, idle))
        assertEquals("set_property video-rotate 90", sent.first())
        // The mounted top is the panel's right once the picture is turned a quarter clockwise.
        assertTrue("set_property video-margin-ratio-right 0.18" in sent, sent.toString())
        assertTrue("set_property video-margin-ratio-top 0.3" in sent, sent.toString())
        assertTrue("loadfile /store/logo replace image-display-duration=inf" in sent, sent.toString())
    }

    @Test
    fun identifyingDrawsItsOwnLayer() {
        assertEquals(
            listOf("overlay 2 ass-events"),
            lines(MpvScene.commands(playlist, playlist.copy(identify = "Lobby"))),
        )
    }
}
