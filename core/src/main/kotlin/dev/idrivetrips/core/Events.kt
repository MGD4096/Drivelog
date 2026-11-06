package dev.idrivetrips.core

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** What happened to the car. [hasDue] types can carry a "valid until / next due" date (and kilometres for the oil). */
enum class EventType(private val english: String, val hasDue: Boolean) {
    INSPECTION("Technical inspection", true),
    INSURANCE("Insurance (third-party liability)", true),
    OIL_CHANGE("Oil change", true),
    REPAIR("Repair", false),
    OTHER("Other", false);

    /** The name in the language of the app. */
    val label: String get() = tr(english)
}

/**
 * One entry in a car's log: something done (an oil change, a repair), or a cover bought (insurance, an inspection)
 * together with when it runs out. Dates are plain calendar dates, so time zones never move them.
 */
data class CarEvent(
    val id: String,
    val type: EventType,
    /** When it was done or started. */
    val date: LocalDate,
    /** A short description; needed for repairs and other things, optional for the rest. */
    val title: String? = null,
    /** Inspection and insurance: valid until. Oil: the date by which the next change is due. */
    val dueDate: LocalDate? = null,
    val odometerKm: Double? = null,
    /** Oil: the odometer reading at which the next change is due. */
    val nextAtKm: Double? = null,
    /** Money spent, in the currency the phone uses. */
    val cost: Double? = null,
    val note: String? = null,
) {
    /** What to call it in a list. */
    val displayTitle: String get() = if (title.isNullOrBlank()) type.label else if (type == EventType.OTHER || type == EventType.REPAIR) title else "${type.label}: $title"

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

enum class DueLevel { OVERDUE, SOON, OK }

/** The state of one thing that runs out: for example "insurance, 12 days left". */
data class Due(
    val type: EventType,
    val event: CarEvent,
    val dueDate: LocalDate?,
    val daysLeft: Long?,
    val nextAtKm: Double?,
    val kmLeft: Double?,
    val level: DueLevel,
)

object Reminders {
    /**
     * How much of the interval is still left, from 1.0 (just renewed) to 0.0 (run out), for a gauge. Measured from the
     * date and from the kilometres of the entry; when both are known the one that is closer to running out decides.
     * Null when the entry does not say how long it was meant to last.
     */
    fun remainingFraction(due: Due): Double? {
        val byDate = due.dueDate?.let { end ->
            val total = ChronoUnit.DAYS.between(due.event.date, end)
            val left = due.daysLeft
            if (total > 0 && left != null) left.toDouble() / total else if (left != null && left <= 0) 0.0 else null
        }
        val byKm = due.nextAtKm?.let { end ->
            val start = due.event.odometerKm
            val left = due.kmLeft
            if (start != null && end > start && left != null) left / (end - start) else if (left != null && left <= 0) 0.0 else null
        }
        val all = listOfNotNull(byDate, byKm)
        return if (all.isEmpty()) null else all.min().coerceIn(0.0, 1.0)
    }

    /** The defaults for "about to run out"; the person can change both in the app's settings. */
    const val SOON_DAYS = 14L
    const val SOON_KM = 1500.0

    /**
     * For inspection, insurance and oil: the latest entry of that kind decides when the next one is due. Entries without
     * a due date or kilometre limit say nothing. Worst first. [odometerKm] is the car's current reading, if known; without
     * it only dates are checked.
     */
    fun upcoming(
        events: List<CarEvent>,
        today: LocalDate,
        odometerKm: Double?,
        soonDays: Long = SOON_DAYS,
        soonKm: Double = SOON_KM,
    ): List<Due> {
        val result = ArrayList<Due>()
        for (type in EventType.values().filter { it.hasDue }) {
            val latest = events.filter { it.type == type }.maxWithOrNull(compareBy<CarEvent> { it.date }) ?: continue
            val days = latest.dueDate?.let { ChronoUnit.DAYS.between(today, it) }
            val kmLeft = if (latest.nextAtKm != null && odometerKm != null) latest.nextAtKm - odometerKm else null
            if (days == null && latest.nextAtKm == null) continue
            val overdue = (days != null && days < 0) || (kmLeft != null && kmLeft < 0)
            val soon = (days != null && days <= soonDays) || (kmLeft != null && kmLeft <= soonKm)
            val level = if (overdue) DueLevel.OVERDUE else if (soon) DueLevel.SOON else DueLevel.OK
            result.add(Due(type, latest, latest.dueDate, days, latest.nextAtKm, kmLeft, level))
        }
        return result.sortedWith(compareBy<Due> { it.level.ordinal }.thenBy { it.daysLeft ?: Long.MAX_VALUE })
    }
}

/** What the person chose in the settings. */
data class ReminderSettings(
    val enabled: Boolean = true,
    val warnDays: Long = Reminders.SOON_DAYS,
    val warnKm: Double = Reminders.SOON_KM,
)

/** A car as the reminder check sees it. */
data class CarForReminders(val address: String, val name: String, val events: List<CarEvent>, val odometerKm: Double?)

/** One notification to show. [key] says which entry and which stage (soon / overdue) it is about. */
data class ReminderNotice(val car: CarForReminders, val due: Due, val key: String)

/**
 * Decides which notifications are due, so that each stage of each entry is announced once: when it comes within the
 * warning time, and again when it has run out. Renewing the entry (a new insurance, a new oil change) starts over.
 */
object ReminderPlanner {
    class Plan(val notices: List<ReminderNotice>, private val activeKeys: Set<String>) {
        /** The set to remember afterwards: old keys that still apply, plus the ones just announced. */
        fun nextNotified(previous: Set<String>): Set<String> = previous.intersect(activeKeys) + notices.map { it.key }
    }

    fun plan(cars: List<CarForReminders>, today: LocalDate, notified: Set<String>, settings: ReminderSettings): Plan {
        val notices = ArrayList<ReminderNotice>()
        val active = HashSet<String>()
        for (car in cars) {
            for (due in Reminders.upcoming(car.events, today, car.odometerKm, settings.warnDays, settings.warnKm)) {
                if (due.level == DueLevel.OK) continue
                val key = "${car.address.lowercase(Locale.ROOT)}|${due.event.id}|${due.level.name}"
                active.add(key)
                if (settings.enabled && key !in notified) notices.add(ReminderNotice(car, due, key))
            }
        }
        return Plan(notices, active)
    }
}

object EventCosts {
    /** Sum of the costs of events dated in [year], or of all of them when [year] is null. */
    fun total(events: List<CarEvent>, year: Int? = null): Double =
        events.filter { year == null || it.date.year == year }.sumOf { it.cost ?: 0.0 }
}

/** All cars' events in one small JSON file, keyed by Bluetooth address (case does not matter). */
class FileCarEventsStore(private val file: File) {

    /** Newest first. */
    @Synchronized
    fun list(address: String): List<CarEvent> = readAll()[address.lowercase(Locale.ROOT)].orEmpty().sortedByDescending { it.date }

    @Synchronized
    @Throws(IOException::class)
    fun save(address: String, event: CarEvent) {
        val all = readAll()
        val key = address.lowercase(Locale.ROOT)
        all[key] = all[key].orEmpty().filter { it.id != event.id } + event
        write(all)
    }

    @Synchronized
    @Throws(IOException::class)
    fun delete(address: String, id: String) {
        val all = readAll()
        val key = address.lowercase(Locale.ROOT)
        all[key] = all[key].orEmpty().filter { it.id != id }
        write(all)
    }

    @Synchronized
    @Throws(IOException::class)
    fun removeCar(address: String) {
        val all = readAll()
        if (all.remove(address.lowercase(Locale.ROOT)) != null) write(all)
    }

    private fun readAll(): LinkedHashMap<String, List<CarEvent>> {
        val out = LinkedHashMap<String, List<CarEvent>>()
        val text = try { if (file.isFile) file.readText() else null } catch (e: IOException) { null } ?: return out
        val root = try { Json.parseObject(text) } catch (e: Json.JsonException) { null } ?: return out
        for ((key, value) in root) {
            val rows = value as? List<*> ?: continue
            out[key.lowercase(Locale.ROOT)] = rows.mapNotNull { fromMap(it as? Map<*, *>) }
        }
        return out
    }

    private fun fromMap(m: Map<*, *>?): CarEvent? {
        m ?: return null
        val id = m["id"] as? String ?: return null
        val type = try { EventType.valueOf(m["type"] as? String ?: return null) } catch (e: IllegalArgumentException) { return null }
        val date = date(m["date"]) ?: return null
        return CarEvent(
            id = id,
            type = type,
            date = date,
            title = (m["title"] as? String)?.takeIf { it.isNotBlank() },
            dueDate = date(m["dueDate"]),
            odometerKm = m["odometerKm"] as? Double,
            nextAtKm = m["nextAtKm"] as? Double,
            cost = m["cost"] as? Double,
            note = (m["note"] as? String)?.takeIf { it.isNotBlank() },
        )
    }

    private fun date(any: Any?): LocalDate? =
        try { (any as? String)?.let { LocalDate.parse(it) } } catch (e: DateTimeParseException) { null }

    private fun toMap(e: CarEvent): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = e.id
        m["type"] = e.type.name
        m["date"] = e.date.toString()
        e.title?.let { m["title"] = it }
        e.dueDate?.let { m["dueDate"] = it.toString() }
        e.odometerKm?.let { m["odometerKm"] = it }
        e.nextAtKm?.let { m["nextAtKm"] = it }
        e.cost?.let { m["cost"] = it }
        e.note?.let { m["note"] = it }
        return m
    }

    private fun write(all: Map<String, List<CarEvent>>) {
        val json = Json.stringify(all.filterValues { it.isNotEmpty() }.mapValues { (_, list) -> list.map { toMap(it) } })
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
