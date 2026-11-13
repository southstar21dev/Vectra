package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.designsystem.components.DecorativeLoopFps
import dev.southstar21.vectra.core.designsystem.components.rememberLoop
import dev.southstar21.vectra.core.designsystem.components.rememberOnScreen
import dev.southstar21.vectra.core.designsystem.components.trackOnScreen
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.TransitMap
import dev.southstar21.vectra.core.maps.haversineMeters
import dev.southstar21.vectra.core.maps.position
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.MapState
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.maps.MapColors
import dev.southstar21.vectra.core.maps.composeOverlaysTrackMap
import dev.southstar21.vectra.core.designsystem.theme.Radius

data class PeekStop(val id: String, val lat: Double, val lon: Double)

/** A pinned line's stop, drawn as a small badge in the line's colour. */
data class PeekPin(
    val id: String,
    val lat: Double,
    val lon: Double,
    val label: String,
    /** Hex without '#', as the catalog stores it. */
    val color: String? = null,
    val textColor: String? = null,
)

/** How far the user must move before the camera follows. */
private const val FollowDistanceMeters = 40.0

/** Small, medium and large two-layer shadows used by the map markers. */
internal object NearbyShadows {
    fun sm(c: Color = Color.Black.copy(alpha = 0.1f)) = listOf(BoxShadow(y = 1f, blur = 3f, color = c), BoxShadow(y = 1f, blur = 2f, spread = -1f, color = c))
    fun md(c: Color = Color.Black.copy(alpha = 0.1f)) = listOf(BoxShadow(y = 4f, blur = 6f, spread = -1f, color = c), BoxShadow(y = 2f, blur = 4f, spread = -2f, color = c))
    fun lg(c: Color = Color.Black.copy(alpha = 0.1f)) = listOf(BoxShadow(y = 10f, blur = 15f, spread = -3f, color = c), BoxShadow(y = 4f, blur = 6f, spread = -4f, color = c))
}

private val PingEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * A radar ping (1s cubic-bezier(0,0,.2,1), repeating: scale→2 and opacity→0 by
 * 75%). With reduced motion the ping is hidden when [hideOnReducedMotion],
 * otherwise the ring stays static.
 */
@Composable
internal fun NearbyPing(color: Color, size: Dp, hideOnReducedMotion: Boolean = true) {
    val reduce = Vectra.reduceMotion
    if (reduce && hideOnReducedMotion) return
    val onScreen = rememberOnScreen()
    val loop = rememberLoop(1000, enabled = !reduce && onScreen.value, maxFps = DecorativeLoopFps)
    Box(
        Modifier
            .size(size)
            .trackOnScreen(onScreen)
            .graphicsLayer {
                val p = if (reduce) 0f else PingEasing.transform((loop.value / 0.75f).coerceAtMost(1f))
                val s = 1f + p
                scaleX = s; scaleY = s
                alpha = 1f - p
            }
            .background(color, CircleShape),
    )
}

/** The pulsing blue "you are here" dot (20dp ping + 14dp core with a white ring). */
@Composable
internal fun NearbyUserDot(hidePingOnReducedMotion: Boolean = true) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        NearbyPing(Palette.Blue400.copy(alpha = 0.7f), 20.dp, hidePingOnReducedMotion)
        Box(
            Modifier
                // 14dp core + a 2dp ring drawn outside it.
                .size(18.dp)
                .boxShadow(CircleShape, NearbyShadows.lg(MapColors.Location.copy(alpha = 0.5f)))
                .background(Color.White, CircleShape)
                .padding(2.dp)
                .background(MapColors.Location, CircleShape),
        )
    }
}

/** A fixed-size, theme-tinted stop marker for the peek map (the progress rail has its own animated dot). */
@Composable
private fun PeekStopDot() {
    val dark = Vectra.colors.isDark
    Box(
        Modifier
            .size(10.dp)
            .boxShadow(CircleShape, NearbyShadows.sm())
            .background(if (dark) Palette.Slate900 else Color.White, CircleShape)
            .border(2.dp, if (dark) Palette.Slate300 else Palette.Slate700, CircleShape),
    )
}

