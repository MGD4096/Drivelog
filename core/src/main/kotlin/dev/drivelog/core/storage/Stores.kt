package dev.drivelog.core.storage

import dev.drivelog.carapi.CarValues
import dev.drivelog.core.car.CarDetails
import dev.drivelog.core.car.CarEvent
import dev.drivelog.core.car.FuelEntry
import dev.drivelog.core.car.LastReading
import dev.drivelog.core.trip.Trip
import dev.drivelog.core.trip.TripLog
import dev.drivelog.core.trip.TripMeta
import java.io.IOException

/*
 * What the logic and the screens need from the stored data, as small interfaces (the File...Store classes implement
 * them). Code depends on the narrowest one it uses, so it can be given a fake in a test and does not care that the
 * data lives in JSON files.
 *
 * The contract every implementation keeps: car keys (Bluetooth addresses) compare case-insensitively; reading never
 * throws, a missing or damaged file reads as empty; writing throws IOException and leaves the old data whole.
 */

/** Loads one trip with its whole track. */
fun interface TripLoader {
    /** The trip, or null when there is none with this id or its file is damaged. */
    fun load(id: String): Trip?
}

/** The trips' summaries, newest first. */
fun interface TripLister {
    fun list(): List<TripMeta>
}

/** Every trip: recorded, read, changed and moved between cars. */
interface TripStore : TripLoader, TripLister {
    @Throws(IOException::class) fun save(trip: Trip): TripMeta
    @Throws(IOException::class) fun updateLog(id: String, log: TripLog): TripMeta?
    @Throws(IOException::class) fun delete(id: String)
    /** Moves every trip of [oldId] to [newId]; returns how many moved. */
    @Throws(IOException::class) fun reassignCar(oldId: String, newId: String, newName: String): Int
    /** The trip being recorded, saved now and then so a crash loses little. */
    @Throws(IOException::class) fun saveDraft(trip: Trip)
    fun loadDraft(): Trip?
    fun clearDraft()
}

/** Data kept per car that can follow the car to a new Bluetooth address or be removed with it. */
interface PerCarData {
    @Throws(IOException::class) fun moveCar(from: String, to: String)
    @Throws(IOException::class) fun removeCar(address: String)
}

/** What the person told about each car (model, VIN, plugin, photos, typed odometer, where it is parked). */
interface CarDetailsStore : PerCarData {
    fun all(): Map<String, CarDetails>
    /** The car's details; empty [CarDetails] when nothing is stored. */
    fun get(address: String): CarDetails
    @Throws(IOException::class) fun put(address: String, details: CarDetails)
}

/** The service log of each car. */
interface CarEventsStore : PerCarData {
    /** Newest first. */
    fun list(address: String): List<CarEvent>
    @Throws(IOException::class) fun save(address: String, event: CarEvent)
    @Throws(IOException::class) fun delete(address: String, id: String)
}

/** The refuellings of each car. */
interface FuelStore : PerCarData {
    /** Newest first. */
    fun list(carId: String): List<FuelEntry>
    @Throws(IOException::class) fun save(carId: String, entry: FuelEntry)
    @Throws(IOException::class) fun delete(carId: String, id: String)
}

/** The latest values each car sent, kept apart from the trips. */
interface LastReadingStore : PerCarData {
    fun all(): Map<String, LastReading>
    fun get(carId: String): LastReading?
    /** Merges [values] into what is stored (a value the car did not send this time is kept). */
    @Throws(IOException::class) fun put(carId: String, values: CarValues, atMs: Long)
}
