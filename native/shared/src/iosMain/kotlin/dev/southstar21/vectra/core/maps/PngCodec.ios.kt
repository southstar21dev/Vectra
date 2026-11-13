package dev.southstar21.vectra.core.maps

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

actual fun decodeImage(bytes: ByteArray, size: Int): ArgbImage? = runCatching {
    val source = Image.makeFromEncoded(bytes)
    val surface = Surface.makeRaster(ImageInfo(size, size, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
    surface.canvas.drawImageRect(source, Rect.makeWH(size.toFloat(), size.toFloat()))
    val bmp = Bitmap().apply { allocPixels(ImageInfo(size, size, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)) }
    surface.readPixels(bmp, 0, 0)
    val raw = bmp.readPixels() ?: return null
    // BGRA bytes (little-endian) == ARGB ints
    val px = IntArray(size * size) { i ->
        val o = i * 4
        ((raw[o + 3].toInt() and 0xFF) shl 24) or ((raw[o + 2].toInt() and 0xFF) shl 16) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o].toInt() and 0xFF)
    }
    ArgbImage(size, size, px)
}.getOrNull()

actual fun encodePng(image: ArgbImage): ByteArray? = runCatching {
    val raw = ByteArray(image.pixels.size * 4)
    image.pixels.forEachIndexed { i, p ->
        val o = i * 4
        raw[o] = (p and 0xFF).toByte()
        raw[o + 1] = ((p shr 8) and 0xFF).toByte()
        raw[o + 2] = ((p shr 16) and 0xFF).toByte()
        raw[o + 3] = ((p ushr 24) and 0xFF).toByte()
    }
    val info = ImageInfo(image.width, image.height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    Image.makeRaster(info, raw, image.width * 4).encodeToData(EncodedImageFormat.PNG)?.bytes
}.getOrNull()

actual fun ArgbImage.toImageBitmap(): ImageBitmap {
    val raw = ByteArray(pixels.size * 4)
    pixels.forEachIndexed { i, p ->
        val o = i * 4
        raw[o] = (p and 0xFF).toByte()
        raw[o + 1] = ((p shr 8) and 0xFF).toByte()
        raw[o + 2] = ((p shr 16) and 0xFF).toByte()
        raw[o + 3] = ((p ushr 24) and 0xFF).toByte()
    }
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    return Image.makeRaster(info, raw, width * 4).toComposeImageBitmap()
}
