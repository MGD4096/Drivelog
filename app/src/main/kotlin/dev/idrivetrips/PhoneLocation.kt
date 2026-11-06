package dev.idrivetrips

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import dev.idrivetrips.core.Fix
import dev.idrivetrips.core.Fixes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Where the phone is now, for the weather. A rough position is enough (the weather request is rounded to about 1 km). */
object PhoneLocation {
    const val FRESH_MS = 15L * 60 * 1000
    const val OLD_MS = 6L * 60 * 60 * 1000

    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Blocks for up to [timeoutMs]: call it from a background thread. Null without permission or without any position. */
    @SuppressLint("MissingPermission")
    fun find(context: Context, timeoutMs: Long = 10_000): Fix? {
        if (!permitted(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val known = lastKnown(lm)
        Fixes.newest(known, now, FRESH_MS)?.let { return it }
        val fresh = requestOne(lm, timeoutMs)
        if (fresh != null) return fresh
        return Fixes.newest(known, System.currentTimeMillis(), OLD_MS)         // better an old place than none
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): List<Fix> {
        val out = ArrayList<Fix>()
        val providers = try { lm.getProviders(true) } catch (e: RuntimeException) { emptyList<String>() }
        for (p in providers) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } catch (e: RuntimeException) { null }
            if (l != null) out += Fix(l.latitude, l.longitude, l.time)
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun requestOne(lm: LocationManager, timeoutMs: Long): Fix? {
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { try { lm.isProviderEnabled(it) } catch (e: RuntimeException) { false } } ?: return null
        val latch = CountDownLatch(1)
        var result: Location? = null
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val signal = CancellationSignal()
                lm.getCurrentLocation(provider, signal, { it.run() }) { l -> result = l; latch.countDown() }
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) signal.cancel()
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) { result = l; latch.countDown() }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) lm.removeUpdates(listener)
            }
        } catch (e: SecurityException) {
            return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: RuntimeException) {
            return null
        }
        val l = result ?: return null
        return Fix(l.latitude, l.longitude, System.currentTimeMillis())
    }
}
