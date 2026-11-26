package dev.drivelog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.RemoteViews
import dev.drivelog.map.WidgetMap

/** What the home-screen widgets share: the setup before drawing, opening a screen, and handing the views over. */
internal object WidgetKit {
    /** The app context, with the colours and the language set as the launcher shows them (night mode follows the phone). */
    fun prepare(context: Context): Context {
        val app = context.applicationContext
        Colors.dark = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        AppSettings.applyLanguage(app)
        return app
    }

    /** The ids of the placed widgets of [provider]; empty when there is none (then there is nothing to draw). */
    fun ids(context: Context, provider: Class<*>): IntArray {
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return manager.getAppWidgetIds(ComponentName(context, provider))
    }

    /**
     * Runs [render] for the placed widgets of [provider] on the widgets' background thread ([WidgetMap.executor]: maps
     * may load tiles from the network). Safe from any thread; cheap when there is none.
     */
    fun inBackground(context: Context, provider: Class<*>, render: (Context, AppWidgetManager, IntArray) -> Unit) {
        val ids = ids(context, provider)
        if (ids.isEmpty()) return
        val app = context.applicationContext
        WidgetMap.executor.execute { render(app, AppWidgetManager.getInstance(app), ids) }
    }

    /** A tap that opens [intent]; [code] keeps each widget's taps apart. */
    fun open(context: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun push(manager: AppWidgetManager, id: Int, views: RemoteViews) {
        try {
            manager.updateAppWidget(id, views)
        } catch (e: RuntimeException) {
            // the launcher went away: it asks again when it comes back
        }
    }
}
