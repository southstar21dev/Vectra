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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.rememberRasterTileSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.milliseconds

/** RainViewer serves 512px HD tiles for 2× quality. */
private const val HdTileSize = 512

/**
 * The OpenFreeMap basemap with the active RainViewer
 * frame as a raster layer and an amber pin at the forecast location.
 * [onReady] fires once the style has loaded.
 */
@Composable
fun WeatherRadarMap(
    http: HttpClient,
    center: WeatherCoords,
    activeFrame: RadarFrame?,
    host: String,
    modifier: Modifier = Modifier,
    colorScheme: Int = 1,
    opacity: Float = 0.85f,
    onReady: () -> Unit = {},
) {
    val c = Vectra.colors
    val isLight = !c.isDark
    var style by remember(isLight) { mutableStateOf<String?>(null) }
    LaunchedEffect(isLight) {
        style = try {
            fetchRadarBaseStyle(http, isLight)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The cover's safety cap releases the spinner; the surface stays plain.
            null
        }
    }
    Box(modifier.fillMaxSize().background(c.surfaceSecondary)) {
        val json = style ?: return@Box
        val ready by rememberUpdatedState(onReady)
        val baseStyle = remember(json) { BaseStyle.Json(json) }
        val constraints = remember { CameraConstraints { minZoom = 3.0; maxZoom = 9.0 } }
        val surface = c.surfaceSecondary
        val ui = remember(surface) { MapUiOptions { loadColor = surface; drawInPage() } }
        val state = rememberMapState(
            baseStyle = baseStyle,
            initialCameraPosition = CameraPosition(center = Position(longitude = center.longitude, latitude = center.latitude), zoom = 6.0),
        ) {
            if (activeFrame != null && host.isNotEmpty()) {
                // doppler:// routes the tile through the recolouring resource provider (core/maps).
                val url = dopplerTileUrl(getRadarTileUrl(host, activeFrame.path, colorScheme, "1_1", HdTileSize))
                key(url) {
                    val source = rememberRasterTileSource(tiles = listOf(url), tileSize = HdTileSize) {
                        minZoom = 1
                        maxZoom = 7
                    }
                    RasterLayer(
                        id = "radar-layer",
                        source = source,
                        opacity = const(opacity),
                        fadeDuration = const(150.milliseconds),
                    )
                }
            }
        }
        LaunchedEffect(state) {
            state.events.first { it is MapEvent.StyleLoaded || it is MapEvent.FrameRendered || it is MapEvent.StyleLoadFailed }
            ready()
        }
        // No press highlight: the map's input layer reports a press on the first touch, which
        // the app's highlight would draw as a wash over the whole map.
        CompositionLocalProvider(LocalIndication provides NoIndication) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                state = state,
                cameraConstraints = constraints,
                uiOptions = ui,
            ) {
                LocationPin(Modifier.placedAt(Position(longitude = center.longitude, latitude = center.latitude), Alignment.Center))
            }
        }
    }
}

/** The amber pin: a pinging halo around a ringed dot. */
@Composable
private fun LocationPin(modifier: Modifier) {
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
