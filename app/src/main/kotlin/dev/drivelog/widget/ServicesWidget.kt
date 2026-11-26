package dev.drivelog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import dev.drivelog.car.CarActivity
import dev.drivelog.car.CarTexts
import dev.drivelog.core.car.DueLevel
import dev.drivelog.core.car.EventType
import dev.drivelog.core.car.Reminders
import dev.drivelog.core.text.tr
import dev.drivelog.reminders.ReminderCheck
import java.time.LocalDate

/**
 * The home-screen widget with the services coming up on all vehicles ([Reminders.acrossCars], the same entries the
 * reminders look at): the most pressing first, up to [ROWS] with the kind's icon, the vehicle and the kind, and how
 * long is left; overdue in the alert colour, soon in orange. Redrawn with the other widgets ([CarWidget.update]),
 * after the daily reminder check and when the service log changes; tapping a row opens that vehicle's page.
 * Never rename or move this class: placed widgets are kept by its name.
 */
class ServicesWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        private val ROW_IDS = intArrayOf(R.id.services_row1, R.id.services_row2, R.id.services_row3, R.id.services_row4)
        private val ICONS = intArrayOf(R.id.services_icon1, R.id.services_icon2, R.id.services_icon3, R.id.services_icon4)
        private val WHATS = intArrayOf(R.id.services_what1, R.id.services_what2, R.id.services_what3, R.id.services_what4)
        private val WHENS = intArrayOf(R.id.services_when1, R.id.services_when2, R.id.services_when3, R.id.services_when4)
        private val ROWS = ROW_IDS.size

        /** Keeps these request codes apart from the other widgets'. */
        private const val REQUEST_BASE = 2 shl 24

        /** Redraws every widget of this kind; cheap when there is none. Safe from any thread. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ServicesWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun icon(type: EventType): Int = when (type) {
            EventType.INSPECTION -> R.drawable.ic_service_inspection
            EventType.INSURANCE -> R.drawable.ic_service_insurance
            EventType.OIL_CHANGE -> R.drawable.ic_service_oil
            else -> R.drawable.ic_service_other
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = context.applicationContext
            Colors.dark = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            AppSettings.applyLanguage(app)
            val ui = Ui(app)
            val stores = Stores(app)
            val settings = AppSettings.reminders(app)
            val items = Reminders.acrossCars(ReminderCheck.cars(app), LocalDate.now(), settings.warnDays, settings.warnKm).take(ROWS)
            val normal = app.getColor(R.color.widget_text)
            val colours = mapOf(
                DueLevel.OVERDUE to app.getColor(R.color.widget_alert),
                DueLevel.SOON to app.getColor(R.color.widget_warn),
                DueLevel.OK to normal,
            )
            for (id in ids) {
                val views = RemoteViews(app.packageName, R.layout.widget_services)
                views.setTextViewText(R.id.services_title, tr("Coming up"))
                views.setViewVisibility(R.id.services_empty, if (items.isEmpty()) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.services_empty, tr("Nothing due in the next two months."))
                for (slot in 0 until ROWS) {
                    val item = items.getOrNull(slot)
                    views.setViewVisibility(ROW_IDS[slot], if (item == null) View.GONE else View.VISIBLE)
                    if (item == null) continue
                    val (car, due) = item
                    val colour = colours.getValue(due.level)
                    views.setImageViewResource(ICONS[slot], icon(due.type))
                    views.setInt(ICONS[slot], "setColorFilter", colour)
                    views.setTextViewText(WHATS[slot], stores.carName(car.address, car.name) + " · " + due.type.label)
                    views.setTextViewText(WHENS[slot], CarTexts.dueShort(ui, due) + " · " + CarTexts.limits(ui, due))
                    views.setTextColor(WHENS[slot], colour)
                    val open = Intent(app, CarActivity::class.java).putExtra(CarActivity.EXTRA_ADDRESS, car.address)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    views.setOnClickPendingIntent(ROW_IDS[slot], PendingIntent.getActivity(app, REQUEST_BASE + id * (ROWS + 1) + slot, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
                val main = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                views.setOnClickPendingIntent(R.id.services_root, PendingIntent.getActivity(app, REQUEST_BASE + id * (ROWS + 1) + ROWS, main, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                try {
                    manager.updateAppWidget(id, views)
                } catch (e: RuntimeException) {
                    // the launcher went away: it asks again when it comes back
                }
            }
        }
    }
}
