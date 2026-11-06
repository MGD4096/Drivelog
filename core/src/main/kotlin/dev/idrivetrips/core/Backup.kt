package dev.idrivetrips.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The file is not a backup this app can read. The message is meant for the person. */
class BackupException(message: String) : IOException(message)

/**
 * Everything the app keeps, in one ZIP file: the files of the app's data folder (trips, cars, service log, photos) under
 * `data/`, small texts from the app's settings under `extra/`, and a `manifest.json` that says what the file is.
 * Importing replaces the current data; nothing is touched unless the whole file was read successfully.
 */
object Backup {
    const val FORMAT = "idrive-trips-backup"
    const val VERSION = 1

    private const val MANIFEST = "manifest.json"
    private const val DATA = "data/"
    private const val EXTRA = "extra/"
    private const val STAGE = ".restore"
    private const val MAX_EXTRA_BYTES = 5L * 1024 * 1024

    /** Not worth keeping: the route thumbnails are drawn again when needed. */
    private val NOT_EXPORTED = setOf("routes.json")

    class Exported(val files: Int, val bytes: Long)
    class Imported(val files: Int, val extras: Map<String, String>)

    /** Writes the backup to [out] (which stays open; the caller closes it). [extras]: name to text, for settings kept outside [dir]. */
    fun write(dir: File, extras: Map<String, String>, out: OutputStream, nowMs: Long = System.currentTimeMillis()): Exported {
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(Json.stringify(linkedMapOf("format" to FORMAT, "version" to VERSION, "createdMs" to nowMs)).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for ((name, text) in extras) {
            zip.putNextEntry(ZipEntry(EXTRA + name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        var files = 0
        var bytes = 0L
        val top = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        for (entry in top) {
            if (entry.name in NOT_EXPORTED || entry.name == STAGE) continue
            val list = if (entry.isDirectory) entry.walkTopDown().filter { it.isFile }.toList() else listOf(entry)
            for (file in list) {
                if (file.name.endsWith(".tmp")) continue
                val relative = file.relativeTo(dir).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(DATA + relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                files++
                bytes += file.length()
            }
        }
        zip.finish()
        zip.flush()
        return Exported(files, bytes)
    }

    /** Reads a backup from [input] and makes [dir] hold exactly what it contains. Throws [BackupException] for a file that is no backup. */
    fun read(input: InputStream, dir: File): Imported {
        val stage = File(dir, STAGE)
        stage.deleteRecursively()
        if (!stage.mkdirs()) throw IOException("Cannot create $stage")
        try {
            var manifest: Map<String, Any?>? = null
            val extras = LinkedHashMap<String, String>()
            var files = 0
            val zip = try { ZipInputStream(input) } catch (e: Exception) { throw notABackup() }
            while (true) {
                val entry = try { zip.nextEntry } catch (e: IOException) { throw notABackup() } ?: break
                val name = entry.name
                if (entry.isDirectory) continue
                if (name.startsWith("/") || name.contains("\\") || name.split("/").any { it == ".." }) throw notABackup()
                when {
                    name == MANIFEST -> manifest = Json.parseObject(readLimited(zip).toString(Charsets.UTF_8))
                    name.startsWith(EXTRA) -> extras[name.removePrefix(EXTRA)] = readLimited(zip).toString(Charsets.UTF_8)
                    name.startsWith(DATA) -> {
                        val target = File(stage, name.removePrefix(DATA))
                        if (!target.canonicalPath.startsWith(stage.canonicalPath + File.separator)) throw notABackup()
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                        files++
                    }
                }
            }
            val m = manifest
            if (m == null || m["format"] != FORMAT) throw notABackup()
            if (((m["version"] as? Double)?.toInt() ?: Int.MAX_VALUE) > VERSION) {
                throw BackupException(tr("This backup was made by a newer version of the app."))
            }
            // Everything was read: now replace the current data
            for (current in dir.listFiles() ?: emptyArray()) if (current.name != STAGE) current.deleteRecursively()
            for (restored in stage.listFiles() ?: emptyArray()) {
                val target = File(dir, restored.name)
                if (!restored.renameTo(target)) {
                    restored.copyRecursively(target, overwrite = true)
                    restored.deleteRecursively()
                }
            }
            return Imported(files, extras)
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun notABackup() = BackupException(tr("This is not a Drivelog backup."))

    private fun readLimited(zip: ZipInputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_EXTRA_BYTES) throw notABackup()
        }
        return out.toByteArray()
    }
}
