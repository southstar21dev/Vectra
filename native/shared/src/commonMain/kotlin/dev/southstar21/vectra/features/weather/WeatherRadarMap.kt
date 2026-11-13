package dev.southstar21.vectra.features.weather

import androidx.compose.animation.core.RepeatMode
import dev.southstar21.vectra.core.maps.dopplerTileUrl
import dev.southstar21.vectra.core.maps.drawInPage
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.southstar21.vectra.core.designsystem.theme.NoIndication
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.CubicBezierEasing
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.rgba
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.MapState
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.rememberRasterTileSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.milliseconds

/**
 * The OpenFreeMap basemap ([style], already fetched) with the active RainViewer frame as a raster
 * layer at [radarOpacity], centred on [center] with an amber pin there once [showPin]. [onState]
 * hands out the map for its events and camera; [interactive] false holds it still.
 */
@Composable
fun WeatherRadarMap(
    style: String,
    center: WeatherCoords,
    activeFrame: RadarFrame?,
    host: String,
    modifier: Modifier = Modifier,
    radarOpacity: Float = RadarOpacity,
    /** New tiles fade in (frame to frame); off while the layer takes over from the sweep, which must be instant. */
    fadeTiles: Boolean = true,
    interactive: Boolean = true,
    showPin: Boolean = true,
    onState: (MapState) -> Unit = {},
) {
    val c = Vectra.colors
    Box(modifier.fillMaxSize().background(c.surfaceSecondary)) {
        val baseStyle = remember(style) { BaseStyle.Json(style) }
        val constraints = remember { CameraConstraints { minZoom = 3.0; maxZoom = 9.0 } }
        val surface = c.surfaceSecondary
        val ui = remember(surface) { MapUiOptions { loadColor = surface; drawInPage() } }
        val state = rememberMapState(
            baseStyle = baseStyle,
            initialCameraPosition = CameraPosition(center = Position(longitude = center.longitude, latitude = center.latitude), zoom = RadarZoom.toDouble()),
        ) {
            if (activeFrame != null && host.isNotEmpty()) {
                // doppler:// routes the tile through the recolouring resource provider (core/maps).
                val url = dopplerTileUrl(getRadarTileUrl(host, activeFrame.path, 1, "1_1", RadarTilePx))
                key(url) {
                    val source = rememberRasterTileSource(tiles = listOf(url), tileSize = RadarTilePx) {
                        minZoom = 1
                        maxZoom = 7
                    }
                    RasterLayer(
                        id = "radar-layer",
                        source = source,
                        opacity = const(radarOpacity),
                        fadeDuration = const(if (fadeTiles) 150.milliseconds else 0.milliseconds),
                    )
                }
            }
        }
        val latestOnState by rememberUpdatedState(onState)
        LaunchedEffect(state) { latestOnState(state) }
        // No press highlight: the map's input layer reports a press on the first touch, which
        // the app's highlight would draw as a wash over the whole map.
        CompositionLocalProvider(LocalIndication provides NoIndication) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                state = state,
                cameraConstraints = constraints,
                interactions = remember(interactive) { if (interactive) MapInteractions { camera { rotate { enabled = false }; pitch { enabled = false } } } else MapInteractions.None },
                uiOptions = ui,
            ) {
                if (showPin) LocationPin(Modifier.placedAt(Position(longitude = center.longitude, latitude = center.latitude), Alignment.Center))
            }
        }
    }
}

/** The amber pin: a pinging halo around a ringed dot. */
@Composable
internal fun LocationPin(modifier: Modifier = Modifier) {
    val reduce = Vectra.reduceMotion
    val ping = rememberInfiniteTransition(label = "ping")
    // animate-ping: scale 1 → 2, opacity 1 → 0, 1s cubic-bezier(0, 0, 0.2, 1).
    val t by ping.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)), RepeatMode.Restart), label = "pingT")
    val ring = if (Vectra.colors.isDark) rgba(0, 0, 0, 0.8f) else rgba(255, 255, 255, 0.9f)
    Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (!reduce) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { val s = 1f + t; scaleX = s; scaleY = s; alpha = 1f - t }
                    .background(Palette.Amber400.copy(alpha = 0.4f), CircleShape),
            )
        }
        Box(
            Modifier.size(16.dp)
                .boxShadow(CircleShape, listOf(BoxShadow(y = 4f, blur = 6f, spread = -1f, color = rgba(0, 0, 0, 0.1f)), BoxShadow(y = 2f, blur = 4f, spread = -2f, color = rgba(0, 0, 0, 0.1f)), BoxShadow(spread = 2f, color = ring)))
                .background(Palette.Amber500, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(6.dp).background(Color.White, CircleShape))
        }
    }
}
