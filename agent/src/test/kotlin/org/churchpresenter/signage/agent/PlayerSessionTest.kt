package org.churchpresenter.signage.agent

import kotlinx.coroutines.test.runTest
import org.churchpresenter.signage.core.OverrideAction
import org.churchpresenter.signage.core.PROTOCOL_VERSION
import org.churchpresenter.signage.core.Platform
import org.churchpresenter.signage.core.ScreenOverride
import org.churchpresenter.signage.core.ServerMessage
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
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
        assertEquals(KioskMode.IDLE, engine.state.value.mode)
        assertTrue(session.pairingCode in engine.state.value.message.orEmpty())
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
        assertEquals(KioskMode.BLANK, engine.state.value.mode)
        session.handle(ServerMessage.SetOverride(null))
        assertEquals(KioskMode.PLAYLIST, engine.state.value.mode)

        session.handle(ServerMessage.Identify(5))
        assertEquals("Lobby", engine.state.value.identify)
        session.handle(ServerMessage.Refresh)
        assertEquals(1, engine.state.value.reloadToken)
        val update = ServerMessage.UpdateAvailable("0.2.0", 1, 1, "a".repeat(64), "/x")
        assertEquals(SessionNext.STAY, session.handle(update))
    }

    @Test
    fun anUnsupportedOrRejectedServerWaitsLong() = runTest {
        assertEquals(SessionNext.WAIT_LONG, session.handle(ServerMessage.Unsupported(PROTOCOL_VERSION + 1)))
        assertEquals(SessionNext.WAIT_LONG, session.handle(ServerMessage.PairingRejected))
        assertTrue(engine.state.value.message.orEmpty().contains("declined"))
    }
}
