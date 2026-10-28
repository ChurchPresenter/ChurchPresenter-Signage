package org.churchpresenter.signage.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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
    val kioskPrograms = findKioskPrograms(chromium)
    val cec: Cec = if (config.cec) CecCtl() else NoCec
    val engine = PlayerEngine(store, cec, web = chromium != null, interactive = kioskPrograms != null)
    val client = HttpClient(CIO) {
        install(WebSockets) { pingIntervalMillis = PING_MS }
        install(HttpTimeout) { connectTimeoutMillis = CONNECT_TIMEOUT_MS }
    }
    val web = chromium?.let { webParts(it, config, client, engine) }
    var address: String? = null
    val platform = AgentConfig.currentPlatform()
    val app = AppFolder(config.dataDir.resolve("app")).also { it.cleanUp() }
    val launcher = MpvProcessLauncher(
        config.mpv, config.mpvArgs, ipcPath(config),
        onFileError = { engine.streamFailed() },
        onFileEnded = engine::itemEnded,
    )
    val output = config.mpvArgs.firstOrNull { it.startsWith("--vo=") }?.removePrefix("--vo=") ?: "default"
    startDiagnostics(store, platform, output, app)
    val exits = ExitWatch()
    val screen = Mutex()
    val player = MpvPlayer(
        engine.state,
        launcher,
        MpvPlayer.writeBlank(config.dataDir.resolve("blank.png")),
        onExit = { mpvExited(exits, launcher, output) },
        webStream = web?.renderer?.let { it::streamUrl } ?: { it },
        screen = screen,
    )
    val kiosk = kioskPrograms?.let { (cage, browser) -> KioskParts(browser, cage, client, engine, store, screen) }
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
        system = SystemdSetup.find(),
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
        previews = PreviewMaker(previewShot(kiosk, player), config.dataDir),
        updater = updater,
        app = app,
        background = background,
        web = web != null,
        webTest = web?.test,
        interactive = kiosk != null,
        power = SystemdPower.find(),
        tv = tvSwitch(cec),
    )
    if (!store.identity().isPaired) engine.setMessage(session.pairingMessage())

    runBlocking(Dispatchers.IO) {
        launch { engine.probeCec().also { Log.info("CEC: ${engine.cecState}") } }
        launch { engine.run() }
        launch { ServerConnection(client, store, MdnsServerLocator(), session) { address = it }.run() }
        launch { player.run() }
        web?.let { launch { it.renderer.run(engine.state) } }
        kiosk?.let { launch { it.run() } }
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

/**
 * Reports an mpv that keeps exiting, once it has three times in ten minutes. Not an mpv that is not
 * there at all (it never told its version): a missing program is the install's, not a defect, and
 * each failed start is already a warning in the log.
 */
private fun mpvExited(exits: ExitWatch, launcher: MpvProcessLauncher, output: String) {
    if (launcher.version.isEmpty() || !exits.exited()) return
    Diagnostics.report(
        "mpv keeps exiting",
        mapOf("mpv.version" to launcher.version, "mpv.output" to output),
        launcher.recentOutput(),
    )
}

/** Turns the TV on or to standby when ChurchPresenter asks; null without CEC. */
private fun tvSwitch(cec: Cec): ((Boolean) -> Boolean)? =
    if (cec == NoCec) null else { on -> cec.send(if (on) CecCommand.POWER_ON else CecCommand.STANDBY) }

/** cage and the Chromium it shows interactive pages with, said in the log either way; null without both. */
private fun findKioskPrograms(chromium: String?): Pair<String, String>? {
    val cage = chromium?.let { CageKiosk.find("cage") }
    Log.info(if (cage != null) "Interactive pages shown by $cage" else "No cage or Chromium: no interactive pages")
    return cage?.let { it to chromium }
}

/**
 * The kiosk browser for interactive pages, and the screen's touches where libinput-tools is
 * installed: the switch gesture and the pages' idle time.
 */
private class KioskParts(
    chromium: String,
    cage: String,
    client: HttpClient,
    engine: PlayerEngine,
    store: PlayerStore,
    screen: Mutex,
) {
    private val control = KioskControl(store, engine, engine.pinPrompt)
    private val session = KioskSession(
        state = engine.state,
        screen = screen,
        browser = CageKiosk(cage, chromium, client),
        profileDir = CageKiosk.profileDir(),
        onFailed = { url -> engine.linkFailed(listOf(url)) },
        pins = control,
    )
    private val touch = CageKiosk.find("libinput")?.let { libinput ->
        TouchInput(
            libinput,
            orientation = { engine.state.value.orientation },
            onActivity = session::touched,
            onGesture = control::gesture,
        )
    }

    /** What the kiosk shows, for the preview; null while it does not have the screen. */
    suspend fun screenshot(file: Path): Boolean? = session.screenshot(file)

    suspend fun run() = coroutineScope {
        if (touch == null) Log.info("libinput-tools is not installed: no switch gesture on the screen")
        launch { session.run() }
        touch?.let { launch { it.run() } }
    }
}

/** The preview's picture: from the kiosk browser while it has the screen, from mpv otherwise. */
private fun previewShot(kiosk: KioskParts?, player: MpvPlayer): suspend (Path) -> Boolean =
    { file -> kiosk?.screenshot(file) ?: player.screenshot(file) }

/** What draws web pages, and the test of how smoothly it does. */
private class WebParts(val renderer: WebRenderer, val test: WebTest)

/** Draws web pages with the Chromium at [executable], its profile in the data folder. */
private fun webParts(executable: String, config: AgentConfig, client: HttpClient, engine: PlayerEngine): WebParts {
    val frames = MjpegServer()
    val chromium = Chromium(executable, config.dataDir.resolve("web-profile"), client)
    val renderer = WebRenderer(
        browser = chromium,
        server = frames,
        panelWidth = config.screenWidth,
        panelHeight = config.screenHeight,
        onFailed = { url -> engine.linkFailed(listOf(url)) },
        reachable = { url -> client.answers(url, WebRenderer.LOAD_TIMEOUT_MS) },
    )
    return WebParts(renderer, WebTest(engine, frames::framesSent, gpu = { chromium.gpu }))
}

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
