package dev.drivelog.core.export

import dev.drivelog.carapi.CarValues
import dev.drivelog.carapi.Json
import dev.drivelog.core.car.Due
import dev.drivelog.core.car.EventType
import dev.drivelog.core.car.VehicleKind
import java.security.MessageDigest
import java.util.Locale

/** One vehicle as export plugins see it (`CarPluginContract.ACTION_EXPORT`). */
data class ExportVehicle(
    /** The Bluetooth address; only its [ExportState.id] leaves the app. */
    val address: String,
    val name: String,
    val kind: VehicleKind,
    val values: CarValues? = null,
    val readAtMs: Long? = null,
    val parkedLat: Double? = null,
    val parkedLon: Double? = null,
    val parkedAtMs: Long? = null,
    val services: List<Due> = emptyList(),
)

/**
 * The state of the vehicles that export plugins get, as JSON (the format is written down at
 * `CarPluginContract.ACTION_EXPORT`). What is left out of [ExportVehicle] is left out of the JSON.
 */
object ExportState {
    const val FORMAT = 1

    /** A stable id for a vehicle that does not give away its Bluetooth address: the start of its SHA-256. */
    fun id(address: String): String =
        MessageDigest.getInstance("SHA-256").digest(address.lowercase(Locale.ROOT).toByteArray()).take(6)
            .joinToString("") { "%02x".format(it) }

    fun toJson(vehicles: List<ExportVehicle>, atMs: Long): String = Json.stringify(
        linkedMapOf(
            "format" to FORMAT,
            "atMs" to atMs,
            "vehicles" to vehicles.map { v -> vehicle(v) },
        )
    )

    private fun vehicle(v: ExportVehicle): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "id" to id(v.address),
            "name" to v.name,
            "kind" to v.kind.key,
        )
        v.values?.takeIf { it != CarValues.EMPTY }?.let { m["values"] = Json.parseObject(CarValues.toJson(it)) }
        v.readAtMs?.let { m["readAtMs"] = it }
        if (v.parkedLat != null && v.parkedLon != null) {
            m["parked"] = linkedMapOf("lat" to v.parkedLat, "lon" to v.parkedLon, "atMs" to v.parkedAtMs).filterValues { it != null }
        }
        if (v.services.isNotEmpty()) m["services"] = v.services.map { service(it) }
        return m
    }

    private fun service(d: Due): Map<String, Any?> = linkedMapOf<String, Any?>(
        "type" to typeKey(d.type),
        "label" to d.type.label,
        "dueDate" to d.dueDate?.toString(),
        "daysLeft" to d.daysLeft,
        "nextAtKm" to d.nextAtKm,
        "kmLeft" to d.kmLeft,
        "level" to d.level.name.lowercase(Locale.ROOT),
    ).filterValues { it != null }

    fun typeKey(t: EventType): String = t.name.lowercase(Locale.ROOT)
}
