package org.churchpresenter.signage.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.churchpresenter.signage.core.SignageJson
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * One connection to mpv's JSON IPC: each command goes out as a line, and a reader thread takes the
 * replies and events that come back, logging any command mpv refused and any file it could not play.
 */
class MpvIpc(private val transport: Transport) : Closeable {
    /** A full-duplex byte stream to mpv: a Unix socket, or a named pipe on Windows. */
    interface Transport : Closeable {
        /** Reads into [buffer]; -1 at the end. */
        fun read(buffer: ByteBuffer): Int

        fun write(buffer: ByteBuffer)
    }

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, String>()
    private val reader = Thread(::readLoop, "mpv-ipc").apply { isDaemon = true }

    init {
        reader.start()
    }

    /** Sends [commands] in order: each a positional array or a named-argument object. */
    @Synchronized
    fun send(commands: List<JsonElement>) {
        for (command in commands) {
            val id = nextId.getAndIncrement()
            pending[id] = describe(command)
            val line = SignageJson.encodeToString(
                JsonElement.serializer(),
                JsonObject(mapOf("command" to command, "request_id" to JsonPrimitive(id))),
            ) + "\n"
            val bytes = ByteBuffer.wrap(line.toByteArray())
            while (bytes.hasRemaining()) transport.write(bytes)
        }
    }

    override fun close() = transport.close()

    private fun readLoop() {
        val buffer = ByteBuffer.allocate(READ_BUFFER)
        val line = ByteArrayOutputStream()
        try {
            while (transport.read(buffer.clear()) >= 0) splitLines(buffer.flip(), line)
        } catch (_: IOException) {
            // mpv went away; the player notices the process exit and starts it again.
        }
    }

    /** Hands each complete line in [buffer] to [handle]; a part line waits in [line] for the rest. */
    private fun splitLines(buffer: ByteBuffer, line: ByteArrayOutputStream) {
        while (buffer.hasRemaining()) {
            val byte = buffer.get()
            if (byte != NEWLINE) {
                line.write(byte.toInt())
                continue
            }
            handle(line.toString(Charsets.UTF_8))
            line.reset()
        }
    }

    private fun handle(text: String) {
        val message = runCatching { SignageJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = message["request_id"]?.jsonPrimitive?.intOrNull
        val error = message["error"]?.jsonPrimitive?.contentOrNull
        if (id != null) {
            val command = pending.remove(id)
            if (error != null && error != "success") Log.warn("mpv refused $command: $error")
            return
        }
        if (message["event"]?.jsonPrimitive?.contentOrNull == "end-file" &&
            message["reason"]?.jsonPrimitive?.contentOrNull == "error"
        ) {
            Log.warn("mpv could not play a file: ${message["file_error"]?.jsonPrimitive?.contentOrNull}")
        }
    }

    private fun describe(command: JsonElement): String = when (command) {
        is JsonObject -> command["name"]?.jsonPrimitive?.contentOrNull ?: "?"
        else -> command.toString().take(DESCRIBE_CHARS)
    }

    /** A Unix domain socket, as mpv opens on Linux and macOS. */
    class UnixSocket(path: Path) : Transport {
        private val channel = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            connect(UnixDomainSocketAddress.of(path))
        }

        override fun read(buffer: ByteBuffer): Int = channel.read(buffer)

        override fun write(buffer: ByteBuffer) {
            channel.write(buffer)
        }

        override fun close() = channel.close()
    }

    /**
     * A Windows named pipe (`\\.\pipe\…`). Opened as an asynchronous file it is overlapped, so the
     * reader waiting on a reply does not hold up a write as a plain file handle would. A pipe has no
     * position; the 0 passed is ignored.
     */
    class NamedPipe(path: Path) : Transport {
        private val channel = AsynchronousFileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)

        override fun read(buffer: ByteBuffer): Int = unwrap { channel.read(buffer, 0).get() }

        override fun write(buffer: ByteBuffer) {
            unwrap { channel.write(buffer, 0).get() }
        }

        override fun close() = channel.close()

        private fun <T> unwrap(call: () -> T): T = try {
            call()
        } catch (e: ExecutionException) {
            throw IOException(e.cause?.message, e)
        }
    }

    private companion object {
        const val READ_BUFFER = 8192
        const val NEWLINE = '\n'.code.toByte()
        const val DESCRIBE_CHARS = 80
    }
}
