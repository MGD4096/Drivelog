package dev.idrivetrips.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

private fun ev(
    type: EventType, date: String, due: String? = null, nextKm: Double? = null, cost: Double? = null,
    title: String? = null, id: String = EventType::class.java.simpleName + date + type,
) = CarEvent(id = id, type = type, date = LocalDate.parse(date), title = title, dueDate = due?.let { LocalDate.parse(it) }, nextAtKm = nextKm, cost = cost)

class RemindersTest {
    private val today = LocalDate.parse("2026-11-05")

    @Test
    fun levelsFollowTheDueDate() {
        val events = listOf(
            ev(EventType.INSURANCE, "2025-11-20", due = "2026-11-19"),        // 14 days left: soon
            ev(EventType.INSPECTION, "2025-01-10", due = "2026-01-10"),       // passed: overdue
        )
        val up = Reminders.upcoming(events, today, null)
        assertEquals(listOf(EventType.INSPECTION, EventType.INSURANCE), up.map { it.type })
        assertEquals(DueLevel.OVERDUE, up[0].level)
        assertEquals(-299L, up[0].daysLeft)
        assertEquals(DueLevel.SOON, up[1].level)
        assertEquals(14L, up[1].daysLeft)
    }

    @Test
    fun dueTodayIsStillSoonNotOverdue() {
        val up = Reminders.upcoming(listOf(ev(EventType.INSURANCE, "2025-11-05", due = "2026-11-05")), today, null)
        assertEquals(DueLevel.SOON, up.single().level)
        assertEquals(0L, up.single().daysLeft)
    }

    @Test
    fun farAwayIsOk() {
        val up = Reminders.upcoming(listOf(ev(EventType.INSPECTION, "2026-10-01", due = "2027-10-01")), today, null)
        assertEquals(DueLevel.OK, up.single().level)
    }

    @Test
    fun theLatestEntryOfAKindDecides() {
        val events = listOf(
            ev(EventType.INSURANCE, "2024-11-20", due = "2025-11-19"),         // old, expired
            ev(EventType.INSURANCE, "2025-11-20", due = "2027-11-19"),         // renewed
        )
        val up = Reminders.upcoming(events, today, null)
        assertEquals(1, up.size)
        assertEquals(DueLevel.OK, up.single().level)
    }

    @Test
    fun oilIsJudgedByKilometresWhenTheOdometerIsKnown() {
        val oil = ev(EventType.OIL_CHANGE, "2026-05-01", nextKm = 150_000.0)
        assertEquals(DueLevel.OK, Reminders.upcoming(listOf(oil), today, 140_000.0).single().level)
        val soon = Reminders.upcoming(listOf(oil), today, 149_000.0).single()
        assertEquals(DueLevel.SOON, soon.level)
        assertEquals(1000.0, soon.kmLeft!!, 1e-9)
        assertEquals(DueLevel.OVERDUE, Reminders.upcoming(listOf(oil), today, 150_500.0).single().level)
    }

    @Test
    fun withoutAnOdometerOnlyDatesCount() {
        val oil = ev(EventType.OIL_CHANGE, "2026-05-01", nextKm = 150_000.0)
        assertTrue("nothing to judge", Reminders.upcoming(listOf(oil), today, null).single().let { it.level == DueLevel.OK && it.kmLeft == null })
        val both = ev(EventType.OIL_CHANGE, "2025-05-01", due = "2026-05-01", nextKm = 150_000.0)
        assertEquals(DueLevel.OVERDUE, Reminders.upcoming(listOf(both), today, null).single().level)
    }

    @Test
    fun entriesWithoutALimitAndRepairsAreIgnored() {
        val events = listOf(
            ev(EventType.INSPECTION, "2026-01-01"),                           // no due date
            ev(EventType.REPAIR, "2026-02-01", due = "2026-03-01", title = "Brakes"),
            ev(EventType.OTHER, "2026-02-01", title = "Wash"),
        )
        assertTrue(Reminders.upcoming(events, today, 1000.0).isEmpty())
    }
}

class EventCostsTest {
    @Test
    fun totalsPerYearAndOverall() {
        val events = listOf(
            ev(EventType.REPAIR, "2025-12-31", cost = 100.5, title = "a"),
            ev(EventType.REPAIR, "2026-01-01", cost = 200.0, title = "b"),
            ev(EventType.OIL_CHANGE, "2026-06-01", cost = 350.25),
            ev(EventType.INSPECTION, "2026-07-01"),                           // no cost
        )
        assertEquals(550.25, EventCosts.total(events, 2026), 1e-9)
        assertEquals(100.5, EventCosts.total(events, 2025), 1e-9)
        assertEquals(650.75, EventCosts.total(events), 1e-9)
        assertEquals(0.0, EventCosts.total(emptyList()), 1e-9)
    }
}

