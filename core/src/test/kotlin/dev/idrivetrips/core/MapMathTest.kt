package dev.idrivetrips.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

class MercatorTest {

    @Test
    fun cornersAndCentre() {
        assertEquals(0.0, Mercator.lonToX(-180.0), 0.0)
        assertEquals(0.5, Mercator.lonToX(0.0), 0.0)
        assertEquals(1.0, Mercator.lonToX(180.0), 0.0)
        assertEquals(0.5, Mercator.latToY(0.0), 1e-12)
        assertEquals(0.0, Mercator.latToY(Mercator.MAX_LAT), 1e-9)
        assertEquals(1.0, Mercator.latToY(-Mercator.MAX_LAT), 1e-9)
        assertEquals("beyond the poles is clamped, not infinite", 0.0, Mercator.latToY(90.0), 1e-9)
    }

    @Test
    fun roundTrip() {
        for ((lat, lon) in listOf(52.2297 to 21.0122, -33.8688 to 151.2093, 0.0 to 0.0, 69.6492 to 18.9553, -54.8019 to -68.303)) {
            assertEquals(lat, Mercator.yToLat(Mercator.latToY(lat)), 1e-9)
            assertEquals(lon, Mercator.xToLon(Mercator.lonToX(lon)), 1e-9)
        }
    }

    /** The tile numbers by the formula on the OpenStreetMap wiki (written differently, with asinh and tan). */
    private fun wikiTile(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
        val n = 1 shl z
        val x = floor((lon + 180.0) / 360.0 * n).toInt()
        val y = floor((1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * n).toInt()
        return x to y
    }

    @Test
    fun tileNumbersAgreeWithTheWikiFormula() {
        for ((lat, lon) in listOf(52.2297 to 21.0122, 48.8566 to 2.3522, -33.8688 to 151.2093, 40.7128 to -74.006, 0.5 to -0.5)) {
            for (z in listOf(3, 10, 14, 19)) {
                val count = 1 shl z
                val x = floor(Mercator.lonToX(lon) * count).toInt()
                val y = floor(Mercator.latToY(lat) * count).toInt()
                assertEquals("x at ($lat, $lon) z$z", wikiTile(lat, lon, z).first, x)
                assertEquals("y at ($lat, $lon) z$z", wikiTile(lat, lon, z).second, y)
            }
        }
    }
}

class MapCameraTest {

    private val warsaw = MapCamera(Mercator.lonToX(21.0122), Mercator.latToY(52.2297), 12.0, 1080, 1920, 256.0)

    @Test
    fun theCentreIsOnTheScreenCentre() {
        assertEquals(540.0, warsaw.screenX(21.0122), 1e-6)
        assertEquals(960.0, warsaw.screenY(52.2297), 1e-6)
        assertEquals(21.0122, warsaw.centerLon, 1e-9)
        assertEquals(52.2297, warsaw.centerLat, 1e-9)
    }

    @Test
    fun screenAndCoordinatesAreInverses() {
        for ((x, y) in listOf(0.0 to 0.0, 1080.0 to 1920.0, 123.4 to 1700.9, 540.0 to 960.0)) {
            assertEquals(x, warsaw.screenX(warsaw.lonAt(x)), 1e-6)
            assertEquals(y, warsaw.screenY(warsaw.latAt(y)), 1e-6)
        }
    }

    @Test
    fun theWorldIsOneTileWideAtZoomZero() {
        val c = MapCamera(0.5, 0.5, 0.0, 256, 256, 256.0)
        assertEquals(256.0, c.worldSizePx, 0.0)
        assertEquals(0.0, c.screenX(-180.0), 1e-9)
        assertEquals(256.0, c.screenX(180.0), 1e-9)
    }

    @Test
    fun dragMovesTheMapByExactlyTheFingerMovement() {
        val lat = 52.25
        val lon = 21.03
        val x0 = warsaw.screenX(lon)
        val y0 = warsaw.screenY(lat)
        val moved = warsaw.panBy(37.0, -120.0)
        assertEquals(x0 + 37.0, moved.screenX(lon), 1e-6)
        assertEquals(y0 - 120.0, moved.screenY(lat), 1e-6)
    }

    @Test
    fun theMapCannotBeDraggedOffTheWorld() {
        val c = warsaw.panBy(0.0, 1e12)
        assertEquals(0.0, c.worldY, 0.0)
        assertEquals(1.0, warsaw.panBy(-1e12, 0.0).worldX, 0.0)
    }

