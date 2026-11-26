package dev.drivelog.core

import dev.drivelog.carapi.CarValues
import dev.drivelog.core.car.CarEvent
import dev.drivelog.core.car.EventType
import dev.drivelog.core.car.FileFuelStore
import dev.drivelog.core.car.FuelEntry
import dev.drivelog.core.car.FuelLog
import dev.drivelog.core.trip.FileTripStore
import dev.drivelog.core.trip.MileageLog
import dev.drivelog.core.trip.MonthlyStats
import dev.drivelog.core.trip.Trip
import dev.drivelog.core.trip.TripKind
import dev.drivelog.core.trip.TripLog
import dev.drivelog.core.trip.TripMeta
import dev.drivelog.core.trip.TripSummary
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val UTC: ZoneId = ZoneOffset.UTC
private fun ms(date: String, hour: Int = 10): Long = LocalDate.parse(date).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

private fun summary(km: Double, startOdo: Double? = null, startFuel: Double? = null, endFuel: Double? = null, fuelUsed: Double? = null, refueled: Double = 0.0) = TripSummary(
    durationMs = 1_800_000, distanceKm = km, gpsDistanceKm = km, odometerDistanceKm = null, movingTimeMs = 1_500_000,
    avgMovingSpeedKmh = null, maxSpeedKmh = null, startOdometerKm = startOdo, endOdometerKm = startOdo?.plus(km),
    startFuelL = startFuel, endFuelL = endFuel, fuelUsedL = fuelUsed, refueledL = refueled,
    consumptionLPer100Km = fuelUsed?.takeIf { km >= 5 }?.let { it / km * 100 }, carAvgConsumptionKmPerL = null,
    startLat = null, startLon = null, endLat = null, endLon = null,
)

private fun meta(id: String, start: Long, s: TripSummary, log: TripLog = TripLog(), car: String = "AA:01") =
    TripMeta(id, car, "car", start, start + s.durationMs, s, log)

class MileageLogTest {
    private val trips = listOf(
        meta("a", ms("2026-10-31"), summary(10.0, 1_000.0), TripLog(TripKind.BUSINESS, "Client", "Warszawa – Radom", "Jan Kowalski")),
        meta("b", ms("2026-11-02"), summary(20.0, 1_010.0), TripLog(TripKind.BUSINESS, "Client; meeting", "Radom – Warszawa", "Jan")),
        meta("c", ms("2026-11-05"), summary(5.5, 1_030.0), TripLog(TripKind.PRIVATE)),
        meta("d", ms("2026-11-20"), summary(7.0, 1_036.0)),
        meta("e", ms("2026-11-21"), summary(99.0, 500.0), car = "BB:02"),
    )

