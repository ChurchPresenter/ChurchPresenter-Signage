package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.CecState
import org.churchpresenter.signage.core.OverrideAction
import org.churchpresenter.signage.core.Reason
import org.churchpresenter.signage.core.ScreenOverride
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalPathApi::class)
class PlayerEngineTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second
    private val clock = MutableClock(instant("2026-10-19T08:00"))
    private val cec = FakeCec()

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private fun install(vararg content: Content, events: List<Content> = emptyList()) {
        (content.toList() + events).forEach { store.files.put(it.sha, it.bytes.inputStream()) }
        store.commit(StoredManifest("v1", manifestOf(*content, events = events)))
    }

    private fun engine() = PlayerEngine(store, cec, clock::read).also { it.probeCec() }

    @Test
    fun withNoManifestTheIdleCardCarriesTheMessage() {
        val engine = engine()
        engine.setMessage("code 1234")
        assertEquals(ScreenState(ScreenMode.IDLE, message = "code 1234"), engine.state.value)
        assertNull(engine.decision)
        assertEquals(emptyList(), cec.sent)
    }

    @Test
    fun theDefaultPlaylistReachesTheScreen() {
        install(pictureA, pictureB)
        val engine = engine()
        engine.tick()
        val state = engine.state.value
        assertEquals(ScreenMode.PLAYLIST, state.mode)
        assertEquals("welcome", state.playlistId)
        assertEquals(listOf(pictureA, pictureB).map { root.resolve("files/${it.sha}") }, state.items.map { it.file })
        assertEquals(Reason.DEFAULT, engine.decision?.reason)
        assertEquals(CecState.WORKING, engine.cecState)
    }

    @Test
    fun nightBlanksTheScreenAndTurnsTheTvOffOnce() {
        install(pictureA)
        val engine = engine()
        engine.tick()
        clock.now = instant("2026-10-19T22:00")
        engine.tick()
        engine.tick()
        assertEquals(ScreenMode.BLANK, engine.state.value.mode)
        clock.now = instant("2026-10-20T06:00")
        engine.tick()
        assertEquals(listOf(CecCommand.STANDBY, CecCommand.POWER_ON), cec.sent)
    }

    @Test
    fun aPlaylistWithAMissingFileFallsBackToTheIdleCard() {
        install(pictureA)
        store.commit(StoredManifest("v2", manifestOf(pictureA)))
        val engine = engine()
        root.resolve("files/${pictureA.sha}").toFile().delete()
        engine.tick()
        assertEquals(ScreenMode.IDLE, engine.state.value.mode)
    }

    @Test
    fun anOverrideFromTheStoreTakesEffectOnReload() {
        install(pictureA, events = listOf(pictureB))
        val engine = engine()
        engine.tick()
        store.saveOverride(ScreenOverride(OverrideAction.Play("events"), setAtMs = clock.now.toEpochMilli()))
        engine.reloadFromStore()
        assertEquals("events", engine.state.value.playlistId)
        assertEquals(Reason.OVERRIDE, engine.decision?.reason)
    }

    @Test
    fun identifyShowsTheNameForItsSeconds() {
        install(pictureA)
        val engine = engine()
        engine.identify(10)
        assertEquals("Lobby", engine.state.value.identify)
        clock.now = clock.now.plusSeconds(10)
        engine.tick()
        assertNull(engine.state.value.identify)
    }

    @Test
    fun aRefreshBumpsTheRestartToken() {
        val engine = engine()
        engine.restartPlayer()
        assertEquals(1, engine.state.value.restartToken)
    }

    @Test
    fun withoutCecNothingIsSent() {
        install(pictureA)
        val silent = FakeCec(answers = false)
        val engine = PlayerEngine(store, silent, clock::read).also { it.probeCec() }
        clock.now = instant("2026-10-19T23:00")
        engine.tick()
        assertEquals(CecState.UNAVAILABLE, engine.cecState)
        assertEquals(emptyList(), silent.sent)
    }
}
