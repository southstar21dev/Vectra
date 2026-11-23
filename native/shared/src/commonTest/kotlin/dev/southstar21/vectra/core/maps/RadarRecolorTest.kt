package dev.southstar21.vectra.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RadarRecolorTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun keepsTransparentAndHeavyBands() {
        val input = intArrayOf(
            argb(0, 10, 200, 30), // transparent
            argb(255, 230, 200, 40), // yellow
            argb(255, 220, 90, 20), // red/orange
            argb(255, 180, 40, 190), // magenta
        )
        val px = input.copyOf()
        recolorRadarPixels(px)
        assertEquals(input.toList(), px.toList())
    }

    @Test
    fun remapsLightRainToGreenRamp() {
        val px = intArrayOf(argb(255, 0, 120, 255), argb(255, 0, 0, 0))
        recolorRadarPixels(px)
        // intensity 255 -> factor 1 -> (65, 245, 50)
        assertEquals(argb(255, 65, 245, 50), px[0])
        // intensity 0 -> factor 0 -> (20, 160, 15)
        assertEquals(argb(255, 20, 160, 15), px[1])
    }

    @Test
    fun liftsFaintOpacityOnACurve() {
        assertEquals(0, liftAlpha(0))
        assertEquals(255, liftAlpha(255))
        assertTrue(liftAlpha(100) > 150)
        assertTrue(liftAlpha(20) < 50, "near-clear edges stay soft")
        val px = intArrayOf(argb(100, 0, 163, 224))
        recolorRadarPixels(px)
        assertEquals(liftAlpha(100), (px[0] ushr 24) and 0xFF)
    }

    private fun channels(p: Int) = Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)

    @Test
    fun snowPixelsGetTheSnowGradient() {
        // Real snow colours from RainViewer tiles, lightest to heaviest.
        val px = intArrayOf(argb(255, 193, 255, 255), argb(255, 165, 229, 255), argb(255, 127, 191, 255), argb(255, 34, 98, 255))
        recolorRadarPixels(px)
        val greens = px.map { channels(it).second }
        for ((r, g, b) in px.map(::channels)) assertTrue(b >= 240 && g > r && r < 80, "snow should be blue: ($r, $g, $b)")
        // Heavier snow is deeper blue (less green).
        assertEquals(greens.sortedDescending(), greens)
        assertTrue(greens.first() > greens.last() + 80)
    }

    @Test
    fun fullBlueMagentaIsNotSnow() {
        assertTrue(!isRadarSnow(255, 159, 255))
        assertTrue(isRadarSnow(165, 229, 255))
    }

    @Test
    fun rainBluesStayOnTheGreenRamp() {
        val px = intArrayOf(argb(255, 0, 163, 224), argb(255, 108, 209, 235))
        recolorRadarPixels(px)
        for ((r, g, b) in px.map(::channels)) assertTrue(g > 150 && g > r && g > b, "rain should be green: ($r, $g, $b)")
    }

    private val rain = argb(255, 0, 163, 224)
    private val snow = argb(255, 165, 229, 255)

    @Test
    fun mixIsStrongestAtTheEdgeAndFadesAway() {
        // A 40 px wide strip: rain in x 0..19, snow in x 20..39.
        val w = 40
        val px = IntArray(w * 3) { i -> if (i % w < 20) rain else snow }
        val mix = radarMixStrength(px, w, radius = 8)
        val row = (0 until w).map { mix[w + it] }
        assertEquals(1f, row[19])
        assertEquals(1f, row[20])
        assertTrue(row[15] in 0.01f..0.99f, "fades with distance: ${row[15]}")
        assertTrue(row[17] > row[15])
        assertEquals(0f, row[10])
        assertEquals(0f, row[30])
    }

    @Test
    fun mixCornersAreRound() {
        // One snow pixel in a field of rain: a diagonal neighbour at (6, 6) is about 8.5 px away, outside radius 8.
        val w = 21
        val px = IntArray(w * w) { rain }
        px[10 * w + 10] = snow
        val mix = radarMixStrength(px, w, radius = 8)
        assertTrue(mix[10 * w + 3] > 0f, "7 px straight away is inside")
        assertEquals(0f, mix[4 * w + 4], "6 px diagonally (8.5 px) is outside")
    }

    @Test
    fun noMixWithoutBothKinds() {
        assertTrue(radarMixStrength(IntArray(16) { rain }, 4, 2).none { it > 0f })
    }

    @Test
    fun mixPixelsBlendTowardsPinkPurple() {
        val full = intArrayOf(rain, argb(255, 34, 98, 255))
        recolorRadarPixels(full, floatArrayOf(1f, 1f))
        for ((r, g, b) in full.map(::channels)) assertTrue(r > g + 60 && b > g + 60, "mix should be pink/purple: ($r, $g, $b)")
        assertTrue(channels(full[1]).first < channels(full[0]).first, "heavier input gives the deeper end")
        val half = intArrayOf(rain)
        recolorRadarPixels(half, floatArrayOf(0.5f))
        val plain = intArrayOf(rain).also { recolorRadarPixels(it) }
        assertTrue(channels(half[0]).first in channels(plain[0]).first..channels(full[0]).first)
    }

    @Test
    fun mixRadiusKeepsTheBandWidthAcrossZooms() {
        assertEquals(8, radarMixRadius(6))
        assertEquals(16, radarMixRadius(7))
        assertEquals(1, radarMixRadius(1))
    }

    @Test
    fun dopplerUrlsRoundTrip() {
        val https = "https://tilecache.rainviewer.com/v2/radar/abc/512/{z}/{x}/{y}/1/1_1.png"
        assertEquals("doppler://tilecache.rainviewer.com/v2/radar/abc/512/{z}/{x}/{y}/1/1_1.png", dopplerTileUrl(https))
        assertEquals(https, dopplerToHttps(dopplerTileUrl(https)))
    }
}
