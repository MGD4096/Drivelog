package dev.drivelog.id6

import dev.drivelog.carapi.Json

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The phone side of the head-unit bridge protocol (headunit/README.md). The plugin in the car listens on TCP port
 * [DEFAULT_PORT] of the car's Wi-Fi and sends one JSON object per line:
 *
 *  - `{"type":"hello","proto":1,"app":"drivelog-bridge-0.2.0 EntryNav2","t":…,"props":18}` first,
 *  - `{"type":"snapshot","t":…,"values":{"<property>":{…},…}}` the latest value of every property it has,
 *  - `{"type":"cds","t":…,"name":"<property>","value":{…}}` each change, exactly the JSON the car gave,
 *  - `{"type":"ping","t":…}` after 5 s without anything else,
 *  - `{"type":"error","msg":"…"}` for example when too many phones are connected.
 *
 * `t` is the head unit's own clock in ms. Values go to [CdsState] unchanged; properties the app does not know are
 * skipped, so a newer plugin with more properties still works.
 */
class BridgeSession(private val car: CdsState) {

    enum class Kind { HELLO, SNAPSHOT, CDS, PING, ERROR, IGNORED }

    /** The plugin's name and version from its hello, null until then. */
    @Volatile var headUnit: String? = null
        private set

    /** The plugin's error message, if it sent one. */
    @Volatile var error: String? = null
        private set

    /** Values passed on to [CdsState] (stored or ignored as unusable there). */
    @Volatile var values: Int = 0
        private set

    /** Handles one line. Throws [UnsupportedBridgeException] for a plugin of another protocol version. */
    fun handle(line: String): Kind {
        val msg = Json.parseObject(line) ?: return Kind.IGNORED
        return when (msg["type"]) {
            "hello" -> {
                val proto = (msg["proto"] as? Double)?.toInt()
                if (proto != PROTOCOL_VERSION) throw UnsupportedBridgeException("head unit speaks protocol $proto, this app $PROTOCOL_VERSION")
                headUnit = (msg["app"] as? String) ?: "?"
                Kind.HELLO
            }
            "snapshot" -> {
                @Suppress("UNCHECKED_CAST")
                val all = msg["values"] as? Map<String, Any?> ?: return Kind.IGNORED
                for ((name, value) in all) store(name, value)
                Kind.SNAPSHOT
            }
            "cds" -> {
                val name = msg["name"] as? String ?: return Kind.IGNORED
                store(name, msg["value"])
                Kind.CDS
            }
            "ping" -> Kind.PING
            "error" -> {
                error = msg["msg"] as? String ?: "?"
                Kind.ERROR
            }
            else -> Kind.IGNORED
        }
    }

    private fun store(name: String, value: Any?) {
        if (value !is Map<*, *> || name !in CdsProperty.ALL) return
        car.receive(name, Json.stringify(value))
        values++
    }

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}

class UnsupportedBridgeException(message: String) : IOException(message)

/**
 * Keeps a connection to the head-unit plugin while started: connects, reads lines into a [BridgeSession], and when
 * the connection ends or nothing arrives for [silenceMs] (the plugin pings every 5 s) tries again after [retryMs].
 *
 * [connect] returns a connected socket, or null when the car's network is not there right now; it may throw
 * [IOException] (head unit not answering). It runs on the client's own thread.
 */
class BridgeClient(
    private val car: CdsState,
    private val connect: () -> Socket?,
    private val log: (String) -> Unit = {},
    private val retryMs: Long = 3_000,
    private val silenceMs: Int = 15_000,
) {
    enum class State { STOPPED, NO_NETWORK, CONNECTING, CONNECTED }

    @Volatile var state: State = State.STOPPED
        private set

    /** Why the last attempt failed, null after a good connection. */
    @Volatile var lastError: String? = null
        private set

    /** The plugin's name and version while connected. */
    @Volatile var headUnit: String? = null
        private set

    /** Lines handled since start (all connections), for tests and diagnostics. */
    @Volatile var linesRead: Long = 0
        private set

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null
    private val lock = Object()

    @Synchronized
    fun start() {
        if (running) return
        running = true
        state = State.CONNECTING
        thread = Thread({ loop() }, "bridge-client").apply { isDaemon = true; start() }
    }

    /** Stops and waits briefly for the thread; the connection is closed when this returns. */
    @Synchronized
    fun stop() {
        running = false
        closeQuietly(socket)
        synchronized(lock) { lock.notifyAll() }
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) {
            try { t.join(JOIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        state = State.STOPPED
        headUnit = null
    }

    private fun loop() {
        while (running) {
            state = State.CONNECTING
            val s = try {
                connect().also { if (it == null) lastError = null }
            } catch (e: IOException) {
                lastError = e.message ?: e.javaClass.simpleName
                null
            } catch (e: RuntimeException) {
                lastError = e.toString()
                null
            }
            if (s != null) {
                socket = s
                if (running) session(s) else closeQuietly(s)
                socket = null
                headUnit = null
            }
            if (!running) break
            // No car network is not an error; a failed connection keeps its reason in lastError until the next one.
            state = if (s == null && lastError == null) State.NO_NETWORK else State.CONNECTING
            synchronized(lock) { if (running) lock.wait(retryMs) }
        }
        state = State.STOPPED
    }

    private fun session(s: Socket) {
        val session = BridgeSession(car)
        try {
            s.use {
                s.soTimeout = silenceMs
                val input = java.io.BufferedInputStream(s.getInputStream())
                while (running) {
                    val line = readLine(input) ?: throw IOException("closed by the head unit")
                    val kind = session.handle(line)
                    if (kind == BridgeSession.Kind.HELLO) {
                        headUnit = session.headUnit
                        lastError = null
                        state = State.CONNECTED
                        log("connected to ${session.headUnit} at ${s.inetAddress?.hostAddress}")
                    }
                    linesRead++
                    session.error?.let { throw IOException(it) }
                }
            }
        } catch (e: SocketTimeoutException) {
            if (running) { val why = "nothing from the head unit for ${silenceMs / 1000} s"; lastError = why; log(why) }
        } catch (e: IOException) {
            if (running) { lastError = e.message ?: e.javaClass.simpleName; log("connection ended: $lastError") }
        }
    }

    private fun closeQuietly(s: Socket?) { try { s?.close() } catch (_: IOException) {} }

    companion object {
        const val DEFAULT_PORT = 7777
        private const val JOIN_MS = 500L
        /** A snapshot of 13 values is a few kB; anything far bigger is not from the plugin. */
        private const val MAX_LINE = 256 * 1024

        /** One line as UTF-8 without the '\n'; null at the end of the stream. Throws when a line is too long. */
        fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
                if (buf.size() >= MAX_LINE) throw IOException("line longer than $MAX_LINE bytes")
                buf.write(b)
            }
        }
    }
}
