package dev.southstar21.vectra.features.transit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.consumeTouches
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext

/** The island's pills' height. */
internal val IslandHeight = 44.dp

/** What the middle pill says about the times on the map. */
internal enum class FeedLiveness { Live, Timetable, Loading, ZoomIn }

internal fun feedLiveness(arrivalsEnabled: Boolean, loading: Boolean, feed: List<RadarDepartureItem>): FeedLiveness = when {
    !arrivalsEnabled -> FeedLiveness.ZoomIn
    feed.any { it.isPredicted } -> FeedLiveness.Live
    feed.isEmpty() && loading -> FeedLiveness.Loading
    else -> FeedLiveness.Timetable
}

/** The explorer's top: whether the times are live on the left, close on the right (locate stacks under it). */
@Composable
internal fun ExplorerTopBar(liveness: FeedLiveness, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LivenessPill(liveness)
        Box(Modifier.weight(1f))
        Box(
            Modifier
                .size(IslandHeight)
                .glass(CircleShape)
                .tappable(CircleShape, role = Role.Button, onClickLabel = "Close the map", haptic = { tick() }, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { VIcon(RemixIcons.CloseLine, size = 20.dp, tint = c.textPrimary, contentDescription = "Close the map") }
    }
}

@Composable
private fun LivenessPill(liveness: FeedLiveness) {
    val c = Vectra.colors
    val (label, ink) = when (liveness) {
        FeedLiveness.Live -> "Live" to c.tints.emerald.text
        FeedLiveness.Timetable -> "Timetable" to c.textSecondary
        FeedLiveness.Loading -> "Updating" to c.textSecondary
        FeedLiveness.ZoomIn -> "Zoom in for times" to c.textSecondary
    }
    Row(
        Modifier.height(IslandHeight).glass(CircleShape).consumeTouches().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (liveness == FeedLiveness.Live) PulsingDot(Palette.Emerald500) else Box(Modifier.size(8.dp).background(c.textMuted, CircleShape))
        VText(label, style = vtext(TypeScale.Body, Weight.Bold), color = ink, maxLines = 1)
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val reduce = Vectra.reduceMotion
    val t by rememberInfiniteTransition(label = "live").animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "liveT")
    Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) {
        if (!reduce) Box(Modifier.size(8.dp).graphicsLayer { val s = 1f + t * 1.4f; scaleX = s; scaleY = s; alpha = 0.5f * (1f - t) }.background(color, CircleShape))
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

/**
 * The departure being followed, pinned at the top of the sheet under "Following": badge, where it
 * goes and from which stop, the countdown, and Stop to let it go.
 */
@Composable
internal fun FollowedDeparture(selection: ExplorerSelection, countdown: Int, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        VText("FOLLOWING", Modifier.padding(start = 6.dp), vtext(TypeScale.Micro, Weight.Bold, Tracking.Widest), c.textMuted)
        Row(
            Modifier
                .fillMaxWidth()
                .background(nearbyHexAlpha(selection.color, 0x1C), RoundedCornerShape(16.dp))
                .consumeTouches()
                .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.widthIn(min = 40.dp), contentAlignment = Alignment.Center) {
                RouteBadge(selection.shortName, selection.color, selection.textColor, size = RouteBadgeSize.Md)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    VIcon(RemixIcons.ArrowRightLine, size = 13.dp, tint = c.textSecondary)
                    VText(selection.headsign.ifEmpty { "Any direction" }, style = vtext(TypeScale.Body, Weight.Bold), color = c.textPrimary, maxLines = 1)
                }
                VText("from ${selection.stopName}", style = vtext(TypeScale.Caption, Weight.Medium), color = c.textMuted, maxLines = 1)
            }
            NearbyEtaRow(selection.isPredicted, formatNearbyCountdown(countdown))
            VText(
                "Stop",
                Modifier.tappable(CircleShape, role = Role.Button, onClickLabel = "Stop following this line", onClick = onStop).padding(horizontal = 10.dp, vertical = 8.dp),
                vtext(TypeScale.Caption, Weight.Bold),
                c.accentPrimary,
                maxLines = 1,
            )
        }
    }
}

/** A notice under the island (outside Hungary, no location fix). */
@Composable
internal fun IslandNotice(text: String, ink: Color, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.widthIn(max = 520.dp).fillMaxWidth().glass(shape).consumeTouches().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(text, Modifier.weight(1f), vtext(TypeScale.Caption, Weight.Medium), ink)
        if (action != null) {
            VText(
                action.first,
                Modifier.tappable(CircleShape, role = Role.Button, onClick = action.second).padding(horizontal = 8.dp, vertical = 4.dp),
                vtext(TypeScale.Caption, Weight.Bold),
                Vectra.colors.accentPrimary,
                maxLines = 1,
            )
        }
    }
}
