package dev.idrivetrips.core

import java.util.concurrent.ConcurrentHashMap

/** The car-data (CDS) properties the head-unit plugin pushes to the phone. */
object CdsProperty {
    const val ODOMETER = "driving.odometer"
    const val FUEL = "sensors.fuel"
    const val AVG_CONSUMPTION = "driving.averageConsumption"
    const val TEMP_OUTSIDE = "sensors.temperatureExterior"
    const val TEMP_INSIDE = "sensors.temperatureInterior"
    const val DOORS = "sensors.doors"
    const val TRUNK = "sensors.trunk"
    const val LID = "sensors.lid"
    const val SUNROOF = "controls.sunroof"
    const val WINDOW_DRIVER_FRONT = "controls.windowDriverFront"
    const val WINDOW_PASSENGER_FRONT = "controls.windowPassengerFront"
    const val WINDOW_DRIVER_REAR = "controls.windowDriverRear"
    const val WINDOW_PASSENGER_REAR = "controls.windowPassengerRear"

    val ALL: Set<String> = setOf(
        ODOMETER, FUEL, AVG_CONSUMPTION, TEMP_OUTSIDE, TEMP_INSIDE, DOORS, TRUNK, LID, SUNROOF,
        WINDOW_DRIVER_FRONT, WINDOW_PASSENGER_FRONT, WINDOW_DRIVER_REAR, WINDOW_PASSENGER_REAR,
    )
}

/**
 * One reading of everything the app knows about the car. Null means "unknown / not valid right now"
 * (for example the ignition is off). Values arrive in the car's own units: km, litres, degrees C.
 */
