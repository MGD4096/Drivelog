package dev.idrivetrips.core

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/** Talks to the server the way the head-unit plugin does: `GET <path> HTTP/1.0`, read until the connection closes. */
object PluginClient {

    fun get(port: Int, path: String): ByteArray {
        Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", port), 2000)
            sock.soTimeout = 3000
            sock.getOutputStream().write("GET $path HTTP/1.0\r\nHost: telefono\r\nUser-Agent: iDrive-Meteo/1\r\n\r\n".toByteArray())
            sock.getOutputStream().flush()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                // A refused peer may be closed with a reset instead of a clean end of stream
                val n = try { sock.getInputStream().read(buf) } catch (e: SocketException) { -1 }
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    /** The plugin reads the status from bytes 9..11. */
    fun status(raw: ByteArray): Int = String(raw, 9, 3, Charsets.ISO_8859_1).toInt()

    /** ...and the body after the first blank line. */
    fun body(raw: ByteArray): String {
        val text = String(raw, Charsets.ISO_8859_1)
        return text.substring(text.indexOf("\r\n\r\n") + 4)
    }

    /** The plugin percent-encodes everything except letters, digits and - _ . ~ */
    fun encode(json: String): String =
        json.map { c -> if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in "-_.~") c.toString() else "%%%02X".format(c.code) }
            .joinToString("")

    fun push(port: Int, property: String, json: String): Int = status(get(port, "/auto?n=$property&v=${encode(json)}"))
}
