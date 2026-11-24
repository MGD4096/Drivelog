package dev.idrivetrips.bmwid6

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class BridgeSessionTest {
    private val car = CdsState()
    private val session = BridgeSession(car)

    @Test
    fun helloSnapshotAndChanges() {
        assertEquals(BridgeSession.Kind.HELLO, session.handle("""{"type":"hello","proto":1,"app":"drivelog-bridge-0.1.0 NBT Evo","t":5,"props":13}"""))
        assertEquals("drivelog-bridge-0.1.0 NBT Evo", session.headUnit)
        assertEquals(BridgeSession.Kind.SNAPSHOT, session.handle(
            """{"type":"snapshot","t":6,"values":{"driving.odometer":{"odometer":12345},"sensors.fuel":{"fuel":{"tanklevel":31,"range":420}}}}"""))
        assertEquals(12345.0, car.values().odometerKm!!, 0.0)
        assertEquals(31.0, car.values().fuelLiters!!, 0.0)
        assertEquals(BridgeSession.Kind.CDS, session.handle("""{"type":"cds","t":7,"name":"driving.odometer","value":{"odometer":12346}}"""))
        assertEquals(12346.0, car.values().odometerKm!!, 0.0)
        assertEquals(BridgeSession.Kind.PING, session.handle("""{"type":"ping","t":8}"""))
        assertEquals(3, session.values)
    }

    @Test
    fun whatTheAppDoesNotKnowIsSkipped() {
        // a newer plugin may send more properties; garbage lines are not fatal
        assertEquals(BridgeSession.Kind.CDS, session.handle("""{"type":"cds","t":1,"name":"engine.RPMSpeed","value":{"RPMSpeed":800}}"""))
        assertEquals(BridgeSession.Kind.CDS, session.handle("""{"type":"cds","t":1,"name":"sensors.fuel","value":42}"""))
        assertEquals(BridgeSession.Kind.IGNORED, session.handle("not json"))
        assertEquals(BridgeSession.Kind.IGNORED, session.handle("""{"type":"future"}"""))
        assertEquals(BridgeSession.Kind.IGNORED, session.handle("""{"type":"snapshot","values":3}"""))
        assertEquals(0, session.values)
        assertEquals(0L, car.lastUpdateMillis)
    }

    @Test(expected = UnsupportedBridgeException::class)
    fun anotherProtocolVersionIsRefused() {
        session.handle("""{"type":"hello","proto":2,"app":"x","t":1}""")
    }

    @Test
    fun errorMessage() {
        assertEquals(BridgeSession.Kind.ERROR, session.handle("""{"type":"error","msg":"too many phones"}"""))
        assertEquals("too many phones", session.error)
    }

    @Test
    fun polishTextsSurviveUtf8() {
        val bytes = "{\"type\":\"cds\",\"name\":\"x\",\"value\":{\"a\":\"Łódź\"}}\r\nnext\n".toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(bytes)
        assertEquals("{\"type\":\"cds\",\"name\":\"x\",\"value\":{\"a\":\"Łódź\"}}", BridgeClient.readLine(input))
        assertEquals("next", BridgeClient.readLine(input))
        assertNull(BridgeClient.readLine(input))
    }
}

class BridgeClientTest {
    private val car = CdsState()
    private val head = FakeHeadUnit()
    private var client: BridgeClient? = null

    @After
    fun tearDown() {
        client?.stop()
        head.close()
    }

    private fun start(connect: () -> java.net.Socket? = head.connector(), silenceMs: Int = 15_000): BridgeClient =
        BridgeClient(car, connect, retryMs = 50, silenceMs = silenceMs).also { client = it; it.start() }

