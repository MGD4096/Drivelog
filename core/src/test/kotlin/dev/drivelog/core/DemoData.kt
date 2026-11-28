package dev.drivelog.core

import dev.drivelog.carapi.CarValues
import dev.drivelog.core.car.CarDetails
import dev.drivelog.core.car.CarEvent
import dev.drivelog.core.car.EventType
import dev.drivelog.core.car.FileCarDetailsStore
import dev.drivelog.core.car.FileCarEventsStore
import dev.drivelog.core.car.FileFuelStore
import dev.drivelog.core.car.FileLastReadingStore
import dev.drivelog.core.car.FuelEntry
import dev.drivelog.core.car.VehicleKind
import dev.drivelog.core.trip.FileTripStore
import dev.drivelog.core.trip.TripKind
import dev.drivelog.core.trip.TripLog
import dev.drivelog.core.trip.TripRecorder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Made-up data for the README's screenshots (taken on an emulator): three vehicles, two months of trips around Warsaw,
 * the service log and refuellings. Nothing in it is anybody's. Runs only on request:
 * `DRIVELOG_DEMO_OUT=<folder> gradlew :core:test --tests dev.drivelog.core.DemoData` writes the app's files into
 * <folder>/files and the followed vehicles into <folder>/cars.json (for the settings' shared preferences).
 */
class DemoData {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val random = Random(42)

    class Vehicle(val address: String, val name: String, val details: CarDetails, val plugin: Boolean)

    private val car = Vehicle("02:00:00:00:00:01", "MX-5 RF", CarDetails(
        model = "Mazda MX-5 RF 2.0", year = 2021, engineCc = 1998, plate = "WX 1234D", vin = "JMZND00000D000001", plugin = "id6"), true)
    private val estate = Vehicle("02:00:00:00:00:02", "V60", CarDetails(
        model = "Volvo V60 B4", year = 2022, engineCc = 1969, plate = "WX 5678D", usesPlugin = false), false)
    private val bike = Vehicle("02:00:00:00:00:03", "MT-07", CarDetails(
        model = "Yamaha MT-07", year = 2023, engineCc = 689, plate = "WX 9D", kind = VehicleKind.MOTORCYCLE, plugin = "ride"), true)

    /** Places in Warsaw the trips go between (public places, not anybody's address). */
    private val places = mapOf(
        "Mokotów" to (52.1935 to 21.0346), "Wola" to (52.2318 to 20.9844), "Old Town" to (52.2497 to 21.0122),
        "Wilanów" to (52.1652 to 21.0899), "Airport" to (52.1702 to 20.9718), "Kampinos" to (52.3290 to 20.7790),
        "Praga" to (52.2510 to 21.0470), "Ursynów" to (52.1421 to 21.0386),
    )

    @Test
    fun write() {
        val out = System.getenv("DRIVELOG_DEMO_OUT")
        assumeTrue("set DRIVELOG_DEMO_OUT to write the demo data", out != null)
        val root = File(out!!)
        root.deleteRecursively()
        val files = File(root, "files").apply { mkdirs() }
        val trips = FileTripStore(File(files, "trips"))
        val details = FileCarDetailsStore(File(files, "cars.json"))
        val readings = FileLastReadingStore(File(files, FileLastReadingStore.FILE_NAME))
        val events = FileCarEventsStore(File(files, "car_events.json"))
        val fuel = FileFuelStore(File(files, FileFuelStore.FILE_NAME))

        val today = LocalDate.now(zone)
        var carOdo = 18_420.0
        var carFuel = 38.0
        var bikeOdo = 4_210.0
        var lastCarEnd: Pair<Long, Pair<Double, Double>>? = null
        var lastBikeEnd: Pair<Long, Pair<Double, Double>>? = null
        val routes = listOf("Mokotów" to "Wola", "Wola" to "Mokotów", "Mokotów" to "Old Town", "Old Town" to "Wilanów",
            "Wilanów" to "Mokotów", "Mokotów" to "Airport", "Airport" to "Praga", "Praga" to "Ursynów", "Ursynów" to "Mokotów")
        var refuelDue = false
        for (day in 300 downTo 0) {
            val date = today.minusDays(day.toLong())
            val weekday = date.dayOfWeek.value <= 5
            val count = if (weekday) 2 else random.nextInt(0, 2)
            for (n in 0 until count) {
                val (from, to) = if (weekday) (if (n == 0) "Mokotów" to "Wola" else "Wola" to "Mokotów") else routes[random.nextInt(routes.size)]
                val start = date.atTime(if (n == 0) 7 + random.nextInt(0, 2) else 17, random.nextInt(0, 59)).atZone(zone).toInstant().toEpochMilli()
                if (refuelDue) {                                       // filled up to the top just before setting off
                    val liters = 44.0 - carFuel
                    fuel.save(car.address, FuelEntry(FuelEntry.newId(), start - 900_000, liters, cost = liters * (6.19 + random.nextDouble() * 0.4),
                        odometerKm = carOdo, fullTank = true))
                    carFuel = 44.0
                    refuelDue = false
                }
                val kind = if (weekday) TripKind.BUSINESS else TripKind.PRIVATE
                val trip = drive(car, start, places.getValue(from), places.getValue(to), carOdo, carFuel,
                    TripLog(kind, if (kind == TripKind.BUSINESS) "Office" else null, "$from – $to", "Alex"))
                trips.save(trip)
                carOdo = trip.endCar.odometerKm!!
                carFuel -= dev.drivelog.core.trip.TripStats.summarize(trip).gpsDistanceKm * 0.075   // exact here, whole litres in the readings
                lastCarEnd = trip.endMs to places.getValue(to)
                if (carFuel < 12) refuelDue = true
            }
            if (!weekday && day in 2 until 200 && random.nextBoolean()) {         // the motorcycle on weekends
                val start = date.atTime(11, 0).atZone(zone).toInstant().toEpochMilli()
                val trip = drive(bike, start, places.getValue("Mokotów"), places.getValue("Kampinos"), bikeOdo, 11.0, TripLog(TripKind.PRIVATE))
                trips.save(trip)
                bikeOdo = trip.endCar.odometerKm!!
                lastBikeEnd = trip.endMs to places.getValue("Kampinos")
            }
        }
        // the estate has no plugin: its odometer was typed in, plus its trips
        val estateStart = today.minusDays(20).atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        trips.save(drive(estate, estateStart, places.getValue("Mokotów"), places.getValue("Kampinos"), null, null, TripLog(TripKind.PRIVATE)))

        val now = System.currentTimeMillis()
        details.put(car.address, car.details.withParked(lastCarEnd!!.second.first, lastCarEnd.second.second, lastCarEnd.first))
        details.put(estate.address, estate.details.withManualOdometer(52_310.0, now - 30L * 86_400_000).withParked(52.1937, 21.0351, estateStart + 3_600_000))
        details.put(bike.address, bike.details.withParked(lastBikeEnd!!.second.first, lastBikeEnd.second.second, lastBikeEnd.first))
        readings.put(car.address, CarValues(odometerKm = carOdo, fuelLiters = carFuel, rangeKm = carFuel * 14.5, avgConsumptionKmPerL = 14.1,
            outsideC = 6.5, insideC = 21.0, openParts = listOf("passenger window (tilted)"), batteryPercent = 86.0, tyrePressureWarning = false), lastCarEnd.first)
        readings.put(bike.address, CarValues(odometerKm = bikeOdo, fuelLiters = 8.2, rangeKm = 160.0, tyrePressuresBar = listOf(2.3, 2.5)), lastBikeEnd.first)

        fun event(address: String, type: EventType, monthsAgo: Long, due: LocalDate?, odo: Double?, next: Double?, cost: Double, title: String? = null) =
            events.save(address, CarEvent(CarEvent.newId(), type, today.minusMonths(monthsAgo), title, due, odo, next, cost))
        event(car.address, EventType.INSPECTION, 11, today.plusDays(23), 9_800.0, null, 120.0)
        event(car.address, EventType.INSURANCE, 2, today.plusMonths(10), 16_900.0, null, 1_240.0, "full cover")
        event(car.address, EventType.OIL_CHANGE, 7, today.plusMonths(5), 12_500.0, carOdo + 600, 690.0, "5W-30")
        event(car.address, EventType.REPAIR, 1, null, 17_900.0, null, 480.0, "Brake pads, front")
        event(estate.address, EventType.INSPECTION, 3, today.plusMonths(9), 49_800.0, null, 150.0)
        event(estate.address, EventType.INSURANCE, 9, today.plusDays(9), 47_000.0, null, 1_580.0)
        event(bike.address, EventType.OIL_CHANGE, 6, today.plusMonths(6), 3_000.0, 6_000.0, 420.0)
        event(bike.address, EventType.INSURANCE, 7, today.plusMonths(5), 2_900.0, null, 610.0)

        File(root, "cars.json").writeText("[" + listOf(car, estate, bike).joinToString(",") { "{\"address\":\"${it.address}\",\"name\":\"${it.name}\"}" } + "]")
    }

    /** A trip from [from] to [to] along a gently winding road, fixes every 4 s at town speeds. */
    private fun drive(v: Vehicle, startMs: Long, from: Pair<Double, Double>, to: Pair<Double, Double>, odo: Double?, fuel: Double?, log: TripLog): dev.drivelog.core.trip.Trip {
        val r = TripRecorder()
        val values = { o: Double?, f: Double? -> if (v.plugin) CarValues(odometerKm = o, fuelLiters = f, batteryPercent = 85.0) else CarValues.EMPTY }
        r.begin(v.address, v.name, startMs, values(odo, fuel))
        val steps = 160 + random.nextInt(0, 140)
        val bend = (random.nextDouble() - 0.5) * 0.02
        var t = startMs
        var km = 0.0
        var lastLat = from.first
        var lastLon = from.second
        for (i in 1..steps) {
            val f = i.toDouble() / steps
            val lat = from.first + (to.first - from.first) * f + sin(f * Math.PI) * bend + sin(i / 9.0) * 0.0004
            val lon = from.second + (to.second - from.second) * f + (cos(f * Math.PI * 2) - 1) * bend * 0.6
            val step = dev.drivelog.core.trip.TripStats.haversineMeters(lastLat, lastLon, lat, lon)
            t += 4_000
            km += step / 1000
            val speed = step / 4.0                                   // what the GPS measures: the distance over the 4 s
            lastLat = lat
            lastLon = lon
            r.onFix(TripRecorder.Fix(t, lat, lon, 100.0, speed, 5.0))
            if (i % 15 == 0) r.onCar(t, values(odo?.plus(km)?.let { Math.round(it).toDouble() }, fuel?.minus(km * 0.072)?.let { Math.round(it).toDouble() }))
        }
        r.onCar(t, values(odo?.plus(km)?.let { Math.round(it).toDouble() }, fuel?.minus(km * 0.072)?.let { Math.round(it).toDouble() }))
        return r.finish(t + 20_000)!!.copy(log = log)
    }
}
