package dev.drivelog.core

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** One calendar month: how much was driven and what it cost. */
data class MonthStats(
    val month: YearMonth,
    val trips: Int,
    val km: Double,
    val durationMs: Long,
    /** Litres burnt on the month's trips (tank level, or the car's fuel counter for short trips); null when unknown. */
    val fuelL: Double?,
    /** Money spent on fuel (fuel log) and on everything else (service log) in the month. */
    val fuelCost: Double,
    val otherCost: Double,
) {
    /** Only when the litres cover most of the month's kilometres, otherwise it would look far too low. */
    val lPer100Km: Double? get() = fuelL?.takeIf { km >= 20.0 && it > 0 }?.let { it / km * 100.0 }
    val cost: Double get() = fuelCost + otherCost
    val costPerKm: Double? get() = FuelLog.perKm(cost, km)
}

object MonthlyStats {
    /**
     * The [months] calendar months up to and including [last], oldest first, for the trips, refuellings and service
     * entries given (already narrowed to one car, or all cars together). [counterFactor] scales the car's fuel counter
     * ([CounterFuel.factor]).
     */
    fun of(
        trips: List<TripMeta>,
        fuel: List<FuelEntry>,
        events: List<CarEvent>,
        last: YearMonth,
        months: Int = 12,
        counterFactor: (TripMeta) -> Double = { 1.0 },
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MonthStats> {
        fun monthOf(ms: Long) = YearMonth.from(Instant.ofEpochMilli(ms).atZone(zone))
        val byMonth = trips.groupBy { monthOf(it.startMs) }
        val fuelByMonth = fuel.groupBy { monthOf(it.atMs) }
        val eventsByMonth = events.groupBy { YearMonth.from(it.date) }
        return (months - 1 downTo 0).map { back ->
            val m = last.minusMonths(back.toLong())
            val t = byMonth[m].orEmpty()
            val litres = t.mapNotNull { CounterFuel.used(it.summary, counterFactor(it)) }
            val withFuel = t.filter { CounterFuel.used(it.summary, counterFactor(it)) != null }
            // litres for under half the distance would give a consumption that is far too low: unknown instead
            val km = t.sumOf { it.summary.distanceKm }
            val fuelL = if (litres.isEmpty() || withFuel.sumOf { it.summary.distanceKm } < km * 0.5) null else litres.sum()
            MonthStats(
                month = m, trips = t.size, km = km, durationMs = t.sumOf { it.summary.durationMs }, fuelL = fuelL,
                fuelCost = fuelByMonth[m].orEmpty().sumOf { it.cost ?: 0.0 },
                otherCost = eventsByMonth[m].orEmpty().sumOf { it.cost ?: 0.0 },
            )
        }
    }

    /** How [now] compares with [before], as a fraction (0.25 = 25 % more); null when [before] is zero. */
    fun change(now: Double, before: Double): Double? = if (before == 0.0) null else (now - before) / before
}
