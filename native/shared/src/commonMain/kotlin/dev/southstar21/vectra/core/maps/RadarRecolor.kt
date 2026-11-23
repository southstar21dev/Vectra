package dev.southstar21.vectra.core.maps

/**
 * Recolouring behind the `doppler://` scheme: RainViewer's tiles (it serves only its
 * Universal Blue scheme now) are remapped so light and moderate rain reads as a green
 * Doppler ramp, snow as a sky-to-deep-blue ramp, while the heavy bands (yellow,
 * red/orange, magenta) keep their colours. [mix] (see [radarMixStrength]) blends each pixel
 * that far towards a pink-to-purple rain-and-snow ramp. Faint pixels get their opacity lifted
 * ([liftAlpha]) so light precipitation reads as colour, not a see-through smudge.
 * Pixels are ARGB ints, unpremultiplied, as Bitmap#getPixels returns them.
 */
fun recolorRadarPixels(pixels: IntArray, mix: FloatArray? = null) {
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        if (a == 0) continue
        val base = recolorRgb(p)
        val strength = mixBlend(mix?.get(i) ?: 0f)
        val rgb = if (strength > 0f) blendRgb(base, mixRgb(p), strength) else base
        pixels[i] = (liftAlpha(a) shl 24) or rgb
    }
}

/**
 * How far a pixel of mix [strength] takes the pink: a short soft step around the middle, since
 * green and pink half-blended read as a muddy grey.
 */
private fun mixBlend(strength: Float): Float {
    val t = ((strength - 0.35f) / 0.3f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** RGB a raw pixel shows as, without the mix band. */
private fun recolorRgb(p: Int): Int {
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    val factor = maxOf(r, g, b) / 255f
    return when {
        isRadarSnow(r, g, b) -> rampRgb(SnowLight, SnowHeavy, radarSnowIntensity(r))
        heavyBand(r, g, b) > 0f -> p and 0xFFFFFF
        else -> ((20 + 45 * factor).toInt() shl 16) or ((160 + 85 * factor).toInt() shl 8) or (15 + 35 * factor).toInt()
    }
}

/** RGB of a raw pixel as rain and snow mixed, lighter or deeper with its intensity. */
private fun mixRgb(p: Int): Int {
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    val heavy = heavyBand(r, g, b)
    val t = when {
        heavy > 0f -> heavy
        isRadarSnow(r, g, b) -> radarSnowIntensity(r)
        else -> ((255 - maxOf(r, g, b)) / 150f).coerceIn(0f, 1f)
    }
    return rampRgb(MixLight, MixHeavy, t)
}

/** Heavy rain bands keep their colours: magenta 1, red/orange 0.9, yellow 0.75, else 0. */
private fun heavyBand(r: Int, g: Int, b: Int): Float = when {
    r > 110 && b > 110 && g < 80 -> 1f
    r > 150 && g < 130 && b < 80 -> 0.9f
    r > 150 && g > 135 && b < 110 -> 0.75f
    else -> 0f
}

/**
 * Opacity lifted on a curve: RainViewer draws drizzle and soft edges partly see-through, which
 * over the dark map reads as a muddy smudge. Fully clear stays clear, solid stays solid.
 */
internal fun liftAlpha(a: Int): Int = 255 - (255 - a) * (255 - a) / 255

private fun rampRgb(light: IntArray, heavy: IntArray, t: Float): Int =
    (lerp(light[0], heavy[0], t) shl 16) or (lerp(light[1], heavy[1], t) shl 8) or lerp(light[2], heavy[2], t)

private fun blendRgb(from: Int, to: Int, t: Float): Int {
    val r = lerp((from shr 16) and 0xFF, (to shr 16) and 0xFF, t)
    val g = lerp((from shr 8) and 0xFF, (to shr 8) and 0xFF, t)
    val b = lerp(from and 0xFF, to and 0xFF, t)
    return (r shl 16) or (g shl 8) or b
}

/**
 * Snow in RainViewer's tiles: pale cyan (light) to blue (heavy), always full blue with green
 * about 64 above red. Rain never matches: its only full-blue colour is magenta, green below red.
 */
fun isRadarSnow(r: Int, g: Int, b: Int): Boolean = b >= 250 && g > r && (g - r) in 45..75

/** How heavy a snow pixel is, 0 (lightest, red about 200) to 1 (heaviest, red near 0). */
fun radarSnowIntensity(r: Int): Float = (1f - r / 210f).coerceIn(0f, 1f)

private val SnowLight = intArrayOf(70, 190, 255)
private val SnowHeavy = intArrayOf(20, 45, 240)
private val MixLight = intArrayOf(245, 150, 230)
private val MixHeavy = intArrayOf(150, 55, 200)

private fun lerp(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).toInt()
