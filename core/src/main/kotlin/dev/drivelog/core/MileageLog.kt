package dev.drivelog.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Business or private, for the mileage log. */
enum class TripKind(private val english: String) {
    BUSINESS("Business"),
    PRIVATE("Private");

    val label: String get() = tr(english)

    companion object {
        fun parse(s: Any?): TripKind? = values().firstOrNull { it.name == s }
    }
}

/**
 * What the mileage log needs beyond the recording: what kind of trip it was, why, where (the route in words, for
 * example "Warszawa – Radom") and who drove. Everything is optional and typed in by the person.
 */
data class TripLog(
    val kind: TripKind? = null,
    val purpose: String? = null,
    val route: String? = null,
    val driver: String? = null,
) {
    val isEmpty: Boolean get() = this == TripLog()

    /** A trip nobody marked counts as private ([DEFAULT_KIND]). */
    val kindOrDefault: TripKind get() = kind ?: DEFAULT_KIND

    /** The same with blank texts as null and the texts trimmed. */
    fun cleaned(): TripLog = TripLog(kind, purpose.clean(), route.clean(), driver.clean())

    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "kind" to kind?.name, "purpose" to purpose, "route" to route, "driver" to driver,
    ).filterValues { it != null }

    companion object {
        /** What a trip is when nobody said otherwise. */
        val DEFAULT_KIND = TripKind.PRIVATE

        private fun String?.clean(): String? = this?.trim()?.take(200)?.takeIf { it.isNotEmpty() }

        fun fromMap(any: Any?): TripLog {
            val m = any as? Map<*, *> ?: return TripLog()
            return TripLog(TripKind.parse(m["kind"]), m["purpose"] as? String, m["route"] as? String, m["driver"] as? String).cleaned()
        }
    }
}

/**
 * The mileage log of one car for a period (what a tax office or an employer asks for): numbered entries with the date,
 * purpose, route, kilometres and driver, the odometer at the start and end of the period, and the total.
 */
object MileageLog {

    enum class Filter { ALL, BUSINESS, PRIVATE }

    data class Entry(
        val number: Int,
        val date: LocalDate,
        val startMs: Long,
        val kind: TripKind,
        val purpose: String?,
        val route: String?,
        val km: Double,
        val startOdometerKm: Double?,
        val endOdometerKm: Double?,
        val driver: String?,
    )

    data class Log(
        val from: LocalDate,
        val to: LocalDate,
        val entries: List<Entry>,
        val totalKm: Double,
        val businessKm: Double,
        val privateKm: Double,
        /** The odometer at the start of the first and the end of the last trip of the period, when known. */
        val startOdometerKm: Double?,
        val endOdometerKm: Double?,
    )

    /** The car's trips that started from [from] to [to] (both days included), oldest first, numbered from 1. */
    fun build(
        carId: String,
        trips: List<TripMeta>,
        from: LocalDate,
        to: LocalDate,
        filter: Filter = Filter.ALL,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Log {
        val inPeriod = trips.filter { it.carId.equals(carId, ignoreCase = true) }
            .filter { day(it.startMs, zone) in from..to }
            .sortedBy { it.startMs }
        val chosen = inPeriod.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.BUSINESS -> it.log.kindOrDefault == TripKind.BUSINESS
                Filter.PRIVATE -> it.log.kindOrDefault == TripKind.PRIVATE
            }
        }
        val entries = chosen.mapIndexed { i, m ->
            Entry(
                number = i + 1, date = day(m.startMs, zone), startMs = m.startMs, kind = m.log.kindOrDefault,
                purpose = m.log.purpose, route = m.log.route, km = m.summary.distanceKm,
                startOdometerKm = m.summary.startOdometerKm, endOdometerKm = m.summary.endOdometerKm, driver = m.log.driver,
            )
        }
        return Log(
            from = from, to = to, entries = entries,
            totalKm = entries.sumOf { it.km },
            businessKm = entries.filter { it.kind == TripKind.BUSINESS }.sumOf { it.km },
            privateKm = entries.filter { it.kind == TripKind.PRIVATE }.sumOf { it.km },
            startOdometerKm = inPeriod.firstNotNullOfOrNull { it.summary.startOdometerKm },
            endOdometerKm = inPeriod.lastOrNull { it.summary.endOdometerKm != null }?.summary?.endOdometerKm,
        )
    }

    /**
     * The log as CSV for a spreadsheet: a few heading lines (car, plate, period, odometer), the entries, and the totals.
     * Semicolons and decimal commas in Polish (what a Polish spreadsheet opens directly), commas and points otherwise.
     */
    fun csv(log: Log, car: String, plate: String?, language: String = Lang.code): String {
        val pl = language == "pl"
        val sep = if (pl) ";" else ","
        fun num(v: Double?, decimals: Int) = v?.let {
            val s = String.format(Locale.ROOT, "%.${decimals}f", it)
            if (pl) s.replace('.', ',') else s
        } ?: ""
        fun cell(s: String?): String {
            val v = s ?: ""
            return if (v.contains(sep) || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v
        }
        val sb = StringBuilder()
        fun line(vararg cells: String?) = sb.append(cells.joinToString(sep) { cell(it) }).append("\r\n")
        line(tr("Mileage log"))
        line(tr("Car"), car)
        line(tr("Registration number"), plate ?: "")
        line(tr("Period"), "${log.from} – ${log.to}")
        line(tr("Odometer at the start of the period (km)"), num(log.startOdometerKm, 0))
        line(tr("Odometer at the end of the period (km)"), num(log.endOdometerKm, 0))
        line()
        line(tr("No."), tr("Date"), tr("Type"), tr("Purpose of the trip"), tr("Route (from – to)"), tr("Odometer at start"),
            tr("Odometer at end"), tr("Kilometres"), tr("Driver"))
        for (e in log.entries) {
            line(e.number.toString(), e.date.toString(), e.kind.label, e.purpose, e.route, num(e.startOdometerKm, 0),
                num(e.endOdometerKm, 0), num(e.km, 1), e.driver)
        }
        line()
        line(tr("Total (km)"), num(log.totalKm, 1))
        line(tr("Business (km)"), num(log.businessKm, 1))
        line(tr("Private (km)"), num(log.privateKm, 1))
        return sb.toString()
    }

    private fun day(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
