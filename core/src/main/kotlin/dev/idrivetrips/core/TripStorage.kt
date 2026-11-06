package dev.idrivetrips.core

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What the trip list needs, small enough to read for every trip without loading the whole track. */
data class TripMeta(
    val id: String,
    val carId: String,
    val carName: String,
    val startMs: Long,
    val endMs: Long,
    val summary: TripSummary,
)

object TripJson {
    private const val VERSION = 1

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
            "samples" to trip.samples.map { listOf(it.timeMs, it.odometerKm, it.fuelLiters, it.outsideC, it.insideC) },
            "points" to trip.points.map { listOf(it.timeMs, it.lat, it.lon, it.altitudeM, it.speedMps, it.accuracyM) },
        )
    )

    /** Returns null when the text is not a readable trip. */
    fun fromJson(text: String): Trip? {
        val o = Json.parseObject(text) ?: return null
        return try {
            Trip(
                id = o["id"] as String,
                carId = o["carId"] as String,
                carName = o["carName"] as String,
                startMs = (o["startMs"] as Double).toLong(),
                endMs = (o["endMs"] as Double).toLong(),
                startCar = carFromMap(o["startCar"]),
                endCar = carFromMap(o["endCar"]),
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
        )
    }

    // ---- meta ----------------------------------------------------------------------------------------------------

    fun metaToJson(meta: TripMeta): String {
        val s = meta.summary
        return Json.stringify(
            linkedMapOf(
                "v" to VERSION,
                "id" to meta.id,
                "carId" to meta.carId,
                "carName" to meta.carName,
                "startMs" to meta.startMs,
                "endMs" to meta.endMs,
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
                ),
            )
        )
    }

    fun metaFromJson(text: String): TripMeta? {
        val o = Json.parseObject(text) ?: return null
        return try {
            val s = o["summary"] as Map<*, *>
            fun d(k: String) = s[k] as? Double
            TripMeta(
                id = o["id"] as String,
                carId = o["carId"] as String,
                carName = o["carName"] as String,
                startMs = (o["startMs"] as Double).toLong(),
                endMs = (o["endMs"] as Double).toLong(),
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
class FileTripStore(private val dir: File) {

    init {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
    }

    fun save(trip: Trip): TripMeta {
        val meta = TripMeta(trip.id, trip.carId, trip.carName, trip.startMs, trip.endMs, TripStats.summarize(trip))
        writeAtomically(File(dir, "${trip.id}$TRIP_SUFFIX"), TripJson.toJson(trip))
        writeAtomically(File(dir, "${trip.id}$META_SUFFIX"), TripJson.metaToJson(meta))   // last: its presence means "complete"
        return meta
    }

    /** Newest first. Unreadable entries are skipped. */
    fun list(): List<TripMeta> =
        (dir.listFiles { f -> f.name.endsWith(META_SUFFIX) } ?: emptyArray())
            .mapNotNull { f -> try { TripJson.metaFromJson(f.readText()) } catch (e: IOException) { null } }
            .sortedByDescending { it.startMs }

    fun load(id: String): Trip? {
        if (!validId(id)) return null
        val f = File(dir, "$id$TRIP_SUFFIX")
        return try { if (f.isFile) TripJson.fromJson(f.readText()) else null } catch (e: IOException) { null }
    }

    fun delete(id: String) {
        if (!validId(id)) return
        File(dir, "$id$META_SUFFIX").delete()
        File(dir, "$id$TRIP_SUFFIX").delete()
    }

    fun saveDraft(trip: Trip) = writeAtomically(File(dir, DRAFT), TripJson.toJson(trip))

    fun loadDraft(): Trip? {
        val f = File(dir, DRAFT)
        return try { if (f.isFile) TripJson.fromJson(f.readText()) else null } catch (e: IOException) { null }
    }

    fun clearDraft() { File(dir, DRAFT).delete() }

    private fun validId(id: String) = ID_PATTERN.matches(id)

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val TRIP_SUFFIX = ".trip.json"
        private const val META_SUFFIX = ".meta.json"
        private const val DRAFT = "draft.trip.json"
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,100}")
    }
}
