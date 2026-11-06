package dev.idrivetrips

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.idrivetrips.core.CarForReminders
import dev.idrivetrips.core.FileCarDetailsStore
import dev.idrivetrips.core.FileCarEventsStore
import dev.idrivetrips.core.FileTripStore
import dev.idrivetrips.core.ReminderPlanner
import dev.idrivetrips.core.VehicleSummary
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Looks at every car's log and posts a notification for what has come within the warning time or run out. */
object ReminderCheck {

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Safe to call from any thread, at any time: each stage of each entry is announced once. */
    @Synchronized
    fun run(context: Context) {
        val app = context.applicationContext
        val settings = AppSettings.reminders(app)
        if (!settings.enabled || !canNotify(app)) return      // nothing is marked as announced, so it is not lost

        val trips = try { FileTripStore(File(app.filesDir, "trips")).list() } catch (e: Exception) { emptyList() }
        val events = FileCarEventsStore(File(app.filesDir, "car_events.json"))
        val details = FileCarDetailsStore(File(app.filesDir, "cars.json"))
        val cars = Prefs.cars(app).filter { !details.get(it.address).archived }.map { car ->
            val summary = VehicleSummary.of(car.address, trips)
            CarForReminders(car.address, car.name, events.list(car.address), CarTexts.currentOdometerKm(car, summary, details.get(car.address)))
        }
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

/** Runs [ReminderCheck] about once a day, also after the phone restarts. */
class ReminderJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            try {
                ReminderCheck.run(applicationContext)
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
            if (scheduler.getPendingJob(JOB_ID) != null) return
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
