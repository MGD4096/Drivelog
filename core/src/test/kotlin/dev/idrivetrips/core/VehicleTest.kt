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

class VinTest {
    @Test
    fun acceptsAValidVinInAnyWritingAndNormalisesIt() {
        assertEquals("WBA8E9C50GK123456", Vin.parse(" wba8e9c5-0gk 123456 ").value)
        assertTrue(Vin.parse("WBA8E9C50GK123456").ok)
    }

    @Test
    fun emptyMeansNotGiven() {
        assertNull(Vin.parse("   ").value)
        assertTrue(Vin.parse("").ok)
    }

    @Test
    fun rejectsWrongLengthAndForbiddenLetters() {
        assertFalse(Vin.parse("WBA8E9C50GK12345").ok)          // 16
        assertFalse(Vin.parse("WBA8E9C50GK1234567").ok)         // 18
        assertFalse("I is not used", Vin.parse("WBA8E9C50GK12345I").ok)
        assertFalse("O is not used", Vin.parse("WBA8E9C50GK12345O").ok)
        assertFalse("Q is not used", Vin.parse("WBA8E9C50GK12345Q").ok)
        assertFalse("punctuation", Vin.parse("WBA8E9C50GK12345!").ok)
        assertNotNull(Vin.parse("WBA8E9C50GK12345").error)
    }
}

class CarInputTest {
    @Test
    fun yearIsReasonable() {
        assertEquals(2018, CarInput.parseYear("2018", 2026).value)
        assertEquals(2027, CarInput.parseYear("2027", 2026).value)       // next year's models exist
        assertFalse(CarInput.parseYear("2028", 2026).ok)
        assertFalse(CarInput.parseYear("1900", 2026).ok)
        assertFalse(CarInput.parseYear("abc", 2026).ok)
        assertTrue(CarInput.parseYear("", 2026).ok)
        assertNull(CarInput.parseYear("", 2026).value)
    }

    @Test
    fun engineSizeInLitresOrCubicCentimetres() {
        assertEquals(2000, CarInput.parseEngineCc("2.0").value)
        assertEquals(2000, CarInput.parseEngineCc("2,0").value)
        assertEquals(1500, CarInput.parseEngineCc("1.5 L").value)
        assertEquals(3000, CarInput.parseEngineCc("3 l").value)
        assertEquals(1998, CarInput.parseEngineCc("1998").value)
        assertEquals(1998, CarInput.parseEngineCc("1998 cc").value)
        assertEquals(1998, CarInput.parseEngineCc("1998cm3").value)
        assertEquals(6200, CarInput.parseEngineCc("6.2").value)
        assertEquals(998, CarInput.parseEngineCc("998 ccm").value)
    }

    @Test
    fun engineSizeRejectsNonsense() {
        assertFalse(CarInput.parseEngineCc("big").ok)
        assertFalse(CarInput.parseEngineCc("0").ok)
        assertFalse(CarInput.parseEngineCc("50000").ok)
        assertFalse(CarInput.parseEngineCc("25 l").ok)
        assertNull(CarInput.parseEngineCc("  ").value)
        assertTrue(CarInput.parseEngineCc("  ").ok)
    }
}

class VehicleSummaryTest {
    private fun meta(carId: String, startMs: Long, endMs: Long, km: Double, endOdo: Double?, endLat: Double?, endLon: Double?) =
        TripMeta(
            id = "t$startMs", carId = carId, carName = "car", startMs = startMs, endMs = endMs,
            summary = TripSummary(
                durationMs = endMs - startMs, distanceKm = km, gpsDistanceKm = km, odometerDistanceKm = null, movingTimeMs = 0,
                avgMovingSpeedKmh = null, maxSpeedKmh = null, startOdometerKm = endOdo?.let { it - km }, endOdometerKm = endOdo,
                startFuelL = null, endFuelL = null, fuelUsedL = null, refueledL = 0.0, consumptionLPer100Km = null,
                carAvgConsumptionKmPerL = null, startLat = endLat, startLon = endLon, endLat = endLat, endLon = endLon,
            ),
        )

