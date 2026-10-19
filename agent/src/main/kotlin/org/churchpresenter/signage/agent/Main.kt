package org.churchpresenter.signage.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

const val AGENT_VERSION = "0.1.0"

fun main(args: Array<String>) {
    val config = try {
        AgentConfig.parse(args.toList())
    } catch (e: IllegalArgumentException) {
        Log.error("Bad arguments: ${e.message}")
        exitProcess(2)
    }
    config.dataDir.createDirectories()
    val store = PlayerStore(config.dataDir)
    config.address?.let { store.saveIdentity(store.identity().copy(fixedAddress = it)) }
    Log.info("Signage agent $AGENT_VERSION, player ${store.identity().playerId}, data in ${config.dataDir}")

    val engine = PlayerEngine(store, if (config.cec) CecCtl() else NoCec)
    val kiosk = KioskServer(engine, store)
    val client = HttpClient(CIO) {
        install(WebSockets) { pingIntervalMillis = PING_MS }
        install(HttpTimeout) { connectTimeoutMillis = CONNECT_TIMEOUT_MS }
    }
    var address: String? = null
    val session = PlayerSession(
        store = store,
        engine = engine,
        agent = AgentInfo(AGENT_VERSION, AgentConfig.currentPlatform(), config.screenWidth, config.screenHeight),
        sync = {
            val token = store.identity().token
            val at = address
            if (token == null || at == null) SyncResult.Failed("not paired") else {
                ManifestSync(store, HttpContentSource(client, "http://$at", token)).sync()
            }
        },
    )
    if (!store.identity().isPaired) engine.setMessage(session.pairingMessage())

    kiosk.start(config.kioskPort)
    runBlocking(Dispatchers.IO) {
        launch { engine.probeCec().also { Log.info("CEC: ${engine.cecState}") } }
        launch { engine.run() }
        launch { ServerConnection(client, store, MdnsServerLocator(), session) { address = it }.run() }
        launch { watchdog(kiosk, config.browserRestartCommand) }
    }
}

/** Restarts the browser when the kiosk page stops asking for its state. */
private suspend fun watchdog(kiosk: KioskServer, restart: List<String>) {
    if (restart.isEmpty()) return
    val started = Instant.now()
    while (true) {
        delay(WATCHDOG_EVERY_MS)
        val last = kiosk.lastPoll ?: started
        if (Duration.between(last, Instant.now()).toMillis() > PAGE_SILENT_MS) {
            Log.warn("Kiosk page silent; restarting the browser")
            try {
                ProcessBuilder(restart).inheritIO().start()
            } catch (e: IOException) {
                Log.error("Browser restart failed", e)
            }
        }
    }
}

private const val PING_MS = 15_000L
private const val CONNECT_TIMEOUT_MS = 5_000L
private const val WATCHDOG_EVERY_MS = 30_000L
private const val PAGE_SILENT_MS = 90_000L
