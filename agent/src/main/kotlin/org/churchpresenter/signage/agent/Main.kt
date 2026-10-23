package org.churchpresenter.signage.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

const val AGENT_VERSION = "0.1.0"

fun main(args: Array<String>) {
    // Previews are scaled with java.awt, which must not look for a display.
    System.setProperty("java.awt.headless", "true")
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
    val platform = AgentConfig.currentPlatform()
    val player = MpvPlayer(engine.state, MpvProcessLauncher(config.mpv, config.mpvArgs, ipcPath(config)))
    val app = AppFolder(config.dataDir.resolve("app")).also { it.cleanUp() }
    val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var session: PlayerSession
    val updater = Updater(
        app = app,
        platform = platform,
        fetch = { path, consume ->
            val token = store.identity().token ?: throw IOException("not paired")
            val at = address ?: throw IOException("not connected")
            HttpContentSource(client, "http://$at", token).download(path, consume)
        },
        report = { session.outgoing.trySend(it) },
        restart = {
            // Time for the last progress message to reach ChurchPresenter; the service starts the new version.
            Thread.sleep(RESTART_GRACE_MS)
            exitProcess(0)
        },
    )
    session = PlayerSession(
        store = store,
        engine = engine,
        agent = AgentInfo(AGENT_VERSION, platform, config.screenWidth, config.screenHeight),
        sync = {
            val token = store.identity().token
            val at = address
            if (token == null || at == null) SyncResult.Failed("not paired") else {
                ManifestSync(store, HttpContentSource(client, "http://$at", token)).sync()
            }
        },
        previews = PreviewMaker(player::screenshot, config.dataDir),
        updater = updater,
        app = app,
        background = background,
    )
    if (!store.identity().isPaired) engine.setMessage(session.pairingMessage())

    runBlocking(Dispatchers.IO) {
        launch { engine.probeCec().also { Log.info("CEC: ${engine.cecState}") } }
        launch { engine.run() }
        launch { ServerConnection(client, store, MdnsServerLocator(), session) { address = it }.run() }
        launch { player.run() }
        launch {
            // A new version that has run this long without crashing counts as healthy even unpaired.
            delay(HEALTHY_AFTER_MS)
            app.markHealthy()
        }
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
private const val RESTART_GRACE_MS = 1_000L
private const val HEALTHY_AFTER_MS = 120_000L
private const val MAX_SOCKET_PATH = 100
private const val CONNECT_TIMEOUT_MS = 5_000L
