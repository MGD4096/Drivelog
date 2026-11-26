package dev.drivelog.id6

import dev.drivelog.pluginapi.CarPluginContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket

class HeadUnitRoutesTest {
    private class FakeRoute(val name: String, var result: () -> Socket?) : HeadUnitRoute {
        var tries = 0
        var closed = false
        override fun connect(): Socket? { tries++; return result() }
        override fun describe() = name
        override fun close() { closed = true }
    }

    @Test
    fun triesTheRoutesInOrderAndAFailureDoesNotStopTheNext() {
        val socket = Socket()
        val wifi = FakeRoute("wifi") { throw IOException("10.0.0.1:7777: refused") }
        val calls = FakeRoute("calls") { socket }
        val routes = HeadUnitRoutes(listOf(wifi, calls))
        assertSame(socket, routes.connect())
        assertEquals("wifi (10.0.0.1:7777: refused)\ncalls", routes.describe())

        wifi.result = { socket }                                   // the next time the first route works
        assertSame(socket, routes.connect())
        assertEquals(1, calls.tries)                               // ... so the second is not tried
        assertEquals("wifi\ncalls", routes.describe())             // and its error is forgotten

        wifi.result = { null }
        calls.result = { null }
        assertNull(routes.connect())
        routes.close()
        assertTrue(wifi.closed && calls.closed)
        socket.close()
    }

    @Test
    fun aCallsRouteWithoutItsPortSaysWhy() {
        val route = CallsRoute(null, "Address already in use", 10)
        assertNull(route.connect())
        assertEquals("not listening for the head unit (Address already in use)", route.describe())
    }

    @Test
    fun statusForEveryClientState() {
        assertEquals(CarPluginContract.STATE_CONNECTED to "DrivelogBridge 0.1", PluginStatus.of(BridgeClient.State.CONNECTED, "DrivelogBridge 0.1", null))
        assertEquals(CarPluginContract.STATE_NO_CAR to "", PluginStatus.of(BridgeClient.State.NO_NETWORK, null, null))
        assertEquals(CarPluginContract.STATE_STOPPED to "", PluginStatus.of(BridgeClient.State.STOPPED, null, "x"))
        assertEquals(CarPluginContract.STATE_CONNECTING to "", PluginStatus.of(BridgeClient.State.CONNECTING, null, null))
        assertEquals(CarPluginContract.STATE_ERROR to "timeout", PluginStatus.of(BridgeClient.State.CONNECTING, null, "timeout"))
    }
}

class BridgeVersionTest {
    @Test
    fun readsTheVersionFromTheHelloAndSpotsAnOldPlugin() {
        assertEquals(BridgeVersion(0, 2, 0), BridgeVersion.parse("drivelog-bridge-0.2.0 NBT Evo"))
        assertNull(BridgeVersion.parse("something else"))
        assertNull(BridgeVersion.parse(null))
        assertTrue(BridgeVersion.isOutdated("drivelog-bridge-0.1.0 EntryNav2"))
        assertEquals(false, BridgeVersion.isOutdated("drivelog-bridge-0.2.0 EntryNav2"))
        assertEquals(false, BridgeVersion.isOutdated("drivelog-bridge-0.10.0 EntryNav2"))   // numbers, not text
        assertEquals(false, BridgeVersion.isOutdated("?"))                                  // unknown: not called old
        assertEquals(
            CarPluginContract.STATE_CONNECTED to "drivelog-bridge-0.1.0 NBT Evo (update the head-unit plugin to 0.2.0 or newer)",
            PluginStatus.of(BridgeClient.State.CONNECTED, "drivelog-bridge-0.1.0 NBT Evo", null),
        )
    }
}
