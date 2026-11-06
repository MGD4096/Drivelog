package dev.idrivetrips.core

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What the user typed about a car (the car itself is identified by its Bluetooth address). Every field is optional.
 * [photo] is the name of an image file in the app's own storage, not a path.
 */
data class CarDetails(
    val vin: String? = null,
    /** Make and model as one free text, e.g. "BMW M240i". */
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

/** A head-unit plugin the app can receive car values from. */
data class CarPlugin(val id: String, val label: String)

object CarPlugins {
    const val DEFAULT = "bmw"
    val all: List<CarPlugin> = listOf(CarPlugin("bmw", "BMW (iDrive Plus)"))

    fun label(id: String): String = all.firstOrNull { it.id == id }?.label ?: id
}

/** The outcome of reading a text field: the value (null when left empty), or a message for the person when it is wrong. */
class Parsed<out T>(val value: T?, val error: String? = null) {
    val ok: Boolean get() = error == null
}

object Vin {
    /** Upper case, without spaces and dashes. */
    fun normalize(raw: String): String = raw.filter { !it.isWhitespace() && it != '-' }.uppercase(Locale.ROOT)

    /**
     * A VIN has 17 letters and digits, never I, O or Q. The check digit is not tested: only North American VINs have a
     * valid one, European VINs usually do not.
     */
    fun isValid(vin: String): Boolean = vin.length == 17 && vin.all { (it in 'A'..'Z' || it in '0'..'9') && it != 'I' && it != 'O' && it != 'Q' }

    fun parse(text: String): Parsed<String> {
        val v = normalize(text)
        if (v.isEmpty()) return Parsed(null)
        if (v.length != 17) return Parsed(null, tr("A VIN has 17 characters (you typed {0}).", v.length))
        if (!isValid(v)) return Parsed(null, tr("A VIN uses only letters and digits, and never I, O or Q."))
        return Parsed(v)
    }
}

object CarInput {
    const val FIRST_YEAR = 1950

    fun parseYear(text: String, currentYear: Int): Parsed<Int> {
        val t = text.trim()
        if (t.isEmpty()) return Parsed(null)
        val y = t.toIntOrNull()
        if (y == null || y < FIRST_YEAR || y > currentYear + 1) return Parsed(null, tr("Year must be between {0} and {1}.", FIRST_YEAR, currentYear + 1))
        return Parsed(y)
    }

    /** An odometer reading typed in by hand, in kilometres. Empty means not given. */
    fun parseOdometerKm(text: String): Parsed<Double> = parseDecimal(text, tr("Odometer"), 0.0, 3_000_000.0)

    /** A plain number such as a price or a distance; a comma may be the decimal mark. Empty means not given. */
    fun parseDecimal(text: String, what: String, min: Double, max: Double): Parsed<Double> {
        val t = text.trim().replace(',', '.').replace(" ", "")
        if (t.isEmpty()) return Parsed(null)
        val v = t.toDoubleOrNull()
        if (v == null || v.isNaN() || v.isInfinite() || v < min || v > max) return Parsed(null, tr("{0} must be a number from {1} to {2}.", what, min.toLong(), max.toLong()))
        return Parsed(v)
    }

    /**
     * Engine size as people write it: "2.0", "2,0 l", "1998", "1998 cc". Small numbers (or any with a decimal point, or
     * ending in "l") are litres, otherwise cubic centimetres. The result is in cubic centimetres.
     */
    fun parseEngineCc(text: String): Parsed<Int> {
        var t = text.trim().lowercase(Locale.ROOT).replace(',', '.')
        if (t.isEmpty()) return Parsed(null)
        var unit: String? = null
        for (u in listOf("cm3", "ccm", "cc", "litres", "liters", "litre", "liter", "l")) {
            if (t.endsWith(u)) { unit = u; t = t.removeSuffix(u).trim(); break }
        }
        val n = t.toDoubleOrNull() ?: return Parsed(null, tr("Engine size: use litres (2.0) or cubic centimetres (1998)."))
        val litres = when {
            unit == "cc" || unit == "ccm" || unit == "cm3" -> false
            unit != null -> true                          // l, litre(s), liter(s)
            t.contains('.') || n <= 20.0 -> true
            else -> false
        }
        val cc = if (litres) Math.round(n * 1000.0).toInt() else Math.round(n).toInt()
        if (cc < 50 || cc > 20_000) return Parsed(null, tr("Engine size must be between 0.05 and 20 litres."))
        return Parsed(cc)
    }
}

/** What the Cars screen shows about one car, worked out from its recorded trips. */
data class VehicleSummary(
    val tripCount: Int,
    val totalKm: Double,
    /** The same, for trips that started in the calendar year asked for. */
    val yearTripCount: Int,
    val yearKm: Double,
    /** Odometer at the end of the car's latest trip that had one. */
    val odometerKm: Double?,
    val odometerAtMs: Long?,
    /** Where the car's latest trip with a GPS track ended: where it is parked. */
    val parkedLat: Double?,
    val parkedLon: Double?,
    val parkedAtMs: Long?,
    /** Start time and distance of each of the car's trips, for adding up what was driven since a manual odometer entry. */
    val tripKms: List<Pair<Long, Double>> = emptyList(),
) {
    /** Kilometres driven on trips that started at or after [sinceMs]. */
    fun kmSince(sinceMs: Long): Double = tripKms.filter { it.first >= sinceMs }.sumOf { it.second }

    /**
     * The odometer of a car without a plugin: the reading typed in by hand plus every trip made since it was entered.
     * Null when no reading was ever entered.
     */
    fun manualOdometerNow(d: CarDetails): Double? {
        val base = d.manualOdometerKm ?: return null
        return base + kmSince(d.manualOdometerAtMs ?: Long.MAX_VALUE)
    }

    companion object {
        fun of(
            carId: String,
            trips: List<TripMeta>,
            year: Int = LocalDate.now().year,
            zone: ZoneId = ZoneId.systemDefault(),
        ): VehicleSummary {
            val mine = trips.filter { it.carId.equals(carId, ignoreCase = true) }.sortedByDescending { it.endMs }
            val thisYear = mine.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).year == year }
            val odo = mine.firstOrNull { (it.summary.endOdometerKm ?: it.summary.startOdometerKm) != null }
            val park = mine.firstOrNull { it.summary.endLat != null && it.summary.endLon != null }
            return VehicleSummary(
                tripCount = mine.size,
                totalKm = mine.sumOf { it.summary.distanceKm },
                yearTripCount = thisYear.size,
                yearKm = thisYear.sumOf { it.summary.distanceKm },
                odometerKm = odo?.let { it.summary.endOdometerKm ?: it.summary.startOdometerKm },
                odometerAtMs = odo?.endMs,
                parkedLat = park?.summary?.endLat,
                parkedLon = park?.summary?.endLon,
                parkedAtMs = park?.endMs,
                tripKms = mine.map { it.startMs to it.summary.distanceKm },
            )
        }
    }
}

