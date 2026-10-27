package org.churchpresenter.signage.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import javax.jmdns.JmDNS

/** Where ChurchPresenter might be, each as `host:port`. */
fun interface ServerLocator {
    /**
     * The addresses known without looking, tried first: the one given at install, then the last that
     * answered. The given one is a first choice, not the only one, so a ChurchPresenter that moved
     * is still found by [browse] when it advertises on this network.
     */
    fun known(identity: PlayerIdentity): List<String> =
        listOfNotNull(identity.fixedAddress, identity.lastAddress).distinct()

    /** Whatever advertises ChurchPresenter on the networks this machine is on now. */
    suspend fun browse(): List<String>
}

/**
 * Finds ChurchPresenter by its `_churchpresenter._tcp` advert, listening on every network this
 * machine is on at once, so a screen with both a cable and Wi-Fi finds it on either.
 */
class MdnsServerLocator(
    private val browseMs: Long = DEFAULT_BROWSE_MS,
    private val interfaces: () -> List<InetAddress> = ::lanAddresses,
) : ServerLocator {
    override suspend fun browse(): List<String> = coroutineScope {
        interfaces().map { address -> async(Dispatchers.IO) { browseOn(address) } }.awaitAll().flatten().distinct()
    }

    private fun browseOn(address: InetAddress): List<String> = try {
        JmDNS.create(address).use { mdns ->
            mdns.list(SERVICE_TYPE, browseMs).flatMap { info ->
                info.inet4Addresses.map { "${it.hostAddress}:${info.port}" }
            }
        }
    } catch (e: IOException) {
        Log.warn("mDNS browse on ${address.hostAddress} failed", e)
        emptyList()
    }

    companion object {
        const val SERVICE_TYPE = "_churchpresenter._tcp.local."
        const val DEFAULT_BROWSE_MS = 3_000L

        /** This machine's IPv4 addresses on networks that are up and carry multicast, loopback left out. */
        fun lanAddresses(): List<InetAddress> = try {
            NetworkInterface.networkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && !it.isPointToPoint }
                .flatMap { it.inetAddresses().toList() }
                .filterIsInstance<Inet4Address>()
        } catch (e: SocketException) {
            Log.warn("Could not list this machine's networks", e)
            emptyList()
        }
    }
}
