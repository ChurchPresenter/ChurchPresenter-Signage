package org.churchpresenter.signage.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.Orientation
import java.nio.file.Path
import java.util.Locale

/**
 * The mpv commands that take the screen from one [ScreenState] to the next, sending only what
 * changed: a playlist edit reloads the files, a new pairing code only redraws the text.
 *
 * Files are loaded with per-file options (how long a picture stays, where a video stops) through
 * mpv's named-argument form of `loadfile`, which reads the same on mpv 0.35 and later. A stream is
 * loaded from its own URL; a web page from the local MJPEG stream [WebRenderer] draws it into.
 */
object MpvScene {
    /** The idle card's text and the identify card are drawn in this space and scaled to the screen. */
    const val RES_X = 1920
    const val RES_Y = 1080

    private const val CARD_OVERLAY = 1
    private const val IDENTIFY_OVERLAY = 2

    /**
     * [previous] is what mpv shows now, or null when it has just started. [blank] is a black picture:
     * shown when there is nothing else, rather than stopping, so mpv always has a frame to draw the
     * idle card's text on and to take the Signage tab's preview from. [webStream] gives the local
     * stream a web page is drawn into, from the page's URL.
     */
    fun commands(
        previous: ScreenState?,
        next: ScreenState,
        blank: Path,
        webStream: (String) -> String = { it },
    ): List<JsonElement> = buildList {
        if (previous == null || previous.orientation != next.orientation) {
            add(setProperty("video-rotate", JsonPrimitive(next.orientation.clockwiseDegrees)))
        }
        val content = contentOf(next)
        if (previous == null || boxOf(previous) != boxOf(next) || previous.orientation != next.orientation) {
            addAll(margins(boxOf(next), next.orientation))
        }
        if (previous == null || contentOf(previous) != content) addAll(load(content, blank, webStream))
        val card = OsdText.idleCard(next)
        if (previous == null || OsdText.idleCard(previous) != card) add(overlay(CARD_OVERLAY, card))
        val identify = OsdText.identify(next)
        if (previous == null || OsdText.identify(previous) != identify) add(overlay(IDENTIFY_OVERLAY, identify))
    }

    private sealed interface Content {
        data class Playlist(val items: List<ScreenItem>, val stepped: Boolean) : Content

        data class Logo(val file: Path) : Content

        data object Nothing : Content
    }

    private fun contentOf(state: ScreenState): Content = when {
        state.mode == ScreenMode.PLAYLIST && state.items.isNotEmpty() -> Content.Playlist(state.items, state.stepped)
        state.mode == ScreenMode.IDLE && state.idleLogo != null -> Content.Logo(state.idleLogo)
        else -> Content.Nothing
    }

    private fun boxOf(state: ScreenState): List<Double> = if (contentOf(state) is Content.Logo) LOGO_BOX else NO_MARGINS

    private fun load(content: Content, blank: Path, webStream: (String) -> String): List<JsonElement> =
        when (content) {
            Content.Nothing -> listOf(loadfile(blank.toString(), "replace", "image-display-duration=inf"))
            is Content.Logo -> listOf(loadfile(content.file.toString(), "replace", "image-display-duration=inf"))
            is Content.Playlist -> content.items.mapIndexed { index, item ->
                val target = when (item.kind) {
                    MediaKind.STREAM -> item.url.orEmpty()
                    MediaKind.WEB -> webStream(item.url.orEmpty())
                    MediaKind.IMAGE, MediaKind.VIDEO -> item.file.toString()
                }
                val options = options(item, content.items.size == 1, content.stepped)
                loadfile(target, if (index == 0) "replace" else "append", options)
            }
        }

    /**
     * A lone item stays up or loops in place; reloading it would flash black between plays. One step
     * of a rotation ([stepped]) has no time of its own, since the engine moves on — except a video
     * without one, which plays to its end, where mpv's end-file moves it on.
     */
    private fun options(item: ScreenItem, alone: Boolean, stepped: Boolean): String {
        val stream = STREAM_OPTIONS.takeIf { item.kind == MediaKind.STREAM }
        val playback = when {
            // A page's frames come as they are drawn and none when it is still, so mpv shows each as
            // it arrives, holds no buffer, and the stream never ends while the page is up.
            item.kind == MediaKind.WEB -> WEB_OPTIONS
            item.kind == MediaKind.IMAGE && (alone || stepped) -> "image-display-duration=inf"
            item.kind == MediaKind.IMAGE ->
                "image-display-duration=${seconds(item.durationMs ?: ScreenItem.DEFAULT_PICTURE_MS)}"
            stepped -> "loop-file=inf".takeUnless { item.kind == MediaKind.VIDEO && item.durationMs == null }
            item.durationMs != null -> "end=${seconds(item.durationMs)}"
            alone -> "loop-file=inf"
            else -> null
        }
        return listOfNotNull(stream, playback).joinToString(",")
    }

    private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.3f", ms / MS_PER_SECOND)

    /** Top, right, bottom, left, as fractions of the screen as it is mounted. */
    private val LOGO_BOX = listOf(0.18, 0.3, 0.42, 0.3)
    private val NO_MARGINS = listOf(0.0, 0.0, 0.0, 0.0)
    private val SIDES = listOf("top", "right", "bottom", "left")

    /** mpv's margins are the panel's sides; turned with the picture, the mounted top is another side. */
    private fun margins(mounted: List<Double>, orientation: Orientation): List<JsonElement> {
        val turns = orientation.clockwiseDegrees / QUARTER
        return SIDES.indices.map { i ->
            setProperty("video-margin-ratio-${SIDES[(i + turns) % SIDES.size]}", JsonPrimitive(mounted[i]))
        }
    }

    private fun loadfile(target: String, flags: String, options: String) = buildJsonObject {
        put("name", "loadfile")
        put("url", target)
        put("flags", flags)
        if (options.isNotEmpty()) put("options", options)
    }

    private fun overlay(id: Int, ass: String?) = buildJsonObject {
        put("name", "osd-overlay")
        put("id", id)
        put("format", if (ass == null) "none" else "ass-events")
        put("data", ass.orEmpty())
        put("res_x", RES_X)
        put("res_y", RES_Y)
        put("z", id)
    }

    private fun setProperty(name: String, value: JsonPrimitive): JsonArray = buildJsonArray {
        add(JsonPrimitive("set_property"))
        add(JsonPrimitive(name))
        add(value)
    }

    /** A stream that stops answering ends after this many seconds, so the player can set it aside. */
    private const val STREAM_OPTIONS = "network-timeout=15"
    private const val WEB_OPTIONS = "demuxer-lavf-format=mpjpeg,untimed=yes,cache=no,demuxer-readahead-secs=0"

    private const val QUARTER = 90
    private const val MS_PER_SECOND = 1000.0
}
