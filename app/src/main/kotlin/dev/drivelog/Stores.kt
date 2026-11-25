package dev.drivelog

import android.content.Context
import dev.drivelog.core.CarDetailsStore
import dev.drivelog.core.CarEventsStore
import dev.drivelog.core.FileCarDetailsStore
import dev.drivelog.core.FileCarEventsStore
import dev.drivelog.core.FileFuelStore
import dev.drivelog.core.FileLastReadingStore
import dev.drivelog.core.FileTripStore
import dev.drivelog.core.FuelStore
import dev.drivelog.core.LastReadingStore
import dev.drivelog.core.TripStore
import java.io.File
import java.io.IOException

/**
 * Every data store of the app, with its file name, in one place. Screens, the trip service, receivers and the widget
 * take their stores from here instead of building them from paths. Cheap to create; each store is opened on first use.
 * Not shared between threads beyond what the stores themselves allow: create one per screen or per job.
 */
class Stores(context: Context) {
    private val dir: File = context.applicationContext.filesDir

    val details: CarDetailsStore by lazy { FileCarDetailsStore(File(dir, CARS)) }
    val readings: LastReadingStore by lazy { FileLastReadingStore(File(dir, FileLastReadingStore.FILE_NAME)) }
    val fuel: FuelStore by lazy { FileFuelStore(File(dir, FileFuelStore.FILE_NAME)) }
    val events: CarEventsStore by lazy { FileCarEventsStore(File(dir, EVENTS)) }

    /** The trips, or null when their folder cannot be created (a full or broken storage). */
    val trips: TripStore? by lazy {
        try {
            FileTripStore(File(dir, TRIPS))
        } catch (e: IOException) {
            null
        }
    }

    /** [trips] for code that cannot go on without them; throws [IOException] like the store itself. */
    fun requireTrips(): TripStore = trips ?: throw IOException("Cannot open the trips folder")

    /** The car's model when it is filled in, otherwise [fallback] (usually its Bluetooth name). */
    fun carName(address: String, fallback: String): String = details.get(address).model?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        const val CARS = "cars.json"
        const val TRIPS = "trips"
        const val EVENTS = "car_events.json"
        const val ROUTES = "routes.json"
    }
}