class CarEventTitleTest {
    @Test
    fun titleShowsKindAndDescription() {
        assertEquals("Oil change", ev(EventType.OIL_CHANGE, "2026-01-01").displayTitle)
        assertEquals("Oil change: Castrol 5W-30", ev(EventType.OIL_CHANGE, "2026-01-01", title = "Castrol 5W-30").displayTitle)
        assertEquals("Front brakes", ev(EventType.REPAIR, "2026-01-01", title = "Front brakes").displayTitle)
        assertEquals("Repair", ev(EventType.REPAIR, "2026-01-01").displayTitle)
    }

    @Test
    fun idsDiffer() {
        assertEquals(12, CarEvent.newId().length)
        assertFalse(CarEvent.newId() == CarEvent.newId())
    }
}

class FileCarEventsStoreTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("events").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private val full = CarEvent(
        id = "abc", type = EventType.OIL_CHANGE, date = LocalDate.parse("2026-05-01"), title = "5W-30 \"Castrol\" <1 L>",
        dueDate = LocalDate.parse("2027-05-01"), odometerKm = 140_000.0, nextAtKm = 155_000.0, cost = 399.99, note = "Garage in Łódź\nnew filter",
    )

    @Test
    fun savesAndReadsBackEveryField() {
        val f = File(dir, "sub/events.json")
        FileCarEventsStore(f).save("AA:BB", full)
        assertEquals(listOf(full), FileCarEventsStore(f).list("aa:bb"))
    }

    @Test
    fun listIsNewestFirstAndSaveReplacesById() {
        val store = FileCarEventsStore(File(dir, "e.json"))
        store.save("AA", ev(EventType.REPAIR, "2026-01-01", title = "old", id = "1"))
        store.save("AA", ev(EventType.REPAIR, "2026-03-01", title = "new", id = "2"))
        assertEquals(listOf("2", "1"), store.list("AA").map { it.id })
        store.save("AA", ev(EventType.REPAIR, "2026-05-01", title = "edited", id = "1"))
        assertEquals(listOf("1", "2"), store.list("AA").map { it.id })
        assertEquals("edited", store.list("AA").first().title)
        assertEquals(2, store.list("AA").size)
    }

    @Test
    fun carsAreKeptApartAndCanBeRemoved() {
        val store = FileCarEventsStore(File(dir, "e.json"))
        store.save("AA", ev(EventType.REPAIR, "2026-01-01", title = "x", id = "1"))
        store.save("BB", ev(EventType.REPAIR, "2026-01-01", title = "y", id = "2"))
        store.delete("AA", "1")
        assertTrue(store.list("AA").isEmpty())
        assertEquals(1, store.list("BB").size)
        store.removeCar("BB")
        assertTrue(store.list("BB").isEmpty())
    }

    @Test
    fun damagedEntriesAreSkippedAndDamagedFileIsEmpty() {
        val f = File(dir, "e.json")
        f.writeText("""{"aa":[{"id":"1","type":"NOPE","date":"2026-01-01"},{"id":"2","type":"REPAIR","date":"31.12.2026"},
            {"id":"3","type":"REPAIR","date":"2026-01-01","title":"ok"},5,{"type":"REPAIR","date":"2026-01-01"}]}""")
        assertEquals(listOf("3"), FileCarEventsStore(f).list("AA").map { it.id })
        f.writeText("{ broken")
        val store = FileCarEventsStore(f)
        assertTrue(store.list("AA").isEmpty())
        store.save("AA", full)                                           // and it can be written over
        assertEquals(1, store.list("AA").size)
    }

    @Test
    fun noTemporaryFileRemains() {
        FileCarEventsStore(File(dir, "e.json")).save("AA", full)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }
}

class DecimalInputAndYearStatsTest {
    @Test
    fun decimalAcceptsCommaAndSpaces() {
        assertEquals(1234.5, CarInput.parseDecimal("1 234,5", "Cost", 0.0, 1e7).value!!, 1e-9)
        assertEquals(0.0, CarInput.parseDecimal("0", "Cost", 0.0, 1e7).value!!, 1e-9)
        assertNull(CarInput.parseDecimal("  ", "Cost", 0.0, 1e7).value)
        assertTrue(CarInput.parseDecimal("", "Cost", 0.0, 1e7).ok)
    }

