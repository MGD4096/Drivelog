package dev.idrivetrips.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.net.URLEncoder

class ProtocolTest {

    private val car = CarState()
    private val protocol = BridgeProtocol(car, "1.0", 1_780_000_000L)

    private fun body(r: HttpResponse) = String(r.body, Charsets.UTF_8)

    @Test
    fun pontePageMatchesWhatThePluginParses() {
        val r = protocol.handle("GET", "/ponte")
        assertEquals(200, r.status)
        val text = body(r)
        assertTrue(text, text.startsWith("IDRIVEPONTE 1.0 "))
        assertTrue(text, text.contains(" avvio=1780000000"))
        for (key in listOf("meteo", "notizie", "locali", "carburanti", "traffico", "auto", "vettura")) {
            assertTrue("$key=0 means nothing to download: $text", text.contains(" $key=0"))
        }
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun startCounterIsNeverZero() {
        assertTrue(body(BridgeProtocol(car, "1.0", 0L).handle("GET", "/ponte")).contains("avvio=1\n"))
        assertTrue(body(BridgeProtocol(car, "1.0", -5L).handle("GET", "/ponte")).contains("avvio=1\n"))
    }

    @Test
    fun acceptsACarValueTheWayThePluginEncodesIt() {
        val json = """{"fuel":{"tanklevel":30,"range":420,"reserve":0}}"""
        // The plugin percent-encodes everything except letters, digits and - _ . ~
        val encoded = json.map { c -> if (c.isLetterOrDigit() && c.code < 128 || c in "-_.~") c.toString() else "%%%02X".format(c.code) }.joinToString("")
        val r = protocol.handle("GET", "/auto?n=sensors.fuel&v=$encoded")
        assertEquals(200, r.status)
        assertEquals("OK\n", body(r))
        assertEquals(30.0, car.snapshot().fuelLiters!!, 0.0)
        assertEquals(420.0, car.snapshot().rangeKm!!, 0.0)
    }

    @Test
    fun aKnownPropertyWithAnUnusableValueStillGets200SoThePluginDoesNotStall() {
        val r = protocol.handle("GET", "/auto?n=sensors.fuel&v=" + URLEncoder.encode("{\"truncated\":\"abc", "UTF-8"))
        assertEquals(200, r.status)
        assertEquals("IGNORED\n", body(r))
    }

    @Test
    fun rejectsRequestsNoPluginWouldSend() {
        assertEquals(400, protocol.handle("GET", "/auto?n=nope&v=%7B%7D").status)
        assertEquals(400, protocol.handle("GET", "/auto?n=sensors.fuel").status)
        assertEquals(400, protocol.handle("GET", "/auto").status)
        assertEquals(400, protocol.handle("GET", "/auto?n=sensors.fuel&v=%ZZ").status)
    }

    @Test
    fun unknownPathsAndMethods() {
        assertEquals(404, protocol.handle("GET", "/meteo.png").status)
        assertEquals(404, protocol.handle("GET", "/").status)
        assertEquals(405, protocol.handle("POST", "/auto?n=sensors.fuel&v=%7B%7D").status)
    }
}

class HttpServerTest {

    private val servers = ArrayList<BridgeHttpServer>()

    @After
    fun stopServers() = servers.forEach { it.stop() }

    private fun startServer(car: CarState, allowed: (java.net.InetAddress) -> Boolean = ::isLocalNetworkAddress): BridgeHttpServer {
        val s = BridgeHttpServer(0, BridgeProtocol(car, "1.0", 1_780_000_000L), allowed)
        s.start()
        servers.add(s)
        return s
    }

    private fun pluginGet(port: Int, path: String) = PluginClient.get(port, path)
    private fun statusOf(raw: ByteArray) = PluginClient.status(raw)
    private fun bodyOf(raw: ByteArray) = PluginClient.body(raw)

    @Test
    fun answersPonteLikeThePluginExpects() {
        val server = startServer(CarState())
        val raw = pluginGet(server.boundPort, "/ponte")
        assertTrue(String(raw, Charsets.ISO_8859_1).startsWith("HTTP/1.0 200 OK\r\n"))
        assertEquals(200, statusOf(raw))
        assertTrue(bodyOf(raw).startsWith("IDRIVEPONTE"))
    }

    @Test
    fun carValuesSentOverTheSocketReachTheCarState() {
        val car = CarState()
        val server = startServer(car)
        val raw = pluginGet(server.boundPort, "/auto?n=driving.odometer&v=%7B%22odometer%22%3A12345%7D")
        assertEquals(200, statusOf(raw))
        assertEquals("OK\n", bodyOf(raw))
        assertEquals(12345.0, car.snapshot().odometerKm!!, 0.0)
    }

    @Test
    fun contentLengthMatchesTheBody() {
        val server = startServer(CarState())
        val text = String(pluginGet(server.boundPort, "/nothing"), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.0 404 Not Found\r\n"))
        assertTrue(text.contains("Content-Length: ${bodyOf(text.toByteArray(Charsets.ISO_8859_1)).length}\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
    }

    @Test
    fun peersOutsideTheLocalNetworkGetNoAnswer() {
        val server = startServer(CarState()) { false }
        assertEquals(0, pluginGet(server.boundPort, "/ponte").size)
    }

    @Test
    fun garbageRequestsGet400AndTheServerKeepsWorking() {
        val server = startServer(CarState())
        Socket("127.0.0.1", server.boundPort).use { sock ->
            sock.soTimeout = 3000
            sock.getOutputStream().write("hello\r\n\r\n".toByteArray())
            val answer = sock.getInputStream().readBytes()
            assertEquals(400, statusOf(answer))
        }
        assertEquals(200, statusOf(pluginGet(server.boundPort, "/ponte")))
    }

    @Test
    fun handlesManyRequestsInARow() {
        val car = CarState()
        val server = startServer(car)
        repeat(50) { i ->
            assertEquals(200, statusOf(pluginGet(server.boundPort, "/auto?n=driving.odometer&v=%7B%22odometer%22%3A${1000 + i}%7D")))
        }
        assertEquals(1049.0, car.snapshot().odometerKm!!, 0.0)
    }

    @Test
    fun stopFreesThePort() {
        val server = startServer(CarState())
        val port = server.boundPort
        server.stop()
        val again = BridgeHttpServer(port, BridgeProtocol(CarState(), "1.0", 1L))
        again.start()
        servers.add(again)
        assertEquals(port, again.boundPort)
    }
}
