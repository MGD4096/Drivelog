package dev.idrivetrips.core

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * A minimal HTTP/1.0 server. The head-unit plugin sends `GET <path> HTTP/1.0` and reads until the connection closes,
 * so every response carries `Connection: close`. Only private-network and loopback peers are served, never the internet.
 */
class BridgeHttpServer(
    private val port: Int,
    private val protocol: BridgeProtocol,
    private val peerAllowed: (InetAddress) -> Boolean = ::isLocalNetworkAddress,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var acceptThread: Thread? = null

    /** The port actually bound (useful when constructed with port 0 in tests). */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port), BACKLOG)
        } catch (e: IOException) {
            socket.close()      // e.g. the port is taken: do not leak the unbound socket
            throw e
        }
        serverSocket = socket
        val pool = Executors.newFixedThreadPool(WORKERS, daemonThreads("bridge-worker"))
        executor = pool
        acceptThread = Thread({ acceptLoop(socket, pool) }, "bridge-accept").apply { isDaemon = true; start() }
        log("HTTP server listening on port ${socket.localPort}")
    }

    /**
     * Stops the server. When this returns the port is free again: closing a listening socket does not release the
     * port until the thread blocked in accept() has left it, so that thread is waited for (briefly).
     */
    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        val accepting = acceptThread
        acceptThread = null
        if (accepting != null && accepting !== Thread.currentThread()) {
            try { accepting.join(ACCEPT_JOIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (e: IOException) { break }
            try {
                pool.execute { serve(client) }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                closeQuietly(client)
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                if (!peerAllowed(client.inetAddress)) return
                client.soTimeout = READ_TIMEOUT_MS
                val input = client.getInputStream()
                val requestLine = readLine(input) ?: return
                val response = answer(requestLine)
                // Read the headers so the client is not reset while still sending; their content is not needed.
                var lines = 0
                while (lines++ < MAX_HEADER_LINES) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                }
                val head = "HTTP/1.0 ${response.status} ${reason(response.status)}\r\n" +
                    "Content-Type: ${response.contentType}\r\n" +
                    "Content-Length: ${response.body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                val out = client.getOutputStream()
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.write(response.body)
                out.flush()
            }
        } catch (e: IOException) {
            log("request failed: ${e.message}")
        } catch (e: RuntimeException) {
            log("request error: $e")
        }
    }

    private fun answer(requestLine: String): HttpResponse {
        val parts = requestLine.split(' ')
        if (parts.size < 2 || !parts[1].startsWith("/")) return HttpResponse.text(400, "BAD REQUEST\n")
        val response = protocol.handle(parts[0], parts[1])
        log("${parts[0]} ${parts[1]} -> ${response.status}")
        return response
    }

    /** One line, up to MAX_LINE bytes; null when the stream ends first or the line is too long. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= MAX_LINE) return null
            sb.append(b.toChar())
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
    }

    private fun closeQuietly(s: Socket) { try { s.close() } catch (_: IOException) {} }

    private fun daemonThreads(prefix: String): ThreadFactory {
        var n = 0
        return ThreadFactory { r -> Thread(r, "$prefix-${++n}").apply { isDaemon = true } }
    }

    companion object {
        private const val ACCEPT_JOIN_MS = 2000L
        private const val BACKLOG = 16
        private const val WORKERS = 4
        private const val READ_TIMEOUT_MS = 3000
        private const val MAX_LINE = 4096
        private const val MAX_HEADER_LINES = 64
    }
}

/** Loopback, private (10/8, 172.16/12, 192.168/16) and link-local addresses: never the public internet. */
fun isLocalNetworkAddress(a: InetAddress): Boolean = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress
