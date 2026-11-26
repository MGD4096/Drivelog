package dev.drivelog.core

/** The speed shown while a trip is recorded: the car's own while it is fresh, otherwise the phone's GPS while that is. */
object LiveSpeed {
    /** How long a speed stays good to show. */
    const val FRESH_MS = 10_000L

    /**
     * [carKmh] arrived at [carAtMs] (0: never), [gpsKmh] with the fix at [fixAtMs] (0: never). Null when neither is
     * fresh at [nowMs].
     */
    fun pick(carKmh: Double?, carAtMs: Long, gpsKmh: Double?, fixAtMs: Long, nowMs: Long): Double? =
        carKmh?.takeIf { carAtMs > 0 && nowMs - carAtMs < FRESH_MS }
            ?: gpsKmh?.takeIf { fixAtMs > 0 && nowMs - fixAtMs < FRESH_MS }
}
