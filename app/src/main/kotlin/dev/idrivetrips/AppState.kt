package dev.idrivetrips

import android.content.Context
import dev.idrivetrips.core.CarState
import dev.idrivetrips.core.Json
import dev.idrivetrips.core.TripSummary

/** A car the user chose from the paired Bluetooth devices. */
data class CarRef(val address: String, val name: String)

/** Which cars to follow; kept in SharedPreferences as a small JSON list. */
object Prefs {
    private const val FILE = "settings"
    private const val KEY_CARS = "cars"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun cars(c: Context): List<CarRef> {
        val text = prefs(c).getString(KEY_CARS, null) ?: return emptyList()
        val list = try { Json.parse(text) as? List<*> } catch (e: Json.JsonException) { null } ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val address = m["address"] as? String ?: return@mapNotNull null
            CarRef(address, (m["name"] as? String) ?: address)
        }
    }

    fun addCar(c: Context, car: CarRef) = save(c, cars(c).filter { !it.address.equals(car.address, true) } + car)

    fun removeCar(c: Context, address: String) = save(c, cars(c).filter { !it.address.equals(address, true) })

    /** Replaces the whole list (used when data is imported). */
    fun setCars(c: Context, cars: List<CarRef>) = save(c, cars)

    private fun save(c: Context, cars: List<CarRef>) {
        val json = Json.stringify(cars.map { linkedMapOf("address" to it.address, "name" to it.name) })
        prefs(c).edit().putString(KEY_CARS, json).apply()
    }
}

enum class Phase { IDLE, RECORDING, WAITING_FOR_RECONNECT }

/** What the service is doing, published for the screen to show. */
data class ServiceStatus(
    val phase: Phase = Phase.IDLE,
    val carName: String = "",
    val serverRunning: Boolean = false,
    val serverError: String? = null,
    /** Set when GPS cannot be used (permission missing, no GPS), so the trip has no track. */
    val gpsProblem: String? = null,
    val lastFixMs: Long = 0L,
    val speedKmh: Double? = null,
    val trip: TripSummary? = null,
    val reconnectDeadlineMs: Long = 0L,
    /** A one-off note such as "Trip saved" shown after a trip ends. */
    val message: String? = null,
)

/** State shared between the service and the screen. Both live in the same process. */
object AppState {
    /** The latest car values; written by the HTTP server threads, read by the service and the screen. */
    val car = CarState()

    @Volatile var status: ServiceStatus = ServiceStatus()
}
