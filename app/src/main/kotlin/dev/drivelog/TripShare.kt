package dev.drivelog

import android.app.Activity
import android.app.AlertDialog
import dev.drivelog.core.Csv
import dev.drivelog.core.Gpx
import dev.drivelog.core.TripLoader
import dev.drivelog.core.TripMeta
import dev.drivelog.core.tr
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService

/** Shares one trip as GPX (route and car values) or CSV (spreadsheet). */
class TripShare(private val activity: Activity, private val ui: Ui, private val trips: TripLoader?, private val io: ExecutorService) {

    fun choose(m: TripMeta) {
        AlertDialog.Builder(activity)
            .setTitle(tr("Share as"))
            .setItems(arrayOf(tr("GPX (route and car values)"), tr("CSV (spreadsheet)"))) { _, which -> share(m, gpx = which == 0) }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun share(m: TripMeta, gpx: Boolean) {
        val loader = trips ?: return
        io.execute {
            val trip = loader.load(m.id)
            val file: File? = trip?.let {
                try {
                    SharedFiles.newFile(activity, "${it.id}.${if (gpx) "gpx" else "csv"}").apply { writeText(if (gpx) Gpx.write(it) else Csv.write(it)) }
                } catch (e: IOException) {
                    null
                }
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (file == null) ui.toast(tr("Could not prepare the file."))
                else SharedFiles.send(activity, file, if (gpx) "application/gpx+xml" else "text/csv", tr("Share trip"))
            }
        }
    }
}
