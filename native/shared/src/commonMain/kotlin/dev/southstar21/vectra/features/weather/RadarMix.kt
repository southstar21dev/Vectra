package dev.southstar21.vectra.features.weather

/** Pixels faint enough to be edge antialiasing don't start a mix band. */
private const val MixMinAlpha = 40

/**
 * How strongly each pixel belongs to a rain-and-snow band, 0 to 1. RainViewer has no mixed type
 * (its rain/snow split comes from a coarse temperature model), so this takes the band to be where
 * rain and snow meet: full strength right at the edge, fading to nothing [radius] px away (round
 * distance, so the model's boxy snow areas get soft corners). [pixels] is a [width]-wide raw tile.
 */
internal fun radarMixStrength(pixels: IntArray, width: Int, radius: Int): FloatArray {
    val height = pixels.size / width
    val snow = BooleanArray(pixels.size)
    val rain = BooleanArray(pixels.size)
    for (i in pixels.indices) {
        val p = pixels[i]
        if ((p ushr 24) and 0xFF < MixMinAlpha) continue
        if (isRadarSnow((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)) snow[i] = true else rain[i] = true
    }
    val cap = radius + 1f
    val toSnow = distanceTo(snow, width, height, cap)
    val toRain = distanceTo(rain, width, height, cap)
    return FloatArray(pixels.size) { i ->
        when {
            snow[i] -> falloff(toRain[i], radius)
            rain[i] -> falloff(toSnow[i], radius)
            else -> 0f
        }
    }
}

/** 1 next to the other kind, easing to 0 at [radius] px. */
private fun falloff(d: Float, radius: Int): Float {
    if (d >= radius) return 0f
    val t = 1f - (d - 1f).coerceAtLeast(0f) / (radius - 1).coerceAtLeast(1)
    return t * t * (3f - 2f * t)
}

private const val Diagonal = 1.4142135f

/** Distance from each pixel to the nearest set one in [mask] (chamfer, about round), capped at [cap]. */
private fun distanceTo(mask: BooleanArray, width: Int, height: Int, cap: Float): FloatArray {
    val d = FloatArray(mask.size) { if (mask[it]) 0f else cap }
    for (y in 0 until height) for (x in 0 until width) relax(d, width, x, y, -1)
    for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) relax(d, width, x, y, 1)
    return d
}

/** One chamfer step: pull pixel ([x], [y]) down from its already-visited neighbours ([dir] -1 above/left, 1 below/right). */
private fun relax(d: FloatArray, width: Int, x: Int, y: Int, dir: Int) {
    val i = y * width + x
    var v = d[i]
    val height = d.size / width
    val nx = x + dir
    val ny = y + dir
    if (nx in 0 until width) v = minOf(v, d[i + dir] + 1f)
    if (ny in 0 until height) {
        val row = i + dir * width
        v = minOf(v, d[row] + 1f)
        if (x - 1 >= 0) v = minOf(v, d[row - 1] + Diagonal)
        if (x + 1 < width) v = minOf(v, d[row + 1] + Diagonal)
    }
    d[i] = v
}

/** The mix band's half-width in tile px at [zoom] for 512 px tiles: about 10 km, whatever the zoom. */
internal fun radarMixRadius(zoom: Int): Int {
    var r = 8.0
    if (zoom > 6) repeat(zoom - 6) { r *= 2 } else repeat(6 - zoom) { r /= 2 }
    return r.toInt().coerceAtLeast(1)
}
