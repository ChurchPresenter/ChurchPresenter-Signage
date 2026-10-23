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
 * mpv's named-argument form of `loadfile`, which reads the same on mpv 0.35 and later.
 */
object MpvScene {
    /** The idle card's text and the identify card are drawn in this space and scaled to the screen. */
    const val RES_X = 1920
    const val RES_Y = 1080

    private const val CARD_OVERLAY = 1
    private const val IDENTIFY_OVERLAY = 2

    /** [previous] is what mpv shows now, or null when it has just started. */
    fun commands(previous: ScreenState?, next: ScreenState): List<JsonElement> = buildList {
        if (previous == null || previous.orientation != next.orientation) {
            add(setProperty("video-rotate", JsonPrimitive(next.orientation.clockwiseDegrees)))
        }
        val content = contentOf(next)
        if (previous == null || boxOf(previous) != boxOf(next) || previous.orientation != next.orientation) {
            addAll(margins(boxOf(next), next.orientation))
        }
        if (previous == null || contentOf(previous) != content) addAll(load(content))
        val card = OsdText.idleCard(next)
        if (previous == null || OsdText.idleCard(previous) != card) add(overlay(CARD_OVERLAY, card))
        val identify = OsdText.identify(next)
        if (previous == null || OsdText.identify(previous) != identify) add(overlay(IDENTIFY_OVERLAY, identify))
    }

    private sealed interface Content {
        data class Playlist(val items: List<ScreenItem>) : Content

        data class Logo(val file: Path) : Content

        data object Nothing : Content
    }

    private fun contentOf(state: ScreenState): Content = when {
        state.mode == ScreenMode.PLAYLIST && state.items.isNotEmpty() -> Content.Playlist(state.items)
        state.mode == ScreenMode.IDLE && state.idleLogo != null -> Content.Logo(state.idleLogo)
        else -> Content.Nothing
    }

    private fun boxOf(state: ScreenState): List<Double> = if (contentOf(state) is Content.Logo) LOGO_BOX else NO_MARGINS

    private fun load(content: Content): List<JsonElement> = when (content) {
        Content.Nothing -> listOf(buildJsonArray { add(JsonPrimitive("stop")) })
        is Content.Logo -> listOf(loadfile(content.file, "replace", "image-display-duration=inf"))
        is Content.Playlist -> content.items.mapIndexed { index, item ->
            loadfile(item.file, if (index == 0) "replace" else "append", options(item, content.items.size == 1))
        }
    }

    /** A lone item stays up or loops in place; reloading it would flash black between plays. */
    private fun options(item: ScreenItem, alone: Boolean): String = when {
        item.kind == MediaKind.IMAGE && alone -> "image-display-duration=inf"
        item.kind == MediaKind.IMAGE ->
            "image-display-duration=${seconds(item.durationMs ?: ScreenItem.DEFAULT_PICTURE_MS)}"
        item.durationMs != null -> "end=${seconds(item.durationMs)}"
        alone -> "loop-file=inf"
        else -> ""
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

    private fun loadfile(file: Path, flags: String, options: String) = buildJsonObject {
        put("name", "loadfile")
        put("url", file.toString())
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

    private const val QUARTER = 90
    private const val MS_PER_SECOND = 1000.0
}
