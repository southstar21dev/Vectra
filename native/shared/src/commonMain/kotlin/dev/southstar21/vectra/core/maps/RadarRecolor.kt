package dev.southstar21.vectra.core.maps

/**
 * Recolouring behind the `doppler://` scheme: RainViewer's colour-scheme-1 tiles
 * are remapped so light and moderate rain reads as a green Doppler ramp, while the
 * heavy bands (yellow, red/orange, magenta) and snow (white) keep their colours.
 * Pixels are ARGB ints, unpremultiplied, as Bitmap#getPixels returns them.
 */
fun recolorRadarPixels(pixels: IntArray) {
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        if (a == 0) continue
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF

        val isYellow = r > 150 && g > 135 && b < 110
        val isRedOrange = r > 150 && g < 130 && b < 80
        val isMagenta = r > 110 && b > 110 && g < 80
        val isWhite = r > 240 && g > 240 && b > 240
        if (isYellow || isRedOrange || isMagenta || isWhite) continue

        val factor = maxOf(r, g, b) / 255f
        val nr = (20 + 45 * factor).toInt()
        val ng = (160 + 85 * factor).toInt()
        val nb = (15 + 35 * factor).toInt()
        pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
}

/** The `doppler://` scheme marks radar tiles the map resource provider recolours. */
const val DopplerScheme = "doppler://"

/** `https://…` → `doppler://…`. */
fun dopplerTileUrl(httpsUrl: String): String = DopplerScheme + httpsUrl.removePrefix("https://")

/** `doppler://…` → the real `https://…` tile URL. */
fun dopplerToHttps(url: String): String = "https://" + url.removePrefix(DopplerScheme)
