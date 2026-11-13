package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.designsystem.components.rememberLoop
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.pillGlass
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.designsystem.theme.tappable

private fun statusText(status: NearbyLocationStatus): String = when (status) {
    NearbyLocationStatus.Tracking, NearbyLocationStatus.Locating -> "Finding your location…"
    NearbyLocationStatus.Failed -> "Location unavailable"
    NearbyLocationStatus.Off -> "Turn on location to see stops near you"
}

/** A spinning ring: a faint [track] circle with one [color] arc. */
@Composable
internal fun NearbyBorderSpinner(size: Dp, color: Color, track: Color, stroke: Dp = 2.dp, gapTop: Boolean = false) {
    val spin = rememberLoop(1000)
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = spin.value * 360f }) {
        val w = stroke.toPx()
        val inset = w / 2f
        val arcSize = Size(this.size.width - w, this.size.height - w)
        if (gapTop) {
            // Coloured on the sides and bottom, transparent on top: all but the top quarter.
            drawArc(color, -45f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        } else {
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            // The top border of a round box covers the 90° arc centred on 12 o'clock.
            drawArc(color, -135f, 90f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        }
    }
}

/** The strip's usual height, on top of the Transit card. */
val NearbyMapStripHeight = 132.dp

/**
 * The top of the transit cards: a slice of the map around you that opens the
 * nearby sheet, with a round expand button (and Locate when the fix failed).
 *
 * [height] overrides the strip's height (default 132dp; the transit page uses
 * 220dp on large screens).
 */
@Composable
fun NearbyMapStrip(
    status: NearbyLocationStatus,
    position: LatLon?,
    stops: List<PeekStop>,
    onOpen: () -> Unit,
    onLocate: () -> Unit,
    modifier: Modifier = Modifier,
    pins: List<PeekPin> = emptyList(),
    highlightStopId: String? = null,
    /** Reports whether a map is still loading, for a parent that covers it with its own spinner. */
    onMapPendingChange: ((Boolean) -> Unit)? = null,
    showLoadingOverlay: Boolean = true,
    height: Dp = NearbyMapStripHeight,
) {
    val c = Vectra.colors
    var mapReady by remember { mutableStateOf(false) }
    val tracking = status == NearbyLocationStatus.Tracking && position != null
    val mapPending = tracking && !mapReady
    val pendingChange by rememberUpdatedState(onMapPendingChange)
    LaunchedEffect(mapPending) { pendingChange?.invoke(mapPending) }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .background(c.surfaceOverlay),
    ) {
        if (tracking) {
            NearbyPeekMap(
                center = position,
                stops = stops,
                pins = pins,
                highlightStopId = highlightStopId,
                onReady = { mapReady = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                Modifier.fillMaxSize().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (status == NearbyLocationStatus.Locating) {
                    NearbyBorderSpinner(16.dp, c.tints.blue.text, c.tints.blue.text.copy(alpha = 0.3f))
                } else {
                    VIcon(RemixIcons.Crosshair2Line, size = 20.dp, tint = c.tints.blue.text)
                }
                VText(statusText(status), style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textSecondary)
            }
        }
        if (tracking && showLoadingOverlay) LoadingOverlay(show = !mapReady, label = "Loading map…")
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(64.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, c.surfaceSecondary))),
        )

        // The full-bleed tap target above the (non-interactive) map. No highlight: it would
        // cover the bottom fade and show the strip's hard edge across the card.
        Box(
            Modifier
                .matchParentSize()
                .tappable(role = null, highlight = false, onClick = onOpen)
                .semantics { contentDescription = "Open the nearby transit map" },
        )

        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status == NearbyLocationStatus.Failed) {
                // Small outlined pill button.
                val locatePress = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .pressScale(locatePress)
                        .pillGlass(CircleShape)
                        .tappable(role = null, highlight = false, interactionSource = locatePress, onClick = onLocate)
                        .height(32.dp)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { VText("Locate", style = vtext(TypeScale.Caption), color = c.textPrimary, maxLines = 1) }
            }
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .pressScale(press)
                    .size(32.dp)
                    .boxShadow(CircleShape, NearbyShadows.sm())
                    .background(c.surfaceSecondary.copy(alpha = c.surfaceSecondary.alpha * 0.8f), CircleShape)
                    .border(1.dp, c.borderSubtle, CircleShape)
                    .tappable(role = null, highlight = false, interactionSource = press, onClick = onOpen)
                    .semantics { contentDescription = "Open the nearby transit map" },
                contentAlignment = Alignment.Center,
            ) {
                VIcon(RemixIcons.FullscreenLine, size = 14.dp, tint = c.textSecondary)
            }
        }
    }
}
