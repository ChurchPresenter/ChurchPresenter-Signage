package org.churchpresenter.signage.agent

import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The local stream mpv plays a web page from: frames as they come, the last again while still. */
class MjpegServerTest {
    private val server = MjpegServer(repeatMs = REPEAT_MS)

    @AfterTest
    fun close() = server.close()

    private fun open(path: String): Pair<Socket, DataInputStream> {
        val socket = Socket(InetAddress.getLoopbackAddress(), server.port)
        socket.soTimeout = READ_TIMEOUT_MS
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        return socket to DataInputStream(socket.getInputStream())
    }

    private fun DataInputStream.line(): String {
        val out = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0 || c == '\n'.code) return out.toString().trimEnd('\r')
            out.append(c.toChar())
        }
    }

    /** Reads up to the next part's JPEG and returns it. */
    private fun DataInputStream.nextFrame(): ByteArray {
        var length = -1
        while (true) {
            val line = line()
            if (line.startsWith("Content-Length:")) length = line.substringAfter(':').trim().toInt()
            if (line.isEmpty() && length >= 0) break
        }
        return ByteArray(length).also { readFully(it) }
    }

    @Test
    fun aStreamGetsEachNewFrameAndTheLastAgainWhileThePageIsStill() {
        val (socket, input) = open("/web/page")
        socket.use {
            assertEquals("HTTP/1.0 200 OK", input.line())
            server.publish("page", byteArrayOf(1, 2, 3))
            assertContentEquals(byteArrayOf(1, 2, 3), input.nextFrame())
            // Nothing new: the same frame again after repeatMs, so mpv is never left waiting.
            assertContentEquals(byteArrayOf(1, 2, 3), input.nextFrame())
            server.publish("page", byteArrayOf(4))
            assertContentEquals(byteArrayOf(4), input.nextFrame())
        }
        // Two new frames went out; the repeat is not counted.
        assertEquals(2L, server.framesSent)
    }

    @Test
    fun anotherPageIsNotSentAndAnUnknownPathIsRefused() {
        val (socket, input) = open("/web/page")
        socket.use {
            input.line()
            server.publish("other", byteArrayOf(9))
            server.publish("page", byteArrayOf(7))
            assertContentEquals(byteArrayOf(7), input.nextFrame())
        }
        val (refused, answer) = open("/nothing")
        refused.use { assertTrue(answer.line().startsWith("HTTP/1.0 404")) }
    }

    private companion object {
        const val REPEAT_MS = 20L
        const val READ_TIMEOUT_MS = 2_000
    }
}
