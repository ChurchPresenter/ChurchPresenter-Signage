package org.churchpresenter.signage.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.churchpresenter.signage.core.Features
import org.churchpresenter.signage.core.OverrideAction
import org.churchpresenter.signage.core.PROTOCOL_VERSION
import org.churchpresenter.signage.core.Platform
import org.churchpresenter.signage.core.PlayerMessage
import org.churchpresenter.signage.core.ScreenOverride
import org.churchpresenter.signage.core.ServerMessage
import org.churchpresenter.signage.core.UpdateState
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class PlayerSessionTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second
    private val clock = MutableClock(instant("2026-10-19T08:00"))
    private val engine = PlayerEngine(store, FakeCec(), clock::read)
    private val source = FakeSource("v1", manifestOf(pictureA), listOf(pictureA))
    private var syncs = 0
    private val session = PlayerSession(
        store, engine, AgentInfo("0.1.0", Platform.LINUX_ARM64, 1920, 1080),
        sync = { syncs++; ManifestSync(store, source).sync() },
        random = Random(7),
    )

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    @Test
    fun anUnpairedPlayerOffersItsCode() {
        store.saveIdentity(store.identity().released())
        val hello = session.hello()
        assertEquals(session.pairingCode, hello.pairingCode)
        assertEquals(4, session.pairingCode.length)
        assertEquals(PROTOCOL_VERSION, hello.protocolMax)
        assertNull(hello.manifestVersion)
    }

    @Test
    fun pairingStoresTheOwnerAndReconnects() = runTest {
        store.saveIdentity(store.identity().released())
        assertEquals(SessionNext.STAY, session.handle(ServerMessage.PairingPending(session.pairingCode)))
        assertTrue(session.pairingCode in engine.state.value.message.orEmpty())

        assertEquals(SessionNext.RECONNECT, session.handle(ServerMessage.Paired("token", OWNER, "Main PC")))
        val identity = store.identity()
        assertEquals("token", identity.token)
        assertEquals(PROTOCOL_VERSION, identity.ownerProtocol)
        assertNull(session.hello().pairingCode)
        assertNull(engine.state.value.message)
    }

    @Test
    fun connectingSyncsAndAChangeSyncsOnlyWhenTheVersionDiffers() = runTest {
        session.onConnected()
        assertEquals("v1", store.manifest()?.version)
        assertEquals("welcome", engine.state.value.playlistId)
        assertEquals(1, syncs)

        session.handle(ServerMessage.ManifestChanged("v1"))
        assertEquals(1, syncs)
        source.version = "v2"
        session.handle(ServerMessage.ManifestChanged("v2"))
        assertEquals(2, syncs)
        assertEquals(null, session.status().errors.firstOrNull())
        assertEquals("welcome", session.status().showingPlaylistId)
    }

    @Test
    fun aFailedSyncIsReported() = runTest {
        source.failManifest = true
        session.onConnected()
        assertTrue(session.status().errors.single().contains("unreachable"))
    }

    @Test
    fun releaseForgetsTheOwnerAndContent() = runTest {
        session.onConnected()
        store.saveOverride(ScreenOverride(OverrideAction.TvOff, setAtMs = 1))
        assertEquals(SessionNext.RECONNECT, session.handle(ServerMessage.Released))
        assertFalse(store.identity().isPaired)
        assertNull(store.manifest())
        assertNull(store.override())
        assertEquals(ScreenMode.IDLE, engine.state.value.mode)
        assertTrue(session.pairingCode in engine.state.value.message.orEmpty())
    }

    @Test
    fun aFactoryResetForgetsTheOwnerContentAndLastAddressButKeepsTheId() = runTest {
        session.onConnected()
        val id = store.identity().playerId
        store.saveIdentity(
            store.identity().copy(
                token = "t",
                ownerId = OWNER,
                lastAddress = "10.0.0.2:8765",
                fixedAddress = "10.0.0.1:8765",
            ),
        )
        store.saveOverride(ScreenOverride(OverrideAction.TvOff, setAtMs = 1))
        assertEquals(SessionNext.RECONNECT, session.handle(ServerMessage.FactoryReset))
        val identity = store.identity()
        assertFalse(identity.isPaired)
        assertEquals(id, identity.playerId)
        assertNull(identity.lastAddress)
        assertEquals("10.0.0.1:8765", identity.fixedAddress)
        assertNull(store.manifest())
        assertNull(store.override())
        assertTrue(Features.FACTORY_RESET in session.features)
    }

    @Test
    fun theWaitingMessageNamesTheComputerItWaitsOn() {
        session.serverAddress = "10.0.0.9:8765"
        assertTrue("at 10.0.0.9:8765" in session.pairingMessage())
        assertTrue(session.pairingCode in session.pairingMessage())
    }

    @Test
    fun aRevokedTokenReleasesToo() {
        session.onUnauthorized()
        assertFalse(store.identity().isPaired)
    }

    @Test
    fun switchesIdentifyAndRefreshReachTheEngine() = runTest {
        session.onConnected()
        val override = ScreenOverride(OverrideAction.TvOff, setAtMs = clock.now.toEpochMilli())
        assertEquals(SessionNext.STAY, session.handle(ServerMessage.SetOverride(override)))
        assertEquals(override, store.override())
        assertEquals(ScreenMode.BLANK, engine.state.value.mode)
        session.handle(ServerMessage.SetOverride(null))
        assertEquals(ScreenMode.PLAYLIST, engine.state.value.mode)

        session.handle(ServerMessage.Identify(5))
        assertEquals("Lobby", engine.state.value.identify)
        session.handle(ServerMessage.Refresh)
        assertEquals(1, engine.state.value.restartToken)
        val update = ServerMessage.UpdateAvailable("0.2.0", 1, 1, "a".repeat(64), "/x")
        assertEquals(SessionNext.STAY, session.handle(update))
    }

    @Test
    fun aWipeDeletesTheContentAndFetchesTheManifestAgain() = runTest {
        session.onConnected()
        assertTrue(store.files.has(pictureA.sha))
        source.manifest = manifestOf()
        source.version = "empty"
        assertEquals(SessionNext.STAY, session.handle(ServerMessage.Wipe))
        assertFalse(store.files.has(pictureA.sha))
        assertEquals("empty", store.manifest()?.version)
        assertTrue(store.identity().isPaired)
    }

    @Test
    fun aPlayerListsOnlyWhatItCanDo() = runTest {
        assertEquals(BASICS.toList(), session.hello().features)
        val app = AppFolder(root.resolve("app"))
        val withPreview = sessionWith(previews = PreviewMaker({ false }, root), app = app)
        assertEquals(
            listOf(*BASICS, Features.PREVIEW),
            withPreview.hello().features,
        )
        root.resolve("app/0.1.0").createDirectories()
        Files.createSymbolicLink(root.resolve("app/current"), Path.of("0.1.0"))
        val updater = Updater(app, Platform.LINUX_ARM64, { _, _ -> }, {}, {})
        assertEquals(
            listOf(*BASICS, Features.PREVIEW, Features.UPDATE),
            sessionWith(app = app, previews = PreviewMaker({ false }, root), updater = updater).hello().features,
        )
    }

    @Test
    fun aPreviewRequestIsAnsweredOnTheSide() = runTest {
        val screen = PreviewMaker.jpeg(BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB))
        val withPreview = sessionWith(previews = PreviewMaker({ file -> file.writeBytes(screen); true }, root))
        assertEquals(SessionNext.STAY, withPreview.handle(ServerMessage.PreviewRequest(maxWidth = 320)))
        val preview = withPreview.outgoing.tryReceive().getOrNull() as PlayerMessage.Preview
        assertEquals(320, preview.width)
    }

    @Test
    fun aScreenshotThatNeverComesDoesNotHoldUpTheConnection() = runTest {
        val screen = PreviewMaker.jpeg(BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB))
        val release = CompletableDeferred<Unit>()
        val shots = AtomicInteger()
        val shoot: suspend (Path) -> Boolean = { file ->
            shots.incrementAndGet()
            release.await()
            file.writeBytes(screen)
            true
        }
        val maker = PreviewMaker(shoot, root)
        val waiting = sessionWith(previews = maker, background = backgroundScope)

        assertEquals(SessionNext.STAY, waiting.handle(ServerMessage.PreviewRequest(maxWidth = 320)))
        while (shots.get() == 0) yield()
        // A second request while the first is still waiting is not queued behind it.
        assertEquals(SessionNext.STAY, waiting.handle(ServerMessage.PreviewRequest(maxWidth = 320)))
        assertEquals(1, shots.get())

        release.complete(Unit)
        assertEquals(320, (waiting.outgoing.receive() as PlayerMessage.Preview).width)
    }

    @Test
    fun webPagesAndTheirTestAreListedOnlyWhenThePlayerCanDoThem() = runTest {
        val test = WebTest(engine, { 0 }, pause = {}, settleMs = 0)
        val basic = sessionWith(webTest = test).hello().features
        assertEquals(BASICS.toList(), basic)
        assertEquals(
            listOf(
                *BASICS, Features.WEB, Features.PAGE_CODE, Features.WEB_TEST,
            ),
            sessionWith(web = true, webTest = test, background = backgroundScope).hello().features,
        )
    }

    @Test
    fun aTouchScreenSaysItReloadsItsPagesWhileNobodyUsesThem() = runTest {
        val features = sessionWith(interactive = true).hello().features
        assertTrue(Features.TOUCH_RELOAD in features && Features.IDLE_SCREEN in features)
        assertFalse(Features.TOUCH_RELOAD in sessionWith(web = true).hello().features)
    }

    @Test
    fun aWebTestRunsInTheBackgroundAndAnswers() = runTest {
        var frames = 0L
        val test = WebTest(engine, { frames }, pause = { frames += 15 }, settleMs = 0)
        val testing = sessionWith(web = true, webTest = test, background = backgroundScope)
        assertEquals(SessionNext.STAY, testing.handle(ServerMessage.WebTest(fps = listOf(15), secondsEach = 2)))
        testScheduler.advanceUntilIdle()
        val result = testing.outgoing.receive() as PlayerMessage.WebTestResult
        assertEquals(15, result.runs.single().fps)
        // Without the test, the request is ignored.
        assertEquals(SessionNext.STAY, sessionWith().handle(ServerMessage.WebTest()))
    }

    @Test
    fun aStatusCarriesTheMachinesHealth() {
        val reader = HealthReader(CpuLoad(root.resolve("no-stat")), { Memory(1000, 250) }, { 61.5 }, { 3600 })
        val status = PlayerSession(
            store, engine, AgentInfo("0.1.0", Platform.LINUX_ARM64, 1920, 1080),
            sync = { SyncResult.UpToDate }, health = reader,
        ).status()
        val health = listOf(status.cpuPercent, status.memoryPercent, status.temperatureC, status.uptimeSeconds)
        assertEquals(listOf(null, 75, 61.5, 3600L), health)
    }

    @Test
    fun anUpdateRunsInTheBackgroundAndReportsBack() = runTest {
        val app = AppFolder(root.resolve("app"))
        lateinit var updating: PlayerSession
        val updater = Updater(app, Platform.LINUX_ARM64, { _, _ -> }, { updating.outgoing.trySend(it) }, {})
        updating = sessionWith(app = app, updater = updater, background = backgroundScope)
        updating.handle(ServerMessage.UpdateAvailable("0.2.0", 1, 1, "a".repeat(64), "/x"))
        testScheduler.advanceUntilIdle()
        val progress = updating.outgoing.receive() as PlayerMessage.UpdateProgress
        assertEquals(UpdateState.FAILED, progress.state)
    }

    @Test
    fun aRollbackIsReportedOnceConnected() = runTest {
        val app = AppFolder(root.resolve("app").createDirectories())
        root.resolve("app/rolled-back").writeText("0.2.0")
        val rolled = sessionWith(app = app)
        rolled.onConnected()
        val progress = rolled.outgoing.tryReceive().getOrNull() as PlayerMessage.UpdateProgress
        assertEquals("0.2.0" to UpdateState.ROLLED_BACK, progress.version to progress.state)
        assertNull(app.takeRolledBack())
    }

    private fun sessionWith(
        previews: PreviewMaker? = null,
        updater: Updater? = null,
        app: AppFolder? = null,
        background: CoroutineScope? = null,
        web: Boolean = false,
        webTest: WebTest? = null,
        interactive: Boolean = false,
    ) = PlayerSession(
        store, engine, AgentInfo("0.1.0", Platform.LINUX_ARM64, 1920, 1080),
        sync = { ManifestSync(store, source).sync() },
        random = Random(7),
        previews = previews,
        updater = updater,
        app = app,
        background = background,
        web = web,
        webTest = webTest,
        interactive = interactive,
    )

    @Test
    fun anUnsupportedOrRejectedServerWaitsLong() = runTest {
        assertEquals(SessionNext.WAIT_LONG, session.handle(ServerMessage.Unsupported(PROTOCOL_VERSION + 1)))
        assertEquals(SessionNext.WAIT_LONG, session.handle(ServerMessage.PairingRejected))
        assertTrue(engine.state.value.message.orEmpty().contains("declined"))
    }

    private companion object {
        /** What every player lists. */
        val BASICS = arrayOf(
            Features.WIPE,
            Features.FACTORY_RESET,
            Features.STREAM,
            Features.ROTATION,
            Features.MESSAGE,
        )
    }
}
