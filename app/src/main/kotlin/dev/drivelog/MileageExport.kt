package dev.drivelog

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextUtils
import android.text.TextPaint
import android.widget.Toast
import dev.drivelog.core.FileCarDetailsStore
import dev.drivelog.core.FileTripStore
import dev.drivelog.core.MileageLog
import dev.drivelog.core.tr
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ExecutorService

/**
 * The mileage log of one car as a PDF (to print or send) or a CSV (for a spreadsheet): the person picks the period, which
 * trips (all, business, private) and the format, then the share sheet opens.
 */
class MileageExport(private val activity: Activity, private val ui: Ui, private val io: ExecutorService) {

    private class Period(val label: String, val from: LocalDate, val to: LocalDate)

    fun start(car: CarRef) {
        val now = YearMonth.now()
        val year = now.year
        val periods = listOf(
            Period(tr("This month"), now.atDay(1), now.atEndOfMonth()),
            Period(tr("Last month"), now.minusMonths(1).atDay(1), now.minusMonths(1).atEndOfMonth()),
            Period(tr("This year"), LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)),
            Period(tr("Last year"), LocalDate.of(year - 1, 1, 1), LocalDate.of(year - 1, 12, 31)),
        )
        AlertDialog.Builder(activity)
            .setTitle(tr("Mileage log: which period?"))
            .setItems(periods.map { "${it.label}  (${ui.date(it.from)} – ${ui.date(it.to)})" }.toTypedArray()) { _, i -> chooseFilter(car, periods[i]) }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun chooseFilter(car: CarRef, period: Period) {
        val filters = listOf(MileageLog.Filter.ALL to tr("All trips"), MileageLog.Filter.BUSINESS to tr("Business trips only"), MileageLog.Filter.PRIVATE to tr("Private trips only"))
        AlertDialog.Builder(activity)
            .setTitle(tr("Which trips?"))
            .setItems(filters.map { it.second }.toTypedArray()) { _, i -> chooseFormat(car, period, filters[i].first) }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun chooseFormat(car: CarRef, period: Period, filter: MileageLog.Filter) {
        AlertDialog.Builder(activity)
            .setTitle(tr("Share as"))
            .setItems(arrayOf(tr("PDF (to print or send)"), tr("CSV (spreadsheet)"))) { _, i -> export(car, period, filter, pdf = i == 0) }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun export(car: CarRef, period: Period, filter: MileageLog.Filter, pdf: Boolean) {
        io.execute {
            var uri: Uri? = null
            var empty = false
            try {
                val trips = FileTripStore(File(activity.filesDir, "trips")).list()
                val d = FileCarDetailsStore(File(activity.filesDir, "cars.json")).get(car.address)
                val log = MileageLog.build(car.address, trips, period.from, period.to, filter)
                empty = log.entries.isEmpty()
                if (!empty) {
                    val name = d.model ?: car.name
                    val dir = File(activity.cacheDir, ExportProvider.EXPORT_DIR).apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val base = "mileage-log-${period.from}-${period.to}".replace(Regex("[^A-Za-z0-9_-]"), "")
                    val file = File(dir, if (pdf) "$base.pdf" else "$base.csv")
                    if (pdf) writePdf(file, log, name, d.plate) else file.writeText("﻿" + MileageLog.csv(log, name, d.plate))
                    uri = Uri.parse("content://${activity.packageName}.exports/${file.name}")
                }
            } catch (e: IOException) {
                uri = null
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                when {
                    empty -> Toast.makeText(activity, tr("No trips of this car in that period."), Toast.LENGTH_LONG).show()
                    uri == null -> Toast.makeText(activity, tr("Could not prepare the file."), Toast.LENGTH_LONG).show()
                    else -> {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType(if (pdf) "application/pdf" else "text/csv")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        activity.startActivity(Intent.createChooser(send, tr("Share the mileage log")))
                    }
                }
            }
        }
    }

    /** A4 landscape, one table over as many pages as it needs, the heading and totals as in the CSV. */
    private fun writePdf(file: File, log: MileageLog.Log, car: String, plate: String?) {
        val doc = PdfDocument()
        val width = 842
        val height = 595
        val margin = 36f
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f }
        val bold = TextPaint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val title = TextPaint(bold).apply { textSize = 15f }
        val line = Paint().apply { strokeWidth = 0.5f; color = 0xFF999999.toInt() }
        // column widths in points, together the page width without the margins
        val cols = listOf(
            tr("No.") to 26f, tr("Date") to 56f, tr("Type") to 52f, tr("Purpose of the trip") to 170f, tr("Route (from – to)") to 196f,
            tr("Odometer at start") to 62f, tr("Odometer at end") to 62f, tr("Kilometres") to 50f, tr("Driver") to 96f,
        )
        val rowH = 15f
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun cell(canvas: android.graphics.Canvas, s: String, x: Float, w: Float, paint: TextPaint, right: Boolean = false) {
            val fitted = TextUtils.ellipsize(s, paint, w - 6f, TextUtils.TruncateAt.END).toString()
            val tx = if (right) x + w - 3f - paint.measureText(fitted) else x + 3f
            canvas.drawText(fitted, tx, y + rowH - 4.5f, paint)
        }
        fun header(canvas: android.graphics.Canvas) {
            var x = margin
            for ((name, w) in cols) { cell(canvas, name, x, w, bold); x += w }
            y += rowH
            canvas.drawLine(margin, y, width - margin, y, line)
        }
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(width, height, pageNo).create())
            page = p
            y = margin
            val c = p.canvas
            if (pageNo == 1) {
                c.drawText(tr("Mileage log"), margin, y + 14f, title)
                y += 26f
                val facts = listOf(
                    tr("Car") to car,
                    tr("Registration number") to (plate ?: "—"),
                    tr("Period") to "${ui.date(log.from)} – ${ui.date(log.to)}",
                    tr("Odometer at the start of the period (km)") to (log.startOdometerKm?.let { ui.km(it) } ?: "—"),
                    tr("Odometer at the end of the period (km)") to (log.endOdometerKm?.let { ui.km(it) } ?: "—"),
                )
                for ((k, v) in facts) {
                    c.drawText("$k: ", margin, y + 10f, bold)
                    c.drawText(v, margin + bold.measureText("$k: "), y + 10f, text)
                    y += 13f
                }
                y += 8f
            }
            header(c)
        }
        newPage()
        for (e in log.entries) {
            if (y + rowH > height - margin - 50f) newPage()
            val c = page!!.canvas
            val values = listOf(
                e.number.toString(), ui.date(e.date), e.kind.label, e.purpose ?: "", e.route ?: "",
                e.startOdometerKm?.let { ui.km(it) } ?: "", e.endOdometerKm?.let { ui.km(it) } ?: "", ui.f1(e.km), e.driver ?: "",
            )
            var x = margin
            for ((i, v) in values.withIndex()) {
                val (_, w) = cols[i]
                cell(c, v, x, w, text, right = i == 0 || i >= 5 && i <= 7)
                x += w
            }
            y += rowH
            c.drawLine(margin, y, width - margin, y, line)
        }
        val c = page!!.canvas
        y += 8f
        for ((k, v) in listOf(tr("Total (km)") to log.totalKm, tr("Business (km)") to log.businessKm, tr("Private (km)") to log.privateKm)) {
            c.drawText("$k: ", margin, y + 10f, bold)
            c.drawText(ui.f1(v), margin + bold.measureText("$k: "), y + 10f, text)
            y += 13f
        }
        page?.let { doc.finishPage(it) }
        try {
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }
}
