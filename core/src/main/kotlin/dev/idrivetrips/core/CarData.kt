package dev.idrivetrips.core

import dev.idrivetrips.carapi.CarValues

/**
 * One reading of everything the app knows about the car: the neutral values a car plugin delivers
 * ([CarValues], module car-api). The app's own name for them.
 */
typealias CarSnapshot = CarValues

/**
 * The latest car values from the car's plugin, thread-safe: the plugin connection writes, the trip recorder and the
 * screens read. Every delivery replaces the whole reading (a plugin always sends everything it knows).
 */
class CarState(private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile private var values: CarValues = CarValues.EMPTY

    /** Wall-clock time of the last delivery, 0 if nothing has arrived since the last reset. */
    @Volatile var lastUpdateMillis: Long = 0L
        private set

    /** Takes a reading from a plugin; implausible values are dropped first ([CarValues.checked]). */
    fun receive(reading: CarValues) {
        values = reading.checked()
        lastUpdateMillis = clock()
    }

    fun snapshot(): CarSnapshot = values

    fun reset() {
        values = CarValues.EMPTY
        lastUpdateMillis = 0L
    }
}
