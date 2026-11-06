package dev.idrivetrips

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.idrivetrips.core.CarDetails
import dev.idrivetrips.core.CarEvent
import dev.idrivetrips.core.CarInput
import dev.idrivetrips.core.CarPlugins
import dev.idrivetrips.core.DueLevel
import dev.idrivetrips.core.EventCosts
import dev.idrivetrips.core.EventType
import dev.idrivetrips.core.FileCarDetailsStore
import dev.idrivetrips.core.FileCarEventsStore
import dev.idrivetrips.core.FileTripStore
import dev.idrivetrips.core.LastKnownCar
import dev.idrivetrips.core.ManualOdometer
import dev.idrivetrips.core.Parsed
import dev.idrivetrips.core.Reminders
import dev.idrivetrips.core.TripMeta
import dev.idrivetrips.core.VehicleSummary
import dev.idrivetrips.core.Vin
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.Calendar
import java.util.concurrent.Executors
import dev.idrivetrips.core.tr
import dev.idrivetrips.core.trn

/**
 * Everything about one car: its details (with the edit button), usage, where it is parked, what is about to run out
 * (inspection, insurance, oil) and a log of services and repairs with their costs.
 */
class CarActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var ui: Ui
    private lateinit var widgets: CarWidgets
    private lateinit var details: FileCarDetailsStore
    private lateinit var events: FileCarEventsStore
    private lateinit var content: LinearLayout
    private lateinit var address: String
    private var car: CarRef? = null
    private var trips: List<TripMeta> = emptyList()
    private var known: LastKnownCar? = null
    private var photoCallback: ((List<Uri>) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Colors.prepare(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        widgets = CarWidgets(this, ui, io)
        details = FileCarDetailsStore(File(filesDir, "cars.json"))
        events = FileCarEventsStore(File(filesDir, "car_events.json"))

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
        io.execute {
            val store = try { FileTripStore(File(filesDir, "trips")) } catch (e: IOException) { null }
            val list = try { store?.list() ?: emptyList() } catch (e: Exception) { emptyList() }
            val last = try { store?.let { LastKnownCar.find(address, list, it) } } catch (e: Exception) { null }
            runOnUiThread {
                if (!isDestroyed) {
                    trips = list
                    known = last
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

    // ---- the page ------------------------------------------------------------------------------------------------------

    private fun render() {
        val car = car ?: return
        widgets.releaseMaps()
        content.removeAllViews()

        val d = details.get(address)
        val s = VehicleSummary.of(address, trips)
        val log = events.list(address)
        val today = LocalDate.now()
        val odometer = widgets.currentOdometerKm(car, s, d)

        // header
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            // Back, title and menu: the same round buttons as the section headers, all centred on one line
            val barHeight = ui.dp(44)
            addView(roundButton("‹", tr("Back")) { finish() }, LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)))
            addView(ui.title(if (d.year == null || d.model == null) car.name else "${d.year} ${d.model}").apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, barHeight, 1f).apply { leftMargin = ui.dp(12); rightMargin = ui.dp(12) })
            val more = roundButton("⋮", tr("More options")) {}
            more.setOnClickListener {
                android.widget.PopupMenu(this@CarActivity, more).apply {
                    menu.add(0, 1, 0, tr("Edit car"))
                    menu.add(0, 4, 1, tr("Change Bluetooth device"))
                    menu.add(0, 3, 2, if (d.archived) tr("Restore from archive") else tr("Archive car"))
                    menu.add(0, 2, 3, tr("Remove car"))
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> editCar(d)
                            2 -> confirmRemove(d)
                            4 -> changeBluetoothDevice()
                            3 -> {
                                try {
                                    details.put(address, details.get(address).copy(archived = !d.archived))
                                    toast(if (d.archived) tr("Car restored from the archive.") else tr("Car archived."))
                                } catch (e: IOException) {
                                    toast(tr("Could not save: {0}", e.message))
                                }
                                render()
                            }
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
            setOnClickListener { if (allPhotos.isEmpty()) pickPhotos { addPhotos(it) } else openGallery(0) }
        })
        content.addView(sectionHeader(if (allPhotos.size > 1) tr("Photos ({0})", allPhotos.size) else tr("Photos"), "+", tr("Add photos")) { pickPhotos { addPhotos(it) } })
        if (allPhotos.isNotEmpty()) content.addView(photoStrip(allPhotos))

        // details
        content.addView(sectionHeader(tr("Details"), "i", "Car information") { showCarInfo(d, s) })
        fun split(text: String): Pair<String, String?> {                      // "50 030 km (live)" -> "50 030 km", "live"
            val i = text.indexOf(" (")
            return if (i < 0 || !text.endsWith(")")) text to null else text.substring(0, i) to text.substring(i + 2, text.length - 1)
        }
        val (odoMain, odoNote) = split(widgets.odometerText(car, s, d))
        val detailRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val odoTile = detailTile(tr("Odometer"), odoMain, if (d.usesPlugin) odoNote else (odoNote ?: tr("Tap to type it in"))).apply {
            if (!d.usesPlugin) {                                       // no plugin: the reading is typed in, here or in Edit car
                isClickable = true
                setOnClickListener { editOdometer(d) }
            }
        }
        if (d.usesPlugin) {
            val (fuelMain, fuelNote) = split(widgets.fuelText(car, known))
            val fuelParts = fuelMain.split(" · ")
            detailRow.addView(odoTile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = ui.dp(4) })
            detailRow.addView(detailTile(tr("Fuel"), fuelParts.first(), (fuelParts.drop(1) + listOfNotNull(fuelNote)).joinToString(" · ").ifEmpty { null }),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = ui.dp(4) })
        } else {
            detailRow.addView(odoTile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        content.addView(detailRow)
        if (d.usesPlugin) {
            val doors = widgets.doorsStatus(car, known)
            val colour = when (doors.closed) {
                true -> Colors.good
                false -> Colors.warn
                null -> Colors.muted
            }
            val openParts = dev.idrivetrips.core.OpenPartsText.parts(doors.parts)
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
            content.addView(ui.card().apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(silhouette, LinearLayout.LayoutParams(ui.dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(16) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        }
        content.addView(ui.card().apply {
            addView(ui.body().apply { text = tr("VIN"); textSize = 13f; setTextColor(Colors.muted) })
            addView(ui.body().apply {
                text = d.vin ?: tr("Not set. Add it with Edit car.")
                if (d.vin != null) { typeface = Typeface.MONOSPACE; textSize = 17f; letterSpacing = 0.05f } else setTextColor(Colors.muted)
                setTextIsSelectable(true)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })

        // trips
        val mine = trips.filter { it.carId.equals(address, ignoreCase = true) }          // newest first
        content.addView(sectionHeader(tr("Trips"), "›", "All trips of this car") {
            startActivity(Intent(this, CarTripsActivity::class.java).putExtra(EXTRA_ADDRESS, address))
        })
        val tilesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tilesRow.addView(tripTile("${today.year}", s.yearTripCount, s.yearKm), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(4) })
        tilesRow.addView(tripTile(tr("All time"), s.tripCount, s.totalKm), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(4) })
        content.addView(tilesRow)
        mine.firstOrNull()?.let { last ->
            val cells = ArrayList<View>()
            cells += statCell(ui.f1(last.summary.distanceKm), tr("km"))
            cells += statCell(ui.duration(last.summary.durationMs), tr("time"))
            last.summary.maxSpeedKmh?.let { cells += statCell(ui.f0(it), tr("top km/h")) }
            last.summary.consumptionLPer100Km?.let { cells += statCell(ui.f1(it), tr("L/100 km")) }
            content.addView(ui.card().apply {
                addView(ui.body().apply { text = tr("Last trip  ·  {0}", ui.dateTime(last.startMs)); setTextColor(Colors.subtle); textSize = 13f })
                addView(LinearLayout(this@CarActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, ui.dp(8), 0, ui.dp(4))
                    for (c in cells) addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        }

        // parked
        val lat = s.parkedLat
        val lon = s.parkedLon
        val at = s.parkedAtMs
        if (lat != null && lon != null && at != null) {
            content.addView(sectionHeader(tr("Parked"), "↗", "Open in maps app") { widgets.openInMapsApp(lat, lon, tr("Parked: {0}", car.name)) })
        } else {
            content.addView(sectionHeader(tr("Parked")))
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
        content.addView(sectionHeader(tr("Reminders")))
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
        content.addView(sectionHeader(tr("Service log and costs"), "+", "Add an event") { editEvent(null, odometer) })
        if (log.any { it.cost != null }) {
            val costly = log.filter { it.cost != null }
            val yearRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            yearRow.addView(costTile(tr("Spent in {0}", today.year), ui.money(EventCosts.total(log, today.year)), costly.count { it.date.year == today.year }),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(4) })
            yearRow.addView(costTile(tr("Spent in total"), ui.money(EventCosts.total(log)), costly.size),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(4) })
            content.addView(yearRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(6) })
        }
        if (log.isEmpty()) {
            content.addView(ui.body().apply { text = tr("Nothing logged yet."); setTextColor(Colors.muted) })
        }
        for (e in log) content.addView(eventRow(e, odometer))
    }

    /** A section heading; with [symbol] it gets a round 40 dp button at its right end (add, open all, open in maps). */
    private fun sectionHeader(title: String, symbol: String? = null, description: String = "", onClick: () -> Unit = {}): View {
        val strong = Colors.strong
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        container.addView(ui.heading(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (symbol != null) {
            container.addView(roundButton(symbol, description, onClick), LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)).apply { topMargin = ui.dp(7) })
        }
        return container
    }

    /** A grey tile: small caption, the value big, and a line of detail (live, or when it was read). */
    private fun detailTile(label: String, value: String, note: String?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        background = android.graphics.drawable.GradientDrawable().apply { setColor(Colors.card); cornerRadius = ui.dp(14).toFloat() }
        addView(ui.body().apply { text = label; textSize = 13f; setTextColor(Colors.muted) })
        addView(ui.body().apply { text = value; textSize = 20f; setTypeface(typeface, Typeface.BOLD) })
        if (note != null) addView(ui.body().apply { text = note; textSize = 12f; setTextColor(Colors.subtle) })
    }

    /** A big number with a small caption under it. */
    private fun statCell(value: String, label: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(ui.body().apply { text = value; textSize = 19f; setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
        addView(ui.body().apply { text = label; textSize = 12f; setTextColor(Colors.muted) })
    }

    /** "2026" / "1,240 km" / "37 trips" on a light-blue tile; "no trips" when there are none. */
    /** A green tile for money: what it covers, the amount big, and how many entries it is made of. */
    private fun costTile(caption: String, amount: String, entries: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        background = android.graphics.drawable.GradientDrawable().apply { setColor(Colors.goodBg); cornerRadius = ui.dp(14).toFloat() }
        addView(ui.body().apply { text = caption; textSize = 13f; setTextColor(Colors.good) })
        addView(ui.body().apply {
            text = amount
            textSize = 22f; setTextColor(Colors.good); setTypeface(typeface, Typeface.BOLD); maxLines = 1
        })
        addView(ui.body().apply { text = trn(entries, "{0} entry", "{0} entries"); textSize = 13f; setTextColor(Colors.good) })
    }

    private fun tripTile(caption: String, count: Int, km: Double): View {
        val strong = Colors.strong
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Colors.soft); cornerRadius = ui.dp(14).toFloat() }
            addView(ui.body().apply { text = caption; textSize = 13f; setTextColor(strong) })
            addView(ui.body().apply {
                text = if (count == 0) "—" else "${ui.km(km)} km"
                textSize = 22f; setTextColor(strong); setTypeface(typeface, Typeface.BOLD); maxLines = 1
            })
            addView(ui.body().apply {
                text = if (count == 0) tr("no trips") else trn(count, "{0} trip", "{0} trips")
                textSize = 13f; setTextColor(strong)
            })
        }
    }

    /** The round light-blue button used in every header: a symbol on a circle, 30 dp (set the size in the layout params). */
    private fun roundButton(symbol: String, description: String, onClick: () -> Unit) = TextView(this).apply {
        text = symbol
        textSize = 22f
        setTextColor(Colors.strong)
        setTypeface(typeface, Typeface.BOLD)
        gravity = android.view.Gravity.CENTER
        includeFontPadding = false
        contentDescription = description
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Colors.soft)
        }
        isClickable = true
        setOnClickListener { onClick() }
        setPadding(0, 0, 0, ui.dp(1))
    }

    private fun serviceIcon(type: EventType): Int = when (type) {
        EventType.OIL_CHANGE -> R.drawable.ic_service_oil
        EventType.INSPECTION -> R.drawable.ic_service_inspection
        EventType.INSURANCE -> R.drawable.ic_service_insurance
        EventType.REPAIR -> R.drawable.ic_service_repair
        EventType.OTHER -> R.drawable.ic_service_other
    }

    /** One entry of the service log as a card: the icon of its kind, what and when, and the cost on the right. */
    private fun eventRow(e: CarEvent, odometer: Double?): View {
        val strong = Colors.strong
        val icon = ImageView(this).apply {
            setImageResource(serviceIcon(e.type))
            setColorFilter(strong)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Colors.soft) }
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.body().apply { text = e.displayTitle; setTypeface(typeface, Typeface.BOLD) })
            val when_ = ArrayList<String>()
            when_ += ui.date(e.date)
            e.odometerKm?.let { when_ += "${ui.km(it)} km" }
            addView(ui.body().apply { text = when_.joinToString("  ·  "); setTextColor(Colors.subtle); textSize = 13f })
            val next = ArrayList<String>()
            e.dueDate?.let { next += (if (e.type == EventType.OIL_CHANGE) tr("next by ") else tr("valid until ")) + ui.date(it) }
            e.nextAtKm?.let { next += tr("next at {0} km", ui.km(it)) }
            if (next.isNotEmpty()) addView(ui.body().apply { text = next.joinToString("  ·  "); setTextColor(strong); textSize = 13f })
            e.note?.let { addView(ui.body().apply { text = it; setTextColor(Colors.muted); textSize = 13f }) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Colors.card); cornerRadius = ui.dp(14).toFloat() }
            addView(icon, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(12) })
            e.cost?.let { cost ->
                addView(ui.body().apply { text = ui.money(cost); setTypeface(typeface, Typeface.BOLD) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(8) })
            }
            isClickable = true
            setOnClickListener { editEvent(e, odometer) }
        }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) }
        return row
    }

    // ---- editing the car -----------------------------------------------------------------------------------------------

    /** Opens the system picker; several pictures can be chosen at once. */
    fun pickPhotos(onPicked: (List<Uri>) -> Unit) {
        photoCallback = onPicked
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(pick, REQ_PHOTO)
        } catch (e: ActivityNotFoundException) {
            photoCallback = null
            toast(tr("No app found to pick a picture."))
        }
    }

    @Deprecated("The framework Activity has no other way to get a result without libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO) return
        val callback = photoCallback
        photoCallback = null
        if (resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it } }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isNotEmpty()) callback?.invoke(uris)
    }

    /** Copies the chosen pictures into the app and adds them to the gallery; the first one ever added becomes the main photo. */
    private fun addPhotos(uris: List<Uri>) {
        io.execute {
            val names = uris.mapNotNull { PhotoStore.import(this, it) }
            runOnUiThread {
                if (isDestroyed) { names.forEach { PhotoStore.delete(this, it) }; return@runOnUiThread }
                var d = details.get(address)
                val added = ArrayList<String>()
                for (n in names) {
                    val next = d.withPhoto(n)
                    if (next !== d && next != d) { d = next; added += n } else PhotoStore.delete(this, n)
                }
                try {
                    if (added.isNotEmpty()) details.put(address, d)
                } catch (e: IOException) {
                    added.forEach { PhotoStore.delete(this, it) }
                    toast(tr("Could not save: {0}", e.message))
                    return@runOnUiThread
                }
                when {
                    added.size < uris.size && added.isEmpty() -> toast(tr("Could not add the pictures (unreadable, or the gallery is full: {0} photos).", CarDetails.MAX_PHOTOS))
                    added.size < uris.size -> toast(tr("Added {0} of {1} pictures.", added.size, uris.size))
                    else -> toast(if (added.size == 1) tr("Photo added.") else tr("{0} photos added.", added.size))
                }
                render()
            }
        }
    }

    private fun openGallery(index: Int) {
        startActivity(Intent(this, PhotoGalleryActivity::class.java).putExtra(EXTRA_ADDRESS, address).putExtra(PhotoGalleryActivity.EXTRA_INDEX, index))
    }

    /** The photos as a row of small pictures; the main one is marked with a star. */
    private fun photoStrip(all: List<String>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        all.forEachIndexed { i, name ->
            val image = widgets.roundedImage(ui.dp(10)).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Colors.line)
                contentDescription = if (i == 0) tr("Main photo") else tr("Photo {0}", i + 1)
                setOnClickListener { openGallery(i) }
            }
            widgets.loadInto(image, name, 320)
            val cell = FrameLayout(this).apply {
                addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                if (i == 0) addView(TextView(this@CarActivity).apply {
                    text = "★"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    gravity = android.view.Gravity.CENTER
                    includeFontPadding = false
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Colors.primary) }
                }, FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), android.view.Gravity.TOP or android.view.Gravity.START).apply { setMargins(ui.dp(5), ui.dp(5), 0, 0) })
            }
            row.addView(cell, LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)).apply { if (i > 0) leftMargin = ui.dp(8) })
        }
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun field(hint: String, value: String?, type: Int, maxLength: Int = 60) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(maxLength))
        setText(value ?: "")
    }

    /** The "i" popup: the Bluetooth device and, for a car with a plugin, everything the plugin has reported. */
    private fun showCarInfo(d: CarDetails, s: VehicleSummary) {
        val c = car ?: return
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8)) }
        fun caption(text: String) = ui.body().apply {
            this.text = text.uppercase(); textSize = 11f; letterSpacing = 0.08f
            setTextColor(Colors.muted); setTypeface(typeface, Typeface.BOLD); setPadding(0, ui.dp(14), 0, ui.dp(4))
        }
        fun row(label: String, value: String?) {
            if (value == null) return
            form.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, ui.dp(5), 0, ui.dp(5))
                addView(ui.body().apply { text = label; textSize = 14f; setTextColor(Colors.muted) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.body().apply { text = value; textSize = 14f; gravity = android.view.Gravity.END; setTypeface(typeface, Typeface.BOLD) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            })
        }
        form.addView(caption(tr("Bluetooth device")))
        row(tr("Name"), c.name)
        row(tr("Address"), c.address)
        if (d.usesPlugin) {
            val live = AppState.status.phase != Phase.IDLE && AppState.status.carName == c.name
            val snap = if (live) AppState.car.snapshot() else known?.snapshot
            form.addView(caption(tr("Data from the plugin")))
            val source = when {
                live -> tr("Live: a trip is in progress")
                known != null -> tr("As reported at the end of the trip on {0}", ui.date(known!!.atMs))
                else -> tr("Nothing received yet: it appears after the first trip with the plugin")
            }
            form.addView(ui.body().apply { text = source; textSize = 12f; setTextColor(Colors.muted); setPadding(0, 0, 0, ui.dp(4)) })
            row(tr("Plugin"), CarPlugins.all.firstOrNull { it.id == d.plugin }?.label)
            val odo = (snap?.odometerKm ?: s.odometerKm)
            row(tr("Odometer"), odo?.let { tr("{0} km", ui.km(it)) })
            row(tr("Fuel in tank"), snap?.fuelLiters?.let { "${ui.f0(it)} L" })
            row(tr("Range"), snap?.rangeKm?.let { tr("{0} km", ui.f0(it)) })
            row(tr("Average consumption"), snap?.avgConsumptionKmPerL?.takeIf { it > 0 }?.let { "${ui.f1(100.0 / it)} L/100 km" })
            row(tr("Outside temperature"), snap?.outsideC?.let { "${ui.f1(it)} °C" })
            row(tr("Inside temperature"), snap?.insideC?.let { "${ui.f1(it)} °C" })
            val doors = widgets.doorsStatus(c, known)
            row(tr("Doors and windows"), if (doors.closed == null) null else doors.text)
            if (snap == null && s.odometerKm == null) form.addView(ui.body().apply { text = tr("No data yet."); textSize = 14f })
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Car information"))
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(tr("OK"), null)
            .show()
    }

    private fun editCar(old: CarDetails) {
        var saved = false

        val strong = Colors.strong
        val grey = Colors.card
        fun rounded(color: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = ui.dp(12).toFloat() }
        fun caption(text: String) = ui.body().apply {
            this.text = text.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(Colors.muted)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, ui.dp(16), 0, ui.dp(6))
        }
        fun labelled(label: String, input: EditText, hint: String): LinearLayout {
            input.hint = hint
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.body().apply { text = label; textSize = 13f; setTextColor(Colors.subtle) })
                addView(input)
            }
        }

        val modelField = field("", old.model, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val yearField = field("", old.year?.toString(), InputType.TYPE_CLASS_NUMBER, 4)
        val engineField = field("", old.engineCc?.let { "$it cc" }, InputType.TYPE_CLASS_TEXT, 12)
        val vinField = field(
            "", old.vin,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 24,
        )

        var pluginOn = old.usesPlugin
        var pluginIndex = CarPlugins.all.indexOfFirst { it.id == old.plugin }.coerceAtLeast(0)
        val pluginPicker = ui.body().apply {
            textSize = 16f
            setTextColor(Colors.text)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = rounded(grey)
            isClickable = true
        }
        fun showPlugin() { pluginPicker.text = "${CarPlugins.all[pluginIndex].label}   ▾" }
        showPlugin()
        pluginPicker.setOnClickListener {
            AlertDialog.Builder(this@CarActivity)
                .setTitle(tr("Plugin"))
                .setItems(CarPlugins.all.map { it.label }.toTypedArray()) { _, which -> pluginIndex = which; showPlugin() }
                .show()
        }
        val odometerField = field("", ManualOdometer.shownText(old, VehicleSummary.of(address, trips)), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 9)
        val odometerAtStart = odometerField.text.toString()
        val pluginBox = labelled(tr("Plugin"), EditText(this).also { it.visibility = View.GONE }, "").apply { addView(pluginPicker) }
        val odometerBox = labelled(tr("Odometer (km); trips are added to it automatically"), odometerField, tr("e.g. 154 000"))
        val hint = ui.body().apply { textSize = 12f; setTextColor(Colors.muted); setPadding(0, ui.dp(6), 0, 0) }

        // where the car's data comes from: two big choices instead of a checkbox
        val sourceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sourceTiles = ArrayList<Triple<LinearLayout, TextView, TextView>>()
        fun updatePluginFields() {
            for ((i, t) in sourceTiles.withIndex()) {
                val chosen = (i == 0) == pluginOn
                t.first.background = rounded(if (chosen) strong else grey)
                t.second.setTextColor(if (chosen) Color.WHITE else Colors.text)
                t.third.setTextColor(if (chosen) Color.argb(220, 255, 255, 255) else Colors.muted)
            }
            pluginBox.visibility = if (pluginOn) View.VISIBLE else View.GONE
            odometerBox.visibility = if (pluginOn) View.GONE else View.VISIBLE
            hint.text = if (pluginOn) tr("Odometer, fuel, doors and windows come from the head-unit plugin.")
                else tr("No data from the car: type in the odometer once; every trip you record is added to it.")
        }
        for ((i, pair) in listOf(tr("Plugin") to tr("Reports odometer, fuel, doors"), tr("No plugin") to tr("Odometer typed in by hand")).withIndex()) {
            val title = ui.body().apply { text = pair.first; textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
            val sub = ui.body().apply { text = pair.second; textSize = 11f }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                isClickable = true
                addView(title)
                addView(sub)
                setOnClickListener { pluginOn = i == 0; updatePluginFields() }
            }
            sourceTiles += Triple(box, title, sub)
            sourceRow.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 0) rightMargin = ui.dp(8) })
        }
        updatePluginFields()

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(0), ui.dp(20), ui.dp(8))
            // The Bluetooth device is changed from the ⋮ menu, not here
            addView(caption(tr("Car")))
            addView(labelled(tr("Make and model"), modelField, tr("e.g. BMW M240i")))
            addView(LinearLayout(this@CarActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(labelled(tr("Year"), yearField, tr("e.g. 2019")), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(10) })
                addView(labelled(tr("Engine"), engineField, tr("3.0 or 2998 cc")), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
            addView(labelled(tr("VIN (17 characters)"), vinField, tr("optional")), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
            addView(caption(tr("Data source")))
            addView(sourceRow)
            addView(hint)
            addView(pluginBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
            addView(odometerBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Edit car"))
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(tr("Save"), null)          // set below, so that a mistake keeps the dialog open
            .setNegativeButton(tr("Cancel"), null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val year = CarInput.parseYear(yearField.text.toString(), Calendar.getInstance().get(Calendar.YEAR))
            val engine = CarInput.parseEngineCc(engineField.text.toString())
            val vin = Vin.parse(vinField.text.toString())
            yearField.error = year.error
            engineField.error = engine.error
            vinField.error = vin.error
            val odometer = if (pluginOn) Parsed<Double>(null) else CarInput.parseOdometerKm(odometerField.text.toString())
            odometerField.error = odometer.error
            if (!year.ok || !engine.ok || !vin.ok || !odometer.ok) return@setOnClickListener

            try {
                // The photos are managed in the gallery: keep them as they are now (they may have changed while this was open)
                var updated = details.get(address).copy(
                    vin = vin.value, model = modelField.text.toString().trim().ifEmpty { null }, year = year.value, engineCc = engine.value,
                    usesPlugin = pluginOn,
                    plugin = if (pluginOn) CarPlugins.all[pluginIndex.coerceIn(0, CarPlugins.all.size - 1)].id else old.plugin,
                )
                // The typed reading counts as entered now when it was changed here; with a plugin it is left alone
                if (!pluginOn) updated = ManualOdometer.afterEdit(updated, odometerAtStart, odometerField.text.toString(), odometer.value, System.currentTimeMillis())
                details.put(address, updated)
            } catch (e: IOException) {
                toast(tr("Could not save: {0}", e.message))
                return@setOnClickListener
            }
            saved = true
            dialog.dismiss()
            render()
        }
    }

    /** What the odometer of a car without a plugin shows now: the reading typed in plus the trips recorded since. */
    /** For a car without a plugin: type in the odometer reading now. */
    private fun editOdometer(d: CarDetails) {
        val input = field(tr("Odometer in km"), ManualOdometer.shownText(d, VehicleSummary.of(address, trips)), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 9)
        val shownAtStart = input.text.toString()
        val box = LinearLayout(this).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Odometer"))
            .setView(box)
            .setPositiveButton(tr("Save"), null)
            .setNegativeButton(tr("Cancel"), null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val km = CarInput.parseOdometerKm(input.text.toString())
            input.error = km.error
            if (!km.ok) return@setOnClickListener
            try {
                details.put(address, ManualOdometer.afterEdit(details.get(address), shownAtStart, input.text.toString(), km.value, System.currentTimeMillis()))
            } catch (e: IOException) {
                toast(tr("Could not save: {0}", e.message))
                return@setOnClickListener
            }
            dialog.dismiss()
            render()
        }
    }

    /** Points this car at another paired Bluetooth device (a new radio, a new phone pairing); everything recorded so far moves along. */
    private fun changeBluetoothDevice() {
        val car = car ?: return
        if (AppState.status.phase != Phase.IDLE && AppState.status.carName == car.name) {
            toast(tr("A trip is being recorded for this car. Change the device after it ends."))
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), MainActivity.REQ_BASIC)
            toast(tr("Allow Bluetooth access, then try again."))
            return
        }
        val taken = Prefs.cars(this).map { it.address.lowercase() }.toSet()
        val devices = try {
            getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices?.toList()
        } catch (e: SecurityException) {
            null
        }?.filter { it.address.lowercase() !in taken }.orEmpty()
        if (devices.isEmpty()) {
            toast(tr("No other paired devices found. Pair the new device in Android's Bluetooth settings first."))
            return
        }
        val labels = devices.map { d -> (try { d.name } catch (e: SecurityException) { null }) ?: d.address }
        AlertDialog.Builder(this)
            .setTitle(tr("Which device is this car now?"))
            .setItems(labels.toTypedArray()) { _, i -> confirmDeviceChange(CarRef(devices[i].address, labels[i])) }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun confirmDeviceChange(target: CarRef) {
        val old = car ?: return
        AlertDialog.Builder(this)
            .setTitle(tr("Use {0} for this car?", target.name))
            .setMessage(tr("The trips, photos and service log stay with the car. From now on a trip starts when the phone connects to {0}, not to {1}.", target.name, old.name))
            .setPositiveButton(tr("Change")) { _, _ -> moveCarTo(old, target) }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun moveCarTo(old: CarRef, target: CarRef) {
        val progress = AlertDialog.Builder(this).setMessage(tr("Moving the history…")).setCancelable(false).show()
        io.execute {
            var store: FileTripStore? = null
            var error: String? = null
            try {
                store = FileTripStore(File(filesDir, "trips"))
                store.reassignCar(old.address, target.address, target.name)
                details.moveCar(old.address, target.address)
                events.moveCar(old.address, target.address)
                Prefs.replaceCar(this, old.address, target)
            } catch (e: IOException) {
                error = e.message ?: e.toString()
                try { store?.reassignCar(target.address, old.address, old.name) } catch (x: IOException) { /* best effort */ }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (error != null) {
                    toast(tr("Could not change the device: {0}", error))
                } else {
                    toast(tr("Bluetooth device changed."))
                    startActivity(Intent(this, CarActivity::class.java).putExtra(EXTRA_ADDRESS, target.address))
                    finish()
                }
            }
        }
    }

    private fun confirmRemove(d: CarDetails) {
        val car = car ?: return
        AlertDialog.Builder(this)
            .setTitle(tr("Remove {0}?", car.name))
            .setMessage(
                tr("Trips already recorded stay in the Trips tab. The photos, the details and the service log of this car are deleted, and trips will no longer start when the phone connects to it.")
            )
            .setPositiveButton(tr("Remove")) { _, _ ->
                Prefs.removeCar(this, address)
                d.allPhotos.forEach { PhotoStore.delete(this, it) }
                try { details.remove(address) } catch (e: IOException) { /* the entry stays unused */ }
                try { events.removeCar(address) } catch (e: IOException) { /* likewise */ }
                this.car = null
                finish()
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    // ---- editing the log -----------------------------------------------------------------------------------------------

    private fun pickDate(initial: LocalDate, onPicked: (LocalDate) -> Unit) {
        DatePickerDialog(this, { _, y, m, day -> onPicked(LocalDate.of(y, m + 1, day)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    private fun editEvent(existing: CarEvent?, odometer: Double?) {
        var type = existing?.type ?: EventType.OIL_CHANGE
        var date = existing?.date ?: LocalDate.now()
        var due: LocalDate? = existing?.dueDate ?: if (existing == null && type.hasDue) date.plusYears(1) else null
        var dueTouched = existing != null

        val strong = Colors.strong
        val grey = Colors.card
        fun rounded(color: Int, dp: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = ui.dp(dp).toFloat() }
        fun caption(text: String) = ui.body().apply {
            this.text = text.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(Colors.muted)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, ui.dp(16), 0, ui.dp(6))
        }
        fun labelled(label: String, input: EditText, hint: String): View {
            input.hint = hint
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.body().apply { text = label; textSize = 13f; setTextColor(Colors.subtle) })
                addView(input)
            }
        }
        /** A grey tile showing a small caption and a value; tapping it runs [onTap]. */
        fun tile(onTap: () -> Unit): Triple<LinearLayout, TextView, TextView> {
            val cap = ui.body().apply { textSize = 12f; setTextColor(Colors.muted) }
            val value = ui.body().apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                background = rounded(grey, 12)
                isClickable = true
                setOnClickListener { onTap() }
                addView(cap)
                addView(value)
            }
            return Triple(box, cap, value)
        }

        // what kind of entry: one tile per type, the chosen one filled in blue
        val typeIcons = HashMap<EventType, Pair<LinearLayout, Pair<ImageView, TextView>>>()
        val shortNames = mapOf(
            EventType.OIL_CHANGE to tr("Oil"), EventType.INSPECTION to tr("Inspection"), EventType.INSURANCE to tr("Insurance"),
            EventType.REPAIR to tr("Repair"), EventType.OTHER to tr("Other"),
        )
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val titleField = field("", existing?.title, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 80)
        val nextKmField = field("", existing?.nextAtKm?.let { String.format(java.util.Locale.ROOT, "%.0f", it) }, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 9)
        val odoField = field(
            "", (existing?.odometerKm ?: if (existing == null) odometer else null)?.let { String.format(java.util.Locale.ROOT, "%.0f", it) },
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 9,
        )
        val costField = field("", existing?.cost?.let { String.format(java.util.Locale.ROOT, "%.2f", it) }, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 12)
        val noteField = field("", existing?.note, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 200)

        var changed: () -> Unit = {}   // set to the redraw once everything it touches exists
        val (dateBox, _, dateValue) = tile { pickDate(date) { date = it; if (!dueTouched && type.hasDue) due = date.plusYears(1); changed() } }
        val (dueBox, dueCaption, dueValue) = tile { pickDate(due ?: date.plusYears(1)) { due = it; dueTouched = true; changed() } }
        val dueClear = ui.body().apply {
            text = "✕"
            textSize = 18f
            setTextColor(Colors.muted)
            gravity = android.view.Gravity.CENTER
            contentDescription = tr("Clear the date")
            isClickable = true
            setOnClickListener { due = null; dueTouched = true; changed() }
        }
        val dueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(dueBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(dueClear, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        }
        val titleBox = labelled("", titleField, "")
        val nextKmBox = labelled(tr("Next change at (km)"), nextKmField, tr("e.g. 215 000"))

        fun dueLabel() = if (type == EventType.OIL_CHANGE) tr("Next change by") else tr("Valid until")

        fun refresh() {
            for ((t, parts) in typeIcons) {
                val (box, iconAndText) = parts
                val chosen = t == type
                box.background = rounded(if (chosen) strong else grey, 12)
                iconAndText.first.setColorFilter(if (chosen) Color.WHITE else strong)
                iconAndText.second.setTextColor(if (chosen) Color.WHITE else Colors.subtle)
            }
            (titleBox as LinearLayout).getChildAt(0).let { (it as TextView).text = if (type.hasDue) tr("Details (optional)") else tr("What was done") }
            titleField.hint = if (type.hasDue) tr("e.g. oil type, insurer") else tr("e.g. new brake pads (required)")
            dateValue.text = ui.date(date)
            (dateBox.getChildAt(0) as TextView).text = if (type.hasDue) tr("Date done or bought") else tr("Date")
            dueRow.visibility = if (type.hasDue) View.VISIBLE else View.GONE
            dueCaption.text = dueLabel()
            dueValue.text = due?.let { ui.date(it) } ?: tr("Not set")
            nextKmBox.visibility = if (type == EventType.OIL_CHANGE) View.VISIBLE else View.GONE
        }
        fun defaultDue() { if (!dueTouched && type.hasDue) due = date.plusYears(1) }

        for (t in EventType.values()) {
            val icon = ImageView(this).apply { setImageResource(serviceIcon(t)); scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val name = ui.body().apply { text = shortNames[t]; textSize = 10f; gravity = android.view.Gravity.CENTER; maxLines = 1; includeFontPadding = false }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(6))
                isClickable = true
                contentDescription = t.label
                addView(icon, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { bottomMargin = ui.dp(3) })
                addView(name)
                setOnClickListener { type = t; defaultDue(); refresh() }
            }
            typeIcons[t] = box to (icon to name)
            typeRow.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (t != EventType.OTHER) rightMargin = ui.dp(4) })
        }
        changed = { refresh() }
        refresh()

        val numbers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val odoBox = labelled(if (details.get(address).usesPlugin) tr("Odometer (km)") else tr("Odometer (km) – updates the car"), odoField, tr("km"))
            val costBox = labelled(tr("Cost ({0})", ui.currencySymbol()), costField, "0.00")
            addView(odoBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(10) })
            addView(costBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(0), ui.dp(20), ui.dp(8))
            addView(caption(tr("Type")))
            addView(typeRow)
            addView(caption(tr("When")))
            addView(dateBox)
            addView(dueRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
            addView(caption(tr("Details")))
            addView(titleBox)
            addView(nextKmBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
            addView(caption(tr("Odometer and cost")))
            addView(numbers)
            addView(caption(tr("Note")))
            addView(noteField.also { it.hint = tr("Garage, parts, … (optional)") })
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) tr("New service entry") else tr("Edit entry"))
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(tr("Save"), null)
            .setNegativeButton(tr("Cancel"), null)
        if (existing != null) builder.setNeutralButton(tr("Delete")) { _, _ -> confirmDeleteEvent(existing) }
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title = titleField.text.toString().trim().ifEmpty { null }
            val odo: Parsed<Double> = CarInput.parseDecimal(odoField.text.toString(), tr("Odometer"), 0.0, 2_000_000.0)
            val next: Parsed<Double> = if (type == EventType.OIL_CHANGE) CarInput.parseDecimal(nextKmField.text.toString(), tr("Next change"), 0.0, 2_000_000.0) else Parsed(null)
            val cost: Parsed<Double> = CarInput.parseDecimal(costField.text.toString(), tr("Cost"), 0.0, 10_000_000.0)
            titleField.error = if (!type.hasDue && title == null) tr("Describe what was done") else null
            odoField.error = odo.error
            nextKmField.error = next.error
            costField.error = cost.error
            if (titleField.error != null || !odo.ok || !next.ok || !cost.ok) return@setOnClickListener
            val dueSaved = if (type.hasDue) due else null
            if (dueSaved != null && dueSaved.isBefore(date)) {
                toast(tr("The \"{0}\" date is before the date of the entry.", dueLabel()))
                return@setOnClickListener
            }
            val note = noteField.text.toString().trim().ifEmpty { null }
            try {
                events.save(address, CarEvent(existing?.id ?: CarEvent.newId(), type, date, title, dueSaved, odo.value, next.value, cost.value, note))
                val car = details.get(address)
                val raised = ManualOdometer.afterServiceEntry(car, VehicleSummary.of(address, trips), odo.value, System.currentTimeMillis())
                if (raised != car) details.put(address, raised)
            } catch (e: IOException) {
                toast(tr("Could not save: {0}", e.message))
                return@setOnClickListener
            }
            dialog.dismiss()
            render()
        }
    }

    private fun confirmDeleteEvent(e: CarEvent) {
        AlertDialog.Builder(this)
            .setTitle(tr("Delete this entry?"))
            .setMessage("${ui.date(e.date)}  ·  ${e.displayTitle}")
            .setPositiveButton(tr("Delete")) { _, _ ->
                try { events.delete(address, e.id) } catch (x: IOException) { toast(tr("Could not delete: {0}", x.message)) }
                render()
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_ADDRESS = "address"
        private const val REQ_PHOTO = 3
    }
}
