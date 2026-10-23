package org.churchpresenter.signage.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalPathApi::class)
class MpvIpcTest {
    private val dir = createTempDirectory("mpv-ipc")

    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    @Test
    fun eachCommandGoesOutAsOneLineWithItsOwnId() {
        val socket = dir.resolve("mpv.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            MpvIpc(MpvIpc.UnixSocket(socket)).use { ipc ->
                server.accept().use { mpv ->
                    ipc.send(
                        listOf(
                            buildJsonArray { add(JsonPrimitive("stop")) },
                            buildJsonObject { put("name", "loadfile") },
                        ),
                    )
                    val lines = Channels.newReader(mpv, Charsets.UTF_8).buffered()
                    assertEquals("""{"command":["stop"],"request_id":1}""", lines.readLine())
                    assertEquals("""{"command":{"name":"loadfile"},"request_id":2}""", lines.readLine())
                }
            }
        }
    }
}
