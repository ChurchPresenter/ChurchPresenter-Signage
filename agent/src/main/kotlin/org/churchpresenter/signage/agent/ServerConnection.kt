package org.churchpresenter.signage.agent

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.churchpresenter.signage.core.PlayerMessage
import org.churchpresenter.signage.core.ServerMessage
import org.churchpresenter.signage.core.SignageJson
import java.io.IOException

/**
 * Keeps one WebSocket open to ChurchPresenter whenever it is reachable, and gives up gracefully when
 * it is not — the player plays on regardless. Retries back off from 2 s to a minute.
 */
class ServerConnection(
    private val client: HttpClient,
    private val store: PlayerStore,
    private val locator: ServerLocator,
    private val session: PlayerSession,
    private val onAddress: (String) -> Unit = {},
) {
    suspend fun run() {
        var backoff = MIN_BACKOFF_MS
        while (true) {
            val next = connectToAny()
            backoff = when (next) {
                SessionNext.RECONNECT -> MIN_BACKOFF_MS
                SessionNext.WAIT_LONG -> MAX_BACKOFF_MS
                SessionNext.STAY, null -> (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            if (next != SessionNext.RECONNECT) delay(backoff)
        }
    }

    private suspend fun connectToAny(): SessionNext? {
        for (address in locator.candidates(store.identity())) {
            try {
                return connect(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.info("ChurchPresenter not reachable at $address: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.info("Connection to $address ended: ${e.message}")
            }
        }
        return null
    }

    private suspend fun connect(address: String): SessionNext {
        var next = SessionNext.STAY
        val token = store.identity().token
        try {
            client.webSocket(urlString = "ws://$address/api/signage/ws", request = { token?.let(::bearerAuth) }) {
                store.saveIdentity(store.identity().copy(lastAddress = address))
                onAddress(address)
                send(Frame.Text(SignageJson.encodeToString(PlayerMessage.serializer(), session.hello())))
                session.onConnected()
                val reporter = launch {
                    while (true) {
                        send(Frame.Text(SignageJson.encodeToString(PlayerMessage.serializer(), session.status())))
                        delay(STATUS_EVERY_MS)
                    }
                }
                for (frame in incoming) {
                    val message = (frame as? Frame.Text)?.let(::decode)
                    if (message != null) next = session.handle(message)
                    if (next != SessionNext.STAY) break
                }
                reporter.cancel()
            }
        } catch (e: WebSocketException) {
            if (token != null && HttpStatusCode.Unauthorized.value.toString() in e.message.orEmpty()) {
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
        const val MAX_BACKOFF_MS = 60_000L
        const val STATUS_EVERY_MS = 60_000L
    }
}
