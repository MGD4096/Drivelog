package dev.drivelog.core.car

import dev.drivelog.carapi.Json
import dev.drivelog.core.storage.AtomicFile
import dev.drivelog.core.storage.CarDetailsStore
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * What the user typed about a car (the car itself is identified by its Bluetooth address). Every field is optional.
 * [photo] is the name of an image file in the app's own storage, not a path.
 */
data class CarDetails(
    val vin: String? = null,
    /** Make and model as one free text, e.g. "Mazda MX-5 2.0". */
    val model: String? = null,
    val year: Int? = null,
    /** Engine displacement in cubic centimetres. */
    val engineCc: Int? = null,
    /** The main photo: the one shown on the car list and at the top of the car details. */
    val photo: String? = null,
    /** The other photos of the gallery, in the order they were added. */
    val morePhotos: List<String> = emptyList(),
    /**
     * Whether the car reports its values through a head-unit plugin. A car that does not has no odometer or fuel
     * readings of its own: its odometer is typed in ([manualOdometerKm]).
     */
    val usesPlugin: Boolean = true,
    /** Which plugin, as an id from [CarPlugins]. Only meaningful when [usesPlugin]. */
    val plugin: String = CarPlugins.DEFAULT,
    val manualOdometerKm: Double? = null,
    /** When the odometer was typed in (epoch milliseconds). */
    val manualOdometerAtMs: Long? = null,
    /** Out of use: hidden in the main list (shown under "Archive"), no trips are recorded for it and no reminders are sent. */
    val archived: Boolean = false,
    /**
     * Where the latest trip ended, saved or not (a trip too short to keep is not saved, but the car was still moved).
     * [VehicleSummary.of] uses it when it is newer than the end of the latest saved trip.
     */
    val parkedLat: Double? = null,
    val parkedLon: Double? = null,
    val parkedAtMs: Long? = null,
    /** Registration number, for the mileage log. */
    val plate: String? = null,
    /** A car or a motorcycle: what the pages draw (a motorcycle has no doors or windows). */
    val kind: VehicleKind = VehicleKind.CAR,
) {
    val isEmpty: Boolean get() = this == CarDetails()

    /** The main photo first, then the others. */
    val allPhotos: List<String> get() = listOfNotNull(photo) + morePhotos

    /** Adds a photo to the gallery; the first one becomes the main photo. Unchanged when it is there already or the gallery is full. */
    fun withPhoto(name: String): CarDetails = when {
        name in allPhotos || allPhotos.size >= MAX_PHOTOS -> this
        photo == null -> copy(photo = name)
        else -> copy(morePhotos = morePhotos + name)
    }

    /** Makes [name] the main photo; the one that was main goes back among the others. Unchanged for a photo that is not in the gallery. */
    fun withMainPhoto(name: String): CarDetails = when {
        name == photo || name !in morePhotos -> this
        else -> copy(photo = name, morePhotos = listOfNotNull(photo) + morePhotos.filter { it != name })
    }

    /** The odometer reading to use for a car without a plugin; null when nothing was typed in yet. */
    fun withManualOdometer(km: Double?, atMs: Long): CarDetails =
        if (km == null) copy(manualOdometerKm = null, manualOdometerAtMs = null) else copy(manualOdometerKm = km, manualOdometerAtMs = atMs)

    /** Remembers where the car was left at [atMs]. */
    fun withParked(lat: Double, lon: Double, atMs: Long): CarDetails = copy(parkedLat = lat, parkedLon = lon, parkedAtMs = atMs)

    /** Takes a photo out of the gallery; when it was the main one the next photo takes its place. */
    fun withoutPhoto(name: String): CarDetails = when {
        name == photo -> copy(photo = morePhotos.firstOrNull(), morePhotos = morePhotos.drop(1))
        name in morePhotos -> copy(morePhotos = morePhotos.filter { it != name })
        else -> this
    }

    companion object {
        const val MAX_PHOTOS = 30
    }
}

/** What kind of vehicle it is. [key] is what cars.json stores; a missing or unknown one is a car. */
enum class VehicleKind(val key: String) {
    CAR("car"),
    MOTORCYCLE("motorcycle");

    companion object {
        fun fromKey(key: String?): VehicleKind = values().firstOrNull { it.key == key } ?: CAR
    }
}

/**
 * A car plugin: a separate app on the phone that delivers car values (plugin-api). [id] is what a car's details
 * store; [label] is the plugin's own name. Found at run time among the installed apps.
 */
data class CarPlugin(val id: String, val label: String)

object CarPlugins {
    /**
     * The id of "Drivelog ID6", the plugin a car uses unless set otherwise. Not written to cars.json when it is the
     * car's choice, so renaming it changed nothing in saved data (earlier versions had only this plugin).
     */
    const val DEFAULT = "id6"

