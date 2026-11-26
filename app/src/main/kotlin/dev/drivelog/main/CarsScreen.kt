package dev.drivelog.main

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import dev.drivelog.AppSettings
import dev.drivelog.AppState
import dev.drivelog.CarRef
import dev.drivelog.Colors
import dev.drivelog.MainActivity
import dev.drivelog.Phase
import dev.drivelog.Prefs
import dev.drivelog.Stores
import dev.drivelog.Ui
import dev.drivelog.car.CarActivity
import dev.drivelog.car.CarWidgets
import dev.drivelog.car.ServicePanel
import dev.drivelog.core.car.CarOrder
import dev.drivelog.core.car.LastKnownCar
import dev.drivelog.core.car.Reminders
import dev.drivelog.core.car.VehicleSummary
import dev.drivelog.core.text.tr
import dev.drivelog.core.text.trn
import dev.drivelog.core.trip.TripMeta
import dev.drivelog.map.WeatherTile
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * The "Cars" tab: one compact card per car. Tapping a card opens [CarActivity] with everything about that car. The
 * trip figures on the card are for the current calendar year only.
 */
class CarsScreen(
    private val activity: MainActivity,
    private val ui: Ui,
    io: ExecutorService,
) {
    private val stores = Stores(activity)
    private val details = stores.details
    private val readings = stores.readings
    private val events = stores.events
    private val widgets = CarWidgets(activity, ui, io)
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var trips: List<TripMeta> = emptyList()
    private var known: Map<String, LastKnownCar> = emptyMap()

    val view: View = ScrollView(activity).apply {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24))
            addView(ui.title(tr("Cars")))
            addView(list)
            addView(ui.gap(8))
            addView(ui.button(tr("Add a paired Bluetooth device")) { addCar() })
        }
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** [lastKnown]: fuel and range at the end of each car's latest trip, keyed by lower-case Bluetooth address. */
    fun refresh(allTrips: List<TripMeta>, lastKnown: Map<String, LastKnownCar>) {
        trips = allTrips
        known = lastKnown
        redraw()
    }

    /** Whether the archived cars are listed under the "Archive" button. */
    private var showArchive = false

    fun redraw() {
        widgets.releaseMaps()
        list.removeAllViews()
        val status = AppState.status
        val ordered = CarOrder.byLastDriven(Prefs.cars(activity), trips, { it.address }) { status.phase != Phase.IDLE && status.carName == it.name }
        val (archived, cars) = ordered.partition { details.get(it.address).archived }
        if (ordered.isEmpty()) {
            list.addView(ui.body().apply {
                text = tr("No car yet. Pair your car with the phone in Android's Bluetooth settings, then add it here. A trip starts by itself when the phone connects to a car on this list.")
            })
            return
        }
        list.addView(WeatherTile(activity, ui).here(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ui.dp(8)
        })
        for (car in cars) list.addView(card(car), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ui.dp(14)
        })
        if (archived.isNotEmpty()) {
            list.addView(ui.pill(tr("Archive ({0})", archived.size) + if (showArchive) "   ▴" else "   ▾") {
                showArchive = !showArchive
                redraw()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(14) })
            if (showArchive) for (car in archived) list.addView(card(car).apply { alpha = 0.7f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ui.dp(14)
            })
        }
    }

    fun release() = widgets.releaseMaps()

    private fun open(car: CarRef) {
        activity.startActivity(Intent(activity, CarActivity::class.java).putExtra(CarActivity.EXTRA_ADDRESS, car.address))
    }

    private fun card(car: CarRef): View {
        val d = details.get(car.address)
        val s = VehicleSummary.of(car.address, trips, details = d, reading = readings.get(car.address))
        val card = ui.card()
        card.isClickable = true
        card.setOnClickListener { open(car) }

        card.addView(widgets.photo(d.photo, 150))
        card.addView(ui.body().apply {
            text = if (d.year == null || d.model == null) car.name else "${d.year} ${d.model}"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, ui.dp(10), 0, 0)
        })
        card.addView(ui.body().apply {
            setPadding(0, ui.dp(8), 0, 0)
            text = buildString {
                // without when the values were read (the car's page says); a typed-in odometer with its date
                append(tr("Odometer: ")).append(widgets.odometerText(car, s, d, withWhen = false)).append('\n')
                if (d.usesPlugin) append(tr("Fuel: ")).append(widgets.fuelText(car, known[car.address.lowercase(Locale.ROOT)], withWhen = false)).append('\n')
                if (d.usesPlugin) {
                    val doors = widgets.doorsStatus(car, known[car.address.lowercase(Locale.ROOT)])
                    if (doors.closed != null) append(tr("Doors and windows: ")).append(doors.text).append('\n')
                }
                append(tr("This year: "))
                append(if (s.yearTripCount == 0) tr("no trips") else trn(s.yearTripCount, "{0} trip", "{0} trips") + ", " + tr("{0} km", ui.km(s.yearKm)))
            }
        })

        // What is coming up: oil, inspection and insurance as tiles, coloured by how close they are
        val settings = AppSettings.reminders(activity)
        val dues = Reminders.upcoming(events.list(car.address), LocalDate.now(), widgets.currentOdometerKm(car, s, d), settings.warnDays, settings.warnKm)
        card.addView(ServicePanel(activity, ui).build(dues, widgets.currentOdometerKm(car, s, d), compact = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.dp(10)
        })

        val lat = s.parkedLat
        val lon = s.parkedLon
        if (lat != null && lon != null) {
            card.addView(widgets.miniMap(lat, lon) { open(car) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(130)).apply {
                topMargin = ui.dp(10)
            })
            card.addView(ui.body().apply {
                text = tr("Parked since {0}", ui.dateTime(s.parkedAtMs ?: 0L))
                setTextColor(Colors.muted)
                textSize = 13f
                setPadding(0, ui.dp(2), 0, 0)
            })
        }
        return card
    }

    private fun addCar() {
        if (Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), MainActivity.REQ_BASIC)
            toast(tr("Allow Bluetooth access, then try again."))
            return
        }
        val devices = try {
            activity.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList()
        } catch (e: SecurityException) {
            null
        }
        if (devices.isNullOrEmpty()) {
            toast(tr("No paired devices found. Is Bluetooth on, and is the car paired?"))
            return
        }
        val labels = devices.map { d -> (try { d.name } catch (e: SecurityException) { null }) ?: d.address }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(tr("Which one is your car?"))
            .setItems(labels) { _, i ->
                Prefs.addCar(activity, CarRef(devices[i].address, labels[i]))
                redraw()
            }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
