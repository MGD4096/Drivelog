package dev.idrivetrips.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Finds the `head-unit` folder next to `core`, wherever the tests are started from. */
private fun findHeadUnit(): File? {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    repeat(6) {
        val candidate = File(dir, "head-unit")
        if (File(candidate, "plugin/ponte_plugin.cpp").isFile) return candidate
        dir = dir?.parentFile
    }
    return null
}

/** The property names the plugin sends: the `kProprieta[]` list in its source. */
private fun pluginProperties(source: String): List<String> {
    val start = source.indexOf("kProprieta[] = {")
    require(start >= 0) { "kProprieta list not found in the plugin source" }
    val end = source.indexOf("};", start)
    return Regex("\"([A-Za-z.]+)\"").findAll(source.substring(start, end)).map { it.groupValues[1] }.toList()
}

class PluginSourceTest {

    @Test
    fun appAcceptsExactlyThePropertiesThePluginSends() {
        val head = findHeadUnit()
        assumeTrue("head-unit folder not found", head != null)
        val sent = pluginProperties(File(head, "plugin/ponte_plugin.cpp").readText())
        assertEquals("the plugin sends 13 car properties", 13, sent.size)
        // A property the app refused (400) would make the plugin retry it forever and block all values behind it
        assertEquals(sent.toSet(), CdsProperty.ALL)
    }
}

/**
 * Runs the real plugin source (built for the PC against a stand-in for the iDrive framework, see
 * head-unit/plugin/host-test/prova_host.cpp) against the real server of this project. The stand-in feeds the plugin
 * car values exactly as the head unit's CDS does; the plugin finds the "phone", polls /ponte and pushes the values
 * over a real socket to port 8765.
 */
class PluginHostTest {
    private lateinit var work: File
    private val requests: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var server: BridgeHttpServer? = null

    @Before
    fun setUp() {
        work = Files.createTempDirectory("plugin-host").toFile()
    }

    @After
    fun tearDown() {
        server?.stop()
        work.deleteRecursively()
    }

    private fun run(vararg command: String, timeoutSec: Long): Pair<Int, String> {
        val p = ProcessBuilder(*command).directory(work).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to output }
        return p.exitValue() to output
    }

    @Test
    fun theRealPluginTalksToTheAppServer() {
        val head = findHeadUnit()
        assumeTrue("head-unit folder not found", head != null)
        val hasCompiler = try { run("clang++", "--version", timeoutSec = 20).first == 0 } catch (e: java.io.IOException) { false }
        assumeTrue("clang++ not installed: skipping the plugin host test", hasCompiler)
        val portFree = try { ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(8765)); true } } catch (e: java.io.IOException) { false }
        assumeTrue("port 8765 is busy (the plugin always uses it): skipping", portFree)

        val binary = File(work, "plugin_host").path
        val (compileExit, compileOut) = run(
            "clang++", "-O1", "-fno-exceptions", "-fno-rtti", "-DPROVA_HOST", "-DPLUGIN_METEO",
            File(head, "plugin/host-test/prova_host.cpp").path, "-o", binary, timeoutSec = 180,
        )
        assertEquals("host build of the plugin failed:\n$compileOut", 0, compileExit)

        val car = CarState()
        val srv = BridgeHttpServer(8765, BridgeProtocol(car, "1.0", 1_780_000_000L), log = { requests.add(it) })
        srv.start()
        server = srv

        val (exit, out) = run(binary, timeoutSec = 60)
        assertEquals("the plugin host run failed:\n$out", 0, exit)

        // The plugin found the phone and accepted the handshake
        assertTrue(out, out.contains("trovaTelefono -> 127.0.0.1"))
        assertTrue(out, out.contains("/ponte codice 200: IDRIVEPONTE 1.0 "))
        assertTrue(requests.toString(), requests.any { it == "GET /ponte -> 200" })
        // With every page counter at 0 it never asks for pictures
        assertFalse(requests.toString(), requests.any { it.contains(".png") && !it.contains("/notizia/") })

        // 13 properties were registered, every value pushed got 200
        assertTrue(out, out.contains("proprieta' registrate: 13"))
        val pushes = requests.filter { it.startsWith("GET /auto?") }
        assertTrue("no value was refused", pushes.all { it.endsWith("-> 200") })
        fun count(property: String) = pushes.count { it.startsWith("GET /auto?n=$property&") }
        // First round: odometer (the newer of two), fuel, doors. Second round: only the sunroof (the fuel value is
        // unchanged and not sent again). After the phone "restarts" (avvio= changes) all four are sent once more.
        assertEquals(pushes.toString(), 2, count("driving.odometer"))
        assertEquals(pushes.toString(), 2, count("sensors.fuel"))
        assertEquals(pushes.toString(), 2, count("sensors.doors"))
        assertEquals(pushes.toString(), 2, count("controls.sunroof"))
        assertEquals(pushes.toString(), 8, pushes.size)

        // What the app ended up knowing, from the values the stand-in fed to the plugin
        val c = car.snapshot()
        assertEquals(12346.0, c.odometerKm!!, 0.0)
        assertEquals(30.0, c.fuelLiters!!, 0.0)
        assertEquals(420.0, c.rangeKm!!, 0.0)
        assertEquals(listOf("sunroof (tilted)", "driver door"), c.openParts)
    }
}
