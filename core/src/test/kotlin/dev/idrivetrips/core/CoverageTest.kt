package dev.idrivetrips.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun summary(vararg trips: Pair<Long, Double>) =
    VehicleSummary(trips.size, trips.sumOf { it.second }, 0, 0.0, null, null, null, null, null, tripKms = trips.toList())

/** The odometer of a car without a plugin: the reading typed in plus the trips since. */
class ManualOdometerTest {
    private val car = CarDetails(usesPlugin = false).withManualOdometer(100_000.0, 1_000L)

    @Test
    fun theFieldFirstHoldsTheReadingPlusTheTripsSinceIt() {
        val s = summary(500L to 99.0, 2_000L to 12.4, 3_000L to 30.0)
        assertEquals("100042", ManualOdometer.shownText(car, s))            // the trip before the entry is not counted
    }

    @Test
    fun noReadingMeansAnEmptyField() {
        assertEquals("", ManualOdometer.shownText(CarDetails(usesPlugin = false), summary(1_000L to 5.0)))
        assertEquals("", ManualOdometer.text(null))
        assertEquals("1235", ManualOdometer.text(1234.6))
    }

    @Test
    fun savingTheFieldAsItWasShownKeepsTheTripsCounted() {
        val s = summary(2_000L to 40.0)
        val shown = ManualOdometer.shownText(car, s)
        val after = ManualOdometer.afterEdit(car, shown, shown, 100_040.0, 9_000L)
        assertSame("nothing to change", car, after)
        assertEquals(100_040.0, s.manualOdometerNow(after)!!, 1e-9)
    }

    @Test
    fun aChangedReadingIsValidFromNow() {
        val s = summary(2_000L to 40.0)
        val after = ManualOdometer.afterEdit(car, "100040", "100100", 100_100.0, 9_000L)
        assertEquals(100_100.0, after.manualOdometerKm!!, 0.0)
        assertEquals(9_000L, after.manualOdometerAtMs)
        assertEquals("the earlier trip no longer adds to it", 100_100.0, s.manualOdometerNow(after)!!, 1e-9)
        assertEquals(100_107.0, summary(9_500L to 7.0).manualOdometerNow(after)!!, 1e-9)
    }

    @Test
    fun clearingTheFieldForgetsTheReading() {
        val after = ManualOdometer.afterEdit(car, "100040", "", null, 9_000L)
        assertNull(after.manualOdometerKm)
        assertNull(after.manualOdometerAtMs)
    }

    @Test
    fun spacesAroundTheNumberAreNotAChange() {
        assertSame(car, ManualOdometer.afterEdit(car, "100040", " 100040 ", 100_040.0, 9_000L))
    }

    @Test
    fun anOlderServiceEntryDoesNotTurnTheOdometerBack() {
        val s = summary(2_000L to 800.0)                                    // shows 100800
        assertSame(car, ManualOdometer.afterServiceEntry(car, s, 100_100.0, 9_000L))
        assertSame("equal is not higher", car, ManualOdometer.afterServiceEntry(car, s, 100_800.0, 9_000L))
    }

    @Test
    fun aHigherServiceEntryRaisesTheOdometer() {
        val s = summary(2_000L to 800.0)
        val after = ManualOdometer.afterServiceEntry(car, s, 101_000.0, 9_000L)
        assertEquals(101_000.0, after.manualOdometerKm!!, 0.0)
        assertEquals(9_000L, after.manualOdometerAtMs)
    }

    @Test
    fun aFirstServiceEntryBecomesTheReadingAndAnEntryWithoutKmChangesNothing() {
        val blank = CarDetails(usesPlugin = false)
        assertEquals(55_000.0, ManualOdometer.afterServiceEntry(blank, summary(), 55_000.0, 7L).manualOdometerKm!!, 0.0)
        assertSame(blank, ManualOdometer.afterServiceEntry(blank, summary(), null, 7L))
    }

    @Test
    fun aCarWithAPluginIsLeftAlone() {
        val plugin = CarDetails(usesPlugin = true)
        assertSame(plugin, ManualOdometer.afterServiceEntry(plugin, summary(), 55_000.0, 7L))
    }
}

class SummaryAndOrderTest {
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
    fun kmSinceCountsTripsStartedAtOrAfterTheMoment() {
        val s = summary(1_000L to 10.0, 2_000L to 20.0, 3_000L to 40.0)
        assertEquals(60.0, s.kmSince(2_000L), 1e-9)
        assertEquals(0.0, s.kmSince(3_001L), 1e-9)
        assertEquals(70.0, s.kmSince(0L), 1e-9)
    }

    @Test
    fun lastDrivenIsTheEndOfTheLatestTripAndIgnoresAddressCase() {
        val trips = listOf(meta("AA:01", 100), meta("aa:01", 300), meta("BB:02", 200))
        assertEquals(300L, CarOrder.lastDrivenMs("Aa:01", trips))
        assertNull(CarOrder.lastDrivenMs("CC:03", trips))
    }
}

class VinAndPartsTest {
    private val language = Lang.code
    @After fun restore() { Lang.code = language }

    @Test
    fun vinIsNormalisedAndChecked() {
        assertEquals("WBAKR010X0N000001", Vin.normalize(" wba-kr010 x0n000001 "))
        assertTrue(Vin.isValid("WBAKR010X0N000001"))
        assertFalse("too short", Vin.isValid("WBAKR010X0N00001"))
        assertFalse("no I, O or Q", Vin.isValid("WBAKR010X0N00000O"))
        assertFalse("no lower case or symbols", Vin.isValid("wbakr010x0n000001"))
        assertFalse(Vin.isValid("WBAKR010X0N00000-"))
    }

