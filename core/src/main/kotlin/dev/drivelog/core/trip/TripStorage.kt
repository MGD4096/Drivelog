package dev.drivelog.core.trip

import dev.drivelog.carapi.Json
import dev.drivelog.core.car.CarSnapshot
import dev.drivelog.core.car.DriveTotals
import dev.drivelog.core.storage.AtomicFile
import dev.drivelog.core.storage.TripStore
import java.io.File
import java.io.IOException

/** What the trip list needs, small enough to read for every trip without loading the whole track. */
data class TripMeta(
    val id: String,
    val carId: String,
    val carName: String,
    val startMs: Long,
    val endMs: Long,
    val summary: TripSummary,
    /** The same as [Trip.log]. */
    val log: TripLog = TripLog(),
)

object TripJson {
    private const val VERSION = 1

    /**
     * How the summary in a meta file was worked out. Raise it when [TripStats.summarize] changes in a way saved trips
     * should follow: [FileTripStore.list] works out older ones again from their tracks. 2: the car's moving time is
     * checked against its top speed. 3: GPS first for the top speed and the moving time, the car only without a track.
     */
    const val SUMMARY_VERSION = 3

    /** The [SUMMARY_VERSION] of a meta file's text; 1 for files from before it was written down. */
    fun summaryVersion(text: String): Int =
        ((try { Json.parseObject(text) } catch (e: Json.JsonException) { null })?.get("sv") as? Double)?.toInt() ?: 1

    // ---- trip ----------------------------------------------------------------------------------------------------

    fun toJson(trip: Trip): String = Json.stringify(
        linkedMapOf(
            "v" to VERSION,
            "id" to trip.id,
            "carId" to trip.carId,
            "carName" to trip.carName,
            "startMs" to trip.startMs,
            "endMs" to trip.endMs,
            "startCar" to carToMap(trip.startCar),
            "endCar" to carToMap(trip.endCar),
            "log" to trip.log.toMap(),
            "carTotals" to linkedMapOf(
                "maxSpeedKmh" to trip.carTotals.maxSpeedKmh,
                "movingMs" to trip.carTotals.movingMs,
                "counterFuelL" to trip.carTotals.counterFuelL,
            ),
            "samples" to trip.samples.map { listOf(it.timeMs, it.odometerKm, it.fuelLiters, it.outsideC, it.insideC) },
            "points" to trip.points.map { listOf(it.timeMs, it.lat, it.lon, it.altitudeM, it.speedMps, it.accuracyM) },
        )
    )

