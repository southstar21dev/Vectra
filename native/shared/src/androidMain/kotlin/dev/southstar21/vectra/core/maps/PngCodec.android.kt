package dev.southstar21.vectra.core.maps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

actual fun decodeImage(bytes: ByteArray, size: Int): ArgbImage? {
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val bmp = if (decoded.width == size && decoded.height == size) decoded else Bitmap.createScaledBitmap(decoded, size, size, true)
    val px = IntArray(size * size)
    bmp.getPixels(px, 0, size, 0, 0, size, size)
    return ArgbImage(size, size, px)
}

actual fun encodePng(image: ArgbImage): ByteArray? {
    val bmp = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    val out = ByteArrayOutputStream()
    return if (bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
}
