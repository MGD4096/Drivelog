package dev.drivelog.id6

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The other way round: the head unit calls the phone.
 *
 * With wireless Android Auto the phone's connection to the car's Wi-Fi belongs to Android Auto alone (Android gives
 * a network that an app asked for by name only to that app), so this app cannot open a connection to the head unit.
 * A connection that comes in to a port this app listens on still reaches it. While nobody is connected, the head-unit
 * plugin calls the phones on its Wi-Fi on [PHONE_PORT] (headunit/src/drivelog_bridge.cpp, "calling the phones"); the
 * call then carries the same protocol lines as a connection the phone opened.
 *
 * Only calls from a local network address (the head unit's Wi-Fi) are taken. Port 0 picks a free port (tests).
 */
class HeadUnitCalls(
    port: Int = PHONE_PORT,
    private val allowed: (InetAddress) -> Boolean = ::isLocalPeer,
) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }

    val boundPort: Int get() = server.localPort

    /** Calls taken so far, and who made the latest (for the plugin's screen). */
    @Volatile var calls: Int = 0
        private set
    @Volatile var lastCaller: String? = null
        private set

    /** The next call, or null when none came within [timeoutMs]. Throws once closed. */
    fun accept(timeoutMs: Int): Socket? {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = (until - System.currentTimeMillis()).toInt()
            if (left <= 0) return null
            server.soTimeout = left
            val s = try { server.accept() } catch (e: SocketTimeoutException) { return null }
            if (allowed(s.inetAddress)) {
                calls++
                lastCaller = s.inetAddress?.hostAddress
                return s
            }
            try { s.close() } catch (_: IOException) {}
        }
    }

    override fun close() {
        try { server.close() } catch (_: IOException) {}
    }

    companion object {
        /** Must match bridge::kDefaultPhonePort in headunit/src/bridge_core.hpp (PluginSourceTest checks it). */
        const val PHONE_PORT = 7778

        /** Private, link-local or loopback: never a caller from the internet. */
        fun isLocalPeer(a: InetAddress): Boolean = a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress
    }
}