    @Test
    fun aMonthOfOneCarOldestFirstAndNumbered() {
        val log = MileageLog.build("aa:01", trips, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"), zone = UTC)
        assertEquals(listOf(1, 2, 3), log.entries.map { it.number })
        assertEquals(listOf("Radom – Warszawa", null, null), log.entries.map { it.route })
        assertEquals(32.5, log.totalKm, 1e-9)
        assertEquals(20.0, log.businessKm, 1e-9)
        assertEquals(12.5, log.privateKm, 1e-9)                         // the unmarked trip counts as private
        assertEquals(listOf(TripKind.BUSINESS, TripKind.PRIVATE, TripKind.PRIVATE), log.entries.map { it.kind })
        assertEquals(listOf(5.5, 7.0), MileageLog.build("AA:01", trips, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"), MileageLog.Filter.PRIVATE, UTC).entries.map { it.km })
        assertEquals(1_010.0, log.startOdometerKm!!, 1e-9)
        assertEquals(1_043.0, log.endOdometerKm!!, 1e-9)

        val business = MileageLog.build("AA:01", trips, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-30"), MileageLog.Filter.BUSINESS, UTC)
        assertEquals(listOf(10.0, 20.0), business.entries.map { it.km })
        assertEquals(1_000.0, business.startOdometerKm!!, 1e-9)          // the period's odometer, whatever the filter
    }

    @Test
    fun csvQuotesWhatNeedsItAndUsesTheLanguagesNumbers() {
        val log = MileageLog.build("AA:01", trips, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"), zone = UTC)
        val pl = MileageLog.csv(log, "Mini", "WX 12345", "pl")
        assertTrue(pl, pl.contains("1;2026-11-02;"))
        assertTrue(pl, pl.contains("\"Client; meeting\""))
        assertTrue(pl, pl.contains(";20,0;"))
        val en = MileageLog.csv(log, "Mini", null, "en")
        assertTrue(en, en.contains("1,2026-11-02,"))
        assertTrue(en, en.contains(",20.0,"))
    }

    @Test
    fun theLogIsStoredWithTheTripAndCanBeChanged() {
        val dir = Files.createTempDirectory("trips").toFile()
        try {
            val store = FileTripStore(dir)
            val trip = Trip("20261102T100000Z-AA01", "AA:01", "car", 0, 600_000, emptyList(), emptyList(), CarValues.EMPTY, CarValues.EMPTY)
            store.save(trip)
            assertTrue(store.list().single().log.isEmpty)
            store.updateLog(trip.id, TripLog(TripKind.BUSINESS, "  Client ", " ", "Jan"))
            assertEquals(TripLog(TripKind.BUSINESS, "Client", null, "Jan"), store.list().single().log)
            assertEquals(TripKind.BUSINESS, store.load(trip.id)!!.log.kind)
            assertNull(store.updateLog("nope", TripLog()))
        } finally {
            dir.deleteRecursively()
        }
    }
}

class FuelLogTest {
    private lateinit var dir: File
    @Before fun setUp() { dir = Files.createTempDirectory("fuel").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test
    fun findsRefuelsBetweenAndDuringTripsButNotOnesAlreadyLogged() {
        val trips = listOf(
            meta("t1", ms("2026-11-01"), summary(30.0, 1_000.0, startFuel = 40.0, endFuel = 12.0)),
            meta("t2", ms("2026-11-03"), summary(20.0, 1_030.0, startFuel = 50.0, endFuel = 48.0)),   // +38 L while off
            meta("t3", ms("2026-11-05"), summary(20.0, 1_050.0, startFuel = 48.5, endFuel = 46.0)),   // 0.5 L: sloshing
            meta("t4", ms("2026-11-07"), summary(80.0, 1_070.0, startFuel = 46.0, endFuel = 52.0, refueled = 15.0)),
        )
        val found = FuelLog.detect("AA:01", trips, emptyList(), emptySet(), 0)
        assertEquals(listOf("in:t4", "gap:t2"), found.map { it.key })
        assertEquals(38.0, found[1].liters, 1e-9)
        assertEquals(1_030.0, found[1].odometerKm!!, 1e-9)

        val logged = listOf(FuelEntry("x", ms("2026-11-03", 8), 37.5))            // two hours before the trip
        assertEquals(listOf("in:t4"), FuelLog.detect("AA:01", trips, logged, emptySet(), 0).map { it.key })
        assertTrue(FuelLog.detect("AA:01", trips, emptyList(), setOf("in:t4", "gap:t2"), 0).isEmpty())
        assertTrue(FuelLog.detect("AA:01", trips, emptyList(), emptySet(), ms("2026-11-08")).isEmpty())
    }

    @Test
    fun tankToTankCountsPartialFillsAndSkipsEntriesWithoutOdometer() {
        val entries = listOf(
            FuelEntry("1", 1, 40.0, 240.0, 10_000.0),
            FuelEntry("2", 2, 10.0, 60.0, 10_200.0, fullTank = false),
            FuelEntry("3", 3, 25.0, 150.0, 10_500.0),
            FuelEntry("4", 4, 30.0, 180.0, null),
            FuelEntry("5", 5, 30.0, 180.0, 11_000.0),
        )
        val intervals = FuelLog.intervals(entries)
        assertEquals(2, intervals.size)
        assertEquals(7.0, intervals[0].lPer100Km, 1e-9)                          // 35 L over 500 km
        assertEquals(6.0, intervals[1].lPer100Km, 1e-9)
        assertEquals(6.5, FuelLog.average(intervals)!!, 1e-9)
        assertEquals(6.0, entries[0].pricePerLiter!!, 1e-9)
        assertNull(FuelLog.average(emptyList()))
        assertEquals(0.5, FuelLog.perKm(50.0, 100.0)!!, 1e-9)
    }

    @Test
    fun theStoreKeepsCarsApartAndMovesThem() {
        val store = FileFuelStore(File(dir, "fuel.json"))
        val e = FuelEntry("1", 5, 40.0, 240.0, 10_000.0, fullTank = false, note = "Orlen", detectedKey = "gap:t2")
        store.save("AA:01", e)
        store.save("AA:01", FuelEntry("2", 9, 10.0))
        store.save("BB:02", FuelEntry("3", 7, 20.0))
        assertEquals(listOf("2", "1"), FileFuelStore(File(dir, "fuel.json")).list("aa:01").map { it.id })
        assertEquals(e, store.list("AA:01")[1])
        store.delete("AA:01", "2")
        store.moveCar("AA:01", "CC:03")
        assertEquals(listOf("1"), store.list("CC:03").map { it.id })
        store.removeCar("BB:02")
        assertTrue(store.list("BB:02").isEmpty())
    }
}

class MonthlyStatsTest {
    @Test
    fun monthsOldestFirstWithCostsAndComparison() {
        val trips = listOf(
            meta("a", ms("2026-10-10"), summary(100.0, fuelUsed = 7.0)),
            meta("b", ms("2026-11-03"), summary(200.0, fuelUsed = 12.0)),
            meta("c", ms("2026-11-04"), summary(50.0)),                                // no fuel: still under half
        )
        val fuel = listOf(FuelEntry("f", ms("2026-11-05"), 40.0, 250.0))
        val events = listOf(CarEvent("e", EventType.REPAIR, LocalDate.parse("2026-11-20"), "Brakes", cost = 150.0))
        val months = MonthlyStats.of(trips, fuel, events, YearMonth.of(2026, 11), 3, zone = UTC)
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11)), months.map { it.month })
        assertEquals(0, months[0].trips)
        val nov = months[2]
        assertEquals(250.0, nov.km, 1e-9)
        assertEquals(12.0, nov.fuelL!!, 1e-9)
        assertEquals(4.8, nov.lPer100Km!!, 1e-9)
        assertEquals(400.0, nov.cost, 1e-9)
        assertEquals(1.6, nov.costPerKm!!, 1e-9)
        assertEquals(1.5, MonthlyStats.change(nov.km, months[1].km)!!, 1e-9)
        assertNull(MonthlyStats.change(1.0, 0.0))
    }
}

class KmByKindTest {
    @Test
    fun businessAndPrivateOfOneMonth() {
        val trips = listOf(
            meta("a", ms("2026-11-03"), summary(120.0), TripLog(kind = TripKind.BUSINESS)),
            meta("b", ms("2026-11-04"), summary(30.0), TripLog(kind = TripKind.PRIVATE)),
            meta("c", ms("2026-11-05"), summary(10.0)),                                      // unmarked: private
            meta("d", ms("2026-10-31"), summary(500.0), TripLog(kind = TripKind.BUSINESS)),  // another month
        )
        val km = MonthlyStats.kmByKind(trips, YearMonth.of(2026, 11), UTC)
        assertEquals(120.0, km.getValue(TripKind.BUSINESS), 1e-9)
        assertEquals(40.0, km.getValue(TripKind.PRIVATE), 1e-9)
        assertEquals(0.0, MonthlyStats.kmByKind(emptyList(), YearMonth.of(2026, 11), UTC).getValue(TripKind.BUSINESS), 0.0)
    }
}
