package org.churchpresenter.signage.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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
class MpvIpc(
    private val transport: Transport,
    /** Told when mpv could not play something it was given, from the reader thread. */
    private val onFileError: () -> Unit = {},
) : Closeable {
    /** A full-duplex byte stream to mpv: a Unix socket, or a named pipe on Windows. */
    interface Transport : Closeable {
        /** Reads into [buffer]; -1 at the end. */
        fun read(buffer: ByteBuffer): Int

        fun write(buffer: ByteBuffer)
    }

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, String>()
    private val awaited = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private val reader = Thread(::readLoop, "mpv-ipc").apply { isDaemon = true }

    init {
        reader.start()
    }

    /** Sends [commands] in order: each a positional array or a named-argument object. */
    fun send(commands: List<JsonElement>) {
        commands.forEach(::write)
    }

    /** Sends [command] and waits for mpv's answer: true if it did it, false if it refused or gave none in time. */
    suspend fun request(command: JsonElement, timeoutMs: Long = REQUEST_TIMEOUT_MS): Boolean {
        val answer = CompletableDeferred<Boolean>()
        val id = write(command) { awaited[it] = answer }
        return try {
            withTimeoutOrNull(timeoutMs) { answer.await() } ?: false
        } finally {
            awaited.remove(id)
        }
    }

    /** Writes one command line; [register] sees its id before mpv can answer. */
    @Synchronized
    private fun write(command: JsonElement, register: (Int) -> Unit = {}): Int {
        val id = nextId.getAndIncrement()
        pending[id] = describe(command)
        register(id)
        val line = SignageJson.encodeToString(
            JsonElement.serializer(),
            JsonObject(mapOf("command" to command, "request_id" to JsonPrimitive(id))),
        ) + "\n"
        val bytes = ByteBuffer.wrap(line.toByteArray())
        while (bytes.hasRemaining()) transport.write(bytes)
        return id
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
            val done = error == "success"
            if (!done) Log.warn("mpv refused $command: $error")
            awaited.remove(id)?.complete(done)
            return
        }
        if (message["event"]?.jsonPrimitive?.contentOrNull == "end-file" &&
            message["reason"]?.jsonPrimitive?.contentOrNull == "error"
        ) {
            Log.warn("mpv could not play a file: ${message["file_error"]?.jsonPrimitive?.contentOrNull}")
            onFileError()
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
        const val REQUEST_TIMEOUT_MS = 10_000L
    }
}
