package dev.drivelog.car

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.drivelog.AppSettings
import dev.drivelog.AppState
import dev.drivelog.CarRef
import dev.drivelog.Colors
import dev.drivelog.Phase
import dev.drivelog.Prefs
import dev.drivelog.R
import dev.drivelog.Stores
import dev.drivelog.Ui
import dev.drivelog.core.car.CarDetails
import dev.drivelog.core.car.CarEvent
import dev.drivelog.core.car.CarHealth
import dev.drivelog.core.car.EventCosts
import dev.drivelog.core.car.FuelEntry
import dev.drivelog.core.car.LastKnownCar
import dev.drivelog.core.car.LastReading
import dev.drivelog.core.car.OpenPartsText
import dev.drivelog.core.car.Reminders
import dev.drivelog.core.car.VehicleKind
import dev.drivelog.core.car.VehicleSummary
import dev.drivelog.core.text.tr
import dev.drivelog.core.trip.CounterFuel
import dev.drivelog.core.trip.TripMeta
import dev.drivelog.trips.MileageExport
import dev.drivelog.trips.StatsActivity
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Everything about one car: its details (with the edit button), usage, where it is parked, what is about to run out
 * (inspection, insurance, oil) and a log of services and repairs with their costs.
 *
 * The activity loads the data and lays out the page; each part with dialogs of its own lives in its own class:
 * [CarPhotos], [CarInfoDialog], [CarEditor], [CarRecordActions], [ServiceLogPanel], [FuelPanel]. The common views are
 * in [CarPageViews].
 */
class CarActivity : Activity() {
    private val stores by lazy { Stores(this) }

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var ui: Ui
    private lateinit var widgets: CarWidgets
    private lateinit var views: CarPageViews
    private lateinit var content: LinearLayout
    private lateinit var address: String
    private var car: CarRef? = null
    private var trips: List<TripMeta> = emptyList()
    private var known: LastKnownCar? = null
    /** The latest values the car sent, saved apart from the trips. */
    private var reading: LastReading? = null
    /** This car's refuellings, newest first. */
    private var fuelEntries: List<FuelEntry> = emptyList()
    /** The 12 V battery at the start of the latest trips, oldest first. */
    private var batteryHistory: List<CarHealth.BatteryReading> = emptyList()