    @Test
    fun takesOdometerAndParkingFromTheLatestTripOfThatCarOnly() {
        val trips = listOf(
            meta("AA:01", 1_000, 2_000, 10.0, 50_010.0, 50.0, 19.0),
            meta("AA:01", 5_000, 6_000, 20.0, 50_030.0, 51.0, 20.0),     // latest of car 1
            meta("BB:02", 9_000, 9_500, 5.0, 7_005.0, 52.0, 21.0),      // newer, but another car
            meta("manual", 9_900, 9_950, 1.0, null, 53.0, 22.0),
        )
        val s = VehicleSummary.of("aa:01", trips)       // address case does not matter
        assertEquals(2, s.tripCount)
        assertEquals(30.0, s.totalKm, 1e-9)
        assertEquals(50_030.0, s.odometerKm!!, 1e-9)
        assertEquals(6_000L, s.odometerAtMs)
        assertEquals(51.0, s.parkedLat!!, 1e-9)
        assertEquals(20.0, s.parkedLon!!, 1e-9)
        assertEquals(6_000L, s.parkedAtMs)
    }

    @Test
    fun skipsTripsWithoutGpsOrOdometerButKeepsWhatEarlierTripsKnow() {
        val trips = listOf(
            meta("AA:01", 1_000, 2_000, 10.0, 40_000.0, 50.0, 19.0),
            meta("AA:01", 5_000, 6_000, 20.0, null, null, null),          // no odometer, no GPS
        )
        val s = VehicleSummary.of("AA:01", trips)
        assertEquals(40_000.0, s.odometerKm!!, 1e-9)
        assertEquals(2_000L, s.odometerAtMs)
        assertEquals(50.0, s.parkedLat!!, 1e-9)
        assertEquals(2_000L, s.parkedAtMs)
    }

    @Test
    fun aCarWithoutTripsHasNothing() {
        val s = VehicleSummary.of("AA:01", emptyList())
        assertEquals(0, s.tripCount)
        assertNull(s.odometerKm)
        assertNull(s.parkedLat)
    }
}

class CarDetailsStoreTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("cars").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private val full = CarDetails(vin = "WBA8E9C50GK123456", model = "BMW \"M240i\" <xDrive>", year = 2018, engineCc = 2998, photo = "a.jpg")

    @Test
    fun savesAndReadsBackEveryField() {
        val store = FileCarDetailsStore(File(dir, "sub/cars.json"))
        assertTrue(store.get("AA:BB").isEmpty)
        store.put("AA:BB", full)
        assertEquals(full, FileCarDetailsStore(File(dir, "sub/cars.json")).get("AA:BB"))
        assertEquals("address case does not matter", full, store.get("aa:bb"))
    }

    @Test
    fun keepsCarsApartAndRemoves() {
        val store = FileCarDetailsStore(File(dir, "cars.json"))
        store.put("AA:01", full)
        store.put("BB:02", CarDetails(model = "Mini"))
        assertEquals("Mini", store.get("BB:02").model)
        assertEquals(2998, store.get("AA:01").engineCc)
        store.remove("AA:01")
        assertTrue(store.get("AA:01").isEmpty)
        assertEquals("Mini", store.get("BB:02").model)
        store.put("BB:02", CarDetails())            // emptying a car's details removes its entry
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun aDamagedFileIsTreatedAsEmpty() {
        val f = File(dir, "cars.json")
        f.writeText("{ not json")
        val store = FileCarDetailsStore(f)
        assertTrue(store.all().isEmpty())
        store.put("AA:01", CarDetails(model = "X"))          // and can be written over
        assertEquals("X", store.get("AA:01").model)
    }

    @Test
    fun noTemporaryFileRemains() {
        val store = FileCarDetailsStore(File(dir, "cars.json"))
        store.put("AA:01", full)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }
}

class PhotoGalleryTest {
    @Test
    fun theFirstPhotoIsMainAndTheOthersFollow() {
        val d = CarDetails().withPhoto("p1.jpg").withPhoto("p2.jpg").withPhoto("p3.jpg").withPhoto("p2.jpg")
        assertEquals("p1.jpg", d.photo)
        assertEquals(listOf("p2.jpg", "p3.jpg"), d.morePhotos)
        assertEquals(listOf("p1.jpg", "p2.jpg", "p3.jpg"), d.allPhotos)
    }

    @Test
    fun anotherPhotoCanBecomeMainAndTheOldMainStaysInTheGallery() {
        val d = CarDetails().withPhoto("p1.jpg").withPhoto("p2.jpg").withPhoto("p3.jpg").withMainPhoto("p3.jpg")
        assertEquals("p3.jpg", d.photo)
        assertEquals(listOf("p1.jpg", "p2.jpg"), d.morePhotos)
        assertEquals(d, d.withMainPhoto("p3.jpg"))
        assertEquals(d, d.withMainPhoto("unknown.jpg"))
    }

