package org.churchpresenter.signage.agent

import kotlinx.serialization.json.JsonPrimitive
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.PlaylistItem
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The church's own CSS and JavaScript, as the scripts each page is given. */
class PageCodeTest {
    @Test
    fun nothingWrittenIsNoCode() {
        assertNull(PageCode.of(null, null))
        assertNull(PageCode.of("  ", "\n"))
        assertEquals(PageCode("a { }", null), PageCode.of("a { }", " "))
    }

    @Test
    fun theCssGoesInAsAStyleSheetOfItsOwnText() {
        val css = "header { display: none } /* \"quoted\" */\n</script>"
        val (style) = PageCode(css, null).scripts()
        // Written as a string, so nothing in it can end the script.
        assertTrue(JsonPrimitive(css).toString() in style)
        assertTrue("adoptedStyleSheets" in style && "window.top !== window" in style)
    }

    @Test
    fun theScriptRunsAsWrittenOnLinesOfItsOwn() {
        val script = "document.title = 'x' // last line"
        val scripts = PageCode("a { }", script).scripts()
        assertEquals(2, scripts.size)
        val run = scripts[1]
        assertTrue("\n$script\n" in run)
        assertTrue("try { run(); }" in run && "DOMContentLoaded" in run)
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun onlyAWebItemCarriesIt() {
        val root = createTempDirectory("signage-page-code")
        val items = PlayableItems(ContentFiles(root), LinkHealth(), web = true)
        val web = PlaylistItem(kind = MediaKind.WEB, url = "https://example.org", pageCss = "a { }", pageScript = " ")
        assertEquals(PageCode("a { }", null), items.screenItem(web)?.pageCode)
        val stream = PlaylistItem(kind = MediaKind.STREAM, url = "rtsp://camera", pageCss = "a { }")
        assertNull(items.screenItem(stream)?.pageCode)
        root.deleteRecursively()
    }
}
