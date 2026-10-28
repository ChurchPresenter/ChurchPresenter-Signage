package org.churchpresenter.signage.agent

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.churchpresenter.signage.core.Features
import org.churchpresenter.signage.core.Platform
import org.churchpresenter.signage.core.PowerAction
import org.churchpresenter.signage.core.ReleaseSigning
import org.churchpresenter.signage.core.ServerMessage
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What ChurchPresenter asks of the machine itself: restart or shut down, the TV on or off, and the
 * features a player lists for them, only where it can.
 */
@OptIn(ExperimentalPathApi::class)
class MachineCommandsTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second
    private val engine = PlayerEngine(store, FakeCec())
    private val agent = AgentInfo("0.1.0", Platform.LINUX_ARM64, 1920, 1080)
    private val powered = mutableListOf<PowerAction>()
    private val tv = mutableListOf<Boolean>()

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    @Test
    fun aPlayerListsOnlyWhatItCanDo() = runTest {
        val bare = PlayerSession(store, engine, agent, sync = { SyncResult.UpToDate }, random = Random(1))
        listOf(Features.POWER, Features.TV, Features.SYSTEM_UPDATE, Features.INTERACTIVE)
            .forEach { assertFalse(it in bare.features, it) }
        val able = PlayerSession(
            store, engine, agent, sync = { SyncResult.UpToDate }, random = Random(1),
            background = backgroundScope, interactive = true,
            power = Power { powered += it; true }, tv = { tv += it; true },
        )
        listOf(Features.POWER, Features.TV, Features.INTERACTIVE).forEach { assertTrue(it in able.features, it) }
        assertFalse(Features.SYSTEM_UPDATE in able.features, "no updater with a setup service")
    }

    @Test
    fun theTvAndThePowerAreSwitchedAsAsked() = runTest {
        val session = PlayerSession(
            store, engine, agent, sync = { SyncResult.UpToDate }, random = Random(1),
            background = backgroundScope, power = Power { powered += it; true }, tv = { tv += it; true },
        )
        assertEquals(SessionNext.STAY, session.handle(ServerMessage.Tv(on = false)))
        assertEquals(SessionNext.STAY, session.handle(ServerMessage.Tv(on = true)))
        runCurrent()
        assertEquals(listOf(false, true), tv)
        session.handle(ServerMessage.Power(PowerAction.RESTART))
        runCurrent()
        assertTrue(powered.isEmpty(), "a moment later, so the socket closes cleanly")
        advanceTimeBy(POWER_DELAY_MS + 1)
        assertEquals(listOf(PowerAction.RESTART), powered)
    }

    @Test
    fun aPlayerThatCannotIgnoresTheMachinesCommands() = runTest {
        val session = PlayerSession(
            store, engine, agent, sync = { SyncResult.UpToDate }, random = Random(1), background = backgroundScope,
        )
        listOf(ServerMessage.Tv(true), ServerMessage.Power(PowerAction.SHUT_DOWN), ServerMessage.UpdateSystem)
            .forEach { assertEquals(SessionNext.STAY, session.handle(it)) }
        advanceTimeBy(POWER_DELAY_MS + 1)
        assertTrue(powered.isEmpty() && tv.isEmpty())
    }

    @Test
    fun installShTrustsTheSameReleaseKeyAsThePlayer() {
        // The root setup service checks install.sh with this key; it must be the one the player has.
        val script = Path("../install/install.sh").readText()
        assertTrue(ReleaseSigning.PUBLIC_KEY in script, "install.sh carries ReleaseSigning.PUBLIC_KEY")
    }

    private companion object {
        /** The session's wait before it restarts or shuts down (its own POWER_DELAY_MS). */
        const val POWER_DELAY_MS = 1_000L
    }
}