    @Test
    fun removingTheMainPhotoPromotesTheNextOne() {
        val d = CarDetails().withPhoto("p1.jpg").withPhoto("p2.jpg").withPhoto("p3.jpg")
        val noMain = d.withoutPhoto("p1.jpg")
        assertEquals("p2.jpg", noMain.photo)
        assertEquals(listOf("p3.jpg"), noMain.morePhotos)
        assertEquals(listOf("p1.jpg", "p2.jpg"), d.withoutPhoto("p3.jpg").allPhotos)
        assertTrue(d.withoutPhoto("p1.jpg").withoutPhoto("p2.jpg").withoutPhoto("p3.jpg").isEmpty)
        assertEquals(d, d.withoutPhoto("unknown.jpg"))
    }

    @Test
    fun theGalleryHasALimit() {
        var d = CarDetails()
        for (i in 0 until CarDetails.MAX_PHOTOS + 5) d = d.withPhoto("p$i.jpg")
        assertEquals(CarDetails.MAX_PHOTOS, d.allPhotos.size)
    }

    @Test
    fun theGallerySurvivesTheFileAndOldFilesStillLoad() {
        val dir = java.nio.file.Files.createTempDirectory("cars").toFile()
        val file = java.io.File(dir, "cars.json")
        val store = FileCarDetailsStore(file)
        store.put("AA:BB", CarDetails(model = "X").withPhoto("p1.jpg").withPhoto("p2.jpg").withPhoto("p3.jpg"))
        val back = FileCarDetailsStore(file).get("aa:bb")
        assertEquals("p1.jpg", back.photo)
        assertEquals(listOf("p2.jpg", "p3.jpg"), back.morePhotos)
        file.writeText("""{"aa:bb":{"model":"X","photo":"old.jpg"}}""")            // a file from before the gallery
        val old = FileCarDetailsStore(file).get("aa:bb")
        assertEquals(listOf("old.jpg"), old.allPhotos)
        dir.deleteRecursively()
    }
}

class PluginSettingTest {
    @Test
    fun aCarUsesTheBmwPluginUnlessToldOtherwise() {
        val d = CarDetails()
        assertTrue(d.usesPlugin)
        assertEquals("bmw", d.plugin)
        assertEquals(listOf("bmw"), CarPlugins.all.map { it.id })
        assertTrue(CarPlugins.label("bmw").contains("BMW"))
        assertEquals("zzz", CarPlugins.label("zzz"))
        assertTrue("a car that only has defaults is still empty", d.isEmpty)
    }

    @Test
    fun theSettingAndTheTypedOdometerSurviveTheFile() {
        val dir = java.nio.file.Files.createTempDirectory("plugin").toFile()
        val file = java.io.File(dir, "cars.json")
        val store = FileCarDetailsStore(file)
        store.put("AA:BB", CarDetails(model = "Fiat").copy(usesPlugin = false).withManualOdometer(123456.0, 1_700_000_000_000L))
        val back = FileCarDetailsStore(file).get("aa:bb")
        assertFalse(back.usesPlugin)
        assertEquals(123456.0, back.manualOdometerKm!!, 1e-9)
        assertEquals(1_700_000_000_000L, back.manualOdometerAtMs)
        store.put("CC:DD", CarDetails(model = "BMW").copy(plugin = "other"))
        assertEquals("other", FileCarDetailsStore(file).get("cc:dd").plugin)
        assertTrue(FileCarDetailsStore(file).get("ee:ff").usesPlugin)
        file.writeText("""{"aa:bb":{"model":"Old"}}""")                    // a file from before this setting
        val old = FileCarDetailsStore(file).get("aa:bb")
        assertTrue(old.usesPlugin)
        assertNull(old.manualOdometerKm)
        dir.deleteRecursively()
    }

    @Test
    fun theTypedOdometerCanBeCleared() {
        val d = CarDetails().withManualOdometer(5000.0, 1L).withManualOdometer(null, 2L)
        assertNull(d.manualOdometerKm)
        assertNull(d.manualOdometerAtMs)
    }

    @Test
    fun odometerInput() {
        assertEquals(123456.0, CarInput.parseOdometerKm("123 456").value!!, 1e-9)
        assertEquals(98765.5, CarInput.parseOdometerKm("98765,5").value!!, 1e-9)
        assertNull(CarInput.parseOdometerKm("").value)
        assertTrue(CarInput.parseOdometerKm("").ok)
        assertFalse(CarInput.parseOdometerKm("-5").ok)
        assertFalse(CarInput.parseOdometerKm("abc").ok)
        assertFalse(CarInput.parseOdometerKm("99999999").ok)
    }
}

