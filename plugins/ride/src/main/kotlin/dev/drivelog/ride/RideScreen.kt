package dev.drivelog.ride

import dev.drivelog.carapi.CarValues
import java.util.Locale

/** One view of the vehicle app's screen, as the accessibility service sees it: its id without the package, its text. */
data class ScreenNode(val id: String?, val text: String?, val children: List<ScreenNode> = emptyList()) {
    /** This node and everything below it, depth first. */
    fun all(): Sequence<ScreenNode> = sequenceOf(this) + children.asSequence().flatMap { it.all() }

    fun find(id: String): ScreenNode? = all().firstOrNull { it.id == id }

    companion object {
        /** "com.example.app:id/fuel_level" → "fuel_level"; null for views without an id. */
        fun shortId(full: String?): String? = full?.substringAfter(":id/")?.takeIf { it.isNotEmpty() }
    }
}

/** What one look at the screen gave: the values it shows, and when the vehicle sent them ([atMs], estimated). */
data class RideReading(val values: CarValues, val atMs: Long)

/**
 * Reads the vehicle app's status screens. The main screen has the fuel level and the range; the vehicle's details
 * (opened by tapping the vehicle) add the odometer and the tyre pressures. Both say when the vehicle last sent them
 * ("Updated: 2 weeks ago"). It goes by the views' ids, not by their labels, so the app's language does not matter
 * for the values; only the time text is read in English and Polish.
 *
 * Nothing else on the screen is read: the app also shows the weather for where the phone is, and that stays out.
 */
object RideScreen {
    /** The vehicle's picture on the main screen: tapping it opens the details (odometer, tyres). */
    const val VEHICLE_IMAGE_ID = "status_bike_image"

    /** True on the details: the odometer tile is there. */
    fun showsDetails(root: ScreenNode): Boolean = tileText(root, "total_mileage_tile") != null


    /** Null when the screen shows none of the vehicle's values (another screen of the app). */
    fun read(root: ScreenNode, nowMs: Long, tankLiters: Double?): RideReading? {
        val fuel = statusValue(root, "fuel_level")
        val range = statusValue(root, "remaining_range")
        val fuelLiters = fuel?.let { (number, unit) ->
            when (unit.lowercase(Locale.ROOT)) {
                "%" -> tankLiters?.takeIf { it > 0 }?.let { number / 100.0 * it }
                "l" -> number
                else -> null
            }
        }
        val values = CarValues(
            odometerKm = tileText(root, "total_mileage_tile")?.let { distanceKm(it) },
            fuelLiters = fuelLiters,
            rangeKm = range?.let { (number, unit) -> if (unit.equals("mi", ignoreCase = true)) number * KM_PER_MILE else number },
            tyrePressuresBar = tyres(root),
        ).checked()
        if (values == CarValues.EMPTY) return null
        val updated = (root.find("toolbar_subtitle")?.text ?: root.find("connection_status")?.text)
        return RideReading(values, updated?.let { UpdatedAgo.parse(it, nowMs) } ?: nowMs)
    }

    /** The main screen's number and unit pair, "42" + "%", "74" + "km". */
    private fun statusValue(root: ScreenNode, id: String): Pair<Double, String>? {
        val box = root.find(id) ?: return null
        val number = box.find("status_motor_info_text")?.text?.let { number(it) } ?: return null
        val unit = box.find("status_motor_more_info_text")?.text?.trim().orEmpty()
        return number to unit
    }

    private fun tileText(root: ScreenNode, id: String): String? = root.find(id)?.find("value_content_row1")?.text

    /** Front and rear; only when both are known ("--" until the vehicle has measured them). */
    private fun tyres(root: ScreenNode): List<Double>? {
        val front = tileText(root, "current_tire_pressure_front_tile")?.let { pressureBar(it) } ?: return null
        val rear = tileText(root, "current_tire_pressure_rear_tile")?.let { pressureBar(it) } ?: return null
        return listOf(front, rear)
    }