    private val fuelPanel by lazy { FuelPanel(this, ui, stores.fuel) }
    private val photos by lazy { CarPhotos(this, ui, widgets, stores.details, address, io) { render() } }
    private val infoDialog by lazy { CarInfoDialog(this, ui, views, widgets) }
    private val editor by lazy { CarEditor(this, ui, views, stores.details, address, { trips }) { render() } }
    private val serviceLog by lazy { ServiceLogPanel(this, ui, views, stores.details, stores.events, address, { trips }) { render() } }
    private val record by lazy {
        CarRecordActions(this, ui, stores, checkNotNull(car) { "the page is closing: no car" }, io,
            onChanged = { render() },
            onMoved = { target ->
                startActivity(Intent(this, CarActivity::class.java).putExtra(EXTRA_ADDRESS, target.address))
                finish()
            },
            onRemoved = { car = null; finish() },
        )
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** The odometer, fuel and doors part of the page, redrawn on its own while a trip runs ([refreshLive]). */
    private val liveBox by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
    private var liveKey: Pair<Boolean, Long>? = null
    private val liveTick = object : Runnable {
        override fun run() {
            refreshLive()
            handler.postDelayed(this, LIVE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Colors.prepare(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        widgets = CarWidgets(this, ui, io)
        views = CarPageViews(this, ui)

        address = intent.getStringExtra(EXTRA_ADDRESS) ?: run { finish(); return }
        car = Prefs.cars(this).firstOrNull { it.address.equals(address, true) }
        if (car == null) { finish(); return }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(32))
        }
        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Android 15 draws apps edge to edge: keep the content clear of the status and navigation bars
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                }
                insets
            }
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (car == null) return
        load()
        handler.postDelayed(liveTick, LIVE_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(liveTick)
        super.onPause()
    }

    /** Reads the trips (and what they tell about the car) in the background, then draws the page again. */
    private fun load() {
        io.execute {
            val store = stores.trips
            val list = store?.list() ?: emptyList()
            val r = stores.readings.get(address)
            val fuel = stores.fuel.list(address)
            val last = LastKnownCar.withReading(store?.let { LastKnownCar.find(address, list, it) }, r)
            val battery = store?.let { CarHealth.batteryHistory(address, list, it) }
            runOnUiThread {
                if (!isDestroyed) {
                    trips = list
                    known = last
                    reading = r
                    fuelEntries = fuel
                    batteryHistory = battery.orEmpty()
                    render()
                }
            }
        }
    }

    override fun onDestroy() {
        if (car != null) widgets.releaseMaps()
        io.shutdown()
        super.onDestroy()
    }

    @Deprecated("The framework Activity has no other way to get a result without libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (car != null) photos.onActivityResult(requestCode, resultCode, data)
    }

    // ---- the page ------------------------------------------------------------------------------------------------------
    private fun render() {
        val car = car ?: return
        widgets.releaseMaps()
        content.removeAllViews()
        val d = stores.details.get(address)
        val s = VehicleSummary.of(address, trips, details = d, reading = reading)
        val log = stores.events.list(address)
        val today = LocalDate.now()
        val odometer = widgets.currentOdometerKm(car, s, d)
        // header
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            // Back, title and menu: the same round buttons as the section headers, all centred on one line
            val barHeight = ui.dp(44)
            addView(views.roundButton("‹", tr("Back")) { finish() }, LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)))
            addView(ui.title(if (d.year == null || d.model == null) car.name else "${d.year} ${d.model}").apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, barHeight, 1f).apply { leftMargin = ui.dp(12); rightMargin = ui.dp(12) })
            val more = views.roundButton("⋮", tr("More options")) {}
            more.setOnClickListener {
                android.widget.PopupMenu(this@CarActivity, more).apply {
                    menu.add(0, 1, 0, tr("Edit car"))
                    menu.add(0, 4, 1, tr("Change Bluetooth device"))
                    menu.add(0, 3, 2, if (d.archived) tr("Restore from archive") else tr("Archive car"))
                    menu.add(0, 2, 3, tr("Remove car"))
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> editor.editCar(d)
                            2 -> record.confirmRemove(d)
                            4 -> record.changeBluetoothDevice()
                            3 -> record.toggleArchive(d)
                        }
                        true
                    }
                }.show()
            }
            addView(more, LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)))
        })
        if (d.archived) {
            content.addView(ui.card().apply {
                addView(ui.body().apply {
                    text = tr("This car is archived: no trips are recorded for it and no reminders are sent. Restore it from the ⋮ menu.")
                    textSize = 13f
                    setTextColor(Colors.subtle)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(8) })
        }
        val allPhotos = d.allPhotos
        content.addView(widgets.photo(d.photo, 210).apply {
            isClickable = true
            setOnClickListener { if (allPhotos.isEmpty()) photos.pick() else photos.openGallery(0) }
        })
        content.addView(views.sectionHeader(if (allPhotos.size > 1) tr("Photos ({0})", allPhotos.size) else tr("Photos"), "+", tr("Add photos")) { photos.pick() })
        if (allPhotos.isNotEmpty()) content.addView(photos.strip(allPhotos))
        // details
        content.addView(views.sectionHeader(tr("Details"), "i", "Car information") { infoDialog.show(CarInfoDialog.Facts(car, d, s, known, batteryHistory)) })
        content.addView(liveBox)
        liveKey = liveKeyNow()
        fillLive(car, s, d)
        content.addView(ui.card().apply {
            addView(ui.body().apply { text = tr("VIN"); textSize = 13f; setTextColor(Colors.muted) })
            addView(ui.body().apply {
                text = d.vin ?: tr("Not set. Add it with Edit car.")
                if (d.vin != null) { typeface = Typeface.MONOSPACE; textSize = 17f; letterSpacing = 0.05f } else setTextColor(Colors.muted)
                setTextIsSelectable(true)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        renderRest(car, s, d, log, today, odometer)
    }
    /** Whether a trip of this car is being recorded now: then the page shows the car's values as they come in. */
    private fun isLive(): Boolean {
        val st = AppState.status
        return st.phase != Phase.IDLE && st.carName == car?.name
    }
    /** Changes when the live part of the page has something new to show. */
    private fun liveKeyNow(): Pair<Boolean, Long> = if (isLive()) true to AppState.car.lastUpdateMillis else false to 0L
    /** Every [LIVE_MS] while the page is shown: redraws the odometer, fuel and doors when the car sent new values. */
    private fun refreshLive() {
        val c = car ?: return
        val key = liveKeyNow()
        if (key == liveKey) return
        val wasLive = liveKey?.first == true
        liveKey = key
        if (wasLive && !key.first) { load(); return }          // the trip has ended (and is saved): read everything again
        val d = stores.details.get(address)
        fillLive(c, VehicleSummary.of(address, trips, details = d, reading = reading), d)
    }
    /** The odometer and fuel tiles and the doors-and-windows card (with the 12 V battery and tyres in its corner). */
    private fun fillLive(car: CarRef, s: VehicleSummary, d: CarDetails) {
        liveBox.removeAllViews()
        fun split(text: String): Pair<String, String?> {                      // "50 030 km (live)" -> "50 030 km", "live"
            val i = text.indexOf(" (")
            return if (i < 0 || !text.endsWith(")")) text to null else text.substring(0, i) to text.substring(i + 2, text.length - 1)
        }
        val (odoMain, odoNote) = split(widgets.odometerText(car, s, d))
        val detailRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val odoTile = views.detailTile(tr("Odometer"), odoMain, if (d.usesPlugin) odoNote else (odoNote ?: tr("Tap to type it in"))).apply {
            if (!d.usesPlugin) {                                       // no plugin: the reading is typed in, here or in Edit car
                isClickable = true
                setOnClickListener { editor.editOdometer(d) }
            }
        }
        if (d.usesPlugin) {
            val (fuelMain, fuelNote) = split(widgets.fuelText(car, known))
            val fuelParts = fuelMain.split(" · ")
            detailRow.addView(odoTile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = ui.dp(4) })
            detailRow.addView(views.detailTile(tr("Fuel"), fuelParts.first(), (fuelParts.drop(1) + listOfNotNull(fuelNote)).joinToString(" · ").ifEmpty { null }),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = ui.dp(4) })
        } else {
            detailRow.addView(odoTile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        liveBox.addView(detailRow)
        if (d.usesPlugin && d.kind == VehicleKind.MOTORCYCLE) {
            liveBox.addView(motorcycleCard(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        } else if (d.usesPlugin) {
            val doors = widgets.doorsStatus(car, known)
            val colour = when (doors.closed) {
                true -> Colors.good
                false -> Colors.warn
                null -> Colors.muted
            }
            val openParts = OpenPartsText.parts(doors.parts)
            val silhouette = CarSilhouetteView(this).apply { setState(openParts.map { it.part }, doors.closed != null) }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(ui.body().apply { text = tr("Doors and windows"); textSize = 13f; setTextColor(Colors.muted) })
                addView(ui.body().apply {
                    text = if (doors.closed == true) tr("Closed") else if (doors.closed == false) tr("Open") else "—"
                    textSize = 22f; setTextColor(colour); setTypeface(typeface, Typeface.BOLD)
                })
                for (o in openParts) addView(ui.body().apply {
                    text = "•  " + o.part.display.replaceFirstChar { it.titlecase() } + if (o.tilted) tr(" (tilted)") else ""
                    textSize = 14f; setTextColor(colour); setPadding(0, ui.dp(2), 0, 0)
                })
                val note = doors.note ?: if (doors.closed == null) tr("Not reported yet. It is read from the car during and at the end of a trip.") else null
                if (note != null) addView(ui.body().apply { text = note; textSize = 12f; setTextColor(Colors.subtle); setPadding(0, ui.dp(8), 0, 0) })
            }
            val corner = healthCorner()
            liveBox.addView(ui.card().apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(silhouette, LinearLayout.LayoutParams(ui.dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(16) })
                if (corner != null) addView(corner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.TOP
                    leftMargin = ui.dp(8)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        }
    }
    /**
     * A motorcycle's card in place of the doors card (it has none): its drawing and the tyre pressures, with the 12 V
     * battery in the corner when the plugin reports it.
     */
    private fun motorcycleCard(): View {
        val snap = if (isLive()) AppState.car.snapshot() else known?.snapshot
        val pressures = snap?.tyrePressuresBar
        val warning = snap?.tyrePressureWarning == true
        // the maintainer's own line drawing (res/drawable-nodpi/motorcycle_side.png): a mask, tinted with the text
        // colour, so its inside is the card's colour like the car's drawing
        val drawing = ImageView(this).apply {
            setImageResource(R.drawable.motorcycle_side)
            setColorFilter(Colors.text)
            adjustViewBounds = true
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ui.body().apply { text = tr("Tyre pressures"); textSize = 13f; setTextColor(Colors.muted) })
            addView(ui.body().apply {
                text = pressures?.let { p -> tr("{0} bar", p.joinToString(" / ") { ui.f1(it) }) } ?: "—"
                textSize = 22f; setTextColor(if (warning) Colors.warn else if (pressures != null) Colors.good else Colors.muted)
                setTypeface(typeface, Typeface.BOLD)
            })
            if (pressures == null) addView(ui.body().apply {
                text = tr("Not reported yet. The plugin reads it after a ride, once the motorcycle has measured it.")
                textSize = 12f; setTextColor(Colors.subtle); setPadding(0, ui.dp(8), 0, 0)
            })
        }
        val corner = healthCorner(withTyres = false)
        return ui.card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(drawing, LinearLayout.LayoutParams(ui.dp(128), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(16) })
            if (corner != null) addView(corner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.TOP
                leftMargin = ui.dp(8)
            })
        }
    }

    /**
     * The top right corner of the doors card: the 12 V battery and the tyre pressure warning, live during a trip,
     * otherwise as the latest trips reported them. Null when the car has reported neither. [withTyres] false: the
     * battery alone (the motorcycle card shows the tyres itself).
     */
    private fun healthCorner(withTyres: Boolean = true): View? {
        val snap = if (isLive()) AppState.car.snapshot() else known?.snapshot
        val battery = snap?.batteryPercent ?: batteryHistory.lastOrNull()?.percent
        val tyres = snap?.tyrePressureWarning?.takeIf { withTyres }
        val pressures = snap?.tyrePressuresBar?.takeIf { withTyres }
        if (battery == null && tyres == null && pressures == null) return null
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
            fun item(label: String, value: String, colour: Int, top: Int) {
                addView(ui.body().apply { text = label; textSize = 12f; setTextColor(Colors.muted); gravity = android.view.Gravity.END; setPadding(0, top, 0, 0) })
                addView(ui.body().apply { text = value; textSize = 15f; setTextColor(colour); setTypeface(typeface, Typeface.BOLD); gravity = android.view.Gravity.END })
            }
            battery?.let { item(tr("12 V battery"), tr("{0} %", ui.f0(it)), if (CarHealth.isLow(it)) Colors.warn else Colors.good, 0) }
            tyres?.let { item(tr("Tyres"), if (it) tr("Warning") else tr("OK"), if (it) Colors.warn else Colors.good, if (battery != null) ui.dp(8) else 0) }
            if (tyres == null) pressures?.let { p -> item(tr("Tyres"), tr("{0} bar", p.joinToString(" / ") { ui.f1(it) }), Colors.good, if (battery != null) ui.dp(8) else 0) }
        }
    }
    /** Everything below the details: trips, where the car is parked, reminders and the service log. */
    private fun renderRest(car: CarRef, s: VehicleSummary, d: CarDetails, log: List<CarEvent>, today: LocalDate, odometer: Double?) {
        // trips
        val mine = trips.filter { it.carId.equals(address, ignoreCase = true) }          // newest first
        content.addView(views.sectionHeader(tr("Trips"), "›", "All trips of this car") {
            startActivity(Intent(this, CarTripsActivity::class.java).putExtra(EXTRA_ADDRESS, address))
        })
        val tilesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tilesRow.addView(views.tripTile("${today.year}", s.yearTripCount, s.yearKm), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(4) })
        tilesRow.addView(views.tripTile(tr("All time"), s.tripCount, s.totalKm), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(4) })
        content.addView(tilesRow)
        mine.firstOrNull()?.let { last ->
            val cells = ArrayList<View>()
            cells += views.statCell(ui.f1(last.summary.distanceKm), tr("km"))
            cells += views.statCell(ui.duration(last.summary.durationMs), tr("time"))
            last.summary.maxSpeedKmh?.let { cells += views.statCell(ui.f0(it), tr("top km/h")) }
            CounterFuel.consumption(last.summary, CounterFuel.factor(mine))?.let { (v, counter) -> cells += views.statCell(ui.f1(v) + if (counter) "*" else "", tr("L/100 km")) }
            content.addView(ui.card().apply {
                addView(ui.body().apply { text = tr("Last trip  ·  {0}", ui.dateTime(last.startMs)); setTextColor(Colors.subtle); textSize = 13f })
                addView(LinearLayout(this@CarActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, ui.dp(8), 0, ui.dp(4))
                    for (c in cells) addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.pill(tr("Mileage log")) { MileageExport(this@CarActivity, ui, io).start(car) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(4) })
            addView(ui.pill(tr("Statistics")) { startActivity(Intent(this@CarActivity, StatsActivity::class.java).putExtra(EXTRA_ADDRESS, address)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(4) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        // fuel
        if (d.usesPlugin || fuelEntries.isNotEmpty()) {
            content.addView(views.sectionHeader(tr("Fuel log"), "+", "Add a refuelling") { fuelPanel.edit(address, null, null, odometer) { load() } })
            content.addView(fuelPanel.build(address, mine, fuelEntries, log, today.year) { load() })
        } else {
            content.addView(views.sectionHeader(tr("Fuel log"), "+", "Add a refuelling") { fuelPanel.edit(address, null, null, odometer) { load() } })
            content.addView(ui.body().apply { text = tr("Add a refuelling with its price to see the real consumption and what a kilometre costs."); setTextColor(Colors.muted) })
        }
        // parked
        val lat = s.parkedLat
        val lon = s.parkedLon
        val at = s.parkedAtMs
        if (lat != null && lon != null && at != null) {
            content.addView(views.sectionHeader(tr("Parked"), "↗", "Open in maps app") { widgets.openInMapsApp(lat, lon, tr("Parked: {0}", car.name)) })
        } else {
            content.addView(views.sectionHeader(tr("Parked")))
        }
        if (lat == null || lon == null || at == null) {
            content.addView(ui.body().apply {
                text = tr("Not known yet. The position is saved when a trip with a GPS track ends.")
                setTextColor(Colors.muted)
            })
        } else {
            content.addView(ui.body().apply { text = tr("Since {0}", ui.dateTime(at)) })
            content.addView(widgets.miniMap(lat, lon) { widgets.openBigMap(lat, lon, tr("Parked: {0}", car.name)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(180)).apply { topMargin = ui.dp(6) })
        }
        // reminders
        content.addView(views.sectionHeader(tr("Reminders")))
        val settings = AppSettings.reminders(this)
        val due = Reminders.upcoming(log, today, odometer, settings.warnDays, settings.warnKm)
        content.addView(ServicePanel(this, ui).build(due, odometer))
        content.addView(ui.gap(6))
        if (due.isEmpty()) {
            content.addView(ui.body().apply {
                text = tr("Add an inspection, an insurance or an oil change below, with its valid-until date, to be reminded here and on the Cars tab.")
                setTextColor(Colors.muted)
            })
        }
        // service log
        content.addView(views.sectionHeader(tr("Service log and costs"), "+", "Add an event") { serviceLog.edit(null, odometer) })
        if (log.any { it.cost != null }) {
            val costly = log.filter { it.cost != null }
            val yearRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            yearRow.addView(views.costTile(tr("Spent in {0}", today.year), ui.money(EventCosts.total(log, today.year)), costly.count { it.date.year == today.year }),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(4) })
            yearRow.addView(views.costTile(tr("Spent in total"), ui.money(EventCosts.total(log)), costly.size),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(4) })
            content.addView(yearRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(6) })
        }
        if (log.isEmpty()) {
            content.addView(ui.body().apply { text = tr("Nothing logged yet."); setTextColor(Colors.muted) })
        }
        for (e in log) content.addView(serviceLog.row(e, odometer))
    }
    companion object {
        const val EXTRA_ADDRESS = "address"
        /** How often the page looks for new car values while a trip runs. */
        private const val LIVE_MS = 2_000L
    }
}
