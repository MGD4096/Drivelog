package dev.drivelog.core

import dev.drivelog.carapi.CarValues
import dev.drivelog.carapi.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * The latest values a car reported ([values]) and when the last of them arrived ([atMs]), kept apart from the trips:
 * they are saved as they come in, so they are there also after a trip too short to keep, or one that was deleted.
 */
data class LastReading(val values: CarValues, val atMs: Long) {
    companion object {
        /**
         * The newer reading with the older one's values where the newer has none: a plugin that has not got a value
         * yet (the 12 V battery comes a minute after the start) must not wipe the one it reported last time.
         */
        fun merge(old: LastReading?, new: LastReading): LastReading {
            if (old == null) return new
            val n = new.values
            val o = old.values
            return LastReading(
                CarValues(
                    odometerKm = n.odometerKm ?: o.odometerKm,
                    fuelLiters = n.fuelLiters ?: o.fuelLiters,
                    rangeKm = n.rangeKm ?: o.rangeKm,
                    avgConsumptionKmPerL = n.avgConsumptionKmPerL ?: o.avgConsumptionKmPerL,
                    outsideC = n.outsideC ?: o.outsideC,
                    insideC = n.insideC ?: o.insideC,
                    openParts = n.openParts ?: o.openParts,
                    batteryPercent = n.batteryPercent ?: o.batteryPercent,
                    tyrePressureWarning = n.tyrePressureWarning ?: o.tyrePressureWarning,
                ),
                maxOf(old.atMs, new.atMs),
            )
        }
    }
}

/** [LastReading] per car (Bluetooth address, any case), in one small JSON file. */
class FileLastReadingStore(private val file: File) {

    @Synchronized
    fun all(): Map<String, LastReading> {
        val text = try { if (file.isFile) file.readText() else null } catch (e: IOException) { null } ?: return emptyMap()
        val root = try { Json.parseObject(text) } catch (e: Json.JsonException) { null } ?: return emptyMap()
        val out = LinkedHashMap<String, LastReading>()
        for ((key, value) in root) {
            val m = value as? Map<*, *> ?: continue
            val at = (m["atMs"] as? Double)?.toLong() ?: continue
            val values = (m["values"] as? Map<*, *>)?.let { CarValues.fromJson(Json.stringify(it)) } ?: continue
            out[key.lowercase(Locale.ROOT)] = LastReading(values.checked(), at)
        }
        return out
    }

    @Synchronized
    fun get(carId: String): LastReading? = all()[carId.lowercase(Locale.ROOT)]

    /** Adds a reading of [carId]: values it lacks keep their earlier ones ([LastReading.merge]). An empty reading changes nothing. */
    @Synchronized
    @Throws(IOException::class)
    fun put(carId: String, values: CarValues, atMs: Long) {
        if (values == CarValues.EMPTY) return
        val map = LinkedHashMap(all())
        val key = carId.lowercase(Locale.ROOT)
        map[key] = LastReading.merge(map[key], LastReading(values.checked(), atMs))
        write(map)
    }

    @Synchronized
    @Throws(IOException::class)
    fun remove(carId: String) {
        val map = LinkedHashMap(all())
        if (map.remove(carId.lowercase(Locale.ROOT)) != null) write(map)
    }

    /** For a car whose Bluetooth device was changed. */
    @Synchronized
    @Throws(IOException::class)
    fun moveCar(from: String, to: String) {
        val map = LinkedHashMap(all())
        val r = map.remove(from.lowercase(Locale.ROOT)) ?: return
        map[to.lowercase(Locale.ROOT)] = r
        write(map)
    }

    private fun write(map: Map<String, LastReading>) {
        val json = Json.stringify(map.mapValues { (_, r) -> mapOf("atMs" to r.atMs.toDouble(), "values" to Json.parseObject(CarValues.toJson(r.values))) })
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val FILE_NAME = "car_readings.json"
    }
}
