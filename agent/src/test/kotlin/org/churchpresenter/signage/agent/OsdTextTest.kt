package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.Orientation
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.churchpresenter.signage.core.MediaKind

class OsdTextTest {
    @Test
    fun textIsShownAsWritten() {
        assertEquals("a \\{b\\} c\\\uFEFFn d", OsdText.escape("a {b} c\\n\nd"))
    }

    @Test
    fun theIdleCardHasTheTitleAndTheMessageUnderIt() {
        val card = OsdText.idleCard(ScreenState(ScreenMode.IDLE, idleTitle = "Grace Church", message = "code 1234"))
        val lines = card.orEmpty().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("}Grace Church") && "\\pos(960,497)" in lines[0], lines[0])
        assertTrue(lines[1].endsWith("}code 1234") && "\\pos(960,594)" in lines[1], lines[1])
    }

    @Test
    fun theTapPromptSitsOnADarkBoxAsATouchPagesDoes() {
        val page = ScreenItem(
            null, MediaKind.WEB, null, "https://signup.example.org", interactive = true, idlePrompt = "Tap",
        )
        val prompt = OsdText.tapPrompt(ScreenState(ScreenMode.PLAYLIST, touchOpens = page)).orEmpty().lines()
        assertEquals(2, prompt.size)
        // The box first, under the words, both centred where the prompt goes.
        assertTrue("\\p1" in prompt[0] && "\\pos(960,950)" in prompt[0] && "\\1a&H2E&" in prompt[0], prompt[0])
        assertTrue(prompt[1].endsWith("}Tap") && "\\pos(960,950)" in prompt[1], prompt[1])
        val long = page.copy(idlePrompt = "x".repeat(200))
        val wide = OsdText.tapPrompt(ScreenState(ScreenMode.PLAYLIST, touchOpens = long)).orEmpty().lines()[0]
        // Never wider than most of the screen, however long the line.
        // 90% of 1920: the box's right edge.
        assertTrue("b 1728 0 1728 0 " in wide, wide)
        assertNull(OsdText.tapPrompt(ScreenState(ScreenMode.PLAYLIST)))
    }

    @Test
    fun withALogoTheTitleMovesBelowIt() {
        val card = OsdText.idleCard(ScreenState(ScreenMode.IDLE, idleTitle = "Grace", idleLogo = Path("/logo")))
        assertTrue("\\pos(960,712)" in card.orEmpty(), card)
    }

    @Test
    fun aPortraitScreenTurnsTheTextWithIt() {
        val card = OsdText.idleCard(
            ScreenState(ScreenMode.IDLE, idleTitle = "Grace", orientation = Orientation.PORTRAIT),
        ).orEmpty()
        // 4% of the mounted height (1920 across the panel) above centre, then turned a quarter clockwise.
        assertTrue("\\pos(960,464)" in card && "\\org(960,540)" in card && "\\frz-90" in card, card)
    }

    @Test
    fun nothingToWriteMeansNoCard() {
        assertNull(OsdText.idleCard(ScreenState(ScreenMode.IDLE)))
        assertNull(OsdText.idleCard(ScreenState(ScreenMode.PLAYLIST, message = "code 1234")))
    }

    @Test
    fun identifyDarkensTheScreenUnderTheName() {
        val lines = OsdText.identify(ScreenState(ScreenMode.BLANK, identify = "Lobby")).orEmpty().lines()
        assertTrue("\\p1" in lines[0] && "m 0 0 l 1920 0 1920 1080 0 1080" in lines[0], lines[0])
        assertTrue(lines[1].endsWith("}Lobby"), lines[1])
        assertNull(OsdText.identify(ScreenState(ScreenMode.BLANK)))
    }
}
