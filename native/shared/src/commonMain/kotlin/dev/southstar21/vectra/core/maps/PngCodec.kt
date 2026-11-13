package dev.southstar21.vectra.core.maps

import androidx.compose.ui.graphics.ImageBitmap

/** Decoded image as unpremultiplied ARGB pixels. */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray)

/** Decode a PNG/JPEG, scaled to [size]×[size]. */
expect fun decodeImage(bytes: ByteArray, size: Int): ArgbImage?

expect fun encodePng(image: ArgbImage): ByteArray?

/** The pixels as an image Compose can draw. */
expect fun ArgbImage.toImageBitmap(): ImageBitmap