    /**
     * What the plugin picker offers: the installed plugins, plus the car's current choice when that plugin is not
     * installed (so editing the car does not silently switch it), marked by [notInstalled].
     */
    fun choices(installed: List<CarPlugin>, current: String?, notInstalled: (String) -> String): List<CarPlugin> {
        val list = installed.distinctBy { it.id }.sortedBy { it.label.lowercase() }
        if (current == null || list.any { it.id == current }) return list
        return list + CarPlugin(current, notInstalled(current))
    }

    fun label(id: String, installed: List<CarPlugin>): String = installed.firstOrNull { it.id == id }?.label ?: id
}

/** The details of all cars in one small JSON file, keyed by Bluetooth address (case does not matter). */
class FileCarDetailsStore(private val file: File) : CarDetailsStore {

    @Synchronized
    override fun all(): Map<String, CarDetails> {
        val text = try { if (file.isFile) file.readText() else null } catch (e: IOException) { null } ?: return emptyMap()
        val root = try { Json.parseObject(text) } catch (e: Json.JsonException) { null } ?: return emptyMap()
        val out = LinkedHashMap<String, CarDetails>()
        for ((key, value) in root) {
            val m = value as? Map<*, *> ?: continue
            out[key.lowercase(Locale.ROOT)] = CarDetails(
                vin = (m["vin"] as? String)?.takeIf { it.isNotBlank() },
                model = (m["model"] as? String)?.takeIf { it.isNotBlank() },
                year = (m["year"] as? Double)?.toInt(),
                engineCc = (m["engineCc"] as? Double)?.toInt(),
                photo = (m["photo"] as? String)?.takeIf { it.isNotBlank() },
                morePhotos = (m["morePhotos"] as? List<*>)?.mapNotNull { (it as? String)?.takeIf { n -> n.isNotBlank() } } ?: emptyList(),
                usesPlugin = (m["usesPlugin"] as? Boolean) ?: true,
                plugin = (m["plugin"] as? String)?.takeIf { it.isNotBlank() } ?: CarPlugins.DEFAULT,
                manualOdometerKm = (m["manualOdometerKm"] as? Double)?.takeIf { it >= 0.0 },
                manualOdometerAtMs = (m["manualOdometerAtMs"] as? Double)?.toLong(),
                archived = (m["archived"] as? Boolean) ?: false,
                parkedLat = (m["parkedLat"] as? Double)?.takeIf { it in -90.0..90.0 },
                parkedLon = (m["parkedLon"] as? Double)?.takeIf { it in -180.0..180.0 },
                parkedAtMs = (m["parkedAtMs"] as? Double)?.toLong(),
                plate = (m["plate"] as? String)?.takeIf { it.isNotBlank() },
                kind = VehicleKind.fromKey(m["kind"] as? String),
            )
        }
        return out
    }

    @Synchronized
    override fun get(address: String): CarDetails = all()[address.lowercase(Locale.ROOT)] ?: CarDetails()

    @Synchronized
    @Throws(IOException::class)
    override fun put(address: String, details: CarDetails) {
        val map = LinkedHashMap(all())
        if (details.isEmpty) map.remove(address.lowercase(Locale.ROOT)) else map[address.lowercase(Locale.ROOT)] = details
        write(map)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun removeCar(address: String) = put(address, CarDetails())

    /** Gives the details of [from] to [to] (for a car whose Bluetooth device was changed). */
    @Synchronized
    @Throws(IOException::class)
    override fun moveCar(from: String, to: String) {
        val map = LinkedHashMap(all())
        val d = map.remove(from.lowercase(Locale.ROOT)) ?: return
        map[to.lowercase(Locale.ROOT)] = d
        write(map)
    }

    private fun write(map: Map<String, CarDetails>) {
        val json = Json.stringify(
            map.mapValues { (_, d) ->
                val m = LinkedHashMap<String, Any?>()
                d.vin?.let { m["vin"] = it }
                d.model?.let { m["model"] = it }
                d.year?.let { m["year"] = it.toDouble() }
                d.engineCc?.let { m["engineCc"] = it.toDouble() }
                d.photo?.let { m["photo"] = it }
                if (d.kind != VehicleKind.CAR) m["kind"] = d.kind.key
                if (d.morePhotos.isNotEmpty()) m["morePhotos"] = d.morePhotos
                if (!d.usesPlugin) m["usesPlugin"] = false
                if (d.plugin != CarPlugins.DEFAULT) m["plugin"] = d.plugin
                d.manualOdometerKm?.let { m["manualOdometerKm"] = it }
                d.manualOdometerAtMs?.let { m["manualOdometerAtMs"] = it.toDouble() }
                if (d.archived) m["archived"] = true
                d.plate?.let { m["plate"] = it }
                if (d.parkedLat != null && d.parkedLon != null && d.parkedAtMs != null) {
                    m["parkedLat"] = d.parkedLat
                    m["parkedLon"] = d.parkedLon
                    m["parkedAtMs"] = d.parkedAtMs.toDouble()
                }
                m
            }
        )
        AtomicFile.writeText(file, json)
    }
}
