package dev.drivelog.map

import android.content.Context
import android.location.Geocoder
import java.io.IOException
import java.util.Locale

/**
 * Place names for the mileage log's route, from the phone's own geocoder (Android's, which asks the phone's location
 * service). Null when the phone has none or it does not answer. Call it from a background thread.
 */
object Places {
    /** "Warszawa – Radom"; within one town the streets: "Warszawa (Marszałkowska – Puławska)". */
    fun route(context: Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        fun place(lat: Double, lon: Double): android.location.Address? = try {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
        } catch (e: IOException) {          // no network or the service is busy
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        val a = place(fromLat, fromLon) ?: return null
        val b = place(toLat, toLon) ?: return null
        val townA = a.locality ?: a.subAdminArea ?: a.adminArea
        val townB = b.locality ?: b.subAdminArea ?: b.adminArea
        if (townA != null && townA == townB) {
            val streetA = a.thoroughfare
            val streetB = b.thoroughfare
            return if (streetA != null && streetB != null && streetA != streetB) "$townA ($streetA – $streetB)" else townA
        }
        return listOfNotNull(townA, townB).joinToString(" – ").ifEmpty { null }
    }
}
