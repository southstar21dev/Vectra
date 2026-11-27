package dev.southstar21.vectra.features.weather

import dev.southstar21.vectra.core.maps.decodeImage
import dev.southstar21.vectra.core.util.runSuspendCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor

/** RainViewer serves radar tiles up to this zoom ("Zoom Level Not Supported" past it). */
const val RadarDataMaxZoom = 7

/** The deepest zoom [RadarTileRenderer] makes tiles for; MapLibre stretches those further. */
const val RadarRenderMaxZoom = 10

/** RainViewer's HD tiles. */
const val RadarTilePixels = 512

/** Raw tiles kept for neighbours and deeper zooms (1 MB each). */
private const val CacheTiles = 24

/** A RainViewer tile URL taken apart: `[prefix]/{z}/{x}/{y}[suffix]`. */
class RadarTileRef(val prefix: String, val z: Int, val x: Int, val y: Int, val suffix: String) {
    fun url(z: Int, x: Int, y: Int): String = "$prefix/$z/$x/$y$suffix"

    companion object {
        private val Pattern = Regex("""^(.*/\d+)/(\d+)/(\d+)/(\d+)(/\d+/\d+_\d+\.png)$""")

        /** Null when [url] isn't a RainViewer tile (`…/512/{z}/{x}/{y}/{scheme}/{options}.png`). */
        fun parse(url: String): RadarTileRef? = Pattern.find(url)?.groupValues?.let { g ->
            RadarTileRef(g[1], g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5])
        }
    }
}

/**
 * Turns RainViewer tiles into the radar's own: recoloured, with the rain-and-snow band worked out
 * across tile edges (each tile is processed with a margin borrowed from its neighbours), and, past
 * [RadarDataMaxZoom], cut from the zoom-7 tile and enlarged with a smooth filter instead of the
 * map's blocky stretch. Raw tiles are cached, so neighbours the map loads anyway are fetched once.
 * [fetch] returns a tile's bytes, null when there is none, and throws when it can't be loaded.
 */
