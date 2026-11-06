package dev.idrivetrips.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin

private fun pt(lat: Double, lon: Double) = TrackPoint(0, lat, lon, null, null, null)

class RouteShapeTest {
    @Test
    fun aStraightLineKeepsOnlyItsEnds() {
        val line = (0..500).map { pt(52.0 + it * 0.0001, 21.0 + it * 0.0002) }
        val s = RouteShape.simplify(line)
        assertEquals(2, s.size)
        assertEquals(52.0, s.first().lat, 1e-9)
        assertEquals(52.05, s.last().lat, 1e-9)
    }

    @Test
    fun aLongWindingTrackIsThinnedToTheLimitAndKeepsItsEnds() {
        val circle = (0..2000).map { i -> pt(52.0 + 0.05 * sin(i / 2000.0 * 6.2832), 21.0 + 0.08 * cos(i / 2000.0 * 6.2832)) }
        val s = RouteShape.simplify(circle, 60)
        assertTrue("${s.size}", s.size in 10..60)
        assertEquals(circle.first().lat, s.first().lat, 1e-9)
        assertEquals(circle.last().lon, s.last().lon, 1e-9)
    }

    @Test
    fun badPointsAreDropped() {
        val s = RouteShape.simplify(listOf(pt(52.0, 21.0), pt(Double.NaN, 1.0), pt(95.0, 3.0), pt(52.1, 21.1)))
        assertEquals(listOf(LatLon(52.0, 21.0), LatLon(52.1, 21.1)), s)
        assertTrue(RouteShape.simplify(emptyList()).isEmpty())
        assertEquals(1, RouteShape.simplify(listOf(pt(1.0, 1.0))).size)
    }

    @Test
    fun theOutlineFitsTheBoxWithoutDistortionAndIsCentred() {
        val shape = listOf(LatLon(52.0, 21.0), LatLon(52.1, 21.0), LatLon(52.1, 21.2))
        val spots = RouteShape.fit(shape, 200f, 100f, 10f)
        for (p in spots) { assertTrue(p.x in 10f..190f); assertTrue(p.y in 10f..90f) }
        assertTrue("north is up", spots[1].y < spots[0].y)
        assertTrue("east is right", spots[2].x > spots[1].x)
        val w = spots.maxOf { it.x } - spots.minOf { it.x }
        val h = spots.maxOf { it.y } - spots.minOf { it.y }
        val expectedRatio = (0.2 * cos(Math.toRadians(52.066))) / 0.1
        assertEquals(expectedRatio, (w / h).toDouble(), 0.02)
        assertEquals(200f, spots.minOf { it.x } + spots.maxOf { it.x }, 0.01f)       // centred sideways in a box 200 wide
    }

    @Test
    fun aSinglePlaceIsDrawnInTheMiddle() {
        val spots = RouteShape.fit(listOf(LatLon(52.0, 21.0), LatLon(52.0, 21.0)), 80f, 60f, 5f)
        assertEquals(40f, spots[0].x, 0.01f)
        assertEquals(30f, spots[0].y, 0.01f)
        assertTrue(RouteShape.fit(emptyList(), 80f, 60f, 5f).isEmpty())
    }
}

class RouteCacheTest {
    @Test
    fun keepsOutlinesBetweenRestartsAndForgetsTheOldest() {
        val dir = Files.createTempDirectory("routes").toFile()
        val file = java.io.File(dir, "routes.json")
        val cache = RouteCache(file)
        assertNull(cache.get("a"))
        cache.put("a", listOf(LatLon(52.123456, 21.0), LatLon(52.2, 21.1)))
        val again = RouteCache(file).get("a")!!
        assertEquals(2, again.size)
        assertEquals(52.12346, again[0].lat, 1e-9)           // five decimals are kept
        cache.remove("a")
        assertNull(RouteCache(file).get("a"))
        for (i in 0 until RouteCache.MAX_TRIPS + 10) cache.put("t$i", listOf(LatLon(1.0, 1.0)))
        assertNull(cache.get("t0"))
        assertTrue(cache.get("t${RouteCache.MAX_TRIPS + 9}") != null)
        dir.deleteRecursively()
    }
}