/**
 * What the car reported at the end of its latest trips: fuel and range ([snapshot], at [atMs]) and which doors, windows
 * and so on were open ([openParts], at [openPartsAtMs]). Each comes from the latest trip that had it.
 */
data class LastKnownCar(
    val snapshot: CarSnapshot,
    val atMs: Long,
    val openParts: List<String>? = null,
    val openPartsAtMs: Long? = null,
) {
    companion object {
        /**
         * Looks at the car's latest trips (up to [LOOK_BACK]) for ones that ended with a fuel or range reading, or with
         * a doors-and-windows state. Reads trip files, so call it from a background thread.
         */
        fun find(carId: String, trips: List<TripMeta>, store: FileTripStore): LastKnownCar? {
            val mine = trips.filter { it.carId.equals(carId, ignoreCase = true) }.sortedByDescending { it.endMs }.take(LOOK_BACK)
            var fuel: Pair<CarSnapshot, Long>? = null
            var parts: Pair<List<String>, Long>? = null
            for (meta in mine) {
                if (fuel != null && parts != null) break
                val car = store.load(meta.id)?.endCar ?: continue
                if (fuel == null && (car.fuelLiters != null || car.rangeKm != null)) fuel = car to meta.endMs
                val open = car.openParts
                if (parts == null && open != null) parts = open to meta.endMs
            }
            if (fuel == null && parts == null) return null
            return LastKnownCar(fuel?.first ?: CarSnapshot.EMPTY, fuel?.second ?: parts!!.second, parts?.first, parts?.second)
        }

        const val LOOK_BACK = 5
    }
}

