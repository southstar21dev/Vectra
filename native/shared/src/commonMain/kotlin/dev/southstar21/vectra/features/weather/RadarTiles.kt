package dev.southstar21.vectra.features.weather

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import dev.southstar21.vectra.core.maps.ArgbImage
import dev.southstar21.vectra.core.maps.decodeImage
import dev.southstar21.vectra.core.maps.recolorRadarPixels
import dev.southstar21.vectra.core.maps.toImageBitmap
import dev.southstar21.vectra.core.util.runSuspendCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/*
 * The radar's own copy of its tiles. The sweep draws the live frame itself (MapLibre can't animate
 * a mask over its layer), and the timeline's bars read the rain at your spot from every frame.
 */

/** The zoom the radar opens at; its 512 px tiles are fetched at the same level, one map dp per tile pixel. */
internal const val RadarZoom = 6
internal const val RadarTilePx = 512
private const val WorldPx = RadarTilePx * (1 shl RadarZoom)

/** [lat]/[lon] in the web-mercator world at [RadarZoom], in map dp from the world's top-left. */
internal fun worldPoint(lat: Double, lon: Double): Offset {
    val x = (lon + 180) / 360 * WorldPx
    val rad = lat * PI / 180
    val y = (1 - ln(tan(rad) + 1 / kotlin.math.cos(rad)) / PI) / 2 * WorldPx
    return Offset(x.toFloat(), y.toFloat())
}

/** One tile of the live frame, placed: its top-left in the view, in dp (the camera centred on your spot). */
internal class PlacedTile(val x: Int, val y: Int, val topLeft: Offset, val image: ImageBitmap)

/** The tiles a [width]×[height] dp view centred on [center] shows at [RadarZoom]. */
internal fun tilesInView(center: WeatherCoords, width: Float, height: Float): List<Pair<Int, Int>> {
    val c = worldPoint(center.latitude, center.longitude)
    val x0 = floor((c.x - width / 2) / RadarTilePx).toInt()
    val x1 = floor((c.x + width / 2) / RadarTilePx).toInt()
    val y0 = floor((c.y - height / 2) / RadarTilePx).toInt().coerceAtLeast(0)
    val y1 = floor((c.y + height / 2) / RadarTilePx).toInt().coerceAtMost((1 shl RadarZoom) - 1)
    return (y0..y1).flatMap { y -> (x0..x1).map { x -> x to y } }
}

/** Where tile [x]/[y]'s top-left lands in a [width]×[height] dp view centred on [center]. */
internal fun tileTopLeft(x: Int, y: Int, center: WeatherCoords, width: Float, height: Float): Offset {
    val c = worldPoint(center.latitude, center.longitude)
    return Offset(x * RadarTilePx - c.x + width / 2, y * RadarTilePx - c.y + height / 2)
}

/** How much rain a frame shows at your spot: [level] 0 (dry) to 1 (the heaviest band), in the radar's own [color]. */
internal class RainSample(val level: Float, val color: Color, val snow: Boolean)

internal val DrySample = RainSample(0f, Color.Transparent, snow = false)

/** "Rain here" for a sample. */
internal fun rainLabel(s: RainSample?): String = when {
    s == null -> "Checking…"
    s.level <= 0f -> "Dry"
    s.snow -> "Snow"
    s.level < 0.35f -> "Light rain"
    s.level < 0.6f -> "Moderate rain"
    else -> "Heavy rain"
}

/**
 * Fetches and decodes radar tiles. [liveTiles] for the sweep (recoloured as the map layer is, so the
 * hand-over can't be seen); [rainAt] for the timeline, keeping only the sample.
 */