    @Test
    fun decimalRejectsNonsense() {
        assertFalse(CarInput.parseDecimal("abc", "Cost", 0.0, 1e7).ok)
        assertFalse(CarInput.parseDecimal("-5", "Cost", 0.0, 1e7).ok)
        assertFalse(CarInput.parseDecimal("1e9", "Cost", 0.0, 1e7).ok)
        assertFalse(CarInput.parseDecimal("NaN", "Cost", 0.0, 1e7).ok)
        assertTrue(CarInput.parseDecimal("x", "Cost", 0.0, 1e7).error!!.startsWith("Cost"))
    }

    private fun trip(carId: String, date: String, km: Double): TripMeta {
        val ms = LocalDate.parse(date).atTime(12, 0).atZone(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()
        return TripMeta(
            id = "t$ms", carId = carId, carName = "c", startMs = ms, endMs = ms + 1000,
            summary = TripSummary(
                1000, km, km, null, 0, null, null, null, null, null, null, null, 0.0, null, null, null, null, null, null,
            ),
        )
    }

    @Test
    fun yearStatsCountOnlyThatYearAndThatCar() {
        val trips = listOf(
            trip("AA", "2025-12-31", 100.0),
            trip("AA", "2026-01-01", 10.0),
            trip("AA", "2026-10-10", 20.0),
            trip("BB", "2026-10-10", 999.0),
        )
        val s = VehicleSummary.of("AA", trips, 2026, ZoneId.of("Europe/Warsaw"))
        assertEquals(3, s.tripCount)
        assertEquals(130.0, s.totalKm, 1e-9)
        assertEquals(2, s.yearTripCount)
        assertEquals(30.0, s.yearKm, 1e-9)
        assertEquals(0, VehicleSummary.of("AA", trips, 2024, ZoneId.of("Europe/Warsaw")).yearTripCount)
    }

    @Test
    fun theYearIsJudgedInTheGivenTimeZone() {
        // 23:30 on 31 Dec in Warsaw is already next year in Tokyo, and still the old one in Warsaw
        val ms = LocalDate.parse("2025-12-31").atTime(23, 30).atZone(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()
        val t = trip("AA", "2025-12-31", 5.0).copy(startMs = ms)
        assertEquals(1, VehicleSummary.of("AA", listOf(t), 2025, ZoneId.of("Europe/Warsaw")).yearTripCount)
        assertEquals(1, VehicleSummary.of("AA", listOf(t), 2026, ZoneId.of("Asia/Tokyo")).yearTripCount)
    }
}

class ReminderPlannerTest {
    private val today = LocalDate.parse("2026-11-05")
    private val settings = ReminderSettings(enabled = true, warnDays = 14, warnKm = 1500.0)

    private fun car(vararg events: CarEvent, odo: Double? = null) = CarForReminders("AA:01", "Mini", events.toList(), odo)

    @Test
    fun announcesEachStageOnce() {
        val insurance = ev(EventType.INSURANCE, "2025-11-19", due = "2026-11-19", id = "ins")        // 14 days left
        val first = ReminderPlanner.plan(listOf(car(insurance)), today, emptySet(), settings)
        assertEquals(1, first.notices.size)
        assertEquals("aa:01|ins|SOON", first.notices.single().key)
        val remembered = first.nextNotified(emptySet())

        val again = ReminderPlanner.plan(listOf(car(insurance)), today.plusDays(3), remembered, settings)
        assertTrue("same stage, no second notification", again.notices.isEmpty())

        val overdue = ReminderPlanner.plan(listOf(car(insurance)), today.plusDays(20), again.nextNotified(remembered), settings)
        assertEquals("aa:01|ins|OVERDUE", overdue.notices.single().key)
    }

    @Test
    fun nothingBeforeTheWarningTime() {
        val insurance = ev(EventType.INSURANCE, "2025-12-01", due = "2026-12-01", id = "ins")          // 26 days
        assertTrue(ReminderPlanner.plan(listOf(car(insurance)), today, emptySet(), settings).notices.isEmpty())
        val wider = ReminderPlanner.plan(listOf(car(insurance)), today, emptySet(), settings.copy(warnDays = 30))
        assertEquals(1, wider.notices.size)
    }

    @Test
    fun renewingStartsOverAndOldKeysArePruned() {
        val old = ev(EventType.INSURANCE, "2025-11-19", due = "2026-11-19", id = "old")
        val remembered = ReminderPlanner.plan(listOf(car(old)), today, emptySet(), settings).let { it.nextNotified(emptySet()) }
        assertEquals(setOf("aa:01|old|SOON"), remembered)

        val renewed = ev(EventType.INSURANCE, "2026-11-10", due = "2027-11-10", id = "new")
        val plan = ReminderPlanner.plan(listOf(car(old, renewed)), today.plusDays(6), remembered, settings)
        assertTrue(plan.notices.isEmpty())
        assertEquals("the old key is forgotten", emptySet<String>(), plan.nextNotified(remembered))
    }

    @Test
    fun disabledMeansNoNoticesButKeysStillTracked() {
        val insurance = ev(EventType.INSURANCE, "2025-11-19", due = "2026-11-19", id = "ins")
        val plan = ReminderPlanner.plan(listOf(car(insurance)), today, emptySet(), settings.copy(enabled = false))
        assertTrue(plan.notices.isEmpty())
    }

    @Test
    fun oilByKilometres() {
        val oil = ev(EventType.OIL_CHANGE, "2026-05-01", nextKm = 150_000.0, id = "oil")
        assertTrue(ReminderPlanner.plan(listOf(car(oil, odo = 140_000.0)), today, emptySet(), settings).notices.isEmpty())
        val plan = ReminderPlanner.plan(listOf(car(oil, odo = 149_000.0)), today, emptySet(), settings)
        assertEquals("aa:01|oil|SOON", plan.notices.single().key)
    }

    @Test
    fun severalCarsAreKeptApart() {
        val a = CarForReminders("AA", "A", listOf(ev(EventType.INSURANCE, "2025-11-10", due = "2026-11-10", id = "x")), null)
        val b = CarForReminders("BB", "B", listOf(ev(EventType.INSURANCE, "2025-11-10", due = "2026-11-10", id = "x")), null)
        val plan = ReminderPlanner.plan(listOf(a, b), today, emptySet(), settings)
        assertEquals(listOf("aa|x|SOON", "bb|x|SOON"), plan.notices.map { it.key })
    }
}

class LastKnownCarTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("last").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun trip(id: String, carId: String, endMs: Long, end: CarSnapshot) = Trip(
        id = id, carId = carId, carName = "c", startMs = endMs - 1000, endMs = endMs,
        points = emptyList(), samples = emptyList(), startCar = CarSnapshot.EMPTY, endCar = end,
    )

    @Test
    fun takesTheLatestTripThatHasAReading() {
        val store = FileTripStore(dir)
        store.save(trip("t1", "AA", 1_000, CarSnapshot(fuelLiters = 20.0, rangeKm = 300.0)))
        store.save(trip("t2", "AA", 2_000, CarSnapshot(fuelLiters = 35.0, rangeKm = 480.0)))
        store.save(trip("t3", "AA", 3_000, CarSnapshot.EMPTY))                      // the car sent nothing this time
        store.save(trip("t4", "BB", 4_000, CarSnapshot(fuelLiters = 1.0, rangeKm = 10.0)))
        val found = LastKnownCar.find("aa", store.list(), store)!!
        assertEquals(35.0, found.snapshot.fuelLiters!!, 1e-9)
        assertEquals(480.0, found.snapshot.rangeKm!!, 1e-9)
        assertEquals(2_000L, found.atMs)
    }

    @Test
    fun doorsAndWindowsComeFromTheLatestTripThatReportedThemIndependentlyOfFuel() {
        val store = FileTripStore(dir)
        store.save(trip("t1", "AA", 1_000, CarSnapshot(fuelLiters = 20.0, rangeKm = 300.0, openParts = listOf("driver window"))))
        store.save(trip("t2", "AA", 2_000, CarSnapshot(fuelLiters = 35.0, rangeKm = 480.0)))              // no door state this time
        store.save(trip("t3", "AA", 3_000, CarSnapshot(openParts = emptyList())))                         // all closed, no fuel
        val found = LastKnownCar.find("AA", store.list(), store)!!
        assertEquals(35.0, found.snapshot.fuelLiters!!, 1e-9)
        assertEquals(2_000L, found.atMs)
        assertEquals(emptyList<String>(), found.openParts)
        assertEquals(3_000L, found.openPartsAtMs)
    }

    @Test
    fun aCarThatOnlyReportedDoorsStillHasAnEntry() {
        val store = FileTripStore(dir)
        store.save(trip("t1", "AA", 1_000, CarSnapshot(openParts = listOf("tailgate"))))
        val found = LastKnownCar.find("AA", store.list(), store)!!
        assertNull(found.snapshot.fuelLiters)
        assertEquals(listOf("tailgate"), found.openParts)
    }

    @Test
    fun wordsForTheDoorState() {
        assertEquals("—", OpenPartsText.describe(null))
        assertEquals("Closed", OpenPartsText.describe(emptyList()))
        assertEquals("Open: driver door, rear window", OpenPartsText.describe(listOf("driver door", "rear window")))
    }

    @Test
    fun theLabelsOfOpenPartsMapBackToParts() {
        val parts = OpenPartsText.parts(listOf("driver door", "rear passenger window", "sunroof (tilted)", "something new"))
        assertEquals(listOf(OpenPart(CarPart.DRIVER_DOOR, false), OpenPart(CarPart.REAR_PASSENGER_WINDOW, false), OpenPart(CarPart.SUNROOF, true)), parts)
        assertTrue(OpenPartsText.parts(null).isEmpty())
        // every label the parser of the car data can produce is known here
        val raw = mapOf(
            "sensors.doors" to """{"doors":{"driver":1,"passenger":1,"driverRear":1,"passengerRear":1}}""",
            "sensors.trunk" to """{"trunk":1}""", "sensors.lid" to """{"lid":1}""",
            "controls.windowDriverFront" to """{"windowDriverFront":{"status":2,"position":50}}""",
            "controls.windowPassengerFront" to """{"windowPassengerFront":{"status":2,"position":50}}""",
            "controls.windowDriverRear" to """{"windowDriverRear":{"status":2,"position":50}}""",
            "controls.windowPassengerRear" to """{"windowPassengerRear":{"status":2,"position":50}}""",
            "controls.sunroof" to """{"sunroof":{"status":2,"position":50}}""",
        )
        val labels = CarSnapshot.fromRaw(raw).openParts!!
        assertEquals(labels.toString(), CarPart.values().size, OpenPartsText.parts(labels).size)
    }

    @Test
    fun nullWhenNothingWasEverReported() {
        val store = FileTripStore(dir)
        store.save(trip("t1", "AA", 1_000, CarSnapshot.EMPTY))
        assertNull(LastKnownCar.find("AA", store.list(), store))
        assertNull(LastKnownCar.find("ZZ", store.list(), store))
    }
}

class RemainingFractionTest {
    private fun due(event: CarEvent, today: LocalDate, odo: Double?) = Reminders.upcoming(listOf(event), today, odo).single()