@Composable
private fun PulseDot() {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        NearbyPing(Palette.Amber400.copy(alpha = 0.8f), 16.dp)
        Box(
            Modifier
                .size(14.dp)
                .boxShadow(CircleShape, NearbyShadows.lg())
                .background(Color.White, CircleShape)
                .padding(2.dp)
                .background(Palette.Amber400, CircleShape),
        )
    }
}

@Composable
private fun PinBadge(pin: PeekPin) {
    val shape = Radius.Tag
    Box(
        Modifier
            .defaultMinSize(minWidth = 20.dp)
            .height(20.dp)
            .boxShadow(shape, NearbyShadows.md())
            .background(parseHexColor(pin.color?.takeIf { it.isNotEmpty() }) ?: Palette.Slate500, shape)
            .border(1.dp, Color.Black.copy(alpha = 0.2f), shape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        VText(
            pin.label,
            style = vtext(9.sp to 9.sp, Weight.Extrabold, mono = true),
            color = parseHexColor(pin.textColor?.takeIf { it.isNotEmpty() }) ?: Color.White,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The map slice at the top of the nearby card: the basemap around you, your
 * pulsing position, the nearby stops as dots and the stop with the next
 * departure pulsing amber. Not interactive; the card around it is the button.
 */
@Composable
fun NearbyPeekMap(
    center: LatLon,
    stops: List<PeekStop>,
    highlightStopId: String?,
    modifier: Modifier = Modifier,
    pins: List<PeekPin> = emptyList(),
    onReady: () -> Unit = {},
    /** The nearest stops' next departures (C1), thinned so none touch or hide the expand button. */
    chips: List<NearbyChip> = emptyList(),
) {
    var map by remember { mutableStateOf<MapState?>(null) }
    var centered by remember { mutableStateOf(false) }
    val ready by rememberUpdatedState(onReady)
    var loaded by remember { mutableStateOf(false) }

    // One-shot: report once the map has loaded and a frame has painted.
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        withFrameNanos { }
        ready()
    }

    // The camera eases after you only once you have moved far enough to matter.
    LaunchedEffect(map, center.lat, center.lon) {
        val m = map ?: return@LaunchedEffect
        if (!centered) {
            centered = true
            m.setCameraPosition(CameraPosition(center = center.position, zoom = 15.5))
            return@LaunchedEffect
        }
        val cur = m.cameraPosition.center
        if (haversineMeters(LatLon(cur.latitude, cur.longitude), center) > FollowDistanceMeters) {
            m.animateCamera(CameraUpdate(center = center.position), CameraAnimation.Ease { duration = 900.milliseconds })
        }
    }

    val pulse = highlightStopId?.let { id -> stops.firstOrNull { it.id == id } }
    val shownChips = rememberThinnedChips(map, loaded, chips, ChipChrome(top = 0.dp, controlsBottom = 52.dp, bottom = 24.dp))

    TransitMap(
        initialCenter = center,
        initialZoom = 15.5,
        modifier = modifier.fillMaxSize(),
        minZoom = 10.0,
        dragRotate = false,
        interactive = false,
        attribution = false,
        onReady = { map = it },
        onLoaded = { loaded = true },
        // Explicit: when `layers` is left out, the Compose compiler's applier check pairs the
        // overlay lambda with the MapLibre `layers` slot and reports a false mismatch.
        layers = {},
        overlay = {
            // Draw order: you, stops, pins, then the pulse on top.
            Box(Modifier.placedAt(center.position)) { NearbyUserDot() }
            for (stop in stops) {
                key(stop.id) { Box(Modifier.placedAt(LatLon(stop.lat, stop.lon).position)) { PeekStopDot() } }
            }
            for (pin in pins) {
                key("pin-" + pin.id) { Box(Modifier.placedAt(LatLon(pin.lat, pin.lon).position)) { PinBadge(pin) } }
            }
            if (pulse != null) {
                Box(Modifier.placedAt(LatLon(pulse.lat, pulse.lon).position)) { PulseDot() }
            }
            // The card is the button: the chips are just labels here.
            if (composeOverlaysTrackMap) NearbyChipOverlay(chips, shownChips, focusedId = null, dimmed = false, onTap = {})
        },
    )
}
