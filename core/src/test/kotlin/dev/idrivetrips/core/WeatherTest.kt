package dev.idrivetrips.core

import com.sun.net.httpserver.HttpServer
import org.junit.After
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

private const val SAMPLE = """{"latitude":52.52,"longitude":13.419998,"generationtime_ms":0.05,"utc_offset_seconds":3600,
 "timezone":"Europe/Berlin","current_units":{"time":"iso8601","interval":"seconds","temperature_2m":"°C","apparent_temperature":"°C",
 "weather_code":"wmo code","wind_speed_10m":"km/h","is_day":""},
 "current":{"time":"2026-11-06T12:30","interval":900,"temperature_2m":8.1,"apparent_temperature":5.4,"weather_code":61,"wind_speed_10m":14.3,"is_day":1}}"""

class OpenMeteoParseTest {
    @Test
    fun readsTheCurrentBlock() {
        val w = OpenMeteo.parse(SAMPLE, 5L)!!
        assertEquals(8.1, w.tempC, 1e-9)
        assertEquals(5.4, w.feelsLikeC!!, 1e-9)
        assertEquals(61, w.code)
        assertEquals(14.3, w.windKmh!!, 1e-9)
        assertTrue(w.isDay)
        assertEquals(5L, w.fetchedAtMs)
        assertEquals("Light rain", w.description)
        assertEquals(WeatherKind.RAIN, w.kind)
    }

    @Test
    fun optionalFieldsMayBeMissing() {
        val w = OpenMeteo.parse("""{"current":{"temperature_2m":-3.0,"weather_code":0}}""", 1)!!
        assertNull(w.feelsLikeC)
        assertNull(w.windKmh)
        assertTrue("day by default", w.isDay)
        assertEquals(WeatherKind.CLEAR_DAY, w.kind)
        val night = OpenMeteo.parse("""{"current":{"temperature_2m":-3.0,"weather_code":0,"is_day":0}}""", 1)!!
        assertEquals(WeatherKind.CLEAR_NIGHT, night.kind)
    }

    @Test
    fun refusesAnythingElse() {
        assertNull(OpenMeteo.parse("not json", 1))
        assertNull(OpenMeteo.parse("[]", 1))
        assertNull(OpenMeteo.parse("""{"error":true,"reason":"Latitude must be in range"}""", 1))
        assertNull(OpenMeteo.parse("""{"current":{"weather_code":0}}""", 1))
        assertNull(OpenMeteo.parse("""{"current":{"temperature_2m":8.0}}""", 1))
        assertNull(OpenMeteo.parse("""{"current":{"temperature_2m":999.0,"weather_code":0}}""", 1))
        assertNull(OpenMeteo.parse("""{"current":{"temperature_2m":"warm","weather_code":0}}""", 1))
    }

    @Test
    fun buildsTheRequest() {
        val u = OpenMeteo.url("https://api.open-meteo.com/", 52.5200066, -13.404954)
        assertTrue(u, u.startsWith("https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=-13.40&current="))
        assertTrue(u.contains("temperature_2m,apparent_temperature,weather_code,wind_speed_10m,is_day"))
        assertTrue(u.endsWith("timezone=auto"))
    }
}

class WmoTest {
    @Test
    fun everyCodeOpenMeteoUsesHasAWordingAndAPicture() {
        val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        for (c in codes) assertNotEquals("code $c", "Unknown", Wmo.describe(c))
        assertEquals("Unknown", Wmo.describe(42))
        assertEquals(WeatherKind.CLOUDY, Wmo.kind(42, true))          // an unknown code still gets a picture
    }

    private fun assertNotEquals(message: String, a: Any, b: Any) = assertFalse(message, a == b)

    @Test
    fun picturesFollowTheKindOfWeather() {
        assertEquals(WeatherKind.PARTLY_CLOUDY, Wmo.kind(2, true))
        assertEquals(WeatherKind.CLOUDY, Wmo.kind(3, false))
        assertEquals(WeatherKind.FOG, Wmo.kind(45, true))
        for (c in listOf(51, 55, 56, 61, 65, 66, 67, 80, 82)) assertEquals("code $c", WeatherKind.RAIN, Wmo.kind(c, true))
        for (c in listOf(71, 75, 77, 85, 86)) assertEquals("code $c", WeatherKind.SNOW, Wmo.kind(c, true))
        for (c in listOf(95, 96, 99)) assertEquals("code $c", WeatherKind.STORM, Wmo.kind(c, false))
    }
}

