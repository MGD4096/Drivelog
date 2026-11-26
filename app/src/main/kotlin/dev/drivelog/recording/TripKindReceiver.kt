package dev.drivelog.recording

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.drivelog.AppSettings
import dev.drivelog.Notifications
import dev.drivelog.Stores
import dev.drivelog.core.text.tr
import dev.drivelog.core.trip.TripKind
import java.io.IOException

/**
 * The "Business" / "Private" buttons of the "Trip finished" notification: marks that trip (and fills in the driver
 * from the settings when it has none), then replaces the notification with what was done. Not exported: only the
 * app's own notification can send it.
 */
class TripKindReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_TRIP) ?: return
        val kind = TripKind.parse(intent.getStringExtra(EXTRA_KIND)) ?: return
        val app = context.applicationContext
        AppSettings.applyLanguage(app)
        val pending = goAsync()
        Thread {
            try {
                val store = Stores(app).requireTrips()
                val trip = store.load(id)
                val done = trip != null && store.updateLog(id, trip.log.copy(kind = kind, driver = trip.log.driver ?: AppSettings.driver(app))) != null
                app.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_RESULT)
                if (done) Notifications.tripResult(app, tr("Marked as {0}. Purpose and route can be added in the trip.", kind.label.lowercase()))
            } catch (e: IOException) {
                // the trip can still be marked in the app
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val EXTRA_TRIP = "trip"
        const val EXTRA_KIND = "kind"
    }
}