    @Test
    fun receivesSnapshotAndChangesOverARealSocket() {
        val c = start()
        head.acceptPhone(mapOf("driving.odometer" to """{"odometer":12345}"""))
        waitFor("connected") { c.state == BridgeClient.State.CONNECTED }
        assertEquals("drivelog-bridge-0.1.0 EntryNav2", c.headUnit)
        waitFor("snapshot") { car.values().odometerKm == 12345.0 }
        head.cds("sensors.fuel", """{"fuel":{"tanklevel":31,"range":420}}""")
        head.cds("sensors.doors", """{"doors":{"driver":1,"passenger":0,"driverRear":0,"passengerRear":0}}""")
        waitFor("doors") { car.values().openParts == listOf("driver door") }
        assertEquals(31.0, car.values().fuelLiters!!, 0.0)
        assertNull(c.lastError)
    }

    @Test
    fun reconnectsWhenTheHeadUnitDropsTheConnection() {
        val c = start()
        head.acceptPhone()
        waitFor("connected") { c.state == BridgeClient.State.CONNECTED }
        head.dropPhone()
        waitFor("noticed") { c.state != BridgeClient.State.CONNECTED }
        head.acceptPhone(mapOf("sensors.lid" to """{"lid":1}"""))     // the HMI restarted: a new connection
        waitFor("connected again") { c.state == BridgeClient.State.CONNECTED }
        waitFor("value after reconnect") { car.values().openParts?.isNotEmpty() == true }
    }

    @Test
    fun silenceEndsTheConnection() {
        val c = start(silenceMs = 300)
        head.acceptPhone()
        waitFor("connected") { c.state == BridgeClient.State.CONNECTED }
        head.ping()
        waitFor("timed out") { c.lastError?.contains("nothing from the head unit") == true }
        head.acceptPhone()                       // and connects again by itself
        waitFor("connected again") { c.state == BridgeClient.State.CONNECTED }
    }

    @Test
    fun noNetworkIsNotAnError() {
        val c = start(connect = { null })
        waitFor("no network") { c.state == BridgeClient.State.NO_NETWORK }
        assertNull(c.lastError)
    }

    @Test
    fun aHeadUnitThatDoesNotAnswerIsReported() {
        val c = start(connect = { throw IOException("172.16.224.1:7777: Connection refused") })
        waitFor("error") { c.lastError != null }
        assertEquals("172.16.224.1:7777: Connection refused", c.lastError)
        assertFalse(c.state == BridgeClient.State.CONNECTED)
    }

    @Test
    fun tooManyPhones() {
        val c = start()
        head.acceptPhone()
        head.send("""{"type":"error","msg":"too many phones"}""")
        waitFor("error") { c.lastError == "too many phones" }
    }

    @Test
    fun stopClosesTheConnection() {
        val c = start()
        head.acceptPhone()
        waitFor("connected") { c.state == BridgeClient.State.CONNECTED }
        c.stop()
        assertEquals(BridgeClient.State.STOPPED, c.state)
        assertNull(c.headUnit)
    }
}

/** The plugin and the app must agree on the list of car properties. */
class PluginSourceTest {
    private fun headunitSource(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val f = File(dir, "headunit/src/bridge_core.hpp")
            if (f.isFile) return f
            dir = dir?.parentFile
        }
        return null
    }

    @Test
    fun appAcceptsExactlyThePropertiesThePluginSends() {
        val src = headunitSource()
        assumeTrue("headunit/src/bridge_core.hpp not found", src != null)
        val text = src!!.readText()
        val start = text.indexOf("kProperties[] = {")
        assertTrue("kProperties list not found", start >= 0)
        val sent = Regex("\"([A-Za-z.]+)\"").findAll(text.substring(start, text.indexOf("};", start))).map { it.groupValues[1] }.toList()
        assertEquals(13, sent.size)
        assertEquals(CdsProperty.ALL, sent.toSet())
    }

    @Test
    fun samePort() {
        val src = headunitSource()
        assumeTrue("headunit/src/bridge_core.hpp not found", src != null)
        assertNotNull(Regex("kDefaultPort = ${BridgeClient.DEFAULT_PORT};").find(src!!.readText()))
    }
}
