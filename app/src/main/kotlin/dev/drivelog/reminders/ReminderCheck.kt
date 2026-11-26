package dev.drivelog.reminders

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.drivelog.AppSettings
import dev.drivelog.Notifications
import dev.drivelog.Prefs
import dev.drivelog.ServicesWidget
import dev.drivelog.Stores
import dev.drivelog.Ui
import dev.drivelog.backup.AutoBackup
import dev.drivelog.car.CarTexts
import dev.drivelog.core.car.CarForReminders
import dev.drivelog.core.car.ReminderPlanner
import dev.drivelog.core.car.VehicleSummary
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Looks at every car's log and posts a notification for what has come within the warning time or run out. */
object ReminderCheck {

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Every vehicle in use (not archived) with its service log and its current odometer, as the reminders see them; the
     * services widget ([dev.drivelog.ServicesWidget]) shows the same.
     */
    fun cars(context: Context): List<CarForReminders> {
        val stores = Stores(context)
        val trips = stores.trips?.list() ?: emptyList()
        val details = stores.details
        return Prefs.cars(context).filter { !details.get(it.address).archived }.map { car ->
            val summary = VehicleSummary.of(car.address, trips, reading = stores.readings.get(car.address))
            CarForReminders(car.address, car.name, stores.events.list(car.address), CarTexts.currentOdometerKm(car, summary, details.get(car.address)))
        }
    }

    /** Safe to call from any thread, at any time: each stage of each entry is announced once. */
    @Synchronized
    fun run(context: Context) {
        val app = context.applicationContext
        ServicesWidget.update(app)                             // "days left" moves on every day, notifications or not
        val settings = AppSettings.reminders(app)
        if (!settings.enabled || !canNotify(app)) return      // nothing is marked as announced, so it is not lost

        val cars = cars(app)
        val before = AppSettings.notified(app)
        val plan = ReminderPlanner.plan(cars, LocalDate.now(), before, settings)

        val ui = Ui(app)
        for (notice in plan.notices) {
            Notifications.reminder(
                app,
                notice.key.hashCode(),
                "${notice.car.name}: ${notice.due.type.label}",
                CarTexts.dueDetail(ui, notice.due).replaceFirstChar { it.uppercase() },
                notice.car.address,
            )
        }
        AppSettings.setNotified(app, plan.nextNotified(before))
    }
}

/** Runs [ReminderCheck] and the weekly [AutoBackup] about once a day, also after the phone restarts. */
class ReminderJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            try {
                ReminderCheck.run(applicationContext)
                AutoBackup.runIfDue(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }, "reminder-check").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true       // try again later if Android cut it short
}

object ReminderScheduler {
    private const val JOB_ID = 7

    /** Makes sure the daily check is scheduled; harmless to call often. */
    fun ensureScheduled(context: Context) {
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            // a job kept from an older version may name the service's old class: scheduling again replaces it
            if (scheduler.getPendingJob(JOB_ID)?.service?.className == ReminderJobService::class.java.name) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ReminderJobService::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(24))
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        } catch (e: RuntimeException) {
            // Scheduling can be refused on some devices; the check still runs every time the app is opened
        }
    }
}