internal class RadarTileLoader(private val http: HttpClient, private val host: String) {
    private fun url(frame: RadarFrame, x: Int, y: Int): String =
        getRadarTileUrl(host, frame.path, 1, "1_1", RadarTilePx)
            .replace("{z}", RadarZoom.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

    private suspend fun fetch(frame: RadarFrame, x: Int, y: Int): ArgbImage? = runSuspendCatching {
        val res = http.get(url(frame, x, y))
        if (!res.status.isSuccess()) return@runSuspendCatching null
        val bytes = res.bodyAsBytes()
        withContext(Dispatchers.Default) { decodeImage(bytes, RadarTilePx) }
    }.getOrNull()

    /**
     * The live frame's [tiles] for a [width]×[height] dp view centred on [here]: the rain at [here]
     * read off the one it falls in, and whether any rain shows in the view at all.
     */
    suspend fun liveTiles(frame: RadarFrame, tiles: List<Pair<Int, Int>>, here: WeatherCoords, width: Float, height: Float): LiveFrame {
        val spot = tileSpot(here)
        var sample: RainSample? = null
        var anyRain = false
        val images = HashMap<Pair<Int, Int>, ImageBitmap>()
        for ((x, y) in tiles) {
            val img = fetch(frame, x, y) ?: continue
            withContext(Dispatchers.Default) {
                if (spot.tile == (x to y)) sample = sampleRain(img, spot.px, spot.py)
                if (!anyRain) anyRain = rainInView(img, tileTopLeft(x, y, here, width, height), width, height)
                recolorRadarPixels(img.pixels)
                images[x to y] = img.toImageBitmap()
            }
        }
        return LiveFrame(images, sample, anyRain)
    }

    /** The rain at [here] in [frame]; null when its tile couldn't be fetched. */
    suspend fun rainAt(frame: RadarFrame, here: WeatherCoords): RainSample? {
        val spot = tileSpot(here)
        val img = fetch(frame, spot.tile.first, spot.tile.second) ?: return null
        return withContext(Dispatchers.Default) { sampleRain(img, spot.px, spot.py) }
    }
}

/** The live frame's tiles, the rain at your spot, and whether there's any rain in view to sweep over. */
internal class LiveFrame(val images: Map<Pair<Int, Int>, ImageBitmap>, val here: RainSample?, val rainInView: Boolean)

/** Any rain in the part of a raw tile (at [topLeft], dp = tile px) that a [w]×[h] view shows. */
private fun rainInView(img: ArgbImage, topLeft: Offset, w: Float, h: Float): Boolean {
    val x0 = (-topLeft.x).toInt().coerceIn(0, img.width)
    val x1 = (w - topLeft.x).toInt().coerceIn(0, img.width)
    val y0 = (-topLeft.y).toInt().coerceIn(0, img.height)
    val y1 = (h - topLeft.y).toInt().coerceIn(0, img.height)
    for (y in y0 until y1 step 2) {
        for (x in x0 until x1 step 2) if (rainLevel(img.pixels[y * img.width + x]) > 0f) return true
    }
    return false
}

private class TileSpot(val tile: Pair<Int, Int>, val px: Int, val py: Int)

private fun tileSpot(here: WeatherCoords): TileSpot {
    val p = worldPoint(here.latitude, here.longitude)
    val tx = floor(p.x / RadarTilePx).toInt()
    val ty = floor(p.y / RadarTilePx).toInt()
    return TileSpot(tx to ty, (p.x - tx * RadarTilePx).toInt(), (p.y - ty * RadarTilePx).toInt())
}

/** The heaviest rain within a couple of pixels of ([px], [py]) in a raw (not yet recoloured) tile. */
private fun sampleRain(img: ArgbImage, px: Int, py: Int): RainSample {
    var best = 0
    var bestLevel = 0f
    for (dy in -2..2) for (dx in -2..2) {
        val x = (px + dx).coerceIn(0, img.width - 1)
        val y = (py + dy).coerceIn(0, img.height - 1)
        val p = img.pixels[y * img.width + x]
        val level = rainLevel(p)
        if (level > bestLevel) { bestLevel = level; best = p }
    }
    if (bestLevel <= 0f) return DrySample
    val shown = intArrayOf(best or (0xFF shl 24)).also(::recolorRadarPixels)[0]
    return RainSample(bestLevel, Color(shown), snow = isSnow(best))
}

/** RainViewer scheme 1, roughly: greens and blues are light to moderate, then yellow, red, magenta. */
private fun rainLevel(p: Int): Float {
    val a = (p ushr 24) and 0xFF
    if (a < 40) return 0f
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    return when {
        r > 110 && b > 110 && g < 80 -> 1f
        r > 150 && g < 130 && b < 80 -> 0.85f
        r > 150 && g > 135 && b < 110 -> 0.7f
        isSnow(p) -> 0.4f
        else -> 0.2f + 0.35f * (1 - maxOf(r, g, b) / 255f)
    }
}

private fun isSnow(p: Int): Boolean = ((p shr 16) and 0xFF) > 240 && ((p shr 8) and 0xFF) > 240 && (p and 0xFF) > 240
