package dev.idrivetrips

import dev.idrivetrips.core.tr
import dev.idrivetrips.core.CarDetails
import dev.idrivetrips.core.Due
import dev.idrivetrips.core.DueLevel
import dev.idrivetrips.core.EventType
import dev.idrivetrips.core.VehicleSummary
import dev.idrivetrips.core.trn

/** Plain-language lines about things that run out. */
object CarTexts {

    /** "14 days left", "expired 5 days ago", "1,000 km left" ... joined with " · ". */
    fun dueShort(ui: Ui, due: Due): String {
        val parts = ArrayList<String>()
        due.daysLeft?.let { days ->
            parts += when {
                days < 0 -> tr("expired {0} ago", trn(-days, "{0} day", "{0} days"))
                days == 0L -> tr("ends today")
                else -> tr("{0} left", trn(days, "{0} day", "{0} days"))
            }
        }
        due.kmLeft?.let { km ->
            parts += if (km < 0) tr("{0} km over", ui.km(-km)) else tr("{0} km left", ui.km(km))
        }
        return if (parts.isEmpty()) (if (due.level == DueLevel.OK) tr("ok") else tr("check")) else parts.joinToString(" · ")
    }

    /** "valid until Mar 1, 2027" or "next change by Mar 1, 2027 or at 150,000 km". */
    fun limits(ui: Ui, due: Due): String {
        if (due.type == EventType.OIL_CHANGE) {
            val parts = ArrayList<String>()
            due.dueDate?.let { parts += tr("by {0}", ui.date(it)) }
            due.nextAtKm?.let { parts += tr("at {0} km", ui.km(it)) }
            return tr("next change {0}", parts.joinToString(tr(" or ")))
        }
        return tr("valid until {0}", due.dueDate?.let { ui.date(it) } ?: "—")
    }

    /** "valid until Mar 1, 2027 · 117 days left" */
    fun dueDetail(ui: Ui, due: Due): String = limits(ui, due) + " · " + dueShort(ui, due)

    /** The full block for the details screen. */
    fun dueLong(ui: Ui, due: Due): String = "${due.type.label}: ${limits(ui, due)}\n${dueShort(ui, due)}"

    /**
     * The two lines under a service icon. First the days left ("360 days"), then the kilometres left ("12,400 km"). A
     * tile with a date only shows the date underneath; one with kilometres only shows the target reading.
     */
    fun tileLines(ui: Ui, due: Due?): Pair<String, String> {
        if (due == null) return tr("not set") to ""
        val days = due.daysLeft?.let { d ->
            when {
                d < 0 -> tr("{0} over", trn(-d, "{0} day", "{0} days"))
                d == 0L -> tr("today")
                else -> trn(d, "{0} day", "{0} days")
            }
        }
        val km = due.kmLeft?.let { if (it < 0) tr("{0} km over", ui.km(-it)) else tr("{0} km", ui.km(it)) }
        return when {
            days != null && km != null -> days to km
            days != null -> days to (due.dueDate?.let { ui.date(it) } ?: "")
            km != null -> km to (due.nextAtKm?.let { tr("at {0} km", ui.km(it)) } ?: "")
            due.nextAtKm != null -> tr("at {0} km", ui.km(due.nextAtKm!!)) to tr("odometer unknown")
            else -> "—" to ""
        }
    }

    /**
     * The odometer reading to use now. A car without a plugin has the reading typed in by hand plus its trips since. Otherwise it is
     * live while a trip runs in this car, and from its latest trip after that.
     */
    fun currentOdometerKm(car: CarRef, s: VehicleSummary, d: CarDetails): Double? {
        if (!d.usesPlugin) return s.manualOdometerNow(d)
        val status = AppState.status
        if (status.phase != Phase.IDLE && status.carName == car.name) AppState.car.snapshot().odometerKm?.let { return it }
        return s.odometerKm
    }

}
