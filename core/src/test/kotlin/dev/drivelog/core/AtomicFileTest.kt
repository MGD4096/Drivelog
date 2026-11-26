package dev.drivelog.core

import dev.drivelog.carapi.CarValues
import dev.drivelog.core.storage.AtomicFile
import dev.drivelog.core.trip.FileTripStore
import dev.drivelog.core.trip.Trip
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AtomicFileTest {
    private lateinit var dir: File
    @Before fun setUp() { dir = Files.createTempDirectory("atomic").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test
    fun replacesTheFileCreatesTheFolderAndLeavesNoTemporaryFile() {
        val target = File(dir, "a/b/data.json")
        AtomicFile.writeText(target, "first ż")
        AtomicFile.writeText(target, "second ż")
        assertEquals("second ż", target.readText(Charsets.UTF_8))
        assertFalse(File(target.parentFile, "data.json" + AtomicFile.TMP_SUFFIX).exists())
    }

    @Test
    fun setsTheTimeWhenAsked() {
        val target = File(dir, "tile.png")
        AtomicFile.write(target, byteArrayOf(1, 2, 3), 1_600_000_000_000)
        assertEquals(1_600_000_000_000, target.lastModified())
    }

    @Test
    fun aDamagedTripFileIsSkippedNotFatal() {
        val store = FileTripStore(dir)
        val trip = Trip("20261102T100000Z-AA01", "AA:01", "car", 0, 600_000, emptyList(), emptyList(), CarValues.EMPTY, CarValues.EMPTY)
        store.save(trip)
        File(dir, "20261103T100000Z-AA01.trip.json").writeText("{\"id\": \"cut sh")
        assertEquals(listOf(trip.id), store.list().map { it.id })
        assertNull(store.load("20261103T100000Z-AA01"))
        assertTrue(store.load(trip.id) != null)
    }
}
