package dev.southstar21.vectra.core.maps

import org.maplibre.compose.map.AndroidRenderMode
import org.maplibre.compose.map.MapUiOptions

internal actual fun MapUiOptions.Builder.drawInPage() {
    renderMode = AndroidRenderMode.Texture
}

internal actual val composeOverlaysTrackMap: Boolean = true