    /** "9 667 km", "9,667 km", "6 007 mi" → km. Whole numbers: every separator is a thousands separator. */
    fun distanceKm(text: String): Double? {
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty() || digits.length > 8) return null
        val n = digits.toDouble()
        return if (Regex("\\bmi\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) n * KM_PER_MILE else n
    }

    /** "2,5 bar", "2.9 bar", "36 psi", "250 kPa" → bar; null for "--". */
    fun pressureBar(text: String): Double? {
        val n = number(text) ?: return null
        val t = text.lowercase(Locale.ROOT)
        return when {
            "psi" in t -> n * BAR_PER_PSI
            "kpa" in t -> n / 100.0
            else -> n
        }
    }

    /** The first number in [text], with a decimal comma or point. */
    private fun number(text: String): Double? =
        Regex("\\d+(?:[.,]\\d+)?").find(text)?.value?.replace(',', '.')?.toDoubleOrNull()

    private const val KM_PER_MILE = 1.609344
    private const val BAR_PER_PSI = 0.0689476
}

/**
 * "Updated: 2 weeks ago" / "Zaktualizowano: 2 tygodnie temu" → the time it means. Coarse by nature (the app rounds),
 * which is enough to tell a reading from the end of today's ride from one weeks old. Null when the text is not one of
 * these (then the caller takes "now").
 */
object UpdatedAgo {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    // weeks before days: "tydzień" contains "dzie"
    private val units = listOf(
        Regex("sek|sec") to 1_000L,
        Regex("min") to MINUTE,
        Regex("godz|hour|\\bh\\b") to HOUR,
        Regex("tydz|tygod|week") to 7 * DAY,
        Regex("dzie|dni|day") to DAY,
        Regex("mies|month") to 30 * DAY,
        Regex("rok|lat|year") to 365 * DAY,
    )

    fun parse(text: String, nowMs: Long): Long? {
        val t = text.lowercase(Locale.ROOT)
        if (Regex("przed chwil|teraz|just now|now\\b|moment").containsMatchIn(t)) return nowMs
        if ("wczoraj" in t || "yesterday" in t) return nowMs - DAY
        if (!(" temu" in t || " ago" in t)) return null
        val ago = t.substringAfterLast(':')
        val unit = units.firstOrNull { (r, _) -> r.containsMatchIn(ago) }?.second ?: return null
        val count = Regex("\\d+").find(ago)?.value?.toLongOrNull() ?: 1L          // "godzinę temu", "an hour ago"
        return nowMs - count * unit
    }
}

/** The plugin's memory of the vehicle: the readings of both screens put together. */
object RideReadings {
    /** [new]'s values over [old]'s: the main screen has no odometer, the details no range. */
    fun merge(old: RideReading?, new: RideReading): RideReading {
        if (old == null) return new
        val n = new.values
        val o = old.values
        return RideReading(
            o.copy(
                odometerKm = n.odometerKm ?: o.odometerKm,
                fuelLiters = n.fuelLiters ?: o.fuelLiters,
                rangeKm = n.rangeKm ?: o.rangeKm,
                tyrePressuresBar = n.tyrePressuresBar ?: o.tyrePressuresBar,
            ),
            maxOf(old.atMs, new.atMs),
        )
    }

    /** True when [new] shows a value [old] did not have, or a different one: only then is it worth sending. */
    fun isNew(old: RideReading?, new: RideReading): Boolean {
        if (old == null) return true
        val n = new.values
        val o = old.values
        return (n.odometerKm != null && n.odometerKm != o.odometerKm) ||
            (n.fuelLiters != null && n.fuelLiters != o.fuelLiters) ||
            (n.rangeKm != null && n.rangeKm != o.rangeKm) ||
            (n.tyrePressuresBar != null && n.tyrePressuresBar != o.tyrePressuresBar)
    }
}
