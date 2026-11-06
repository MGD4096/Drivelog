package dev.idrivetrips.core

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

private fun fmt(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

private fun isoTime(ms: Long): String = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

/** The most recent car sample at or before each point in time (values carry forward). */
private class SampleCursor(private val samples: List<CarSample>) {
    private var index = -1
    fun at(timeMs: Long): CarSample? {
        while (index + 1 < samples.size && samples[index + 1].timeMs <= timeMs) index++
        return if (index >= 0) samples[index] else null
    }
}

/** GPX 1.1 with the car values as extensions on every track point. */
object Gpx {
    const val NAMESPACE = "http://idrive-trips.local/gpx/1"

    fun write(trip: Trip): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Drivelog\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:idt=\"$NAMESPACE\">\n")
        val name = escape("${trip.carName} ${isoTime(trip.startMs)}")
        sb.append("  <metadata><name>$name</name><time>${isoTime(trip.startMs)}</time></metadata>\n")
        sb.append("  <trk>\n    <name>$name</name>\n    <trkseg>\n")
        val cursor = SampleCursor(trip.samples)
        for (p in trip.points) {
            sb.append("      <trkpt lat=\"${fmt(p.lat, 7)}\" lon=\"${fmt(p.lon, 7)}\">")
            p.altitudeM?.let { sb.append("<ele>${fmt(it, 1)}</ele>") }
            sb.append("<time>${isoTime(p.timeMs)}</time>")
            val s = cursor.at(p.timeMs)
            val ext = StringBuilder()
            p.speedMps?.let { ext.append("<idt:speed>${fmt(it, 2)}</idt:speed>") }
            s?.odometerKm?.let { ext.append("<idt:odometer>${fmt(it, 0)}</idt:odometer>") }
            s?.fuelLiters?.let { ext.append("<idt:fuel>${fmt(it, 1)}</idt:fuel>") }
            s?.outsideC?.let { ext.append("<idt:outside>${fmt(it, 1)}</idt:outside>") }
            if (ext.isNotEmpty()) sb.append("<extensions>").append(ext).append("</extensions>")
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** One row per track point, with the latest known car values. Decimal point and comma separator, UTC times. */
object Csv {
    const val HEADER = "time_utc,lat,lon,altitude_m,speed_kmh,accuracy_m,odometer_km,fuel_l,outside_c,inside_c"

    fun write(trip: Trip): String {
        val sb = StringBuilder(HEADER).append('\n')
        val cursor = SampleCursor(trip.samples)
        for (p in trip.points) {
            val s = cursor.at(p.timeMs)
            sb.append(isoTime(p.timeMs)).append(',')
                .append(fmt(p.lat, 7)).append(',')
                .append(fmt(p.lon, 7)).append(',')
                .append(p.altitudeM?.let { fmt(it, 1) } ?: "").append(',')
                .append(p.speedMps?.let { fmt(it * 3.6, 1) } ?: "").append(',')
                .append(p.accuracyM?.let { fmt(it, 1) } ?: "").append(',')
                .append(s?.odometerKm?.let { fmt(it, 0) } ?: "").append(',')
                .append(s?.fuelLiters?.let { fmt(it, 1) } ?: "").append(',')
                .append(s?.outsideC?.let { fmt(it, 1) } ?: "").append(',')
                .append(s?.insideC?.let { fmt(it, 1) } ?: "").append('\n')
        }
        return sb.toString()
    }
}
