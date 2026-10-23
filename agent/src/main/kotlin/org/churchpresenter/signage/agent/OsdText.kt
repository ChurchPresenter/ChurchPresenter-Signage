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
    private const val PER_MILLE = 1000

    /** #e8eaed, as ASS writes colours: blue, green, red. */
    private const val TEXT_COLOUR = "EDEAE8"
    private const val MESSAGE_ALPHA = "33"
    private const val SHEET_ALPHA = "26"
}