/** The parts of a car whose open or closed state the plugin reports. */
enum class CarPart(val label: String) {
    DRIVER_DOOR("driver door"), PASSENGER_DOOR("passenger door"),
    REAR_DRIVER_DOOR("rear driver door"), REAR_PASSENGER_DOOR("rear passenger door"),
    DRIVER_WINDOW("driver window"), PASSENGER_WINDOW("passenger window"),
    REAR_DRIVER_WINDOW("rear driver window"), REAR_PASSENGER_WINDOW("rear passenger window"),
    SUNROOF("sunroof"), TAILGATE("tailgate"), BONNET("bonnet");

    /** The name in the language of the app ([label] is the English name the plugin data is matched by). */
    val display: String get() = tr(label)
}

/** An open part: [tilted] for a roof or window that is only raised at the back. */
data class OpenPart(val part: CarPart, val tilted: Boolean)

/** Words for the state of the doors, windows, roof, tailgate and bonnet. */
object OpenPartsText {
    /** Turns the labels of [CarSnapshot.openParts] ("driver window", "sunroof (tilted)") back into parts. Unknown labels are skipped. */
    fun parts(labels: List<String>?): List<OpenPart> = (labels ?: emptyList()).mapNotNull { label ->
        val tilted = label.endsWith(" (tilted)")
        val name = label.removeSuffix(" (tilted)")
        CarPart.values().firstOrNull { it.label == name }?.let { OpenPart(it, tilted) }
    }

    /** One label of [CarSnapshot.openParts], in the language of the app: "driver window", "sunroof (tilted)". */
    fun describeLabel(label: String): String {
        val tilted = label.endsWith(" (tilted)")
        val part = CarPart.values().firstOrNull { it.label == label.removeSuffix(" (tilted)") } ?: return label
        return part.display + if (tilted) tr(" (tilted)") else ""
    }

    /** "Closed", "Open: driver door, rear window", or "—" when the car sent no valid state. */
    fun describe(parts: List<String>?): String = when {
        parts == null -> "—"
        parts.isEmpty() -> tr("Closed")
        else -> tr("Open: {0}", parts.joinToString(", ") { describeLabel(it) })
    }
}

/** The order of the car list: the car driven most recently first. */
object CarOrder {
    /** When the car's latest trip ended, or null when it has none. */
    fun lastDrivenMs(carId: String, trips: List<TripMeta>): Long? =
        trips.filter { it.carId.equals(carId, ignoreCase = true) }.maxOfOrNull { it.endMs }

    /**
     * [items] sorted by the end of their latest trip, newest first; those never driven keep their order at the end.
     * [drivingNow] puts the car being recorded at the top whatever its trips say.
     */
    fun <T> byLastDriven(items: List<T>, trips: List<TripMeta>, carId: (T) -> String, drivingNow: (T) -> Boolean = { false }): List<T> =
        items.sortedByDescending { if (drivingNow(it)) Long.MAX_VALUE else lastDrivenMs(carId(it), trips) ?: Long.MIN_VALUE }
}

/** The details of all cars in one small JSON file, keyed by Bluetooth address (case does not matter). */
class FileCarDetailsStore(private val file: File) {

    @Synchronized
    fun all(): Map<String, CarDetails> {
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
            )
        }
        return out
    }

    @Synchronized
    fun get(address: String): CarDetails = all()[address.lowercase(Locale.ROOT)] ?: CarDetails()

    @Synchronized
    @Throws(IOException::class)
    fun put(address: String, details: CarDetails) {
        val map = LinkedHashMap(all())
        if (details.isEmpty) map.remove(address.lowercase(Locale.ROOT)) else map[address.lowercase(Locale.ROOT)] = details
        write(map)
    }

    @Synchronized
    @Throws(IOException::class)
    fun remove(address: String) = put(address, CarDetails())

    /** Gives the details of [from] to [to] (for a car whose Bluetooth device was changed). */
    @Synchronized
    @Throws(IOException::class)
    fun moveCar(from: String, to: String) {
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
                if (d.morePhotos.isNotEmpty()) m["morePhotos"] = d.morePhotos
                if (!d.usesPlugin) m["usesPlugin"] = false
                if (d.plugin != CarPlugins.DEFAULT) m["plugin"] = d.plugin
                d.manualOdometerKm?.let { m["manualOdometerKm"] = it }
                d.manualOdometerAtMs?.let { m["manualOdometerAtMs"] = it.toDouble() }
                if (d.archived) m["archived"] = true
                m
            }
        )
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
