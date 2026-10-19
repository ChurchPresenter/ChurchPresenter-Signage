package org.churchpresenter.signage.agent

import kotlinx.serialization.Serializable

/**
 * Who this player is and who owns it. Made at first start; [token] and the owner fields are filled
 * by pairing and cleared by a release.
 */
@Serializable
data class PlayerIdentity(
    val playerId: String,
    val token: String? = null,
    val ownerId: String? = null,
    val ownerName: String? = null,
    /** The protocol version of the ChurchPresenter it last paired with; self-update stays within it. */
    val ownerProtocol: Int? = null,
    /** `host:port` given at install, used instead of mDNS when set. */
    val fixedAddress: String? = null,
    /** The last address ChurchPresenter answered on, tried first on the next start. */
    val lastAddress: String? = null,
) {
    val isPaired: Boolean get() = token != null && ownerId != null

    fun released(): PlayerIdentity = copy(token = null, ownerId = null, ownerName = null, ownerProtocol = null)
}