class WeatherSourceTest {
    private lateinit var dir: File
    private lateinit var server: HttpServer
    private val requests = AtomicInteger()
    private val seen: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile private var status = 200
    @Volatile private var body = SAMPLE
    private var now = 1_000_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("weather").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            requests.incrementAndGet()
            seen.add(ex.requestURI.path + "?" + ex.requestURI.rawQuery + " UA=" + ex.requestHeaders.getFirst("User-Agent"))
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun source(): WeatherSource {
        val base = "http://127.0.0.1:${server.address.port}"
        return WeatherSource(File(dir, "weather.json"), WeatherClient(base, "Drivelog/test", 2000, { now }), { now })
    }

    @Test
    fun asksOnceAndThenUsesTheCache() {
        val s = source()
        val r = s.get(52.52, 13.41)!!
        assertFalse(r.stale)
        assertEquals(61, r.weather.code)
        assertEquals(1, requests.get())
        assertTrue(seen.single().contains("latitude=52.52&longitude=13.41"))
        assertTrue(seen.single().endsWith("UA=Drivelog/test"))

        now += 20 * 60_000L
        assertFalse(s.get(52.52, 13.41)!!.stale)
        assertEquals("still fresh", 1, requests.get())
        assertEquals("a place a few metres away is the same place", 1, run { s.get(52.5204, 13.4102); requests.get() })
    }

    @Test
    fun refreshesAfterHalfAnHourAndAnotherPlaceIsAnotherRequest() {
        val s = source()
        s.get(52.52, 13.41)
        now += 31 * 60_000L
        s.get(52.52, 13.41)
        assertEquals(2, requests.get())
        s.get(50.06, 19.94)
        assertEquals(3, requests.get())
    }

    @Test
    fun usesAnOldReadingWhenOfflineButNotAVeryOldOne() {
        val s = source()
        s.get(52.52, 13.41)
        status = 500
        now += 2 * 60 * 60_000L
        val old = s.get(52.52, 13.41)!!
        assertTrue(old.stale)
        assertEquals(61, old.weather.code)
        now += 5 * 60 * 60_000L                                  // now 7 hours old
        assertNull(s.get(52.52, 13.41))
    }

    @Test
    fun theCacheSurvivesARestart() {
        source().get(52.52, 13.41)
        server.stop(0)
        now += 60 * 60_000L
        val r = source().get(52.52, 13.41)!!                      // a new object reads the file
        assertTrue(r.stale)
        assertEquals(8.1, r.weather.tempC, 1e-9)
        assertEquals(5.4, r.weather.feelsLikeC!!, 1e-9)
        assertTrue(r.weather.isDay)
    }

    @Test
    fun badAnswersGiveNothingAndAreNotCached() {
        val s = source()
        body = """{"error":true}"""
        assertNull(s.get(52.52, 13.41))
        body = "<html>captive portal</html>"
        assertNull(s.get(52.52, 13.41))
        status = 429
        body = SAMPLE
        assertNull(s.get(52.52, 13.41))
        assertFalse(File(dir, "weather.json").exists())
    }

    @Test
    fun impossibleCoordinatesAreNotSent() {
        val s = source()
        assertNull(s.get(95.0, 10.0))
        assertNull(s.get(10.0, 200.0))
        assertNull(s.get(Double.NaN, 10.0))
        assertEquals(0, requests.get())
    }

    @Test
    fun theCacheKeepsOnlyTheMostRecentPlaces() {
        val s = source()
        for (i in 0 until WeatherSource.MAX_PLACES + 5) {
            now += 1000
            s.get(10.0 + i * 0.5, 20.0)
        }
        val n = Json.parseObject(File(dir, "weather.json").readText())!!.size
        assertEquals(WeatherSource.MAX_PLACES, n)
        assertNotNull(s.get(10.0 + (WeatherSource.MAX_PLACES + 4) * 0.5, 20.0))
    }

    @Test
    fun anUnreachableServerGivesNullQuickly() {
        val client = WeatherClient("http://127.0.0.1:1", "x", 1000)
        val started = System.nanoTime()
        assertNull(client.fetch(52.0, 13.0))
        assertTrue(System.nanoTime() - started < 3_000_000_000L)
    }
}

class FixesTest {
    @Test
    fun picksTheNewestFixThatIsNotTooOld() {
        val fixes = listOf(Fix(52.0, 22.0, 1_000), Fix(52.1, 22.1, 5_000), Fix(95.0, 22.0, 9_000), Fix(Double.NaN, 1.0, 8_000))
        val f = Fixes.newest(fixes, 10_000, 6_000)!!
        assertEquals(52.1, f.lat, 1e-9)
        assertNull(Fixes.newest(fixes, 10_000, 1_000))
        assertNull(Fixes.newest(emptyList(), 10_000, 1_000))
        assertNull("a fix from the future is not trusted", Fixes.newest(listOf(Fix(1.0, 1.0, 20_000)), 10_000, 60_000))
    }
}