    @Test
    fun zoomKeepsThePointUnderTheFingerInPlace() {
        for ((fx, fy) in listOf(100.0 to 200.0, 540.0 to 960.0, 1000.0 to 1800.0)) {
            val lon = warsaw.lonAt(fx)
            val lat = warsaw.latAt(fy)
            for (z in listOf(5.0, 12.5, 15.3, 18.0)) {
                val c = warsaw.zoomAt(fx, fy, z)
                assertEquals(z, c.zoom, 0.0)
                assertEquals("x at zoom $z", fx, c.screenX(lon), 1e-5)
                assertEquals("y at zoom $z", fy, c.screenY(lat), 1e-5)
            }
        }
    }

    @Test
    fun zoomIsLimited() {
        assertEquals(MapCamera.MAX_ZOOM, warsaw.zoomAt(540.0, 960.0, 40.0).zoom, 0.0)
        assertEquals(MapCamera.MIN_ZOOM, warsaw.zoomAt(540.0, 960.0, -3.0).zoom, 0.0)
    }

    // ---- fitting a track -----------------------------------------------------------------------------------------------

    private val trackBounds = GeoBounds(52.2200, 52.2400, 21.0000, 21.0300)     // roughly 2 km by 2.2 km

    private fun insidePadded(c: MapCamera, b: GeoBounds, l: Double, t: Double, r: Double, bo: Double, tol: Double = 0.5) {
        val left = c.screenX(b.minLon)
        val right = c.screenX(b.maxLon)
        val top = c.screenY(b.maxLat)
        val bottom = c.screenY(b.minLat)
        assertTrue("left $left >= $l", left >= l - tol)
        assertTrue("right $right <= ${c.widthPx - r}", right <= c.widthPx - r + tol)
        assertTrue("top $top >= $t", top >= t - tol)
        assertTrue("bottom $bottom <= ${c.heightPx - bo}", bottom <= c.heightPx - bo + tol)
    }

    @Test
    fun fitShowsTheWholeTrackAsLargeAsPossible() {
        val c = MapCamera.fit(trackBounds, 1080, 1920, 256.0, 100.0, 100.0, 100.0, 100.0)
        insidePadded(c, trackBounds, 100.0, 100.0, 100.0, 100.0)
        // as large as possible: one of the axes uses all of the room
        val usedW = c.screenX(trackBounds.maxLon) - c.screenX(trackBounds.minLon)
        val usedH = c.screenY(trackBounds.minLat) - c.screenY(trackBounds.maxLat)
        assertTrue("width $usedW height $usedH", abs(usedW - 880.0) < 1.0 || abs(usedH - 1720.0) < 1.0)
        // and not tilted to one side: the middle of the track is the middle of the room
        assertEquals(540.0, (c.screenX(trackBounds.minLon) + c.screenX(trackBounds.maxLon)) / 2, 0.5)
        assertEquals(960.0, (c.screenY(trackBounds.minLat) + c.screenY(trackBounds.maxLat)) / 2, 0.5)
    }

    @Test
    fun fitWithUnevenPaddingKeepsTheTrackInsideTheFreeArea() {
        // a card covers the bottom 400 px
        val c = MapCamera.fit(trackBounds, 1080, 1920, 256.0 * 2.75, 60.0, 200.0, 60.0, 400.0)
        insidePadded(c, trackBounds, 60.0, 200.0, 60.0, 400.0)
        val mid = (c.screenY(trackBounds.minLat) + c.screenY(trackBounds.maxLat)) / 2
        assertEquals("centred in the free area (200 to 1520)", 860.0, mid, 0.5)
    }

    @Test
    fun aTrackThatDidNotMoveIsShownAtStreetLevel() {
        val c = MapCamera.fit(GeoBounds(52.2, 52.2, 21.0, 21.0), 1080, 1920)
        assertEquals(MapCamera.SINGLE_POINT_ZOOM, c.zoom, 0.0)
        assertEquals(21.0, c.centerLon, 1e-9)
        assertEquals(52.2, c.centerLat, 1e-9)
    }

