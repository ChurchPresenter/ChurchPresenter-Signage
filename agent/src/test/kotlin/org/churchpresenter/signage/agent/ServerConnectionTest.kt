package org.churchpresenter.signage.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.churchpresenter.signage.core.Platform
import java.util.Collections
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** An unpaired player's search: who it tries, in which order, and what it does when one holds it back. */
@OptIn(ExperimentalPathApi::class)
class ServerConnectionTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second
    private val engine = PlayerEngine(store, FakeCec(), MutableClock(instant("2026-10-19T08:00"))::read)
    private val session = PlayerSession(
        store, engine, AgentInfo("0.1.0", Platform.LINUX_ARM64, 1920, 1080),
        sync = { ManifestSync(store, FakeSource("v1", manifestOf(pictureA), listOf(pictureA))).sync() },
        random = Random(7),
    )
    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest
    fun cleanUp() {
        servers.forEach { it.stop(0, 0) }
        root.deleteRecursively()
    }

    /** A ChurchPresenter on a free port that lists each hello's order in [log], declining when asked. */
    private inner class Instance(val name: String, declines: Boolean, val log: MutableList<String>) {
        val greeted = CompletableDeferred<Unit>()
        private val server = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/api/signage/ws") {
                    incoming.receive()
                    log += name
                    if (declines) close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "held after removal"))
                    greeted.complete(Unit)
                    for (ignored in incoming) Unit
                }
            }
        }.also { it.start(wait = false); servers += it }
        val address: String = runBlocking { "127.0.0.1:${server.engine.resolvedConnectors().first().port}" }
    }

    private fun connectAndWaitFor(
        locator: ServerLocator,
        stopWhen: CompletableDeferred<Unit>,
    ) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        HttpClient(CIO) { install(WebSockets) }.use { client ->
            val connection = ServerConnection(client, store, locator, session)
            val job = scope.launch { connection.run() }
            try {
                withTimeout(WAIT_MS) { stopWhen.await() }
            } finally {
                job.cancel()
                scope.cancel()
            }
        }
    }

    @Test
    fun aDeclinedConnectionMovesOnToTheNextChurchPresenterInTheSamePass() {
        store.saveIdentity(store.identity().released())
        val log = Collections.synchronizedList(mutableListOf<String>())
        val a = Instance("a", declines = true, log)
        val b = Instance("b", declines = false, log)
        connectAndWaitFor(locator(a.address, b.address), b.greeted)
        assertEquals(listOf("a", "b"), log.toList())
    }

    @Test
    fun anUnpairedPlayerLooksAroundBeforeReturningToTheLastOneItUsed() {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val a = Instance("a", declines = true, log)
        val b = Instance("b", declines = false, log)
        store.saveIdentity(store.identity().released().copy(lastAddress = a.address))
        connectAndWaitFor(locator(b.address), b.greeted)
        assertEquals(listOf("b"), log.toList())
    }

    @Test
    fun aPlayerWaitingOnOneChurchPresenterGoesWhenAnotherAsksForIt() = runBlocking {
        store.saveIdentity(store.identity().released())
        val log = Collections.synchronizedList(mutableListOf<String>())
        val a = Instance("a", declines = false, log)
        val b = Instance("b", declines = false, log)
        val scope = CoroutineScope(Dispatchers.IO)
        HttpClient(CIO) { install(WebSockets) }.use { client ->
            val connection = ServerConnection(client, store, locator(b.address), session)
            val job = scope.launch { connection.run() }
            try {
                withTimeout(WAIT_MS) { b.greeted.await() }
                connection.nudged(a.address)
                withTimeout(WAIT_MS) { a.greeted.await() }
            } finally {
                job.cancel()
                scope.cancel()
            }
        }
        assertEquals(listOf("b", "a"), log.toList())
    }

    private fun locator(vararg addresses: String) = ServerLocator { addresses.toList() }

    private companion object {
        const val WAIT_MS = 15_000L
    }
}
