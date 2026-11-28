package dev.drivelog.ha

import dev.drivelog.carapi.Json

/** What the person lets go to Home Assistant (the plugin's screen). */
data class HaChoice(val values: Boolean = true, val parking: Boolean = true, val services: Boolean = true)

/**
 * The body for the Home Assistant webhook: Drivelog's export state (`CarPluginContract.ACTION_EXPORT`) with only what
 * [HaChoice] allows. Null when the state is not JSON Drivelog would send.
 */
object HaPayload {
    fun of(state: String, choice: HaChoice): String? {
        val root = Json.parseObject(state) ?: return null
        val vehicles = (root["vehicles"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: return null
        val kept = vehicles.map { v ->
            val m = LinkedHashMap<String, Any?>()
            for ((k, value) in v) {
                val key = k as? String ?: continue
                val allowed = when (key) {
                    "values", "readAtMs" -> choice.values
                    "parked" -> choice.parking
                    "services" -> choice.services
                    else -> true
                }
                if (allowed) m[key] = value
            }
            m
        }
        return Json.stringify(linkedMapOf("format" to root["format"], "atMs" to root["atMs"], "vehicles" to kept))
    }

    /** The vehicles in [state], as id → name, for the YAML ([HaYaml]). */
    fun vehicles(state: String?): List<Pair<String, String>> {
        val root = state?.let { Json.parseObject(it) } ?: return emptyList()
        return ((root["vehicles"] as? List<*>) ?: emptyList<Any>()).mapNotNull { v ->
            val m = v as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"] as? String ?: return@mapNotNull null
            id to ((m["name"] as? String) ?: id)
        }
    }
}

/**
 * The Home Assistant configuration that turns the webhook's data into entities: trigger-based template sensors, one
 * set per vehicle (odometer, fuel, range, 12 V battery, doors, parking place with its coordinates for the map, and
 * the days left to each service). For `configuration.yaml`; a value the vehicle does not send keeps its last state.
 */
object HaYaml {
    private val SERVICES = listOf("inspection" to "inspection", "insurance" to "insurance", "oil_change" to "oil change")

    /**
     * Whether [url] is on the home network (a private address, `….local`, `localhost`): then the webhook may stay
     * `local_only`. Home Assistant drops requests from outside to a local-only webhook while still answering OK.
     */
    fun isLocal(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        if (host.endsWith(".local") || host == "localhost" || host == "homeassistant") return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
    }

    fun of(webhookId: String, vehicles: List<Pair<String, String>>, choice: HaChoice, local: Boolean = true): String = buildString {
        append("# Drivelog → Home Assistant: add to configuration.yaml, then restart Home Assistant.\n")
        append("template:\n")
        append("  - trigger:\n")
        append("      - platform: webhook   # every Home Assistant version takes this (newer ones also trigger: webhook)\n")
        append("        webhook_id: ").append(webhookId).append('\n')
        append("        allowed_methods: [POST]\n")
        if (local) append("        local_only: true   # the address is on the home network\n")
        else append("        local_only: false  # the address is outside the home network: the webhook must take requests from there\n")
        if (vehicles.isEmpty()) {
            append("    # No vehicle yet: the sensors appear here once Drivelog has sent its first state.\n")
            return@buildString
        }
        append("    sensor:\n")
        for ((id, name) in vehicles) {
            val v = "(trigger.json.vehicles | selectattr('id', 'eq', '$id') | list | first | default({}))"
            fun value(path: String) = "\"{{ $v$path }}\""
            fun sensor(what: String, key: String, extra: List<String>, state: String, attributes: List<String> = emptyList()) {
                append("      - name: \"").append(name.replace("\"", "'")).append(' ').append(what).append("\"\n")
                append("        unique_id: drivelog_").append(id).append('_').append(key).append('\n')
                for (e in extra) append("        ").append(e).append('\n')
                append("        state: ").append(state).append('\n')
                if (attributes.isNotEmpty()) {
                    append("        attributes:\n")
                    for (a in attributes) append("          ").append(a).append('\n')
                }
            }
            if (choice.values) {
                sensor("odometer", "odometer", listOf("unit_of_measurement: km", "device_class: distance", "state_class: total_increasing"),
                    value(".get('values', {}).get('odometerKm', this.state)"))
                sensor("fuel", "fuel", listOf("unit_of_measurement: L", "device_class: volume_storage"),
                    value(".get('values', {}).get('fuelLiters', this.state)"))
                sensor("range", "range", listOf("unit_of_measurement: km", "device_class: distance"),
                    value(".get('values', {}).get('rangeKm', this.state)"))
                sensor("12 V battery", "battery", listOf("unit_of_measurement: \"%\"", "device_class: battery"),
                    value(".get('values', {}).get('batteryPercent', this.state)"))
                sensor("doors", "doors", emptyList(),
                    "\"{% set p = $v.get('values', {}).get('openParts') %}{{ this.state if p is none else ('closed' if p | length == 0 else p | join(', ')) }}\"")
            }
            if (choice.parking) {
                sensor("parked", "parked", listOf("device_class: timestamp"),
                    "\"{% set p = $v.get('parked') %}{{ (p.atMs / 1000) | as_datetime if p and p.atMs else this.state }}\"",
                    listOf(
                        "latitude: \"{{ $v.get('parked', {}).get('lat', this.attributes.latitude | default(none)) }}\"",
                        "longitude: \"{{ $v.get('parked', {}).get('lon', this.attributes.longitude | default(none)) }}\"",
                    ))
            }
            if (choice.services) {
                for ((type, label) in SERVICES) {
                    val s = "($v.get('services', []) | selectattr('type', 'eq', '$type') | list | first | default({}))"
                    sensor("$label", type, listOf("unit_of_measurement: d"),
                        "\"{{ $s.get('daysLeft', this.state) }}\"",
                        listOf("due_date: \"{{ $s.get('dueDate') }}\"", "km_left: \"{{ $s.get('kmLeft') }}\"", "level: \"{{ $s.get('level') }}\""))
                }
            }
        }
    }
}
