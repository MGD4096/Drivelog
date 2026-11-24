package dev.drivelog.id6

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Finds the car's Wi-Fi and opens sockets through it.
 *
 * The car's network has no internet, so Android sends ordinary sockets over mobile data instead: every socket here is
 * made by the Wi-Fi network's own socket factory. The head unit is the network's gateway (it runs the access point);
 * when the network has no gateway, its DHCP server, and last the first address of the phone's subnet.
 */
class CarWifi(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var networks: List<Network> = emptyList()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { synchronized(this@CarWifi) { networks = networks + network } }
        override fun onLost(network: Network) { synchronized(this@CarWifi) { networks = networks - network } }
    }
    private var registered = false

    fun start() {
        if (registered) return
        // Any Wi-Fi, with or without internet (a request asks for internet unless that is removed).
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: RuntimeException) {          // SecurityException without ACCESS_NETWORK_STATE, or too many callbacks
            registered = false
        }
    }

    fun stop() {
        if (!registered) return
        try { cm.unregisterNetworkCallback(callback) } catch (_: RuntimeException) {}
        registered = false
        networks = emptyList()
    }

    /** A socket connected to the head unit's [port], or null when no Wi-Fi is up. Throws when nobody answers. */
    fun connect(port: Int): Socket? {
        val current = networks
        if (current.isEmpty()) return null
        var failure: IOException? = null
        for (network in current) {
            val lp = cm.getLinkProperties(network) ?: continue
            for (host in headUnitCandidates(lp)) {
                val s = network.socketFactory.createSocket()
                try {
                    s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    return s
                } catch (e: IOException) {
                    failure = IOException("${host.hostAddress}:$port: ${e.message}")
                    try { s.close() } catch (_: IOException) {}
                }
            }
        }
        throw failure ?: IOException("the Wi-Fi network has no address")
    }

    private fun headUnitCandidates(lp: LinkProperties): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        lp.routes.filter { it.isDefaultRoute }.mapNotNullTo(out) { it.gateway?.takeIf { g -> g is Inet4Address && !g.isAnyLocalAddress } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.dhcpServerAddress?.let { out.add(it) }
        for (la in lp.linkAddresses) {
            val a = la.address as? Inet4Address ?: continue
            val bits = la.prefixLength
            if (bits !in 8..30) continue
            val ip = a.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            val first = (ip and (0xFFFFFFFFL shl (32 - bits) and 0xFFFFFFFFL)) + 1
            if (first != ip) out.add(InetAddress.getByAddress(ByteArray(4) { i -> (first shr (24 - 8 * i)).toByte() }))
        }
        return out.toList()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
    }
}
