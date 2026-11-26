package dev.drivelog.core.car

import java.util.Locale

/** Which car a home-screen widget shows. Plain rules, so the widget itself only draws. */
object WidgetChoice {
    /**
     * The address of the car to show, out of [cars] (addresses): the one picked for the widget ([chosen]) while it is
     * still among them; otherwise the car of the trip being recorded ([live]); otherwise the car whose values arrived
     * last ([readAtMs], keyed by lower-case address). Null when there is no car. Addresses compare case-insensitively.
     */
    fun pick(chosen: String?, cars: List<String>, live: String?, readAtMs: Map<String, Long>): String? {
        fun find(a: String?) = a?.let { cars.firstOrNull { c -> c.equals(it, ignoreCase = true) } }
        return find(chosen) ?: find(live) ?: cars.maxByOrNull { readAtMs[it.lowercase(Locale.ROOT)] ?: Long.MIN_VALUE }
    }
}
