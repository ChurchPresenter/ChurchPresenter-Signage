package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.Orientation

/**
 * The text mpv draws over the picture, as ASS events in a [MpvScene.RES_X] × [MpvScene.RES_Y] space:
 * the idle card's title and message, and the screen's name while it is identified.
 *
 * Each line is placed as if the screen stood the way it is mounted and then turned with the picture
 * around the panel's centre, so a portrait screen reads upright. Sizes are in thousandths of the
 * panel's short side.
 */
object OsdText {
    /** The idle card's lines, or null when nothing is written on it. */
    fun idleCard(state: ScreenState): String? {
        if (state.mode != ScreenMode.IDLE) return null
        val title = state.idleTitle.takeIf { it.isNotBlank() }
        val message = state.message?.takeIf { it.isNotBlank() }
        if (title == null && message == null) return null
        val hasLogo = state.idleLogo != null
        val titleAt = if (hasLogo) LOGO_TITLE_AT else TITLE_AT
        val messageAt = if (title == null) titleAt else titleAt + MESSAGE_GAP
        return listOfNotNull(
            title?.let { line(it, titleAt, TITLE_SIZE, state.orientation, bold = true) },
            message?.let { line(it, messageAt, MESSAGE_SIZE, state.orientation, alpha = MESSAGE_ALPHA) },
        ).joinToString("\n")
    }

    /**
     * The line over an interactive page's idle playlist that says to touch the screen, on a dark box
     * so it reads over any picture; null when there is none.
     */
    fun tapPrompt(state: ScreenState): String? {
        val text = state.touchOpens?.idlePrompt ?: return null
        val px = PROMPT_SIZE * MpvScene.RES_Y / PER_MILLE
        return promptBox(text.length, px, state.orientation) + "\n" +
            line(text, PROMPT_AT, PROMPT_SIZE, state.orientation, bold = true)
    }

    /**
     * The dark pill under the tap prompt, as a touch page's own prompt has: wide enough for
     * [chars] letters of [px] (a guess from the letters' average width, at most most of the screen's
     * width), centred where the prompt is and turned with it.
     */
    private fun promptBox(chars: Int, px: Int, orientation: Orientation): String {
        val sideways = orientation == Orientation.PORTRAIT || orientation == Orientation.PORTRAIT_FLIPPED
        val mountedWidth = if (sideways) MpvScene.RES_Y else MpvScene.RES_X
        val mountedHeight = if (sideways) MpvScene.RES_X else MpvScene.RES_Y
        val width = (chars * px * LETTER_WIDTH + 2 * px).toInt().coerceAtMost((mountedWidth * MAX_BOX_WIDTH).toInt())
        val height = (px * BOX_HEIGHT).toInt()
        // Half its height: round ends, a pill like the touch page's.
        val radius = height / 2
        val centreX = MpvScene.RES_X / 2
        val centreY = MpvScene.RES_Y / 2
        val y = centreY + ((PROMPT_AT - HALF) * mountedHeight).toInt()
        val tags = "\\an5\\pos($centreX,$y)\\org($centreX,$centreY)\\frz${-orientation.clockwiseDegrees}" +
            "\\p1\\bord0\\shad0\\1c&H$BOX_COLOUR&\\1a&H$BOX_ALPHA&"
        return "{$tags}${roundedBox(width, height, radius)}{\\p0}"
    }

    /**
     * A [width] by [height] box with corners of [r], as an ASS drawing: each corner a quarter circle,
     * its curve's handles [CIRCLE] of the radius along the edges.
     */
    private fun roundedBox(width: Int, height: Int, r: Int): String {
        val k = (r * (1 - CIRCLE)).toInt()
        val right = width - r
        val bottom = height - r
        return "m $r 0 l $right 0 b ${width - k} 0 $width $k $width $r l $width $bottom " +
            "b $width ${height - k} ${width - k} $height $right $height l $r $height " +
            "b $k $height 0 ${height - k} 0 $bottom l 0 $r b 0 $k $k 0 $r 0"
    }

    /** A dark sheet over everything with the screen's name on it, or null when not identifying. */
    fun identify(state: ScreenState): String? {
        val name = state.identify ?: return null
        val sheet = "{\\an7\\pos(0,0)\\p1\\bord0\\shad0\\1c&H000000&\\1a&H$SHEET_ALPHA&}" +
            "m 0 0 l ${MpvScene.RES_X} 0 ${MpvScene.RES_X} ${MpvScene.RES_Y} 0 ${MpvScene.RES_Y}{\\p0}"
        return sheet + "\n" + line(name, HALF, IDENTIFY_SIZE, state.orientation, bold = true)
    }

    /**
     * Text shown as written: ASS reads `{` as the start of a tag and `\` as an escape, and a line
     * break would end the event. The zero-width no-break space after a backslash is how mpv's own
     * scripts keep one literal.
     */
    fun escape(text: String): String = text
        .replace("\\", "\\\uFEFF")
        .replace("{", "\\{")
        .replace("}", "\\}")
        .replace(Regex("\r\n|\r|\n"), " ")

    /** One centred line at [at] (a fraction of the mounted height, from its top). */
    private fun line(
        text: String,
        at: Double,
        size: Int,
        orientation: Orientation,
        bold: Boolean = false,
        alpha: String = "00",
    ): String {
        val centreX = MpvScene.RES_X / 2
        val centreY = MpvScene.RES_Y / 2
        val sideways = orientation == Orientation.PORTRAIT || orientation == Orientation.PORTRAIT_FLIPPED
        val mountedHeight = if (sideways) MpvScene.RES_X else MpvScene.RES_Y
        val y = centreY + ((at - HALF) * mountedHeight).toInt()
        val px = size * MpvScene.RES_Y / PER_MILLE
        val tags = "\\an5\\q2\\pos($centreX,$y)\\org($centreX,$centreY)\\frz${-orientation.clockwiseDegrees}" +
            "\\fs$px\\b${if (bold) 1 else 0}\\bord0\\shad0\\1c&H$TEXT_COLOUR&\\1a&H$alpha&"
        return "{$tags}${escape(text)}"
    }

    private const val HALF = 0.5
    private const val TITLE_AT = 0.46
    private const val LOGO_TITLE_AT = 0.66
    private const val MESSAGE_GAP = 0.09
    private const val TITLE_SIZE = 60
    private const val MESSAGE_SIZE = 35
    private const val IDENTIFY_SIZE = 120
    private const val PROMPT_AT = 0.88
    private const val PROMPT_SIZE = 55
    private const val LETTER_WIDTH = 0.56
    private const val MAX_BOX_WIDTH = 0.9
    private const val BOX_HEIGHT = 1.9

    /** How far a quarter circle's curve handles reach, as a share of its radius. */
    private const val CIRCLE = 0.5523

    /** The touch page's prompt box, rgba(20, 20, 24, 0.82), as ASS writes it: blue, green, red; then how clear. */
    private const val BOX_COLOUR = "181414"
    private const val BOX_ALPHA = "2E"
    private const val PER_MILLE = 1000

    /** #e8eaed, as ASS writes colours: blue, green, red. */
    private const val TEXT_COLOUR = "EDEAE8"
    private const val MESSAGE_ALPHA = "33"
    private const val SHEET_ALPHA = "26"
}
