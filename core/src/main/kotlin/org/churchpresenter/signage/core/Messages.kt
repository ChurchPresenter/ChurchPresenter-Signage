package org.churchpresenter.signage.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a player sends over `/api/signage/ws`. The first message on every connection is [Hello].
 * A paired player puts its token in the `Authorization: Bearer` header of the WebSocket request; an
 * unpaired one connects without it and puts a [Hello.pairingCode] in its hello.
 */
@Serializable
sealed interface PlayerMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(
        /** Random, made once at install and kept for the life of the player. */
        val playerId: String,
        val agentVersion: String,
        val protocolMin: Int,
        val protocolMax: Int,
        /** The version of the manifest it is playing, or null when it has none yet. */
        val manifestVersion: String? = null,
        val screenWidth: Int,
        val screenHeight: Int,
        val platform: Platform,
        /** Shown on the TV and in the Signage tab while an unpaired player waits for approval. */
        val pairingCode: String? = null,
    ) : PlayerMessage

    @Serializable
    @SerialName("status")
    data class Status(
        val showingPlaylistId: String? = null,
        val showingItemSha256: String? = null,
        val reason: String? = null,
        val lastSyncMs: Long? = null,
        val freeDiskBytes: Long? = null,
        val cec: CecState = CecState.UNKNOWN,
        val errors: List<String> = emptyList(),
    ) : PlayerMessage
}

/** What ChurchPresenter sends a player. */
@Serializable
sealed interface ServerMessage {
    /** The player's protocol range does not include ChurchPresenter's; the connection closes. */
    @Serializable
    @SerialName("unsupported")
    data class Unsupported(val serverProtocol: Int) : ServerMessage

    /** An unpaired player is waiting for someone to approve [code] in the Signage tab. */
    @Serializable
    @SerialName("pairing_pending")
    data class PairingPending(val code: String) : ServerMessage

    /** Approved. The player stores [token] and [ownerId] and reconnects with the token. */
    @Serializable
    @SerialName("paired")
    data class Paired(val token: String, val ownerId: String, val ownerName: String) : ServerMessage

    @Serializable
    @SerialName("pairing_rejected")
    data object PairingRejected : ServerMessage

    /** Revoked or released: the player forgets its owner and goes back to waiting for pairing. */
    @Serializable
    @SerialName("released")
    data object Released : ServerMessage

    /** A newer manifest is waiting at `GET /api/signage/manifest`. */
    @Serializable
    @SerialName("manifest_changed")
    data class ManifestChanged(val version: String) : ServerMessage

    /** A manual switch; null releases the current one. */
    @Serializable
    @SerialName("override")
    data class SetOverride(val override: ScreenOverride?) : ServerMessage

    /** Shows the screen's name full screen for a few seconds, to tell screens apart. */
    @Serializable
    @SerialName("identify")
    data class Identify(val seconds: Int = DEFAULT_IDENTIFY_SECONDS) : ServerMessage {
        companion object {
            const val DEFAULT_IDENTIFY_SECONDS = 10
        }
    }

    /** Restarts what draws the screen. */
    @Serializable
    @SerialName("refresh")
    data object Refresh : ServerMessage

    /** ChurchPresenter has an agent build for this platform; the player verifies it before use. */
    @Serializable
    @SerialName("update_available")
    data class UpdateAvailable(
        val agentVersion: String,
        val protocolMin: Int,
        val protocolMax: Int,
        val sha256: String,
        val path: String,
    ) : ServerMessage
}

@Serializable
enum class Platform {
    @SerialName("linux_arm64") LINUX_ARM64,

    @SerialName("linux_x64") LINUX_X64,

    @SerialName("windows_x64") WINDOWS_X64,
}

@Serializable
enum class CecState {
    @SerialName("unknown") UNKNOWN,

    @SerialName("working") WORKING,

    @SerialName("unavailable") UNAVAILABLE,
}

/** Whether a player speaking [min]..[max] can talk to this build. */
fun supportsProtocol(min: Int, max: Int, protocol: Int = PROTOCOL_VERSION): Boolean = protocol in min..max
