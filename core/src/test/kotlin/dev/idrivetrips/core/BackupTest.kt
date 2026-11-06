package dev.idrivetrips.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    private lateinit var root: File
    private lateinit var data: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("backup").toFile()
        data = File(root, "files").apply { mkdirs() }
    }

    @After
    fun tearDown() { root.deleteRecursively() }

    private fun fill(dir: File) {
        File(dir, "trips").mkdirs()
        File(dir, "trips/t1.json").writeText("{\"trip\":1}")
        File(dir, "car_photos").mkdirs()
        File(dir, "car_photos/a.jpg").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(dir, "cars.json").writeText("{\"aa\":{}}")
        File(dir, "routes.json").writeText("cache")
        File(dir, "trips/t2.json.tmp").writeText("half written")
    }

    private fun export(extras: Map<String, String> = emptyMap()): ByteArray = ByteArrayOutputStream().also { Backup.write(data, extras, it, 123L) }.toByteArray()

    @Test
    fun exportThenImportGivesBackTheSameFilesAndSettings() {
        fill(data)
        val bytes = export(mapOf("settings.json" to "{\"language\":\"pl\"}"))
        val other = File(root, "other").apply { mkdirs() }
        File(other, "old.txt").writeText("will be replaced")
        File(other, "trips").mkdirs()
        File(other, "trips/old.json").writeText("old trip")

        val result = Backup.read(ByteArrayInputStream(bytes), other)

        assertEquals(3, result.files)
        assertEquals("{\"language\":\"pl\"}", result.extras["settings.json"])
        assertEquals("{\"trip\":1}", File(other, "trips/t1.json").readText())
        assertEquals(listOf<Byte>(1, 2, 3, 4), File(other, "car_photos/a.jpg").readBytes().toList())
        assertEquals("{\"aa\":{}}", File(other, "cars.json").readText())
        assertFalse("old data is gone", File(other, "old.txt").exists())
        assertFalse(File(other, "trips/old.json").exists())
        assertFalse("caches and half-written files are not exported", File(other, "routes.json").exists() || File(other, "trips/t2.json.tmp").exists())
        assertFalse(File(other, ".restore").exists())
    }

    @Test
    fun aFileThatIsNoBackupChangesNothing() {
        fill(data)
        val notZip = "just some text".toByteArray()
        try {
            Backup.read(ByteArrayInputStream(notZip), data)
            fail()
        } catch (e: BackupException) {
            // expected
        }
        assertEquals("{\"aa\":{}}", File(data, "cars.json").readText())

        val otherZip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("hello.txt")); z.write(1); z.closeEntry() }
        }.toByteArray()
        try {
            Backup.read(ByteArrayInputStream(otherZip), data)
            fail()
        } catch (e: BackupException) {
            // expected
        }
        assertTrue(File(data, "trips/t1.json").exists())
    }

    @Test
    fun entriesThatTryToLeaveTheDataFolderAreRefused() {
        fill(data)
        val evil = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write("{\"format\":\"${Backup.FORMAT}\",\"version\":1}".toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("data/../../escaped.txt"))
                z.write(1)
                z.closeEntry()
            }
        }.toByteArray()
        try {
            Backup.read(ByteArrayInputStream(evil), data)
            fail()
        } catch (e: BackupException) {
            // expected
        }
        assertFalse(File(root, "escaped.txt").exists())
        assertTrue(File(data, "cars.json").exists())
    }

    @Test
    fun aBackupFromANewerVersionIsRefused() {
        val future = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write("{\"format\":\"${Backup.FORMAT}\",\"version\":99}".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        fill(data)
        try {
            Backup.read(ByteArrayInputStream(future), data)
            fail()
        } catch (e: BackupException) {
            // expected
        }
        assertTrue(File(data, "cars.json").exists())
    }

    @Test
    fun anEmptyBackupEmptiesTheFolder() {
        val bytes = export()
        fill(data)
        Backup.read(ByteArrayInputStream(bytes), data)
        assertEquals(0, data.listFiles()!!.size)
    }
}