    @Test
    fun aStraightEastWestTrackFitsByItsWidth() {
        val c = MapCamera.fit(GeoBounds(52.2, 52.2, 21.0, 21.05), 1080, 1920, 256.0, 50.0, 50.0, 50.0, 50.0)
        insidePadded(c, GeoBounds(52.2, 52.2, 21.0, 21.05), 50.0, 50.0, 50.0, 50.0)
        assertEquals(980.0, c.screenX(21.05) - c.screenX(21.0), 1.0)
    }

    @Test
    fun aVeryShortTrackIsNotZoomedBeyondTheFitLimit() {
        val tiny = GeoBounds(52.2000, 52.2001, 21.0000, 21.0001)
        assertEquals(MapCamera.FIT_MAX_ZOOM, MapCamera.fit(tiny, 1080, 1920).zoom, 0.0)
    }

    @Test
    fun aTrackAcrossTheWorldIsLimitedByTheMinimumZoom() {
        val c = MapCamera.fit(GeoBounds(-60.0, 60.0, -170.0, 170.0), 360, 640)
        assertEquals(MapCamera.MIN_ZOOM, c.zoom, 0.0)
    }

    @Test
    fun boundsOfPoints() {
        val pts = listOf(TrackPoint(0, 52.1, 21.2, null, null, null), TrackPoint(1, 52.3, 21.0, null, null, null))
        assertEquals(GeoBounds(52.1, 52.3, 21.0, 21.2), GeoBounds.of(pts))
        assertNull(GeoBounds.of(emptyList()))
    }

    // ---- tiles -----------------------------------------------------------------------------------------------------------

    @Test
    fun tilesCoverEveryCornerOfTheScreen() {
        for (zoom in listOf(3.0, 9.4, 12.0, 14.99, 17.5, 19.0)) {
            for (tileSize in listOf(256.0, 256.0 * 2.75)) {
                val c = warsaw.copy(zoom = zoom, tileSizePx = tileSize)
                val tiles = c.visibleTiles()
                assertTrue(tiles.isNotEmpty())
                for ((px, py) in listOf(0.0 to 0.0, 1079.0 to 0.0, 0.0 to 1919.0, 1079.0 to 1919.0, 540.0 to 960.0)) {
                    val covering = tiles.count { px >= it.left && px < it.left + it.size && py >= it.top && py < it.top + it.size }
                    // Above the north edge or below the south edge of the world there is no map, so no tile
                    val worldY = c.worldY + (py - c.heightPx / 2.0) / c.worldSizePx
                    val expected = if (worldY in 0.0..1.0) 1 else 0
                    assertEquals("pixel ($px, $py) at zoom $zoom, tile size $tileSize", expected, covering)
                }
                assertTrue("no wasteful overdraw: ${tiles.size} tiles", tiles.size <= 80)
            }
        }
    }

    @Test
    fun tilesAreNamedByTheWholePartOfTheZoomAndScaledByTheRest() {
        val c = warsaw.copy(zoom = 12.5)
        val tiles = c.visibleTiles()
        assertTrue(tiles.all { it.z == 12 })
        assertEquals(256.0 * Math.pow(2.0, 0.5), tiles.first().size, 1e-9)
        // the tile under the screen centre is the one the wiki formula names
        val centre = tiles.single { 540.0 >= it.left && 540.0 < it.left + it.size && 960.0 >= it.top && 960.0 < it.top + it.size }
        val n = 1 shl 12
        assertEquals(floor(Mercator.lonToX(21.0122) * n).toInt(), centre.x)
        assertEquals(floor(Mercator.latToY(52.2297) * n).toInt(), centre.y)
    }

    @Test
    fun tilesAreNeverOutsideTheWorldVertically() {
        val north = MapCamera(0.5, 0.0, 6.0, 1080, 1920)
        assertTrue(north.visibleTiles().all { it.y in 0 until 64 })
        val south = MapCamera(0.5, 1.0, 6.0, 1080, 1920)
        assertTrue(south.visibleTiles().all { it.y in 0 until 64 })
    }

    @Test
    fun theTileColumnWrapsAcrossTheDateLine() {
        val c = MapCamera(Mercator.lonToX(179.99), Mercator.latToY(0.0), 5.0, 1080, 1920)
        val tiles = c.visibleTiles()
        assertTrue(tiles.all { it.x in 0 until 32 })
        assertTrue("columns on both sides of the date line", tiles.any { it.x == 31 } && tiles.any { it.x == 0 })
        // a wrapped tile still sits to the right of the last one on the screen
        val last = tiles.filter { it.x == 31 }.maxOf { it.left }
        assertTrue(tiles.filter { it.x == 0 }.all { it.left > last })
    }

