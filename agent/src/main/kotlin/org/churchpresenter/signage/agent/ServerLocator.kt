package org.churchpresenter.signage.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import javax.jmdns.JmDNS

/** Where ChurchPresenter might be, best guess first, each as `host:port`. */
fun interface ServerLocator {
    suspend fun candidates(identity: PlayerIdentity): List<String>
}

/**
 * An address given at install wins outright; otherwise the last address that answered, then whatever
 * advertises `_churchpresenter._tcp` on the local network.
 */
class MdnsServerLocator(private val browseMs: Long = DEFAULT_BROWSE_MS) : ServerLocator {
    override suspend fun candidates(identity: PlayerIdentity): List<String> {
        identity.fixedAddress?.let { return listOf(it) }
        return (listOfNotNull(identity.lastAddress) + browse()).distinct()
    }

    private suspend fun browse(): List<String> = withContext(Dispatchers.IO) {
        try {
            JmDNS.create(InetAddress.getLocalHost()).use { mdns ->
                mdns.list(SERVICE_TYPE, browseMs).flatMap { info ->
                    info.inet4Addresses.map { "${it.hostAddress}:${info.port}" }
                }
            }
        } catch (e: IOException) {
            Log.warn("mDNS browse failed", e)
            emptyList()
        }
    }

    companion object {
        const val SERVICE_TYPE = "_churchpresenter._tcp.local."
        const val DEFAULT_BROWSE_MS = 3_000L
    }
}