class RadarTileRenderer(private val fetch: suspend (String) -> ByteArray?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, Deferred<IntArray?>>()

    /** A tile at a zoom RainViewer serves. [raw] is shared with the cache: read it, never change it. */
    class Tile(val raw: IntArray, val mix: FloatArray, val colored: IntArray)

    /** The finished pixels for [ref] at any zoom up to [RadarRenderMaxZoom]; null when RainViewer has no tile there. */
    suspend fun render(ref: RadarTileRef): IntArray? =
        if (ref.z <= RadarDataMaxZoom) tile(ref)?.colored else deepTile(ref)

    /** Whether RainViewer has [ref]'s tile (fetching it if need be). */
    suspend fun has(ref: RadarTileRef): Boolean = raw(ref, ref.z, ref.x, ref.y) != null

    /** [ref] (zoom up to [RadarDataMaxZoom]) recoloured, with its raw pixels and mix band. */
    suspend fun tile(ref: RadarTileRef): Tile? {
        val centre = raw(ref, ref.z, ref.x, ref.y) ?: return null
        val pad = radarMixRadius(ref.z)
        val size = RadarTilePixels + 2 * pad
        val win = window(ref, ref.z, ref.x * RadarTilePixels - pad, ref.y * RadarTilePixels - pad, size, size)
        return withContext(Dispatchers.Default) {
            val winMix = radarMixStrength(win, size, pad)
            recolorRadarPixels(win, winMix)
            Tile(centre, crop(winMix, size, pad, RadarTilePixels), crop(win, size, pad, RadarTilePixels))
        }
    }

    /** A tile past [RadarDataMaxZoom]: its patch of the zoom-7 tile, recoloured there, then enlarged. */
    private suspend fun deepTile(ref: RadarTileRef): IntArray? {
        val dz = (ref.z - RadarDataMaxZoom).coerceAtMost(RadarRenderMaxZoom - RadarDataMaxZoom)
        if (raw(ref, RadarDataMaxZoom, ref.x shr dz, ref.y shr dz) == null) return null
        val span = RadarTilePixels shr dz
        val mixRadius = radarMixRadius(RadarDataMaxZoom)
        // The mix band's reach, plus the two pixels the bicubic filter reads past the patch.
        val pad = mixRadius + 2
        val size = span + 2 * pad
        val win = window(ref, RadarDataMaxZoom, ref.x * span - pad, ref.y * span - pad, size, size)
        return withContext(Dispatchers.Default) {
            recolorRadarPixels(win, radarMixStrength(win, size, mixRadius))
            upscaleBicubic(win, size, pad, span, RadarTilePixels)
        }
    }

    /**
     * Raw pixels of the [w]×[h] window whose top-left is world pixel ([wx], [wy]) at zoom [z], pieced
     * together from the tiles it touches. Missing or failed tiles leave their part transparent.
     */
    suspend fun window(ref: RadarTileRef, z: Int, wx: Int, wy: Int, w: Int, h: Int): IntArray = coroutineScope {
        val out = IntArray(w * h)
        val parts = (wy.floorDiv(RadarTilePixels)..(wy + h - 1).floorDiv(RadarTilePixels)).flatMap { ty ->
            (wx.floorDiv(RadarTilePixels)..(wx + w - 1).floorDiv(RadarTilePixels)).map { tx ->
                Triple(tx, ty, async { runSuspendCatching { raw(ref, z, tx, ty) }.getOrNull() })
            }
        }
        for ((tx, ty, job) in parts) {
            val px = job.await() ?: continue
            blitTile(px, tx * RadarTilePixels - wx, ty * RadarTilePixels - wy, out, w, h)
        }
        out
    }

    /** Forgets every cached tile (about 24 MB when full); in-flight fetches finish into nothing. */
    fun clear() { scope.launch { lock.withLock { cache.clear() } } }

    /** One raw tile, fetched once however many callers want it; null where there is none. */
    private suspend fun raw(ref: RadarTileRef, z: Int, x: Int, y: Int): IntArray? {
        val n = 1 shl z
        if (y !in 0 until n) return null
        val url = ref.url(z, x.mod(n), y)
        val job = lock.withLock {
            cache.remove(url)?.also { cache[url] = it } ?: scope.async {
                fetch(url)?.let { bytes -> decodeImage(bytes, RadarTilePixels)?.pixels }
            }.also {
                cache[url] = it
                while (cache.size > CacheTiles) cache.remove(cache.keys.first())
            }
        }
        val px = runSuspendCatching { job.await() }
        if (px.getOrNull() == null) forget(url, job)
        return px.getOrThrow()
    }

    /** Drop a failed or empty fetch so the next request tries again. */
    private suspend fun forget(url: String, job: Deferred<IntArray?>) = lock.withLock {
        if (cache[url] === job) cache.remove(url)
    }
}

/** A renderer fetching through [http]: 404/204 mean no tile, other failures throw. */
fun radarTileRenderer(http: HttpClient): RadarTileRenderer = RadarTileRenderer { url ->
    val res = http.get(url)
    when {
        res.status == HttpStatusCode.NotFound || res.status == HttpStatusCode.NoContent -> null
        res.status.isSuccess() -> res.bodyAsBytes()
        else -> error("Tile HTTP ${res.status.value}")
    }
}

/** Copy a [RadarTilePixels]-square tile with its top-left at ([ox], [oy]) into the [w]×[h] [out], clipped. */
internal fun blitTile(src: IntArray, ox: Int, oy: Int, out: IntArray, w: Int, h: Int) {
    val x0 = maxOf(0, ox)
    val x1 = minOf(w, ox + RadarTilePixels)
    if (x0 >= x1) return
    for (y in maxOf(0, oy) until minOf(h, oy + RadarTilePixels)) {
        val s = (y - oy) * RadarTilePixels + (x0 - ox)
        src.copyInto(out, y * w + x0, s, s + (x1 - x0))
    }
}

