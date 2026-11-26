package dev.drivelog

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import dev.drivelog.core.Backup
import dev.drivelog.core.tr
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ExecutorService

/**
 * Export and import of all data as one zip, through the system's file picker. The activity passes its results on
 * through [onActivityResult]; after an import the activity is built again ([Activity.recreate]).
 */
class BackupFlow(private val activity: Activity, private val ui: Ui, private val io: ExecutorService) {

    fun startExport() {
        val name = "drivelog-backup-" + LocalDate.now() + ".zip"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, name)
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ_EXPORT)
        } catch (e: ActivityNotFoundException) {
            ui.toast(tr("Could not export: {0}", e.message))
        }
    }

    fun startImport() {
        if (AppState.status.phase != Phase.IDLE) {
            ui.toast(tr("Stop the trip that is being recorded first."))
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ_IMPORT)
        } catch (e: ActivityNotFoundException) {
            ui.toast(tr("Could not import: {0}", e.message))
        }
    }

    /** Opens the folder picker for the weekly automatic backup ([AutoBackup]). */
    fun chooseAutoFolder() {
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
        } catch (e: ActivityNotFoundException) {
            ui.toast(tr("No app found to pick a folder."))
        }
    }

    /** The automatic backup now, whatever the date of the last one. */
    fun autoBackupNow() {
        io.execute {
            AutoBackup.runIfDue(activity, force = true)
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                val error = AppSettings.autoBackupError(activity)
                ui.toast(if (error == null) tr("Backup written.") else tr("Could not back up: {0}", error))
            }
        }
    }

    /** True when the result was one of the file pickers' (and is handled here). */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT && requestCode != REQ_FOLDER) return false
        val uri = data?.data ?: return true
        if (resultCode != Activity.RESULT_OK) return true
        if (requestCode == REQ_FOLDER) {
            try {
                AutoBackup.choose(activity, uri)
                autoBackupNow()                                     // the first one at once: shows that it works
            } catch (e: SecurityException) {
                ui.toast(tr("Could not back up: {0}", e.message))
            }
            return true
        }
        if (requestCode == REQ_EXPORT) {
            exportTo(uri)
        } else {
            AlertDialog.Builder(activity)
                .setTitle(tr("Import data?"))
                .setMessage(tr("Importing replaces everything in the app (trips, cars, photos, the service log and the settings) with the contents of the file. This cannot be undone."))
                .setPositiveButton(tr("Replace")) { _, _ -> importFrom(uri) }
                .setNegativeButton(tr("Cancel"), null)
                .show()
        }
        return true
    }

    private fun exportTo(uri: Uri) {
        io.execute {
            val result = try {
                val out = activity.contentResolver.openOutputStream(uri) ?: throw IOException("cannot open the file")
                out.use { Backup.write(activity.filesDir, BackupPrefs.collect(activity), it) }.files
            } catch (e: IOException) {   // BackupException is one too
                e
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                ui.toast(if (result is Int) tr("Exported {0} files.", result) else tr("Could not export: {0}", (result as Exception).message))
            }
        }
    }

    private fun importFrom(uri: Uri) {
        io.execute {
            val failure: Exception? = try {
                val input = activity.contentResolver.openInputStream(uri) ?: throw IOException("cannot open the file")
                val imported = input.use { Backup.read(it, activity.filesDir) }
                BackupPrefs.restore(activity, imported.extras)
                null
            } catch (e: IOException) {   // BackupException is one too
                e
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (failure != null) {
                    ui.toast(tr("Could not import: {0}", failure.message))
                } else {
                    AppSettings.applyLanguage(activity)
                    Notifications.ensureChannels(activity)
                    ui.toast(tr("Data imported."))
                    activity.recreate()
                }
            }
        }
    }

    private companion object {
        const val REQ_EXPORT = 3
        const val REQ_IMPORT = 4
        const val REQ_FOLDER = 5
    }
}
