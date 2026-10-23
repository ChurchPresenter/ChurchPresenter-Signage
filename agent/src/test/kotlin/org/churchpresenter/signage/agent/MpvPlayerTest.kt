package org.churchpresenter.signage.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvPlayerTest {
    private class FakeSession : MpvPlayer.Session {
        val sent = mutableListOf<List<JsonElement>>()
        val exit = CompletableDeferred<Unit>()
        var closed = false

        override fun send(commands: List<JsonElement>) {
            sent += commands
        }

        override suspend fun screenshot(file: Path): Boolean = false

        override suspend fun awaitExit() = exit.await()

        override fun close() {
            closed = true
        }
    }

    private val blank = Path.of("/store/blank.png")
    private val state = MutableStateFlow(ScreenState(ScreenMode.IDLE, message = "code 1234"))
    private val sessions = mutableListOf<FakeSession>()
    private var failNextStart = false
    private val launcher = MpvPlayer.Launcher {
        if (failNextStart) {
            failNextStart = false
            throw IOException("no mpv")
        }
        FakeSession().also { sessions += it }
    }

    private fun TestScope.startPlayer() {
        backgroundScope.launch { MpvPlayer(state, launcher, blank, restartDelayMs = 0).run() }
        runCurrent()
    }

    @Test
    fun aFreshMpvGetsEverythingThenOnlyChanges() = runTest {
        startPlayer()
        val first = sessions.single()
        assertEquals(MpvScene.commands(null, state.value, blank), first.sent.single())
        state.value = state.value.copy(message = "code 5678")
        runCurrent()
        assertEquals(1, first.sent.last().size)
    }

    @Test
    fun whenMpvExitsItIsStartedAgainAndToldEverything() = runTest {
        startPlayer()
        sessions.single().exit.complete(Unit)
        runCurrent()
        assertEquals(2, sessions.size)
        assertTrue(sessions[0].closed)
        assertEquals(MpvScene.commands(null, state.value, blank), sessions[1].sent.single())
    }

    @Test
    fun aRefreshStartsAFreshMpv() = runTest {
        startPlayer()
        state.value = state.value.copy(restartToken = 1)
        runCurrent()
        assertEquals(2, sessions.size)
        assertTrue(sessions[0].closed)
        assertEquals(1, sessions[0].sent.size)
    }

    @Test
    fun aFailedStartIsTriedAgain() = runTest {
        failNextStart = true
        startPlayer()
        assertEquals(1, sessions.size)
    }
}

class MpvVersionArgsTest {
    @Test
    fun anOldMpvGetsTheOldNames() {
        assertEquals(
            listOf("--vd-lavc-software-fallback=600", "--alpha=blend"),
            MpvProcessLauncher.versionArgs(setOf("--vd-lavc-software-fallback", "--background", "--alpha")),
        )
    }

    @Test
    fun aNewMpvGetsTheNewNames() {
        assertEquals(
            listOf("--hwdec-software-fallback=600", "--alpha=blend", "--background=color"),
            MpvProcessLauncher.versionArgs(setOf("--hwdec-software-fallback", "--background-color", "--alpha")),
        )
    }
}
