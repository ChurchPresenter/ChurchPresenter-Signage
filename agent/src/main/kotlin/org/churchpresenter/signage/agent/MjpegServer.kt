package org.churchpresenter.signage.agent

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Hands the frames [WebRenderer] draws to mpv: `GET /web/<key>` on the loopback interface answers
 * with an endless `multipart/x-mixed-replace` stream of JPEGs (mpv's `mpjpeg`). A frame goes out as
 * soon as it is published; a page that is still gets its last frame again every [repeatMs], so mpv
 * never waits long enough to give up on it. Only this machine can connect.
 */
class MjpegServer(private val repeatMs: Long = REPEAT_MS) : Closeable {
    private class Frame(val seq: Long, val jpeg: ByteArray)

    private val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val lock = Object()
    private val frames = HashMap<String, Frame>()
    private var nextSeq = 0L
    private val sent = AtomicLong()

    /** How many new frames have gone to mpv so far, each counted once however many streams carried it. */
    val framesSent: Long get() = sent.get()

    /** The port it listens on, on 127.0.0.1. */
    val port: Int get() = socket.localPort

    init {
        Thread(::acceptLoop, "mjpeg-accept").apply { isDaemon = true }.start()
    }

    /** Makes [jpeg] the frame sent for [key] from now on. */
    fun publish(key: String, jpeg: ByteArray) {
        synchronized(lock) {
            frames[key] = Frame(nextSeq++, jpeg)
            lock.notifyAll()
        }
    }

    /** Forgets [key]'s frame; a stream that asks for it waits for the next. */
    fun remove(key: String) {
        synchronized(lock) { frames.remove(key) }
    }

    override fun close() = socket.close()

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            Thread({ client.use(::serve) }, "mjpeg-stream").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        val key = requestedKey(client) ?: return client.getOutputStream().write(NOT_FOUND)
        val out = client.getOutputStream()
        try {
            out.write(HEADER)
            var seen = -1L
            while (true) {
                val frame = nextFrame(key, seen) ?: continue
                out.write(partHeader(frame.jpeg.size))
                out.write(frame.jpeg)
                out.write(CRLF)
                out.flush()
                if (frame.seq != seen) sent.incrementAndGet()
                seen = frame.seq
            }
        } catch (_: IOException) {
            // mpv closed the stream: it moved on to something else.
        }
    }

    /** The newest frame for [key] once it is newer than [seen], or after [repeatMs] the same one again. */
    private fun nextFrame(key: String, seen: Long): Frame? {
        val deadline = System.nanoTime() + repeatMs * NANOS_PER_MS
        synchronized(lock) {
            while (true) {
                val frame = frames[key]
                val left = (deadline - System.nanoTime()) / NANOS_PER_MS
                if ((frame != null && frame.seq != seen) || left <= 0) return frame
                lock.wait(left)
            }
        }
    }

    /** `GET /web/<key>`'s key, after reading the request's headers; null for anything else. */
    private fun requestedKey(client: Socket): String? {
        val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
        val requestLine = input.readLine() ?: return null
        while (input.readLine()?.isNotEmpty() == true) Unit
        val path = requestLine.split(' ').getOrNull(1) ?: return null
        return path.removePrefix(PATH_PREFIX).takeIf { path.startsWith(PATH_PREFIX) && it.isNotEmpty() }
    }

    private fun partHeader(length: Int): ByteArray =
        "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: $length\r\n\r\n".toByteArray(Charsets.US_ASCII)

    private fun OutputStream.write(text: String) = write(text.toByteArray(Charsets.US_ASCII))

    companion object {
        /** How often a still page's frame is sent again. */
        const val REPEAT_MS = 500L
        const val PATH_PREFIX = "/web/"

        private const val BACKLOG = 8
        private const val NANOS_PER_MS = 1_000_000L
        private const val BOUNDARY = "cpframe"
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
        private const val HEADER = "HTTP/1.0 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
        private const val NOT_FOUND = "HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n"
    }
}