    /** Returns null when the text is not a readable trip. */
    fun fromJson(text: String): Trip? {
        // a damaged file (cut short, edited by hand) is skipped like a missing one
        val o = try { Json.parseObject(text) } catch (e: Json.JsonException) { null } ?: return null
        return try {
            Trip(
                id = o["id"] as String,
                carId = o["carId"] as String,
                carName = o["carName"] as String,
                startMs = (o["startMs"] as Double).toLong(),
                endMs = (o["endMs"] as Double).toLong(),
                startCar = carFromMap(o["startCar"]),
                endCar = carFromMap(o["endCar"]),
                log = TripLog.fromMap(o["log"]),
                carTotals = (o["carTotals"] as? Map<*, *>)?.let { t ->
                    DriveTotals(t["maxSpeedKmh"] as? Double, (t["movingMs"] as? Double)?.toLong() ?: 0L, t["counterFuelL"] as? Double)
                } ?: DriveTotals.NONE,
                samples = (o["samples"] as List<*>).map { row ->
                    val r = row as List<*>
                    CarSample((r[0] as Double).toLong(), num(r, 1), num(r, 2), num(r, 3), num(r, 4))
                },
                points = (o["points"] as List<*>).map { row ->
                    val r = row as List<*>
                    TrackPoint((r[0] as Double).toLong(), r[1] as Double, r[2] as Double, num(r, 3), num(r, 4), num(r, 5))
                },
            )
        } catch (e: ClassCastException) {
            null
        } catch (e: NullPointerException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    private fun num(row: List<*>, i: Int): Double? = row.getOrNull(i) as? Double

    private fun carToMap(c: CarSnapshot) = linkedMapOf(
        "odometerKm" to c.odometerKm,
        "fuelLiters" to c.fuelLiters,
        "rangeKm" to c.rangeKm,
        "avgConsumptionKmPerL" to c.avgConsumptionKmPerL,
        "outsideC" to c.outsideC,
        "insideC" to c.insideC,
        "openParts" to c.openParts,
        "batteryPercent" to c.batteryPercent,
        "tyrePressureWarning" to c.tyrePressureWarning,
        "avgSpeedKmh" to c.avgSpeedKmh,
        "tyrePressuresBar" to c.tyrePressuresBar,
    )

    private fun carFromMap(any: Any?): CarSnapshot {
        val m = any as? Map<*, *> ?: return CarSnapshot.EMPTY
        return CarSnapshot(
            odometerKm = m["odometerKm"] as? Double,
            fuelLiters = m["fuelLiters"] as? Double,
            rangeKm = m["rangeKm"] as? Double,
            avgConsumptionKmPerL = m["avgConsumptionKmPerL"] as? Double,
            outsideC = m["outsideC"] as? Double,
            insideC = m["insideC"] as? Double,
            openParts = (m["openParts"] as? List<*>)?.filterIsInstance<String>(),
            batteryPercent = m["batteryPercent"] as? Double,
            tyrePressureWarning = m["tyrePressureWarning"] as? Boolean,
            avgSpeedKmh = m["avgSpeedKmh"] as? Double,
            tyrePressuresBar = (m["tyrePressuresBar"] as? List<*>)?.filterIsInstance<Double>()?.takeIf { it.isNotEmpty() },
        )
    }

    // ---- meta ----------------------------------------------------------------------------------------------------

    fun metaToJson(meta: TripMeta): String {
        val s = meta.summary
        return Json.stringify(
            linkedMapOf(
                "v" to VERSION,
                "sv" to SUMMARY_VERSION,
                "id" to meta.id,
                "carId" to meta.carId,
                "carName" to meta.carName,
                "startMs" to meta.startMs,
                "endMs" to meta.endMs,
                "log" to meta.log.toMap(),
                "summary" to linkedMapOf(
                    "durationMs" to s.durationMs,
                    "distanceKm" to s.distanceKm,
                    "gpsDistanceKm" to s.gpsDistanceKm,
                    "odometerDistanceKm" to s.odometerDistanceKm,
                    "movingTimeMs" to s.movingTimeMs,
                    "avgMovingSpeedKmh" to s.avgMovingSpeedKmh,
                    "maxSpeedKmh" to s.maxSpeedKmh,
                    "startOdometerKm" to s.startOdometerKm,
                    "endOdometerKm" to s.endOdometerKm,
                    "startFuelL" to s.startFuelL,
                    "endFuelL" to s.endFuelL,
                    "fuelUsedL" to s.fuelUsedL,
                    "refueledL" to s.refueledL,
                    "consumptionLPer100Km" to s.consumptionLPer100Km,
                    "carAvgConsumptionKmPerL" to s.carAvgConsumptionKmPerL,
                    "startLat" to s.startLat,
                    "startLon" to s.startLon,
                    "endLat" to s.endLat,
                    "endLon" to s.endLon,
                    "counterFuelL" to s.counterFuelL,
                    "carAvgSpeedKmh" to s.carAvgSpeedKmh,
                    "speedFromCar" to s.speedFromCar,
                ),
            )
        )
    }

    fun metaFromJson(text: String): TripMeta? {
        // a damaged file (cut short, edited by hand) is skipped like a missing one
        val o = try { Json.parseObject(text) } catch (e: Json.JsonException) { null } ?: return null
        return try {
            val s = o["summary"] as Map<*, *>
            fun d(k: String) = s[k] as? Double
            TripMeta(
                id = o["id"] as String,
                carId = o["carId"] as String,
                carName = o["carName"] as String,
                startMs = (o["startMs"] as Double).toLong(),
                endMs = (o["endMs"] as Double).toLong(),
                log = TripLog.fromMap(o["log"]),
                summary = TripSummary(
                    durationMs = (s["durationMs"] as Double).toLong(),
                    distanceKm = s["distanceKm"] as Double,
                    gpsDistanceKm = s["gpsDistanceKm"] as Double,
                    odometerDistanceKm = d("odometerDistanceKm"),
                    movingTimeMs = (s["movingTimeMs"] as Double).toLong(),
                    avgMovingSpeedKmh = d("avgMovingSpeedKmh"),
                    maxSpeedKmh = d("maxSpeedKmh"),
                    startOdometerKm = d("startOdometerKm"),
                    endOdometerKm = d("endOdometerKm"),
                    startFuelL = d("startFuelL"),
                    endFuelL = d("endFuelL"),
                    fuelUsedL = d("fuelUsedL"),
                    refueledL = s["refueledL"] as Double,
                    consumptionLPer100Km = d("consumptionLPer100Km"),
                    carAvgConsumptionKmPerL = d("carAvgConsumptionKmPerL"),
                    startLat = d("startLat"),
                    startLon = d("startLon"),
                    endLat = d("endLat"),
                    endLon = d("endLon"),
                    counterFuelL = d("counterFuelL"),
                    carAvgSpeedKmh = d("carAvgSpeedKmh"),
                    speedFromCar = s["speedFromCar"] as? Boolean ?: false,
                ),
            )
        } catch (e: ClassCastException) {
            null
        } catch (e: NullPointerException) {
            null
        }
    }
}

/**
 * Trips as plain files in one directory: `<id>.trip.json` (the full track) and `<id>.meta.json` (the summary shown in
 * the list). `draft.trip.json` is the trip in progress, rewritten now and then so a killed app loses almost nothing.
 */
class FileTripStore(private val dir: File) : TripStore {

    init {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
    }

    override fun save(trip: Trip): TripMeta {
        val meta = TripMeta(trip.id, trip.carId, trip.carName, trip.startMs, trip.endMs, TripStats.summarize(trip), trip.log.cleaned())
        writeAtomically(File(dir, "${trip.id}$TRIP_SUFFIX"), TripJson.toJson(trip))
        writeAtomically(File(dir, "${trip.id}$META_SUFFIX"), TripJson.metaToJson(meta))   // last: its presence means "complete"
        return meta
    }

    /**
     * Newest first. Unreadable entries are skipped. A summary worked out by an older version of the app
     * ([TripJson.SUMMARY_VERSION]) is worked out again from the trip's track and saved, once.
     */
    override fun list(): List<TripMeta> =
        (dir.listFiles { f -> f.name.endsWith(META_SUFFIX) } ?: emptyArray())
            .mapNotNull { f -> try { read(f) } catch (e: IOException) { null } }
            .sortedByDescending { it.startMs }

    private fun read(f: File): TripMeta? {
        val text = f.readText()
        val meta = TripJson.metaFromJson(text) ?: return null
        if (TripJson.summaryVersion(text) >= TripJson.SUMMARY_VERSION) return meta
        val trip = load(meta.id) ?: return meta
        return try { save(trip) } catch (e: IOException) { meta }
    }

    override fun load(id: String): Trip? {
        if (!validId(id)) return null
        val f = File(dir, "$id$TRIP_SUFFIX")
        return try { if (f.isFile) TripJson.fromJson(f.readText()) else null } catch (e: IOException) { null }
    }

    /** Changes the mileage-log details of a saved trip. Returns the new summary, or null when there is no such trip. */
    @Throws(IOException::class)
    override fun updateLog(id: String, log: TripLog): TripMeta? {
        val trip = load(id) ?: return null
        return save(trip.copy(log = log.cleaned()))
    }

    override fun delete(id: String) {
        if (!validId(id)) return
        File(dir, "$id$META_SUFFIX").delete()
        File(dir, "$id$TRIP_SUFFIX").delete()
    }

    /**
     * Moves every trip of the car [oldId] (and its unfinished draft) to [newId] / [newName], for a car whose Bluetooth device
     * was changed. Returns how many trips were moved.
     */
    @Throws(IOException::class)
    override fun reassignCar(oldId: String, newId: String, newName: String): Int {
        var moved = 0
        for (meta in list()) {
            if (!meta.carId.equals(oldId, ignoreCase = true)) continue
            val trip = load(meta.id) ?: continue
            save(trip.copy(carId = newId, carName = newName))
            moved++
        }
        loadDraft()?.takeIf { it.carId.equals(oldId, ignoreCase = true) }?.let { saveDraft(it.copy(carId = newId, carName = newName)) }
        return moved
    }

    override fun saveDraft(trip: Trip) = writeAtomically(File(dir, DRAFT), TripJson.toJson(trip))

    override fun loadDraft(): Trip? {
        val f = File(dir, DRAFT)
        return try { if (f.isFile) TripJson.fromJson(f.readText()) else null } catch (e: IOException) { null }
    }

    override fun clearDraft() { File(dir, DRAFT).delete() }

    private fun validId(id: String) = ID_PATTERN.matches(id)

    private fun writeAtomically(target: File, text: String) = AtomicFile.writeText(target, text)

    companion object {
        private const val TRIP_SUFFIX = ".trip.json"
        private const val META_SUFFIX = ".meta.json"
        private const val DRAFT = "draft.trip.json"
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,100}")
    }
}
