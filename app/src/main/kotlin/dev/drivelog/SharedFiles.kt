package dev.drivelog

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * Files handed to other apps through the share sheet: written into the cache folder [ExportProvider] serves, then
 * offered with a read-only grant. Only the file being shared is kept there.
 */
object SharedFiles {
    /** A fresh file of this name in the export folder (the folder is emptied first). */
    fun newFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, ExportProvider.EXPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, name)
    }

    /** Opens the share sheet for [file] (made by [newFile]). Main thread. */
    fun send(activity: Activity, file: File, mime: String, title: String) {
        val uri = Uri.parse("content://${activity.packageName}.exports/${file.name}")
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, title))
    }
}
