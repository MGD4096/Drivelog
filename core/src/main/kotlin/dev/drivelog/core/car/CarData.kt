package dev.drivelog.core.car

import dev.drivelog.carapi.CarValues

/**
 * One reading of everything the app knows about the car: the neutral values a car plugin delivers
 * ([CarValues], module car-api). The app's own name for them.
 */
typealias CarSnapshot = CarValues

/**
 * What the car's own speed and fuel counter say about the whole trip so far, added up from every delivery (the trip
 * recorder only looks every few seconds). Null / 0 when the plugin does not send speed or the fuel counter.
 */
data class DriveTotals(
    val maxSpeedKmh: Double? = null,
    /** Time with the car's speed at [MOVING_KMH] or more. */
    val movingMs: Long = 0L,
    /** Fuel burnt according to the car's fuel counter, litres; null when it sent no counter. */
    val counterFuelL: Double? = null,
) {
    companion object {
        val NONE = DriveTotals()
        const val MOVING_KMH = 3.0
        /** A delivery more than this after the previous one does not count the gap as moving (a lost connection). */
        const val MAX_GAP_MS = 15_000L
        /** More fuel than this between two deliveries is a restarted plugin, not fuel burnt. */
        const val MAX_FUEL_STEP_L = 1.0
    }
}

/**
 * The latest car values from the car's plugin, thread-safe: the plugin connection writes, the trip recorder and the
 * screens read. Every delivery replaces the whole reading (a plugin always sends everything it knows), and adds to
 * the [totals] of the drive.
 */
class CarState(private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile private var values: CarValues = CarValues.EMPTY

    /** Wall-clock time of the last delivery, 0 if nothing has arrived since the last reset. */
    @Volatile var lastUpdateMillis: Long = 0L
        private set

    private var totals = DriveTotals.NONE
    private var prevSpeed: Double? = null
    private var prevFuelTotal: Double? = null

    /** Takes a reading from a plugin; implausible values are dropped first ([CarValues.checked]). */
    @Synchronized
    fun receive(reading: CarValues) {
        val v = reading.checked()
        val now = clock()
        val before = lastUpdateMillis
        var t = totals
        val speed = v.speedKmh
        if (speed != null) t = t.copy(maxSpeedKmh = maxOf(t.maxSpeedKmh ?: 0.0, speed))
        val gap = now - before
        if (before > 0 && gap in 1..DriveTotals.MAX_GAP_MS && (prevSpeed ?: 0.0) >= DriveTotals.MOVING_KMH) {
            t = t.copy(movingMs = t.movingMs + gap)
        }
        val fuel = v.fuelUsedTotalL
        if (fuel != null) {
            val prev = prevFuelTotal
            val step = if (prev == null) 0.0 else fuel - prev
            t = t.copy(counterFuelL = (t.counterFuelL ?: 0.0) + if (step in 0.0..DriveTotals.MAX_FUEL_STEP_L) step else 0.0)
        }
        prevSpeed = speed
        prevFuelTotal = fuel ?: prevFuelTotal
        totals = t
        values = v
        lastUpdateMillis = now
    }

    fun snapshot(): CarSnapshot = values

    /** What the car's speed and fuel counter added up to since the last [reset]. */
    @Synchronized
    fun totals(): DriveTotals = totals

    @Synchronized
    fun reset() {
        values = CarValues.EMPTY
        lastUpdateMillis = 0L
        totals = DriveTotals.NONE
        prevSpeed = null
        prevFuelTotal = null
    }
}