private fun crop(src: IntArray, size: Int, pad: Int, span: Int): IntArray {
    val out = IntArray(span * span)
    for (y in 0 until span) {
        val s = (y + pad) * size + pad
        src.copyInto(out, y * span, s, s + span)
    }
    return out
}

private fun crop(src: FloatArray, size: Int, pad: Int, span: Int): FloatArray {
    val out = FloatArray(span * span)
    for (y in 0 until span) {
        val s = (y + pad) * size + pad
        src.copyInto(out, y * span, s, s + span)
    }
    return out
}

/**
 * The [span]-px square at ([pad], [pad]) of a [size]-wide ARGB image, enlarged to [out]×[out] with a
 * Catmull-Rom (bicubic) filter. Works in premultiplied alpha so rain edges fade out instead of darkening.
 */
internal fun upscaleBicubic(src: IntArray, size: Int, pad: Int, span: Int, out: Int): IntArray {
    val taps = BicubicTaps(size, pad, span, out)
    val result = IntArray(out * out)
    val acc = FloatArray(4)
    for (oy in 0 until out) {
        for (ox in 0 until out) {
            acc.fill(0f)
            for (ky in 0..3) {
                val row = taps.index[oy * 4 + ky] * size
                val wy = taps.weight[oy * 4 + ky]
                for (kx in 0..3) addPremultiplied(acc, src[row + taps.index[ox * 4 + kx]], wy * taps.weight[ox * 4 + kx])
            }
            result[oy * out + ox] = unpremultiply(acc)
        }
    }
    return result
}

/** Per output pixel along one axis: the four source pixels the filter reads and their weights. */
private class BicubicTaps(size: Int, pad: Int, span: Int, out: Int) {
    val index = IntArray(out * 4)
    val weight = FloatArray(out * 4)

    init {
        val step = span.toFloat() / out
        for (i in 0 until out) {
            val s = pad + (i + 0.5f) * step - 0.5f
            val base = floor(s).toInt()
            val t = s - base
            for (k in 0..3) {
                index[i * 4 + k] = (base - 1 + k).coerceIn(0, size - 1)
                weight[i * 4 + k] = catmullRom(k - 1 - t)
            }
        }
    }
}

private fun catmullRom(x: Float): Float {
    val a = abs(x)
    return when {
        a < 1f -> 1.5f * a * a * a - 2.5f * a * a + 1f
        a < 2f -> -0.5f * a * a * a + 2.5f * a * a - 4f * a + 2f
        else -> 0f
    }
}

private fun addPremultiplied(acc: FloatArray, p: Int, w: Float) {
    val a = ((p ushr 24) and 0xFF) / 255f
    if (a == 0f) return
    val wa = w * a
    acc[0] += wa
    acc[1] += ((p shr 16) and 0xFF) * wa
    acc[2] += ((p shr 8) and 0xFF) * wa
    acc[3] += (p and 0xFF) * wa
}

private fun unpremultiply(acc: FloatArray): Int {
    val a = acc[0].coerceIn(0f, 1f)
    if (a < 1f / 255f) return 0
    val r = (acc[1] / acc[0] + 0.5f).toInt().coerceIn(0, 255)
    val g = (acc[2] / acc[0] + 0.5f).toInt().coerceIn(0, 255)
    val b = (acc[3] / acc[0] + 0.5f).toInt().coerceIn(0, 255)
    return ((a * 255f + 0.5f).toInt() shl 24) or (r shl 16) or (g shl 8) or b
}

/** The `doppler://` scheme marks radar tiles the map resource provider recolours. */
const val DopplerScheme = "doppler://"

/** `https://…` → `doppler://…`. */
fun dopplerTileUrl(httpsUrl: String): String = DopplerScheme + httpsUrl.removePrefix("https://")

/** `doppler://…` → the real `https://…` tile URL. */
fun dopplerToHttps(url: String): String = "https://" + url.removePrefix(DopplerScheme)
