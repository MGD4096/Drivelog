package dev.idrivetrips

import dev.idrivetrips.core.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build

/** BluetoothDevice from an ACL broadcast, without the deprecated overload on newer Android. */
fun bluetoothDeviceFrom(intent: Intent): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

object Notifications {
    const val CHANNEL_TRIP = "trip"
    const val CHANNEL_ALERT = "alert"
    const val CHANNEL_REMINDER = "reminder"

    const val ID_TRIP = 1
    const val ID_RESULT = 2
    const val ID_ALERT = 3

    /** Extras that make MainActivity start a trip when it is opened from the "tap to start" notification. */
    const val EXTRA_START_ADDRESS = "start_address"
    const val EXTRA_START_NAME = "start_name"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRIP, tr("Trip recording"), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, tr("Problems and prompts"), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, tr("Inspection, insurance and service reminders"), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(context: Context, car: CarRef? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (car != null) {
            intent.putExtra(EXTRA_START_ADDRESS, car.address).putExtra(EXTRA_START_NAME, car.name)
        }
        return PendingIntent.getActivity(context, if (car == null) 0 else 10, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun tripNotification(context: Context, title: String, text: String): Notification {
        val stop = PendingIntent.getService(
            context, 1, Intent(context, TripService::class.java).setAction(TripService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL_TRIP)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_trip), tr("Stop and save"), stop,
                ).build()
            )
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    fun updateTrip(context: Context, title: String, text: String) {
        context.getSystemService(NotificationManager::class.java).notify(ID_TRIP, tripNotification(context, title, text))
    }

    /** Shown when Android refused to start the service from the background: one tap in the notification starts it. */
    fun tapToStart(context: Context, car: CarRef) {
        post(
            context, ID_ALERT, tr("{0} connected", car.name),
            tr("Android would not start trip recording in the background. Tap to start it."), openApp(context, car),
        )
    }

    fun problem(context: Context, title: String, text: String) {
        post(context, ID_ALERT, title, text, openApp(context))
    }

    fun tripResult(context: Context, text: String) {
        post(context, ID_RESULT, tr("Trip finished"), text, openApp(context), CHANNEL_TRIP)
    }

    /** A reminder about a car; tapping it opens that car's screen. [carAddress] null only for the settings test. */
    fun reminder(context: Context, id: Int, title: String, text: String, carAddress: String?) {
        val intent = if (carAddress != null) {
            Intent(context, CarActivity::class.java).putExtra(CarActivity.EXTRA_ADDRESS, carAddress)
        } else {
            Intent(context, MainActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(context, id, title, text, tap, CHANNEL_REMINDER)
    }

    private fun post(context: Context, id: Int, title: String, text: String, tap: PendingIntent, channel: String = CHANNEL_ALERT) {
        try {
            ensureChannels(context)
            val n = Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_trip)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: nothing to show, the app itself still has the information
        }
    }
}
