package dev.southstar21.vectra.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadarTileRendererTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun parsesTileUrls() {
        val ref = RadarTileRef.parse("https://tilecache.rainviewer.com/v2/radar/abc/512/6/35/22/1/1_1.png")!!
        assertEquals(6, ref.z)
        assertEquals(35, ref.x)
        assertEquals(22, ref.y)
        assertEquals("https://tilecache.rainviewer.com/v2/radar/abc/512/7/70/44/1/1_1.png", ref.url(7, 70, 44))
        assertNull(RadarTileRef.parse("https://tiles.openfreemap.org/planet/123/6/35/22.pbf"))
    }

    @Test
    fun blitClipsTilesIntoAWindow() {
        val tile = IntArray(RadarTilePixels * RadarTilePixels) { it }
        val out = IntArray(4 * 4)
        // The tile's bottom-right 2x2 lands in the window's top-left.
        blitTile(tile, 2 - RadarTilePixels, 2 - RadarTilePixels, out, 4, 4)
        val last = RadarTilePixels * RadarTilePixels - 1
        assertEquals(last - RadarTilePixels - 1, out[0])
        assertEquals(last, out[1 * 4 + 1])
        assertEquals(0, out[2])
        assertEquals(0, out[2 * 4])
    }

    @Test
    fun bicubicKeepsFlatColourAndTransparency() {
        val size = 8
        val solid = IntArray(size * size) { argb(200, 20, 160, 15) }
        val up = upscaleBicubic(solid, size, pad = 2, span = 4, out = 16)
        for (p in up) {
            assertEquals(200, (p ushr 24) and 0xFF)
            assertEquals(160, (p shr 8) and 0xFF)
        }
        assertTrue(upscaleBicubic(IntArray(size * size), size, 2, 4, 16).all { it == 0 })
    }

    @Test
    fun bicubicFadesEdgesWithoutDarkening() {
        // Left half green, right half transparent (stored as black, as decoded tiles are).
        val size = 8
        val src = IntArray(size * size) { if (it % size < 4) argb(255, 20, 160, 15) else 0 }
        val up = upscaleBicubic(src, size, pad = 2, span = 4, out = 16)
        for (p in up) {
            if ((p ushr 24) and 0xFF < 8) continue
            assertTrue((p shr 8) and 0xFF >= 150, "edge pixels keep their green, not fade to black")
        }
    }
}
