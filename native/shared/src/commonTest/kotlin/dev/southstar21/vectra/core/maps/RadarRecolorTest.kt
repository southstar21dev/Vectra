package dev.southstar21.vectra.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals

class RadarRecolorTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun keepsTransparentAndHeavyBands() {
        val input = intArrayOf(
            argb(0, 10, 200, 30), // transparent
            argb(255, 230, 200, 40), // yellow
            argb(255, 220, 90, 20), // red/orange
            argb(255, 180, 40, 190), // magenta
            argb(255, 250, 250, 250), // white
        )
        val px = input.copyOf()
        recolorRadarPixels(px)
        assertEquals(input.toList(), px.toList())
    }

    @Test
    fun remapsLightRainToGreenRampKeepingAlpha() {
        val px = intArrayOf(argb(200, 0, 120, 255), argb(255, 0, 0, 0))
        recolorRadarPixels(px)
        // intensity 255 → factor 1 → (65, 245, 50)
        assertEquals(argb(200, 65, 245, 50), px[0])
        // intensity 0 → factor 0 → (20, 160, 15)
        assertEquals(argb(255, 20, 160, 15), px[1])
    }

    @Test
    fun dopplerUrlsRoundTrip() {
        val https = "https://tilecache.rainviewer.com/v2/radar/abc/512/{z}/{x}/{y}/1/1_1.png"
        assertEquals("doppler://tilecache.rainviewer.com/v2/radar/abc/512/{z}/{x}/{y}/1/1_1.png", dopplerTileUrl(https))
        assertEquals(https, dopplerToHttps(dopplerTileUrl(https)))
    }
}
