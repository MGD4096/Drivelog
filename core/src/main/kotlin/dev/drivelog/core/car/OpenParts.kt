package dev.drivelog.core.car

import dev.drivelog.core.text.tr

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