    @Test
    fun noTilesForAnEmptyScreen() {
        assertEquals(emptyList<TileRef>(), warsaw.copy(widthPx = 0).visibleTiles())
    }
}

class TrackColoursTest {

    private fun pt(tSec: Long, north: Double, speed: Double?) =
        TrackPoint(tSec * 1000, 52.0 + north / Geo.METERS_PER_DEGREE, 21.0, null, speed, 5.0)

    @Test
    fun scaleEndsAndStops() {
        assertEquals(0xFF2979FF.toInt(), SpeedColors.color(0.0))
        assertEquals(0xFF00C853.toInt(), SpeedColors.color(130.0 / 3))
        assertEquals(0xFFFFD600.toInt(), SpeedColors.color(130.0 * 2 / 3))
        assertEquals(0xFFD50000.toInt(), SpeedColors.color(130.0))
    }

    @Test
    fun colourIsClampedAndTotal() {
        assertEquals(SpeedColors.color(130.0), SpeedColors.color(400.0))
        assertEquals(SpeedColors.color(0.0), SpeedColors.color(-20.0))
        assertEquals(SpeedColors.color(0.0), SpeedColors.color(Double.NaN))
        for (kmh in listOf(0.0, 12.3, 65.0, 99.9, 130.0, 1e9)) assertEquals(0xFF, SpeedColors.color(kmh) ushr 24)
    }

    @Test
    fun theMiddleOfTwoStopsIsTheirAverage() {
        val c = SpeedColors.color(130.0 / 6)        // halfway between blue and green
        assertEquals((0x29 + 0x00) / 2.0, ((c ushr 16) and 0xFF).toDouble(), 1.0)
        assertEquals((0x79 + 0xC8) / 2.0, ((c ushr 8) and 0xFF).toDouble(), 1.0)
        assertEquals((0xFF + 0x53) / 2.0, (c and 0xFF).toDouble(), 1.0)
    }

    @Test
    fun bandsGrowWithSpeedAndStayInRange() {
        var last = -1
        for (kmh in 0..200) {
            val b = SpeedColors.bandOf(kmh.toDouble(), 24)
            assertTrue(b in 0..23)
            assertTrue(b >= last)
            last = b
        }
        assertEquals(0, SpeedColors.bandOf(Double.NaN, 24))
        assertEquals(23, SpeedColors.bandOf(130.0, 24))
        assertEquals(SpeedColors.color((5 + 0.5) / 24 * 130.0), SpeedColors.bandColor(5, 24))
    }

    @Test
    fun segmentSpeedsPreferWhatTheGpsReported() {
        val speeds = TrackSegments.speedsKmh(listOf(pt(0, 0.0, 10.0), pt(10, 100.0, 20.0), pt(20, 200.0, 30.0)))
        assertEquals(2, speeds.size)
        assertEquals(15.0 * 3.6, speeds[0], 1e-9)
        assertEquals(25.0 * 3.6, speeds[1], 1e-9)
    }

    @Test
    fun segmentSpeedsFallBackToDistanceOverTime() {
        val speeds = TrackSegments.speedsKmh(listOf(pt(0, 0.0, null), pt(10, 200.0, null)))
        assertEquals(20.0 * 3.6, speeds.single(), 0.01)
    }

    @Test
    fun aGlitchTakesTheSpeedOfTheSegmentBefore() {
        val speeds = TrackSegments.speedsKmh(listOf(pt(0, 0.0, null), pt(10, 200.0, null), pt(11, 200.0 + 50_000.0, null)))
        assertEquals(speeds[0], speeds[1], 1e-9)
    }

    @Test
    fun noTimeBetweenPointsGivesNoNaN() {
        val speeds = TrackSegments.speedsKmh(listOf(pt(5, 0.0, null), pt(5, 10.0, null)))
        assertTrue(!speeds.single().isNaN())
        assertEquals(0, TrackSegments.speedsKmh(listOf(pt(0, 0.0, null))).size)
        assertEquals(0, TrackSegments.speedsKmh(emptyList()).size)
        assertNotNull(speeds)
    }
}
