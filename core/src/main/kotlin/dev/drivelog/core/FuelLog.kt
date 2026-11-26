package dev.drivelog.core

import dev.drivelog.carapi.Json
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** One refuelling: when, at what odometer reading, how many litres and what it cost. */
data class FuelEntry(
    val id: String,
    val atMs: Long,
    val liters: Double,
    /** What was paid in total, in the phone's currency. */
    val cost: Double? = null,
    val odometerKm: Double? = null,
    /** Filled up to the top: only full fills mark the ends of a tank-to-tank interval. */
    val fullTank: Boolean = true,
    val note: String? = null,
    /** The [DetectedRefuel.key] this entry was made from, so the suggestion is not shown again. */
    val detectedKey: String? = null,
) {
    val pricePerLiter: Double? get() = cost?.takeIf { liters > 0 }?.let { it / liters }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

/** A rise of the tank level the car reported: probably a refuelling that has no entry yet. */
data class DetectedRefuel(val key: String, val atMs: Long, val liters: Double, val odometerKm: Double?)

/** Consumption from one full fill to the next. */
data class TankInterval(val fromKm: Double, val toKm: Double, val liters: Double, val toMs: Long) {
    val km: Double get() = toKm - fromKm
    val lPer100Km: Double get() = liters / km * 100.0
}

object FuelLog {
    /** Suggestions older than this are not offered any more. */
    const val SUGGEST_DAYS = 60L
    /** An entry this close in time to a detected rise is taken to be that refuelling. */
    const val SAME_REFUEL_MS = 12 * 3_600_000L

    /**
     * Rises of the tank level of [TripStats.REFUEL_MIN_LITERS] or more: between two trips (filled while the car was
     * off, the usual case) and during a trip. Leaves out what [entries] already cover, what was [dismissed], and anything
     * before [sinceMs]. Newest first. The litres are the tank level's whole litres: only a hint for the entry.
     */
    fun detect(
        carId: String,
        trips: List<TripMeta>,
        entries: List<FuelEntry>,
        dismissed: Set<String>,
        sinceMs: Long,
    ): List<DetectedRefuel> {
        val mine = trips.filter { it.carId.equals(carId, ignoreCase = true) }.sortedBy { it.startMs }
        val found = ArrayList<DetectedRefuel>()
        for (i in mine.indices) {
            val m = mine[i]
            val s = m.summary
            if (i > 0) {
                val before = mine[i - 1].summary.endFuelL
                val after = s.startFuelL
                if (before != null && after != null && after - before >= TripStats.REFUEL_MIN_LITERS) {
                    found += DetectedRefuel("gap:${m.id}", m.startMs, after - before, s.startOdometerKm ?: mine[i - 1].summary.endOdometerKm)
                }
            }
            if (s.refueledL >= TripStats.REFUEL_MIN_LITERS) found += DetectedRefuel("in:${m.id}", m.startMs, s.refueledL, s.startOdometerKm)
        }
        val used = entries.mapNotNull { it.detectedKey }.toSet()
        return found
            .filter { it.atMs >= sinceMs && it.key !in used && it.key !in dismissed }
            .filter { d -> entries.none { kotlin.math.abs(it.atMs - d.atMs) <= SAME_REFUEL_MS } }
            .sortedByDescending { it.atMs }
    }

    /**
     * Tank to tank: from one full fill to the next, the litres put in (the next full one and any partial ones in
     * between) over the kilometres driven. Entries without an odometer reading are skipped. Oldest first.
     */
    fun intervals(entries: List<FuelEntry>): List<TankInterval> {
        val withKm = entries.filter { it.odometerKm != null }.sortedBy { it.odometerKm }
        val out = ArrayList<TankInterval>()
        var lastFull: FuelEntry? = null
        var litres = 0.0
        for (e in withKm) {
            if (lastFull != null) litres += e.liters
            if (e.fullTank) {
                val from = lastFull
                val fromKm = from?.odometerKm
                val toKm = e.odometerKm
                if (fromKm != null && toKm != null && toKm - fromKm >= 10.0) out += TankInterval(fromKm, toKm, litres, e.atMs)
                lastFull = e
                litres = 0.0
            }
        }
        return out
    }

    /** All the intervals together: litres over kilometres, L/100 km. Null without a complete interval. */
    fun average(intervals: List<TankInterval>): Double? {
        val km = intervals.sumOf { it.km }
        return if (km <= 0) null else intervals.sumOf { it.liters } / km * 100.0
    }

    fun totalCost(entries: List<FuelEntry>, year: Int? = null, zone: ZoneId = ZoneId.systemDefault()): Double =
        entries.filter { year == null || Instant.ofEpochMilli(it.atMs).atZone(zone).year == year }.sumOf { it.cost ?: 0.0 }

    /** Money per kilometre: [cost] over [km]; null for no kilometres. */
    fun perKm(cost: Double, km: Double): Double? = if (km > 0.0 && cost > 0.0) cost / km else null
}

/** All cars' refuellings in one small JSON file, keyed by Bluetooth address (case does not matter). */
class FileFuelStore(private val file: File) : FuelStore {

    /** Newest first. */
    @Synchronized
    override fun list(carId: String): List<FuelEntry> = readAll()[carId.lowercase(Locale.ROOT)].orEmpty().sortedByDescending { it.atMs }

    @Synchronized
    @Throws(IOException::class)
    override fun save(carId: String, entry: FuelEntry) {
        val all = readAll()
        val key = carId.lowercase(Locale.ROOT)
        all[key] = all[key].orEmpty().filter { it.id != entry.id } + entry
        write(all)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun delete(carId: String, id: String) {
        val all = readAll()
        val key = carId.lowercase(Locale.ROOT)
        all[key] = all[key].orEmpty().filter { it.id != id }
        write(all)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun removeCar(address: String) {
        val carId = address
        val all = readAll()
        if (all.remove(carId.lowercase(Locale.ROOT)) != null) write(all)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun moveCar(from: String, to: String) {
        val all = readAll()
        val moved = all.remove(from.lowercase(Locale.ROOT)) ?: return
        val key = to.lowercase(Locale.ROOT)
        all[key] = all[key].orEmpty() + moved
        write(all)
    }

    private fun readAll(): LinkedHashMap<String, List<FuelEntry>> {
        val out = LinkedHashMap<String, List<FuelEntry>>()
        val text = try { if (file.isFile) file.readText() else null } catch (e: IOException) { null } ?: return out
        val root = try { Json.parseObject(text) } catch (e: Json.JsonException) { null } ?: return out
        for ((key, value) in root) {
            val rows = value as? List<*> ?: continue
            out[key.lowercase(Locale.ROOT)] = rows.mapNotNull { fromMap(it as? Map<*, *>) }
        }
        return out
    }

    private fun fromMap(m: Map<*, *>?): FuelEntry? {
        m ?: return null
        val id = m["id"] as? String ?: return null
        val at = (m["atMs"] as? Double)?.toLong() ?: return null
        val liters = (m["liters"] as? Double)?.takeIf { it > 0 && it < 1000 } ?: return null
        return FuelEntry(
            id = id, atMs = at, liters = liters,
            cost = (m["cost"] as? Double)?.takeIf { it >= 0 },
            odometerKm = (m["odometerKm"] as? Double)?.takeIf { it >= 0 },
            fullTank = m["fullTank"] as? Boolean ?: true,
            note = (m["note"] as? String)?.takeIf { it.isNotBlank() },
            detectedKey = m["detectedKey"] as? String,
        )
    }

    private fun toMap(e: FuelEntry): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to e.id, "atMs" to e.atMs.toDouble(), "liters" to e.liters, "cost" to e.cost, "odometerKm" to e.odometerKm,
        "fullTank" to e.fullTank, "note" to e.note, "detectedKey" to e.detectedKey,
    ).filterValues { it != null }

    private fun write(all: Map<String, List<FuelEntry>>) {
        val json = Json.stringify(all.filterValues { it.isNotEmpty() }.mapValues { (_, list) -> list.map { toMap(it) } })
        AtomicFile.writeText(file, json)
    }

    companion object {
        const val FILE_NAME = "fuel_log.json"
    }
}
