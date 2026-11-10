package org.churchpresenter.signage.agent

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.churchpresenter.signage.core.PlayerMessage
import org.churchpresenter.signage.core.ServerMessage
import org.churchpresenter.signage.core.SignageJson
import java.io.IOException

/**
 * Keeps one WebSocket open to ChurchPresenter whenever it is reachable, and gives up gracefully when
 * it is not — the player plays on regardless. Retries back off from 2 s to [MAX_BACKOFF_MS], and a
 * [nudged] ChurchPresenter is tried at once.
 */
class ServerConnection(
    private val client: HttpClient,
    private val store: PlayerStore,
    private val locator: ServerLocator,
    private val session: PlayerSession,
    private val onAddress: (String) -> Unit = {},
) {
    /** The address a [Nudge][org.churchpresenter.signage.core.Nudge] came from, tried first next time. */
    @Volatile
    private var nudgedAddress: String? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * This screen's owner knocked from [address]: the next attempt starts now, there first. Ignored
     * while connected, since nothing waits then.
     */
    fun nudged(address: String) {
        nudgedAddress = address
        wake.trySend(Unit)
    }

    suspend fun run() {
        var backoff = MIN_BACKOFF_MS
        while (true) {
            val next = connectToAny()
            backoff = when (next) {
                SessionNext.RECONNECT -> MIN_BACKOFF_MS
                SessionNext.WAIT_LONG -> UNSUPPORTED_WAIT_MS
                SessionNext.STAY, SessionNext.DECLINED, null -> (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            // A nudge that came while connected is stale: only one that comes while waiting counts.
            wake.tryReceive()
            if (next != SessionNext.RECONNECT) withTimeoutOrNull(backoff) { wake.receive() }
        }
    }

    /**
     * Tries a nudged address, then the addresses known without looking, then whatever advertises on
     * the network that was not tried. Only a known address is trusted to release this screen: one
     * found on the network or nudged from may be another ChurchPresenter, which does not know the
     * screen and must not make it forget its owner.
     */
    private suspend fun connectToAny(): SessionNext? {
        val identity = store.identity()
        val known = locator.known(identity)
        val nudged = nudgedAddress?.also { nudgedAddress = null }
        val first = listOfNotNull(nudged?.takeIf { it !in known })
        if (identity.token == null) return connectUnpaired(identity, first)
        return tryEach(first, trusted = false)
            ?: tryEach(known, trusted = true)
            ?: tryEach(locator.browse() - known.toSet() - first.toSet(), trusted = false)
    }

    /**
     * A player with no owner has no one to go back to: it tries an address it was given at install,
     * then every ChurchPresenter that advertises, and the one it last used only after those, so a
     * screen removed from one ChurchPresenter is found by another.
     */
    private suspend fun connectUnpaired(identity: PlayerIdentity, first: List<String>): SessionNext? {
        val pinned = listOfNotNull(identity.fixedAddress)
        val last = listOfNotNull(identity.lastAddress)
        return tryEach(first + pinned, trusted = false)
            ?: tryEach(locator.browse() - first.toSet() - pinned.toSet() - last.toSet(), trusted = false)
            ?: tryEach(last - pinned.toSet(), trusted = false)
    }

    private suspend fun tryEach(addresses: List<String>, trusted: Boolean): SessionNext? {
        for (address in addresses) {
            try {
                val next = connect(address, trusted)
                if (next == SessionNext.DECLINED) {
                    Log.info("ChurchPresenter at $address is holding this screen back; trying another")
                    continue
                }
                return next
            } catch (e: CancellationException) {
                // Only this loop being stopped ends it. The socket cancelling itself (a ping that
                // got no answer) is a connection that ended, and is tried again like any other.
                currentCoroutineContext().ensureActive()
                Log.info("Connection to $address ended: ${e.message}")
            } catch (e: IOException) {
                Log.info("ChurchPresenter not reachable at $address: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.info("Connection to $address ended: ${e.message}")
            }
        }
        return null
    }

    private suspend fun connect(address: String, trusted: Boolean): SessionNext {
        var next = SessionNext.STAY
        val token = store.identity().token
        try {
            client.webSocket(urlString = "ws://$address/api/signage/ws", request = { token?.let(::bearerAuth) }) {
                store.saveIdentity(store.identity().copy(lastAddress = address))
                session.serverAddress = address
                onAddress(address)
                send(Frame.Text(SignageJson.encodeToString(PlayerMessage.serializer(), session.hello())))
                session.onConnected()
                val reporter = launch {
                    while (true) {
                        send(Frame.Text(SignageJson.encodeToString(PlayerMessage.serializer(), session.status())))
                        delay(STATUS_EVERY_MS)
                    }
                }
                val forwarder = launch {
                    for (message in session.outgoing) {
                        send(Frame.Text(SignageJson.encodeToString(PlayerMessage.serializer(), message)))
                    }
                }
                for (frame in incoming) {
                    val message = (frame as? Frame.Text)?.let(::decode)
                    if (message != null) next = session.handle(message)
                    if (next != SessionNext.STAY) break
                }
                if (next == SessionNext.STAY && closeReason.await()?.code == CloseReason.Codes.TRY_AGAIN_LATER.code) {
                    next = SessionNext.DECLINED
                }
                reporter.cancel()
                forwarder.cancel()
            }
        } catch (e: WebSocketException) {
            if (token != null && HttpStatusCode.Unauthorized.value.toString() in e.message.orEmpty()) {
                if (!trusted) throw IOException("a ChurchPresenter that does not know this screen", e)
                session.onUnauthorized()
                return SessionNext.RECONNECT
            }
            throw IOException(e.message, e)
        }
        return next
    }

    private fun decode(frame: Frame.Text): ServerMessage? = try {
        SignageJson.decodeFromString(ServerMessage.serializer(), frame.readText())
    } catch (e: IllegalArgumentException) {
        Log.warn("Ignoring a message this agent cannot read", e)
        null
    }

    private companion object {
        const val MIN_BACKOFF_MS = 2_000L

        /** The longest pause between tries while nothing answers: a ChurchPresenter just started is found soon. */
        const val MAX_BACKOFF_MS = 15_000L

        /** The pause after a ChurchPresenter that speaks another protocol, which will not change soon. */
        const val UNSUPPORTED_WAIT_MS = 60_000L
        const val STATUS_EVERY_MS = 60_000L
    }
}
