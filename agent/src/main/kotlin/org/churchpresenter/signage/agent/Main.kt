package org.churchpresenter.signage.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.Path
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

    val player = MpvPlayer(engine.state, MpvProcessLauncher(config.mpv, config.mpvArgs, ipcPath(config)))
    runBlocking(Dispatchers.IO) {
        launch { engine.probeCec().also { Log.info("CEC: ${engine.cecState}") } }
        launch { engine.run() }
        launch { ServerConnection(client, store, MdnsServerLocator(), session) { address = it }.run() }
        launch { player.run() }
    }
}

/**
 * A named pipe on Windows; elsewhere a socket in the data folder, which only the agent's user can open,
 * or in the temp folder when the data folder's path is too long for a socket's (about 100 bytes).
 */
private fun ipcPath(config: AgentConfig): Path {
    if (System.getProperty("os.name").startsWith("Windows")) return Path("\\\\.\\pipe\\churchpresenter-signage-mpv")
    val inData = config.dataDir.toAbsolutePath().resolve("mpv.sock")
    return if (inData.toString().toByteArray().size <= MAX_SOCKET_PATH) inData
    else Path(System.getProperty("java.io.tmpdir"), "churchpresenter-signage-mpv.sock")
}

private const val PING_MS = 15_000L
private const val MAX_SOCKET_PATH = 100
private const val CONNECT_TIMEOUT_MS = 5_000L
