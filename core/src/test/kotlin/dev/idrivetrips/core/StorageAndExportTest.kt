package dev.idrivetrips.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

private fun sampleTrip(carId: String = "AA:BB:CC:DD:EE:FF", startSec: Long = 0, name: String = "Mini <John's> & co"): Trip {
    val r = TripRecorder()
    val t0 = Geo.T0 + startSec * 1000
    r.begin(carId, name, t0, Geo.car(odo = 12_000.0, fuel = 40.0, outside = 8.5, avg = 14.2))
    for (t in 0..60) {
        val fix = Geo.fix(t.toLong(), t * 20.0)
        r.onFix(TripRecorder.Fix(t0 + t * 1000L, fix.lat, fix.lon, fix.altitudeM, fix.speedMps, fix.accuracyM))
    }
    r.onCar(t0 + 30_000, CarSnapshot(odometerKm = 12_001.0, fuelLiters = 39.0, outsideC = 8.0, avgConsumptionKmPerL = 14.0, openParts = listOf("bonnet")))
    return r.finish(t0 + 60_000)!!
}

class StorageTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("trips-test").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test
    fun savedTripLoadsBackIdentical() {
        val store = FileTripStore(dir)
        val trip = sampleTrip()
        store.save(trip)
        assertEquals(trip, store.load(trip.id))
    }

    @Test
    fun listIsNewestFirstAndCarriesTheSummary() {
        val store = FileTripStore(dir)
        val older = sampleTrip(startSec = 0)
        val newer = sampleTrip(startSec = 3600)
        store.save(older)
        store.save(newer)
        val list = store.list()
        assertEquals(listOf(newer.id, older.id), list.map { it.id })
        assertEquals(TripStats.summarize(newer), list[0].summary)
        assertEquals("Mini <John's> & co", list[0].carName)
    }

    @Test
    fun deleteRemovesBothFiles() {
        val store = FileTripStore(dir)
        val trip = sampleTrip()
        store.save(trip)
        store.delete(trip.id)
        assertTrue(store.list().isEmpty())
        assertNull(store.load(trip.id))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun unreadableFilesAreSkippedNotFatal() {
        val store = FileTripStore(dir)
        store.save(sampleTrip())
        File(dir, "broken.meta.json").writeText("{ not json")
        File(dir, "wrongshape.meta.json").writeText("""{"id":5}""")
        File(dir, "broken.trip.json").writeText("[]")
        assertEquals(1, store.list().size)
        assertNull(store.load("broken"))
    }

    @Test
    fun idsCannotEscapeTheDirectory() {
        val store = FileTripStore(dir)
        val outside = File(dir.parentFile, "victim.trip.json").apply { writeText("{}") }
        try {
            assertNull(store.load("../victim"))
            store.delete("../victim")
            assertTrue("file outside the store must survive", outside.exists())
        } finally {
            outside.delete()
        }
    }

    @Test
    fun draftSurvivesAndCanBeCleared() {
        val store = FileTripStore(dir)
        assertNull(store.loadDraft())
        val trip = sampleTrip()
        store.saveDraft(trip)
        assertEquals(trip, store.loadDraft())
        assertTrue("a draft is not listed as a finished trip", store.list().isEmpty())
        store.clearDraft()
        assertNull(store.loadDraft())
    }

    @Test
    fun noTemporaryFilesAreLeftBehind() {
        val store = FileTripStore(dir)
        store.save(sampleTrip())
        store.saveDraft(sampleTrip())
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun tripJsonRejectsGarbage() {
        assertNull(TripJson.fromJson("nope"))
        assertNull(TripJson.fromJson("{}"))
        assertNull("a point without coordinates", TripJson.fromJson("""{"id":"x","carId":"c","carName":"n","startMs":1,"endMs":2,"samples":[],"points":[[1]]}"""))
        assertNull("a sample row that is not an array", TripJson.fromJson("""{"id":"x","carId":"c","carName":"n","startMs":1,"endMs":2,"samples":[5],"points":[]}"""))
        assertNotNull(TripJson.fromJson("""{"id":"x","carId":"c","carName":"n","startMs":1,"endMs":2,"samples":[],"points":[]}"""))
    }
}

class ExportTest {
    private val savedLocale = Locale.getDefault()

    // A locale with a decimal comma must not leak into the files
    @Before fun useCommaLocale() = Locale.setDefault(Locale.forLanguageTag("pl-PL"))
    @After fun restoreLocale() = Locale.setDefault(savedLocale)

    @Test
    fun gpxIsWellFormedAndUsesDecimalPoints() {
        val trip = sampleTrip()
        val xml = Gpx.write(trip)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
        val points = doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt")
        assertEquals(trip.points.size, points.length)
        val first = points.item(0)
        assertEquals("52.0000000", first.attributes.getNamedItem("lat").nodeValue)
        assertEquals("21.0000000", first.attributes.getNamedItem("lon").nodeValue)
        assertFalse("no decimal commas anywhere", xml.contains("52,"))
        assertTrue(xml.contains("<idt:odometer>12000</idt:odometer>"))
        assertTrue(xml.contains("<idt:fuel>40.0</idt:fuel>"))
        assertTrue(xml.contains("<idt:fuel>39.0</idt:fuel>"))
        assertTrue(xml.contains("<idt:speed>20.00</idt:speed>"))
        assertTrue("name is escaped", xml.contains("Mini &lt;John's&gt; &amp; co"))
    }

    @Test
    fun csvCarriesCarValuesForwardAndHasOneRowPerPoint() {
        val trip = sampleTrip()
        val lines = Csv.write(trip).trim().lines()
        assertEquals(Csv.HEADER, lines[0])
        assertEquals(trip.points.size + 1, lines.size)
        assertTrue(lines.all { it.count { c -> c == ',' } == 9 })
        val rows = lines.drop(1).map { it.split(',') }
        // before the change at +30 s the first sample applies, after it the second one
        assertEquals("12000", rows.first()[6])
        assertEquals("40.0", rows.first()[7])
        assertEquals("12001", rows.last()[6])
        assertEquals("39.0", rows.last()[7])
        assertEquals("72.0", rows.first()[4])       // 20 m/s in km/h
        assertTrue(rows.first()[0].endsWith("Z"))
        assertEquals("52.0000000", rows.first()[1])
    }

    @Test
    fun exportsOfAnEmptyTripAreStillValid() {
        val empty = Trip("id", "c", "Car", Geo.T0, Geo.T0, emptyList(), emptyList(), CarSnapshot.EMPTY, CarSnapshot.EMPTY)
        assertEquals(Csv.HEADER + "\n", Csv.write(empty))
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Gpx.write(empty).byteInputStream())
    }
}