data class CarSnapshot(
    val odometerKm: Double? = null,
    val fuelLiters: Double? = null,
    val rangeKm: Double? = null,
    /** The trip computer's average consumption, converted to km per litre. */
    val avgConsumptionKmPerL: Double? = null,
    val outsideC: Double? = null,
    val insideC: Double? = null,
    /** Doors, windows, roof, tailgate and bonnet that are not closed. Null = the car sent no valid state. */
    val openParts: List<String>? = null,
) {
    companion object {
        val EMPTY = CarSnapshot()

        /** Builds a snapshot from the last raw JSON value of every property. Never throws. */
        fun fromRaw(raw: Map<String, String>): CarSnapshot {
            fun obj(name: String): Map<String, Any?>? = raw[name]?.let { Json.parseObject(it) }
            @Suppress("UNCHECKED_CAST")
            fun sub(o: Map<String, Any?>?, key: String): Map<String, Any?>? = o?.get(key) as? Map<String, Any?>
            fun num(v: Any?): Double? = (v as? Double)?.takeIf { !it.isNaN() && !it.isInfinite() }
            fun positive(v: Any?, max: Double): Double? = num(v)?.takeIf { it > 0 && it <= max }

            val fuel = sub(obj(CdsProperty.FUEL), "fuel")
            val parts = openParts(::obj)
            return CarSnapshot(
                odometerKm = positive(obj(CdsProperty.ODOMETER)?.get("odometer"), 10_000_000.0),
                fuelLiters = positive(fuel?.get("tanklevel"), 300.0),
                rangeKm = positive(fuel?.get("range"), 3_000.0),
                avgConsumptionKmPerL = avgConsumption(sub(obj(CdsProperty.AVG_CONSUMPTION), "averageConsumption")),
                outsideC = num(obj(CdsProperty.TEMP_OUTSIDE)?.get("temperatureExterior"))?.takeIf { it > -60 && it < 90 },
                insideC = num(obj(CdsProperty.TEMP_INSIDE)?.get("temperatureInterior"))?.takeIf { it > -60 && it < 90 },
                openParts = parts,
            )
        }

        /**
         * Average consumption in km/l. The car reports a `unit`: 4 = km/l, 1 = l/100 km, 2 = mpg (UK), 3 = mpg (US).
         * `averageConsumption2` is what the instrument cluster of the author's reference car shows; `1` is the fallback.
         */
        private fun avgConsumption(o: Map<String, Any?>?): Double? {
            if (o == null) return null
            fun value(key: String) = (o[key] as? Double)?.takeIf { !it.isNaN() && it > 0 }
            val v = value("averageConsumption2") ?: value("averageConsumption1") ?: return null
            val kmPerL = when ((o["unit"] as? Double)?.toInt() ?: 1) {
                4 -> v
                2 -> v * 0.354006
                3 -> v * 0.425144
                else -> 100.0 / v
            }
            return kmPerL.takeIf { it in 1.0..99.0 }
        }

        private enum class Opening { CLOSED, OPEN, TILTED }

        /** 0 = closed, 1 = open, anything else = not valid. */
        private fun simple(v: Any?): Opening? = when ((v as? Double)?.toInt()) {
            0 -> Opening.CLOSED
            1 -> Opening.OPEN
            else -> null
        }

        /**
         * Windows and sunroof: status 0 closed, 1 partly open, 2 open, 3/255 not valid; a position of 127 or more
         * means "no value" (ignition off). A raised (tilted) sunroof counts as tilted.
         */
        private fun windowLike(o: Map<String, Any?>?): Opening? {
            if (o == null) return null
            val status = (o["status"] as? Double)?.toInt() ?: -1
            val position = ((o["position"] ?: o["openPosition"]) as? Double)?.toInt() ?: 0
            val tilt = (o["tiltPosition"] as? Double)?.toInt() ?: 0
            if (status !in 0..2 || position >= 127 || tilt >= 127) return null
            return when {
                status == 0 && position == 0 && tilt == 0 -> Opening.CLOSED
                tilt > 0 && position == 0 -> Opening.TILTED
                else -> Opening.OPEN
            }
        }

        private fun openParts(obj: (String) -> Map<String, Any?>?): List<String>? {
            val states = LinkedHashMap<String, Opening>()

            @Suppress("UNCHECKED_CAST")
            fun sub(o: Map<String, Any?>?, key: String) = o?.get(key) as? Map<String, Any?>

            windowLike(sub(obj(CdsProperty.SUNROOF), "sunroof"))?.let { states["sunroof"] = it }
            obj(CdsProperty.TRUNK)?.let { o -> simple(o["trunk"])?.let { states["tailgate"] = it } }
            obj(CdsProperty.LID)?.let { o -> simple(o["lid"])?.let { states["bonnet"] = it } }
            sub(obj(CdsProperty.DOORS), "doors")?.let { doors ->
                val names = mapOf(
                    "driver" to "driver door", "driverFront" to "driver door",
                    "passenger" to "passenger door", "passengerFront" to "passenger door",
                    "driverRear" to "rear driver door", "passengerRear" to "rear passenger door",
                )
                for ((key, label) in names) simple(doors[key])?.let { states[label] = it }
            }
            val windows = listOf(
                CdsProperty.WINDOW_DRIVER_FRONT to "driver window",
                CdsProperty.WINDOW_PASSENGER_FRONT to "passenger window",
                CdsProperty.WINDOW_DRIVER_REAR to "rear driver window",
                CdsProperty.WINDOW_PASSENGER_REAR to "rear passenger window",
            )
            for ((property, label) in windows) {
                val key = property.removePrefix("controls.")
                windowLike(sub(obj(property), key))?.let { states[label] = it }
            }
            if (states.isEmpty()) return null
            return states.filterValues { it != Opening.CLOSED }
                .map { (label, s) -> if (s == Opening.TILTED) "$label (tilted)" else label }
        }
    }
}

/** Thread-safe holder of the latest raw value of every car property; written by the HTTP server threads. */
class CarState(private val clock: () -> Long = System::currentTimeMillis) {

    enum class Result { STORED, IGNORED_BAD_VALUE, UNKNOWN_PROPERTY }

    private val raw = ConcurrentHashMap<String, String>()

    /** Wall-clock time of the last accepted value, 0 if nothing has arrived since the last reset. */
    @Volatile var lastUpdateMillis: Long = 0L
        private set

    fun receive(name: String, value: String): Result {
        if (name !in CdsProperty.ALL) return Result.UNKNOWN_PROPERTY
        if (value.isEmpty() || value.length > MAX_VALUE_CHARS || Json.parseObject(value) == null) {
            return Result.IGNORED_BAD_VALUE
        }
        raw[name] = value
        lastUpdateMillis = clock()
        return Result.STORED
    }

    fun snapshot(): CarSnapshot = CarSnapshot.fromRaw(HashMap(raw))

    fun reset() {
        raw.clear()
        lastUpdateMillis = 0L
    }

    companion object {
        /** The plugin never sends more than 399 characters per value. */
        const val MAX_VALUE_CHARS = 400
    }
}
