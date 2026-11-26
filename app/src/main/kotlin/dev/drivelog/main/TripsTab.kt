package dev.drivelog.main

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.drivelog.Colors
import dev.drivelog.Phase
import dev.drivelog.Prefs
import dev.drivelog.ServiceStatus
import dev.drivelog.Ui
import dev.drivelog.core.text.tr
import dev.drivelog.core.trip.TripMeta
import dev.drivelog.trips.StatsActivity
import dev.drivelog.trips.TripActions

/** The Trips tab: a banner while a trip is being recorded, the summary tiles and every trip, newest first. */
class TripsTab(private val activity: Activity, private val ui: Ui, private val actions: TripActions) {
    private val banner = ui.body().apply {
        setBackgroundColor(Colors.soft)
        setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        visibility = View.GONE
    }
    private val summary = ui.body().apply { setTextColor(Colors.muted); setPadding(0, ui.dp(4), 0, ui.dp(8)) }
    private val tiles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    val view: View = ui.scrolling(ui.page().apply {
        addView(ui.title(tr("Trips")))
        addView(banner)
        addView(summary)
        addView(tiles)
        addView(ui.pill(tr("Statistics by month")) { activity.startActivity(Intent(activity, StatsActivity::class.java)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        addView(list)
    })

    fun show(trips: List<TripMeta>) {
        list.removeAllViews()
        tiles.removeAllViews()
        if (trips.isEmpty()) {
            summary.text = tr("No trips yet. Drive with the phone connected to a car on the Cars tab, or start one by hand on the Settings tab.")
            summary.visibility = View.VISIBLE
            return
        }
        summary.visibility = View.GONE
        tiles.addView(actions.summaryTiles(trips))
        actions.fill(list, trips, showCar = Prefs.cars(activity).size > 1)
    }

    /** A one-line banner while a trip is being recorded, whichever tab is open. */
    fun showStatus(s: ServiceStatus) {
        if (s.phase == Phase.IDLE) {
            banner.visibility = View.GONE
            return
        }
        val t = s.trip
        banner.visibility = View.VISIBLE
        banner.text = tr("Recording in {0}", s.carName) + (t?.let { " · ${ui.f1(it.distanceKm)} km · ${ui.duration(it.durationMs)}" } ?: "")
    }
}
