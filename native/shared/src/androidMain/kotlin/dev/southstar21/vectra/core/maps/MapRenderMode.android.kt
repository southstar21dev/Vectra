package dev.southstar21.vectra.core.maps

import org.maplibre.compose.map.AndroidRenderMode
import org.maplibre.compose.map.MapUiOptions

actual fun MapUiOptions.Builder.drawInPage() {
    renderMode = AndroidRenderMode.Texture
}
