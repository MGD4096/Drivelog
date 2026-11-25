package dev.drivelog.carapi

/**
 * The car values a plugin hands to Drivelog, in a neutral form: no brand's names or formats. A plugin turns whatever
 * its car reports into this; Drivelog only ever sees this.
 *
 * Null means "unknown / not valid right now" (for example the ignition is off). Units: km, litres, km per litre,
 * degrees Celsius, whatever units the car's display uses.
 */
data class CarValues(
    /** Odometer reading, km. */
    val odometerKm: Double? = null,
    /** Fuel in the tank, litres. */
    val fuelLiters: Double? = null,
    /** Range the car shows, km. */
    val rangeKm: Double? = null,
    /** The trip computer's average consumption, converted to km per litre. */
    val avgConsumptionKmPerL: Double? = null,
    /** Outside temperature, °C. */
    val outsideC: Double? = null,
    /** Inside temperature, °C. */
    val insideC: Double? = null,
    /**
     * Doors, windows, roof, tailgate and bonnet that are not closed, as labels of [PART_LABELS], each optionally
     * followed by [TILTED] (a roof or window only raised at the back). Empty = everything closed. Null = the car sent no
     * valid state.
     */
    val openParts: List<String>? = null,
    /** Charge of the 12 V battery, %. */
    val batteryPercent: Double? = null,
    /** True while the car warns about tyre pressure, false when it reports no warning. */
    val tyrePressureWarning: Boolean? = null,
    /** Speed the car measures, km/h (0 = standing). */
    val speedKmh: Double? = null,
    /** The trip computer's average speed, km/h. */
    val avgSpeedKmh: Double? = null,
    /**
     * Fuel burnt so far, litres, as a running total that only grows while the plugin is connected (it may start again
     * from 0 when the plugin restarts). Drivelog uses the growth during a trip, never the number itself.
     */
    val fuelUsedTotalL: Double? = null,
) {
    /**
     * The same values with everything implausible dropped (another app sends them, so they are not trusted): numbers
     * outside the ranges below, and open-part labels that are not in [PART_LABELS].
     */
    fun checked(): CarValues = CarValues(
        odometerKm = odometerKm.within(0.0, 10_000_000.0),
        fuelLiters = fuelLiters.within(0.0, 300.0),
        rangeKm = rangeKm.within(0.0, 3_000.0),
        avgConsumptionKmPerL = avgConsumptionKmPerL?.takeIf { it in 1.0..99.0 },
        outsideC = outsideC?.takeIf { it > -60 && it < 90 },
        insideC = insideC?.takeIf { it > -60 && it < 90 },
        openParts = openParts?.filter { isPartLabel(it) }?.distinct()?.take(PART_LABELS.size),
        batteryPercent = batteryPercent.within(0.0, 100.0),
        tyrePressureWarning = tyrePressureWarning,
        speedKmh = speedKmh?.takeIf { !it.isNaN() && it >= 0.0 && it <= 400.0 },
        avgSpeedKmh = avgSpeedKmh.within(0.0, 400.0),
        fuelUsedTotalL = fuelUsedTotalL?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0.0 && it < 1_000_000.0 },
    )

    companion object {
        val EMPTY = CarValues()

        /** Every part a plugin may report open. Drivelog draws and names exactly these. */
        val PART_LABELS: List<String> = listOf(
            "driver door", "passenger door", "rear driver door", "rear passenger door",
            "driver window", "passenger window", "rear driver window", "rear passenger window",
            "sunroof", "tailgate", "bonnet",
        )
        const val TILTED = " (tilted)"

        fun isPartLabel(label: String): Boolean = label.removeSuffix(TILTED) in PART_LABELS

        /** Above zero and at most [max]; NaN, infinity and zero are "unknown". */
        private fun Double?.within(min: Double, max: Double): Double? =
            this?.takeIf { !it.isNaN() && !it.isInfinite() && it > min && it <= max }

        // ---- JSON, for Drivelog's own storage and the tests; the plugin link uses a Bundle (plugin-api) ----------

        fun toJson(v: CarValues): String = Json.stringify(
            linkedMapOf<String, Any?>(
                "odometerKm" to v.odometerKm, "fuelLiters" to v.fuelLiters, "rangeKm" to v.rangeKm,
                "avgConsumptionKmPerL" to v.avgConsumptionKmPerL, "outsideC" to v.outsideC, "insideC" to v.insideC,
                "openParts" to v.openParts, "batteryPercent" to v.batteryPercent, "tyrePressureWarning" to v.tyrePressureWarning,
                "speedKmh" to v.speedKmh, "avgSpeedKmh" to v.avgSpeedKmh, "fuelUsedTotalL" to v.fuelUsedTotalL,
            ).filterValues { it != null }
        )

        /** Null when the text is not a JSON object. Unknown keys are ignored, wrong types count as unknown values. */
        fun fromJson(text: String): CarValues? {
            val o = try { Json.parseObject(text) } catch (e: Json.JsonException) { null } ?: return null
            fun num(key: String) = o[key] as? Double
            return CarValues(
                odometerKm = num("odometerKm"), fuelLiters = num("fuelLiters"), rangeKm = num("rangeKm"),
                avgConsumptionKmPerL = num("avgConsumptionKmPerL"), outsideC = num("outsideC"), insideC = num("insideC"),
                openParts = (o["openParts"] as? List<*>)?.filterIsInstance<String>(),
                batteryPercent = num("batteryPercent"), tyrePressureWarning = o["tyrePressureWarning"] as? Boolean,
                speedKmh = num("speedKmh"), avgSpeedKmh = num("avgSpeedKmh"), fuelUsedTotalL = num("fuelUsedTotalL"),
            )
        }
    }
}
