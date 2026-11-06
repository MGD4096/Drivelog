package dev.idrivetrips.core

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** A 1 x 1 pixel PNG. */
private val PNG: ByteArray = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
)

class TileProviderTest {
    private val osm = TileProvider.OPEN_STREET_MAP

    @Test
    fun buildsTheOpenStreetMapAddress() {
        assertEquals("https://tile.openstreetmap.org/12/2287/1349.png", osm.url(12, 2287, 1349))
    }

    @Test
    fun wrapsTheColumnAndRefusesTilesThatDoNotExist() {
        assertEquals("https://tile.openstreetmap.org/3/7/0.png", osm.url(3, -1, 0))
        assertEquals("https://tile.openstreetmap.org/3/0/0.png", osm.url(3, 8, 0))
        assertNull("above the north edge", osm.url(3, 0, -1))
        assertNull("below the south edge", osm.url(3, 0, 8))
        assertNull("deeper than the server goes", osm.url(20, 0, 0))
        assertNull(osm.url(-1, 0, 0))
    }

    @Test
    fun followsTheServerUsagePolicy() {
        assertTrue("attribution is required", osm.attribution.contains("OpenStreetMap"))
        assertTrue("an app-specific User-Agent, not a library default", osm.userAgent.startsWith("Drivelog/"))
        assertFalse(osm.userAgent.contains("Java", ignoreCase = true))
        assertTrue("https", osm.urlTemplate.startsWith("https://"))
        assertEquals(7L * 24 * 60 * 60 * 1000, TileSource.SEVEN_DAYS_MS)
    }
}

class DiskTileCacheTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("tiles").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test
    fun storesAndReturnsATileWithItsAge() {
        var now = 1_000_000L
        val cache = DiskTileCache(dir, 1_000_000, { now })
        assertNull(cache.get(5, 1, 2))
        cache.put(5, 1, 2, PNG)
        val e = cache.get(5, 1, 2)!!
        assertArrayEquals(PNG, e.bytes)
        assertEquals(1_000_000L, e.savedAtMs)
        now += 5000
        cache.put(5, 1, 2, PNG + byteArrayOf(1))
        assertEquals(PNG.size + 1, cache.get(5, 1, 2)!!.bytes.size)
        assertEquals(1_005_000L, cache.get(5, 1, 2)!!.savedAtMs)
    }

    @Test
    fun theOldestTilesGoFirstWhenTheCacheIsFull() {
        var now = 1_000L
        val cache = DiskTileCache(dir, 1000, { now })
        val tile = ByteArray(300) { 7 }
        for (i in 0 until 3) { now += 1000; cache.put(1, i, 0, tile) }      // 900 bytes, within the limit
        assertEquals(900L, cache.totalBytes())
        now += 1000
        cache.put(1, 3, 0, tile)                                              // 1200 > 1000: cut to 800
        assertNull("oldest removed", cache.get(1, 0, 0))
        assertNull("second oldest removed", cache.get(1, 1, 0))
        assertNotNull(cache.get(1, 2, 0))
        assertNotNull("the one just stored stays", cache.get(1, 3, 0))
        assertTrue(cache.totalBytes() <= 1000)
    }

    @Test
    fun noTemporaryFilesRemain() {
        val cache = DiskTileCache(dir, 1_000_000)
        cache.put(3, 1, 1, PNG)
        assertFalse(dir.walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun aCacheThatCannotBeWrittenToJustMisses() {
        val blocker = File(dir, "blocked").apply { writeText("a file where a folder is needed") }
        val cache = DiskTileCache(blocker, 1000)
        cache.put(1, 0, 0, PNG)           // must not throw
        assertNull(cache.get(1, 0, 0))
    }
}

class TileSourceTest {
    private lateinit var dir: File
    private lateinit var server: HttpServer
    private val requests = AtomicInteger()
    private val agents: MutableList<String?> = Collections.synchronizedList(ArrayList())
    private var provider = TileProvider("", "", 19, "")
    private var now = 10_000_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tilesrc").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            requests.incrementAndGet()
            agents.add(ex.requestHeaders.getFirst("User-Agent"))
            val path = ex.requestURI.path
            val (status, body) = when {
                path.startsWith("/1/") -> 200 to PNG                              // a good tile
                path.startsWith("/2/") -> 200 to "<html>captive portal</html>".toByteArray()
                path.startsWith("/3/") -> 404 to "no".toByteArray()
                path.startsWith("/4/") -> 200 to ByteArray(TileFetcher.MAX_BYTES + 1) { if (it < 4) PNG[it] else 0 }
                else -> 500 to "oops".toByteArray()
            }
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        provider = TileProvider("http://127.0.0.1:${server.address.port}/{z}/{x}/{y}.png", "© test", 19, "Drivelog/test")
    }

    @After
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun source(maxAge: Long = TileSource.SEVEN_DAYS_MS) =
        TileSource(DiskTileCache(dir, 10_000_000, { now }), TileFetcher(provider, 2000, 2000), maxAge, { now })

    @Test
    fun downloadsOnceThenServesFromTheCache() {
        val s = source()
        assertArrayEquals(PNG, s.load(1, 1, 1))
        assertEquals(1, requests.get())
        now += 6L * 24 * 60 * 60 * 1000          // six days later: still fresh
        assertArrayEquals(PNG, s.load(1, 1, 1))
        assertEquals("no second download", 1, requests.get())
    }

    @Test
    fun identifiesItselfWithItsOwnUserAgent() {
        source().load(1, 0, 0)
        assertEquals(listOf<String?>("Drivelog/test"), agents)
    }

    @Test
    fun refreshesATileOlderThanSevenDays() {
        val s = source()
        s.load(1, 1, 1)
        now += 8L * 24 * 60 * 60 * 1000
        assertArrayEquals(PNG, s.load(1, 1, 1))
        assertEquals(2, requests.get())
        assertEquals("the refreshed tile is fresh again", now, DiskTileCache(dir, 1_000_000, { now }).get(1, 1, 1)!!.savedAtMs)
    }

    @Test
    fun anOldTileIsBetterThanNoneWhenTheNetworkFails() {
        val s = source()
        s.load(1, 1, 1)
        now += 30L * 24 * 60 * 60 * 1000
        server.stop(0)                           // no network
        assertArrayEquals(PNG, s.load(1, 1, 1))
        assertNull("nothing cached, nothing downloadable", s.load(1, 9, 9))
    }

    @Test
    fun refusesWhatIsNotAPictureAndDoesNotCacheIt() {
        val s = source()
        assertNull("an HTML page from a captive portal", s.load(2, 0, 0))
        assertNull(DiskTileCache(dir, 1000, { now }).get(2, 0, 0))
        assertNull("404", s.load(3, 0, 0))
        assertNull("500", s.load(9, 0, 0))
        assertNull("too big", s.load(4, 0, 0))
    }

    @Test
    fun anErrorPageDoesNotReplaceAGoodOldTile() {
        val s = source()
        s.load(1, 0, 0)                          // cached
        now += 10L * 24 * 60 * 60 * 1000
        // the same tile now "comes" from a server that answers with an error
        val broken = TileProvider(provider.urlTemplate.replace("{z}", "9"), "", 19, "x")
        val s2 = TileSource(DiskTileCache(dir, 10_000_000, { now }), TileFetcher(broken, 2000, 2000), TileSource.SEVEN_DAYS_MS, { now })
        assertArrayEquals(PNG, s2.load(1, 0, 0))
    }

    @Test
    fun fetcherRecognisesPicturesByTheirFirstBytes() {
        assertTrue(TileFetcher.looksLikeImage(PNG))
        assertTrue(TileFetcher.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)))
        assertFalse(TileFetcher.looksLikeImage("<html>".toByteArray()))
        assertFalse(TileFetcher.looksLikeImage(ByteArray(0)))
    }

    @Test
    fun anUnreachableServerGivesNullQuickly() {
        val unreachable = TileProvider("http://127.0.0.1:1/{z}/{x}/{y}.png", "", 19, "x")
        val started = System.nanoTime()
        assertNull(TileFetcher(unreachable, 1000, 1000).fetch(1, 0, 0))
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 3_000_000_000L)
    }
}
