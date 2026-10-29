package org.churchpresenter.signage.agent

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/**
 * A cursor theme whose pointer is one transparent pixel, for cage. A touch never moves the pointer, so
 * cage would leave its arrow standing in the middle of the touch page; a keyboard's media keys count as
 * a mouse, so it draws one even with no mouse plugged in. cage loads the theme named `default` from
 * `XCURSOR_PATH`, and asks it for these names. Chromium keeps the system's theme (`KioskCommand.CHROMIUM_ENV`),
 * so a mouse's pointer shows once the mouse moves over the page.
 */
object BlankCursor {
    /** The names cage asks for before anything has moved the pointer. */
    val NAMES = listOf("default", "left_ptr")

    /** Writes the theme into [dir] and returns [dir], to be given to cage as `XCURSOR_PATH`. */
    fun write(dir: Path): Path {
        val cursors = dir.resolve("default").resolve("cursors").createDirectories()
        val bytes = file()
        NAMES.forEach { cursors.resolve(it).writeBytes(bytes) }
        return dir
    }

    /** One Xcursor file: a header, one table-of-contents entry, and a 1×1 image of a transparent pixel. */
    fun file(): ByteArray {
        val buffer = ByteBuffer.allocate(FILE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("Xcur".toByteArray()).putInt(FILE_HEADER).putInt(FILE_VERSION).putInt(1)
        buffer.putInt(IMAGE_TYPE).putInt(NOMINAL_SIZE).putInt(FILE_HEADER + TOC_ENTRY)
        buffer.putInt(IMAGE_HEADER).putInt(IMAGE_TYPE).putInt(NOMINAL_SIZE).putInt(1)
        buffer.putInt(1).putInt(1) // width, height
        buffer.putInt(0).putInt(0).putInt(0) // hot spot x, y; delay
        buffer.putInt(0) // the pixel: ARGB, all clear
        return buffer.array()
    }

    private const val FILE_HEADER = 16
    private const val FILE_VERSION = 0x10000
    private const val TOC_ENTRY = 12
    private const val IMAGE_HEADER = 36
    private const val IMAGE_TYPE = 0xfffd0002.toInt()
    private const val NOMINAL_SIZE = 24
    private const val FILE_SIZE = FILE_HEADER + TOC_ENTRY + IMAGE_HEADER + 4
}