    @Test
    fun byDate() {
        val e = CarEvent("a", EventType.INSPECTION, LocalDate.of(2026, 1, 1), dueDate = LocalDate.of(2027, 1, 1))
        assertEquals(1.0, Reminders.remainingFraction(due(e, LocalDate.of(2026, 1, 1), null))!!, 1e-9)
        assertEquals(0.5, Reminders.remainingFraction(due(e, LocalDate.of(2026, 7, 2), null))!!, 0.01)
        assertEquals(0.0, Reminders.remainingFraction(due(e, LocalDate.of(2027, 3, 1), null))!!, 1e-9)
    }

    @Test
    fun byKilometresAndTheCloserOneWins() {
        val e = CarEvent("a", EventType.OIL_CHANGE, LocalDate.of(2026, 1, 1), dueDate = LocalDate.of(2027, 1, 1), odometerKm = 10_000.0, nextAtKm = 20_000.0)
        // half a year gone (about half left by date) but 9,000 of 10,000 km driven: 10 % left
        val f = Reminders.remainingFraction(due(e, LocalDate.of(2026, 7, 2), 19_000.0))!!
        assertEquals(0.1, f, 1e-9)
        assertEquals(0.0, Reminders.remainingFraction(due(e, LocalDate.of(2026, 7, 2), 21_000.0))!!, 1e-9)
    }

    @Test
    fun unknownWhenTheEntryDoesNotSayHowLongItLasts() {
        val e = CarEvent("a", EventType.OIL_CHANGE, LocalDate.of(2026, 1, 1), nextAtKm = 20_000.0)
        assertNull(Reminders.remainingFraction(due(e, LocalDate.of(2026, 2, 1), 15_000.0)))
        assertEquals(0.0, Reminders.remainingFraction(due(e, LocalDate.of(2026, 2, 1), 25_000.0))!!, 1e-9)
    }
}
