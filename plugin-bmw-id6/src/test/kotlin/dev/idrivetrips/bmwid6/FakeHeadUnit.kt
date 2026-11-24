package dev.idrivetrips.bmwid6

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Stands in for the head-unit plugin (headunit/src/drivelog_bridge.cpp) in tests: listens on loopback and writes the
 * protocol lines byte for byte as the plugin does (the plugin's own host test checks the same lines on its side).
 */
class FakeHeadUnit(private val app: String = "drivelog-bridge-0.1.0 EntryNav2") : AutoCloseable {
    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
    private val accepted = LinkedBlockingQueue<Socket>()
    private var current: Socket? = null
    private var t = 1000L

    val port: Int get() = server.localPort

    init {
        Thread({
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                accepted.add(s)
            }
        }, "fake-head-unit").apply { isDaemon = true; start() }
    }

    fun connector(): () -> Socket {
        val p = port            // not inside Socket().apply, where "port" would be the socket's own (0)
        return { Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), p), 2000) } }
    }

    /** Waits for the phone; then sends hello and a snapshot of [values] (property -> JSON). */
    fun acceptPhone(values: Map<String, String> = emptyMap(), timeoutMs: Long = 5000): FakeHeadUnit {
        current?.close()
        current = accepted.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw AssertionError("the phone did not connect")
        send("""{"type":"hello","proto":1,"app":"$app","t":${t++},"props":13}""")
        send("""{"type":"snapshot","t":${t++},"values":{${values.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}""")
        return this
    }

    fun cds(name: String, json: String) = send("""{"type":"cds","t":${t++},"name":"$name","value":$json}""")
    fun ping() = send("""{"type":"ping","t":${t++}}""")

    fun send(line: String) {
        val out: OutputStream = (current ?: throw AssertionError("no phone connected")).getOutputStream()
        out.write((line + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    fun dropPhone() { current?.close(); current = null }

    override fun close() {
        current?.close()
        server.close()
        while (true) (accepted.poll() ?: break).close()
    }
}

/** Polls [cond] until it holds or [timeoutMs] passes. */
fun waitFor(what: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
    val until = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
        Thread.sleep(10)
    }
}
