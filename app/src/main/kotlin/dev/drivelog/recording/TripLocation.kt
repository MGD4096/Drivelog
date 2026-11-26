package dev.drivelog.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import dev.drivelog.core.text.tr
import dev.drivelog.core.trip.TripRecorder

/**
 * The phone's GPS during a trip: one fix a second, passed on as [TripRecorder.Fix] unless [paused] (the car is
 * disconnected and the person walks away from it). Main thread only.
 */
class TripLocation(private val context: Context, private val paused: () -> Boolean, private val onFix: (TripRecorder.Fix) -> Unit) {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private var registered = false

    /** Why there is no track (no GPS, not allowed), or null. */
    var problem: String? = null
        private set

    /** When the last fix arrived (0: none yet) and the speed it had, km/h. */
    var lastFixMs = 0L
        private set
    var lastSpeedKmh: Double? = null
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (paused()) return
            lastFixMs = System.currentTimeMillis()
            lastSpeedKmh = if (location.hasSpeed()) location.speed * 3.6 else null
            onFix(
                TripRecorder.Fix(
                    timeMs = location.time,
                    lat = location.latitude,
                    lon = location.longitude,
                    altitudeM = if (location.hasAltitude()) location.altitude else null,
                    speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                    accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                )
            )
        }

        // Android 10 and older call these on the interface; they have no default implementation there
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun start() {
        problem = null
        lastFixMs = 0L
        lastSpeedKmh = null
        val lm = manager
        val allowed = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (lm == null || !lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            problem = tr("no GPS")
            return
        }
        if (!allowed) {
            problem = tr("location not allowed")
            return
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            registered = true
        } catch (e: SecurityException) {
            problem = tr("location not allowed")
        }
    }

    fun stop() {
        if (!registered) return
        manager?.removeUpdates(listener)
        registered = false
    }
}
