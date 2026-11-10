package org.churchpresenter.signage.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.churchpresenter.signage.core.NUDGE_PORT
import org.churchpresenter.signage.core.Nudge
import org.churchpresenter.signage.core.SignageJson
import org.churchpresenter.signage.core.nudgeOwner
import org.churchpresenter.signage.core.nudgePlayer
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException

/**
 * Listens on [NUDGE_PORT] for this screen's owner knocking ([Nudge]) and hands each knock's address
 * to [onNudge]. A knock from any other ChurchPresenter, or one this screen cannot read, is dropped.
 */
class NudgeListener(
    /** The owner id this screen is paired to now; null while unpaired, when only a knock naming [playerId] counts. */
    private val ownerId: () -> String?,
    private val onNudge: (address: String) -> Unit,
    private val open: () -> DatagramSocket = { DatagramSocket(NUDGE_PORT) },
    /** This player's id, for a ChurchPresenter asking it to come and pair while it is unpaired. */
    private val playerId: () -> String? = { null },
) {
    /** Runs until cancelled; without the port (another program has it) it logs once and gives up. */
    suspend fun run() {
        val socket = try {
            open()
        } catch (e: SocketException) {
            Log.warn("Cannot listen for ChurchPresenter on UDP $NUDGE_PORT: ${e.message}")
            return
        }
        coroutineScope {
            // A blocked receive does not answer cancellation; closing the socket ends it.
            val closer = launch {
                try {
                    awaitCancellation()
                } finally {
                    socket.close()
                }
            }
            withContext(Dispatchers.IO) { receiveAll(socket) }
            closer.cancel()
        }
    }

    private fun receiveAll(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_BYTES)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: IOException) {
                break
            }
            addressOf(packet)?.let(onNudge)
        }
    }

    /**
     * Where [packet]'s ChurchPresenter can be reached, as `host:port`; null unless it is this screen's
     * owner, or, while unpaired, a ChurchPresenter asking this very player to come and pair.
     */
    internal fun addressOf(packet: DatagramPacket): String? {
        val nudge = decode(String(packet.data, packet.offset, packet.length)) ?: return null
        val owner = ownerId()
        val forThisScreen = if (owner != null) {
            nudge.owner == nudgeOwner(owner)
        } else {
            nudge.player != null && nudge.player == playerId()?.let(::nudgePlayer)
        }
        return "${packet.address.hostAddress}:${nudge.port}".takeIf { forThisScreen && nudge.port in VALID_PORTS }
    }

    private fun decode(text: String): Nudge? = try {
        SignageJson.decodeFromString(Nudge.serializer(), text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val MAX_BYTES = 512
        val VALID_PORTS = 1..65_535
    }
}

/** The knocks this player answers: its owner's while paired, and one asking for it by id while not. */
internal fun knocksFor(store: PlayerStore, connection: ServerConnection) = NudgeListener(
    ownerId = { store.identity().ownerId },
    onNudge = connection::nudged,
    playerId = { store.identity().playerId },
)