class SilhouetteTest {
    @Test
    fun everyPartHasOneZone() {
        val zones = Silhouette.zones(emptyList())
        for (p in CarPart.values()) assertEquals(p.toString(), 1, zones.count { it.part == p })
        assertTrue(zones.none { it.open })
    }

    @Test
    fun openPartsAreMarkedAndSidesAreMirrored() {
        val zones = Silhouette.zones(listOf(CarPart.DRIVER_DOOR, CarPart.REAR_PASSENGER_DOOR, CarPart.SUNROOF))
        assertEquals(3, zones.count { it.open })
        val driver = zones.single { it.part == CarPart.DRIVER_DOOR }
        val passenger = zones.single { it.part == CarPart.PASSENGER_DOOR }
        assertTrue("the driver is on the left", driver.x < 0.5f && passenger.x > 0.5f)
        assertEquals(driver.x, 1f - passenger.x - passenger.w, 0.0001f)
        assertEquals(driver.y, passenger.y, 0f)
    }

    @Test
    fun everythingFitsInThePicture() {
        for (z in Silhouette.zones(emptyList())) {
            assertTrue(z.toString(), z.x >= 0f && z.y >= 0f && z.x + z.w <= 1.0f && z.y + z.h <= Silhouette.HEIGHT)
        }
    }
}

class ManualOdometerTripsTest {
    private val none = VehicleSummary(0, 0.0, 0, 0.0, null, null, null, null, null)

    @Test
    fun addsOnlyTripsStartedAfterTheEntry() {
        val s = none.copy(tripKms = listOf(1_000L to 10.0, 2_000L to 25.5, 3_000L to 4.5))
        val d = CarDetails(usesPlugin = false).withManualOdometer(50_000.0, 2_000L)
        assertEquals(50_030.0, s.manualOdometerNow(d)!!, 1e-9)
    }

    @Test
    fun noEntryMeansUnknown() {
        val s = none.copy(tripKms = listOf(1_000L to 10.0))
        assertNull(s.manualOdometerNow(CarDetails(usesPlugin = false)))
    }
}

class CarOrderTest {
    private fun meta(carId: String, endMs: Long) = TripMeta(
        id = "t$endMs", carId = carId, carName = "car", startMs = endMs - 10, endMs = endMs,
        summary = TripSummary(
            durationMs = 10, distanceKm = 1.0, gpsDistanceKm = 1.0, odometerDistanceKm = null, movingTimeMs = 0,
            avgMovingSpeedKmh = null, maxSpeedKmh = null, startOdometerKm = null, endOdometerKm = null,
            startFuelL = null, endFuelL = null, fuelUsedL = null, refueledL = 0.0, consumptionLPer100Km = null,
            carAvgConsumptionKmPerL = null, startLat = null, startLon = null, endLat = null, endLon = null,
        ),
    )

    @Test
    fun theCarDrivenMostRecentlyComesFirstAndUndrivenCarsKeepTheirOrder() {
        val trips = listOf(meta("AA", 100), meta("BB", 300), meta("aa", 200))
        val order = CarOrder.byLastDriven(listOf("AA", "BB", "CC", "DD"), trips, { it })
        assertEquals(listOf("BB", "AA", "CC", "DD"), order)
    }

    @Test
    fun theCarBeingRecordedIsFirst() {
        val trips = listOf(meta("AA", 100), meta("BB", 300))
        val order = CarOrder.byLastDriven(listOf("AA", "BB", "CC"), trips, { it }) { it == "CC" }
        assertEquals(listOf("CC", "BB", "AA"), order)
    }
}

class ArchivedCarTest {
    @Test
    fun theArchivedFlagIsSavedOnlyWhenSetAndReadBack() {
        val dir = Files.createTempDirectory("arch").toFile()
        try {
            val file = File(dir, "cars.json")
            val store = FileCarDetailsStore(file)
            store.put("AA:BB", CarDetails(model = "Fiat").copy(archived = true))
            store.put("CC:DD", CarDetails(model = "BMW"))
            val back = FileCarDetailsStore(file)
            assertTrue(back.get("aa:bb").archived)
            assertFalse(back.get("cc:dd").archived)
            assertFalse(file.readText().contains("archived\":false"))
        } finally { dir.deleteRecursively() }
    }
}
