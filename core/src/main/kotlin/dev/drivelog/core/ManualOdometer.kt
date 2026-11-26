package dev.drivelog.core

import java.util.Locale
/**
 * The odometer of a car without a plugin: a reading typed in by hand, with the trips made since it was entered added on.
 * These rules decide when a typed reading replaces that sum; a wrong answer silently drops the trips counted so far.
 */
object ManualOdometer {
    /** A reading as shown in an edit field: whole kilometres. */
    fun text(km: Double?): String = km?.let { String.format(Locale.ROOT, "%.0f", it) } ?: ""

    /** What the odometer shows now (the reading plus its trips), as the edit field first holds it. */
    fun shownText(d: CarDetails, s: VehicleSummary): String = text(s.manualOdometerNow(d))

    /**
     * The car after the odometer field was saved. A field left as it was shown changes nothing, so the trips counted so far
     * stay counted; a changed one is a new reading, valid from [nowMs].
     */
    fun afterEdit(d: CarDetails, shownBefore: String, typedNow: String, km: Double?, nowMs: Long): CarDetails =
        if (typedNow.trim() == shownBefore.trim()) d else d.withManualOdometer(km, nowMs)

    /** The car after a service entry with [entryKm]: only a reading higher than what the odometer shows now raises it. */
    fun afterServiceEntry(d: CarDetails, s: VehicleSummary, entryKm: Double?, nowMs: Long): CarDetails {
        if (d.usesPlugin || entryKm == null) return d
        val shown = s.manualOdometerNow(d)
        return if (shown == null || entryKm > shown) d.withManualOdometer(entryKm, nowMs) else d
    }
}
