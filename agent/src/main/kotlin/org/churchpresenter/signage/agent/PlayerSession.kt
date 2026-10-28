package org.churchpresenter.signage.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.churchpresenter.signage.core.Features
import org.churchpresenter.signage.core.PROTOCOL_VERSION
import org.churchpresenter.signage.core.PlayerMessage
import org.churchpresenter.signage.core.Platform
import org.churchpresenter.signage.core.ServerMessage
import org.churchpresenter.signage.core.UpdateState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** What this agent tells ChurchPresenter about itself in its hello. */
data class AgentInfo(val version: String, val platform: Platform, val screenWidth: Int, val screenHeight: Int)

/** What the connection does after a message is handled. */
enum class SessionNext { STAY, RECONNECT, WAIT_LONG }

/**
 * What a player does with each message from ChurchPresenter, apart from the socket itself, so it can
 * be tested without one.
 */
class PlayerSession(
    private val store: PlayerStore,
    private val engine: PlayerEngine,
    private val agent: AgentInfo,
    private val sync: suspend () -> SyncResult,
    random: Random = Random.Default,
    private val previews: PreviewMaker? = null,
    private val updater: Updater? = null,
    private val app: AppFolder? = null,
    /** Where a long job, an update, runs, so the connection keeps answering meanwhile. */
    private val background: CoroutineScope? = null,
    /** Whether this player draws web pages: it found a Chromium. */
    private val web: Boolean = false,
    /** Measures how smoothly it shows one, when it can draw web pages. */
    private val webTest: WebTest? = null,
    /** The machine's health, sent with each status. */
    private val health: HealthReader = HealthReader(),
    /** Whether this player shows interactive pages: it found a Chromium and cage. */
    private val interactive: Boolean = false,
    /** Restarts and shuts down the machine; null where the player cannot. */
    private val power: Power? = null,
    /** Turns the TV on (true) or to standby now; null where the player has no CEC. */
    private val tv: ((Boolean) -> Boolean)? = null,
) {
    private val testing = AtomicBoolean(false)

    /** Messages for ChurchPresenter that are not answers to its own: previews and update progress. */
    val outgoing = Channel<PlayerMessage>(OUTGOING_CAPACITY, BufferOverflow.DROP_OLDEST)

    /** What this player can do beyond the basics, as its hello lists it. */
    val features: List<String> = listOfNotNull(
        Features.WIPE,
        Features.STREAM,
        Features.ROTATION,
        Features.WEB.takeIf { web },
        Features.INTERACTIVE.takeIf { interactive },
        Features.POWER.takeIf { power != null && background != null },
        Features.TV.takeIf { tv != null && background != null },
        Features.SYSTEM_UPDATE.takeIf { updater?.updatesSystem == true && background != null },
        Features.WEB_TEST.takeIf { webTest != null && background != null },
        Features.PREVIEW.takeIf { previews != null },
        Features.UPDATE.takeIf { updater != null && app?.canUpdate() == true },
    )

    /** Shown on the TV while unpaired; the same for the whole run so the operator can match it. */
    val pairingCode: String = random.nextInt(PAIRING_CODE_RANGE).toString().padStart(PAIRING_CODE_DIGITS, '0')

    fun hello(): PlayerMessage.Hello {
        val identity = store.identity()
        return PlayerMessage.Hello(
            playerId = identity.playerId,
            agentVersion = agent.version,
            protocolMin = PROTOCOL_VERSION,
            protocolMax = PROTOCOL_VERSION,
            manifestVersion = store.manifest()?.version,
            screenWidth = agent.screenWidth,
            screenHeight = agent.screenHeight,
            platform = agent.platform,
            pairingCode = if (identity.isPaired) null else pairingCode,
            features = features,
        )
    }

    /** Called once the socket is open and the hello sent. */
    suspend fun onConnected() {
        if (store.identity().isPaired) {
            engine.setMessage(null)
            app?.markHealthy()
            app?.takeRolledBack()?.let { version ->
                val why = "it did not start, so ${agent.version} is back"
                outgoing.trySend(PlayerMessage.UpdateProgress(version, UpdateState.ROLLED_BACK, why))
            }
            syncAndReload()
        }
    }

    suspend fun handle(message: ServerMessage): SessionNext = when (message) {
        is ServerMessage.Unsupported -> {
            Log.warn("ChurchPresenter speaks protocol ${message.serverProtocol}; this agent speaks $PROTOCOL_VERSION")
            engine.setMessage(UNSUPPORTED_MESSAGE)
            SessionNext.WAIT_LONG
        }
        is ServerMessage.PairingPending -> {
            engine.setMessage(pairingMessage())
            SessionNext.STAY
        }
        is ServerMessage.Paired -> {
            store.saveIdentity(
                store.identity().copy(
                    token = message.token,
                    ownerId = message.ownerId,
                    ownerName = message.ownerName,
                    ownerProtocol = PROTOCOL_VERSION,
                ),
            )
            engine.setMessage(null)
            SessionNext.RECONNECT
        }
        ServerMessage.PairingRejected -> {
            engine.setMessage(REJECTED_MESSAGE)
            SessionNext.WAIT_LONG
        }
        ServerMessage.Released -> {
            release()
            SessionNext.RECONNECT
        }
        is ServerMessage.ManifestChanged -> {
            if (store.manifest()?.version != message.version) syncAndReload()
            SessionNext.STAY
        }
        is ServerMessage.SetOverride -> {
            store.saveOverride(message.override)
            engine.reloadFromStore()
            SessionNext.STAY
        }
        is ServerMessage.Identify -> {
            engine.identify(message.seconds)
            SessionNext.STAY
        }
        ServerMessage.Refresh -> {
            engine.restartPlayer()
            SessionNext.STAY
        }
        ServerMessage.Wipe -> {
            Log.info("Wiping this screen's content at ChurchPresenter's request")
            store.clearContent()
            engine.reloadFromStore()
            syncAndReload()
            SessionNext.STAY
        }
        is ServerMessage.Power, is ServerMessage.Tv, ServerMessage.UpdateSystem -> {
            machine(message)
            SessionNext.STAY
        }
        is ServerMessage.PreviewRequest -> {
            previews?.make(message.maxWidth)?.let { outgoing.trySend(it) }
            SessionNext.STAY
        }
        is ServerMessage.WebTest -> {
            val test = webTest
            // One at a time: a second request while one runs is answered by the first.
            if (test != null && background != null && testing.compareAndSet(false, true)) {
                Log.info("Testing web pages at ${message.fps.joinToString("/")} frames a second")
                background.launch {
                    try {
                        outgoing.trySend(test.run(message))
                    } finally {
                        testing.set(false)
                    }
                }
            }
            SessionNext.STAY
        }
        is ServerMessage.UpdateAvailable -> {
            val run = updater
            if (run == null || background == null) {
                Log.info("Agent ${message.agentVersion} is available; this player does not update itself")
            } else {
                background.launch { run.install(message) }
            }
            SessionNext.STAY
        }
    }

    /** What ChurchPresenter asks of the machine itself: power, the TV, a system update. */
    private fun machine(message: ServerMessage) {
        val scope = background ?: return
        when (message) {
            is ServerMessage.Power -> power?.let { run ->
                Log.info("ChurchPresenter asked this screen to ${message.action.name.lowercase().replace('_', ' ')}")
                // Off the connection, a moment later, so the log line and the socket close cleanly.
                scope.launch {
                    delay(POWER_DELAY_MS)
                    run.run(message.action)
                }
            }
            is ServerMessage.Tv -> tv?.let { send ->
                Log.info("ChurchPresenter turned the TV ${if (message.on) "on" else "off"}")
                scope.launch { send(message.on) }
            }
            ServerMessage.UpdateSystem -> updater?.let { run -> scope.launch { run.updateSystem() } }
            else -> Unit
        }
    }

    /** The server refused the token: the screen was revoked while this player was away. */
    fun onUnauthorized() = release()

    /** What the player reports about itself. */
    fun status(): PlayerMessage.Status {
        val state = engine.state.value
        val now = health.read()
        return PlayerMessage.Status(
            showingPlaylistId = state.playlistId,
            reason = engine.decision?.reason?.name?.lowercase(),
            lastSyncMs = lastSyncMs,
            freeDiskBytes = store.freeBytes(),
            cec = engine.cecState,
            errors = listOfNotNull(lastError),
            cpuPercent = now.cpuPercent,
            memoryPercent = now.memoryPercent,
            temperatureC = now.temperatureC,
            uptimeSeconds = now.uptimeSeconds,
            interactive = state.kioskWanted,
        )
    }

    fun pairingMessage(): String = "Waiting for approval in ChurchPresenter — code $pairingCode"

    private var lastSyncMs: Long? = null
    private var lastError: String? = null

    private suspend fun syncAndReload() {
        when (val result = sync()) {
            is SyncResult.Updated -> {
                Log.info("Synced manifest ${result.version.take(SHORT_HASH)} (${result.downloaded} new files)")
                lastSyncMs = System.currentTimeMillis()
                lastError = null
                engine.reloadFromStore()
            }
            SyncResult.UpToDate -> {
                lastSyncMs = System.currentTimeMillis()
                lastError = null
            }
            is SyncResult.Failed -> {
                Log.warn("Sync failed: ${result.reason}")
                lastError = result.reason
            }
        }
    }

    private fun release() {
        Log.info("Released by ${store.identity().ownerName}; waiting to be paired again")
        store.saveIdentity(store.identity().released())
        store.clearContent()
        engine.reloadFromStore()
        engine.setMessage(pairingMessage())
    }

    private companion object {
        const val OUTGOING_CAPACITY = 16
        const val POWER_DELAY_MS = 1_000L
        const val PAIRING_CODE_RANGE = 10_000
        const val PAIRING_CODE_DIGITS = 4
        const val SHORT_HASH = 8
        const val UNSUPPORTED_MESSAGE =
            "This screen needs a newer ChurchPresenter, or ChurchPresenter needs to update it"
        const val REJECTED_MESSAGE = "Pairing was declined in ChurchPresenter"
    }
}
