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
import org.churchpresenter.signage.core.Platform
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

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
    if (config.factoryReset) {
        store.factoryReset()
        Log.info("Factory reset: content and pairing removed; the player pairs as a new screen")
        return
    }
    config.address?.let { store.saveIdentity(store.identity().copy(fixedAddress = it)) }
    Log.info("Signage agent $AGENT_VERSION, player ${store.identity().playerId}, data in ${config.dataDir}")

    val chromium = findChromium()
    val engine = PlayerEngine(store, if (config.cec) CecCtl() else NoCec, web = chromium != null)
    val client = HttpClient(CIO) {
        install(WebSockets) { pingIntervalMillis = PING_MS }
        install(HttpTimeout) { connectTimeoutMillis = CONNECT_TIMEOUT_MS }
    }
    val web = chromium?.let { webRenderer(it, config, client, engine) }
    var address: String? = null
    val platform = AgentConfig.currentPlatform()
    val app = AppFolder(config.dataDir.resolve("app")).also { it.cleanUp() }
    val launcher = MpvProcessLauncher(
        config.mpv, config.mpvArgs, ipcPath(config), onFileError = { engine.streamFailed() },
    )
    val output = config.mpvArgs.firstOrNull { it.startsWith("--vo=") }?.removePrefix("--vo=") ?: "default"
    startDiagnostics(store, platform, output, app)
    val exits = ExitWatch()
    val player = MpvPlayer(
        engine.state,
        launcher,
        MpvPlayer.writeBlank(config.dataDir.resolve("blank.png")),
        onExit = {
            if (exits.exited()) {
                Diagnostics.report(
                    "mpv keeps exiting",
                    mapOf("mpv.version" to launcher.version, "mpv.output" to output),
                    launcher.recentOutput(),
                )
            }
        },
        webStream = web?.let { it::streamUrl } ?: { it },
    )
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
        web = web != null,
    )
    if (!store.identity().isPaired) engine.setMessage(session.pairingMessage())

    runBlocking(Dispatchers.IO) {
        launch { engine.probeCec().also { Log.info("CEC: ${engine.cecState}") } }
        launch { engine.run() }
        launch { ServerConnection(client, store, MdnsServerLocator(), session) { address = it }.run() }
        launch { player.run() }
        web?.let { launch { it.run(engine.state) } }
        launch {
            // A new version that has run this long without crashing counts as healthy even unpaired.
            delay(HEALTHY_AFTER_MS)
            app.markHealthy()
        }
    }
}

/** The Chromium that draws web pages, said in the log either way. */
private fun findChromium(): String? = Chromium.find().also { found ->
    Log.info(if (found != null) "Web pages drawn by $found" else "No Chromium found: web pages are not offered")
}

/** Draws web pages with the Chromium at [executable], its profile in the data folder. */
private fun webRenderer(executable: String, config: AgentConfig, client: HttpClient, engine: PlayerEngine) =
    WebRenderer(
        browser = Chromium(executable, config.dataDir.resolve("web-profile"), client),
        server = MjpegServer(),
        panelWidth = config.screenWidth,
        panelHeight = config.screenHeight,
        onFailed = { url -> engine.linkFailed(listOf(url)) },
        reachable = { url -> client.answers(url, WebRenderer.LOAD_TIMEOUT_MS) },
    )

/**
 * Starts crash reporting while the owning ChurchPresenter allows it, and reports an update the
 * launcher rolled back since the last run.
 */
private fun startDiagnostics(store: PlayerStore, platform: Platform, output: String, app: AppFolder) {
    val setup = Diagnostics.Setup(
        dsn = Diagnostics.dsnFrom(Diagnostics::class.java.getResourceAsStream("/sentry.properties")),
        release = AGENT_VERSION,
        production = RELEASE_BUILD,
        platform = platform.name.lowercase(),
        installId = store.identity().playerId,
    )
    Diagnostics.start(setup) { store.manifest()?.manifest?.diagnostics ?: true }
    Diagnostics.tag("mpv.output", output)
    app.rolledBackVersion()?.let { failed ->
        val tags = mapOf("update.failed" to failed, "update.kept" to AGENT_VERSION)
        Diagnostics.report("An update was rolled back", tags)
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
