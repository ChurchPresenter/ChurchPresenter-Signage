package org.churchpresenter.signage.agent

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class BlankCursorTest {
    private val dir = Files.createTempDirectory("blank-cursor")

    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    @Test
    fun `the file is an Xcursor of one transparent pixel`() {
        val bytes = BlankCursor.file()
        val words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("Xcur", String(bytes, 0, 4))
        assertEquals(1, words.getInt(12)) // one entry in the table of contents
        val image = words.getInt(24)
        assertEquals(0xfffd0002.toInt(), words.getInt(image + 4))
        assertEquals(1, words.getInt(image + 12)) // width
        assertEquals(1, words.getInt(image + 16)) // height
        assertEquals(0, words.getInt(image + 32)) // the pixel, fully clear
        assertEquals(image + 36 + 4, bytes.size)
    }

    @Test
    fun `the theme is named default and answers every name asked for first`() {
        val path = BlankCursor.write(dir.resolve("theme"))
        BlankCursor.NAMES.forEach {
            assertContentEquals(BlankCursor.file(), path.resolve("default/cursors/$it").readBytes())
        }
    }
}
