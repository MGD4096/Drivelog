package dev.drivelog.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.drivelog.AppSettings
import dev.drivelog.AppState
import dev.drivelog.Phase
import dev.drivelog.core.storage.Backup
import dev.drivelog.core.storage.BackupRotation
import dev.drivelog.core.text.tr
import java.io.IOException
import java.time.LocalDate

/**
 * A backup of all data written once a week into a folder the person picked once (any folder the phone's file picker
 * offers: the phone, an SD card, a cloud drive's folder). The newest [BackupRotation.KEEP] are kept. It runs with the
 * daily reminder check and when the app opens, on a background thread; failures are kept for the Settings tab.
 */
object AutoBackup {

    /** Keeps the right to write into the folder picked in the file picker, across restarts. */
    fun choose(context: Context, folder: Uri) {
        context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        AppSettings.setAutoBackupFolder(context, folder.toString())
    }

    fun turnOff(context: Context) {
        AppSettings.autoBackupFolder(context)?.let { old ->
            try {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
                // already gone
            }
        }
        AppSettings.setAutoBackupFolder(context, null)
    }

    /** Writes a backup when one is due (or now, with [force]). Background thread only (reads and writes all data). */
    fun runIfDue(context: Context, nowMs: Long = System.currentTimeMillis(), force: Boolean = false) {
        val app = context.applicationContext
        val folder = AppSettings.autoBackupFolder(app)?.let { Uri.parse(it) } ?: return
        if (!force && !BackupRotation.isDue(AppSettings.autoBackupLast(app), nowMs)) return
        if (AppState.status.phase != Phase.IDLE) return            // not while a trip is being written
        try {
            write(app, folder)
            AppSettings.setAutoBackupResult(app, nowMs, null)
        } catch (e: IOException) {
            AppSettings.setAutoBackupResult(app, null, e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {                           // the folder's permission was taken back
            AppSettings.setAutoBackupResult(app, null, tr("No access to the folder any more. Choose it again."))
        } catch (e: IllegalArgumentException) {                    // the folder was deleted
            AppSettings.setAutoBackupResult(app, null, tr("The folder is gone. Choose it again."))
        }
    }

    private fun write(context: Context, folder: Uri) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val existing = children(context, folder)
        val name = BackupRotation.fileName(LocalDate.now())
        existing[name]?.let { DocumentsContract.deleteDocument(resolver, it) }        // a second one the same day replaces it
        val doc = DocumentsContract.createDocument(resolver, parent, "application/zip", name)
            ?: throw IOException("cannot create $name")
        try {
            val out = resolver.openOutputStream(doc) ?: throw IOException("cannot open $name")
            out.use { Backup.write(context.filesDir, BackupPrefs.collect(context), it) }
        } catch (e: IOException) {
            DocumentsContract.deleteDocument(resolver, doc)                           // no half-written backup
            throw e
        }
        for (old in BackupRotation.toDelete(existing.keys.toList() + name)) {
            existing[old]?.let { DocumentsContract.deleteDocument(resolver, it) }
        }
    }

    /** The files in the folder, by name. */
    private fun children(context: Context, folder: Uri): Map<String, Uri> {
        val list = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val out = HashMap<String, Uri>()
        context.contentResolver.query(list, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out[name] = DocumentsContract.buildDocumentUriUsingTree(folder, id)
            }
        }
        return out
    }
}