    @Test
    fun partLabelsAreDescribedAndUnknownOnesPassThrough() {
        assertEquals("driver window", OpenPartsText.describeLabel("driver window"))
        assertEquals("sunroof (tilted)", OpenPartsText.describeLabel("sunroof (tilted)"))
        assertEquals("something new", OpenPartsText.describeLabel("something new"))
        assertEquals("—", OpenPartsText.describe(null))
        assertEquals("Closed", OpenPartsText.describe(emptyList()))
        assertEquals("Open: tailgate, bonnet", OpenPartsText.describe(listOf("tailgate", "bonnet")))
    }

    @Test
    fun partLabelsAreTranslated() {
        Lang.code = "pl"
        assertEquals(tr("sunroof") + tr(" (tilted)"), OpenPartsText.describeLabel("sunroof (tilted)"))
        assertEquals(tr("Closed"), OpenPartsText.describe(emptyList()))
        assertTrue(OpenPartsText.describe(listOf("bonnet")).startsWith(tr("Open: {0}", "").substringBefore("{")))
    }
}

class PluralAndLanguageTest {
    private val language = Lang.code
    @After fun restore() { Lang.code = language }

    @Test
    fun polishPluralForms() {
        val one = listOf(1L)
        val few = listOf(2L, 3L, 4L, 22L, 23L, 24L, 102L, 134L)
        val many = listOf(0L, 5L, 11L, 12L, 13L, 14L, 15L, 21L, 25L, 111L, 112L, 113L, 114L, 1000L)
        one.forEach { assertEquals("$it", 0, Lang.pluralForm(it)) }
        few.forEach { assertEquals("$it", 1, Lang.pluralForm(it)) }
        many.forEach { assertEquals("$it", 2, Lang.pluralForm(it)) }
    }

    @Test
    fun englishPluralsAndPolishPluralsUseTheirOwnForms() {
        Lang.code = "en"
        assertEquals("1 trip", Lang.trn(1, "{0} trip", "{0} trips"))
        assertEquals("2 trips", Lang.trn(2, "{0} trip", "{0} trips"))
        assertEquals("0 trips", Lang.trn(0, "{0} trip", "{0} trips"))
        Lang.code = "pl"
        val forms = Polish.plurals["{0} trips"]!!.split("|")
        assertEquals(3, forms.size)
        assertEquals(forms[0].replace("{0}", "1"), Lang.trn(1, "{0} trip", "{0} trips"))
        assertEquals(forms[1].replace("{0}", "3"), Lang.trn(3, "{0} trip", "{0} trips"))
        assertEquals(forms[2].replace("{0}", "12"), Lang.trn(12, "{0} trip", "{0} trips"))
        assertEquals("a text without a translation falls back to English", "1 widget", Lang.trn(1, "{0} widget", "{0} widgets"))
        assertEquals("5 widgets", Lang.trn(5, "{0} widget", "{0} widgets"))
    }

    @Test
    fun theLanguageIsResolvedFromTheChoiceAndThePhone() {
        assertEquals("pl", Lang.resolve(null, "PL"))
        assertEquals("pl", Lang.resolve(Lang.SYSTEM, "pl"))
        assertEquals("en", Lang.resolve(Lang.SYSTEM, "de"))
        assertEquals("pl", Lang.resolve("pl", "en"))
        assertEquals("en", Lang.resolve("xx", "pl"))
    }

    @Test
    fun placeholdersAreFilledInAnyOrderAndNullIsEmpty() {
        Lang.code = "en"
        assertEquals("b then a", tr("{1} then {0}", "a", "b"))
        assertEquals("x: ", tr("x: {0}", null))
        assertEquals("no placeholders {0}", tr("no placeholders {0}"))
    }
}

class RecorderAndMetaTest {
    @Test
    fun liveSummaryShowsTheTripSoFarAndCancelDropsIt() {
        val r = TripRecorder()
        r.begin("AA", "Car", Geo.T0, Geo.car(odo = 1000.0))
        Geo.drive(r, 60)
        val live = r.liveSummary(Geo.T0 + 60_000)
        assertEquals(1.2, live.gpsDistanceKm, 0.05)
        assertTrue(r.isRecording)
        r.cancel()
        assertFalse(r.isRecording)
        assertEquals(0, r.pointCount)
        assertNull("nothing left to finish", r.finish(Geo.T0 + 70_000))
    }

    @Test
    fun aTripSummaryRoundTripsThroughItsMetaFile() {
        val r = TripRecorder()
        r.begin("AA:BB", "Mini", Geo.T0, Geo.car(odo = 12_000.0, fuel = 40.0, avg = 14.0))
        Geo.drive(r, 120)
        val trip = r.finish(Geo.T0 + 120_000)!!
        val meta = TripMeta(trip.id, trip.carId, trip.carName, trip.startMs, trip.endMs, TripStats.summarize(trip))
        val back = TripJson.metaFromJson(TripJson.metaToJson(meta))
        assertNotNull(back)
        assertEquals(meta, back)
    }

    @Test
    fun anUnreadableMetaFileIsNull() {
        assertNull(TripJson.metaFromJson("not json"))
        assertNull(TripJson.metaFromJson("{}"))
        assertNull(TripJson.metaFromJson("""{"id":"x","carId":"y","carName":"z","startMs":1,"endMs":2,"summary":5}"""))
    }
}
