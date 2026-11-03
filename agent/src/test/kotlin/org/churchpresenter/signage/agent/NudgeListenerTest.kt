package org.churchpresenter.signage.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.churchpresenter.signage.core.Nudge
import org.churchpresenter.signage.core.SignageJson
import org.churchpresenter.signage.core.nudgeOwner
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which knocks a screen acts on: only its own owner's, read whole, from a real port. */
class NudgeListenerTest {
    private val from: InetAddress = InetAddress.getByName("192.168.2.52")

    private fun packet(text: String) = text.toByteArray().let { DatagramPacket(it, it.size, from, 0) }

    private fun nudge(owner: String, port: Int) =
        packet(SignageJson.encodeToString(Nudge.serializer(), Nudge(nudgeOwner(owner), port)))

    private val listener = NudgeListener(ownerId = { "owner-1" }, onNudge = {})

    @Test
    fun `its own owner's knock gives where to connect`() {
        assertEquals("192.168.2.52:8765", listener.addressOf(nudge("owner-1", 8765)))
    }

    @Test
    fun `another ChurchPresenter's knock is dropped`() {
        assertNull(listener.addressOf(nudge("owner-2", 8765)))
    }

    @Test
    fun `a knock with no usable port or unreadable text is dropped`() {
        assertNull(listener.addressOf(nudge("owner-1", 0)))
        assertNull(listener.addressOf(packet("not json")))
        assertNull(listener.addressOf(packet("""{"owner":"x"}""")))
    }

    @Test
    fun `an unpaired screen acts on no knock`() {
        assertNull(NudgeListener(ownerId = { null }, onNudge = {}).addressOf(nudge("owner-1", 8765)))
    }

    @Test
    fun `a knock sent to its port reaches it`() = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val heard = CompletableDeferred<String>()
        val listening = NudgeListener(ownerId = { "owner-1" }, onNudge = { heard.complete(it) }, open = { socket })
        val job = launch(Dispatchers.IO) { listening.run() }
        val bytes = SignageJson.encodeToString(Nudge.serializer(), Nudge(nudgeOwner("owner-1"), 9000)).toByteArray()
        DatagramSocket().use { it.send(DatagramPacket(bytes, bytes.size, socket.localSocketAddress)) }
        assertEquals("127.0.0.1:9000", withTimeout(5_000) { heard.await() })
        job.cancel()
        job.join()
        assertTrue(socket.isClosed)
    }

    @Test
    fun `a port another program holds is given up quietly`() = runBlocking {
        NudgeListener(ownerId = { "owner-1" }, onNudge = {}, open = { throw SocketException("in use") }).run()
    }
}
