package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.SleepingIcon
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.cardDepthShadows
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.insetShadow
import dev.southstar21.vectra.core.designsystem.theme.specularBorder
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.time.formatBudapestHm
import kotlin.math.ceil
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Radius

/**
 * One direction of the tracked route — stops, geometry, vehicles and progress.
 * An all-directions tracker yields up to three of these; a direction tracker one.
 */
data class DirectionView(
    val tripId: String?,
    val stops: List<RouteMapStop>,
    val polyline: List<LatLon>,
    val vehicles: List<RouteMapVehicle>,
    val arrival: Arrival?,
    /** The next departures after `arrival`, same direction. */
    val upcoming: List<Arrival>,
    val trackedIndex: Int,
    val progress: RouteProgress,
    /** Raw timed stopTimes from the trip entry — approximate-position input. */
    val stopTimes: List<FutarTripStopTime>,
    /** Schedule-interpolated metro position when no live vehicle reports. */
    val approxVehicle: RouteMapVehicle? = null,
)

internal fun etaMinutes(ms: Long, now: Long): Int = maxOf(0, ceil((ms - now) / 60_000.0).toInt())

private val CardShape = Radius.Card

/**
 * One compact departure card per direction: route badge, destination, ETA
 * countdown, scheduled clock time, and the visualizer showing how many stops
 * are left to the user.
 */
@Composable
fun DirectionStrip(
    direction: DirectionView,
    routeName: String,
    routeColor: String,
    now: Long,
    /** Second direction gets violet so the tracked directions are distinct. */
    accent: DirectionAccent,
    modifier: Modifier = Modifier,
    routeId: String? = null,
    routeTextColor: String? = null,
    /** The lead direction stands proud of the others. */
    lead: Boolean = false,
    /** Frozen stopsLeft for this direction while the live screen is up (unset = live). */
    frozenStopsLeft: Int? = null,
    hasFrozenStopsLeft: Boolean = false,
) {
    val c = Vectra.colors
    val stops = direction.stops
    val arrival = direction.arrival
    val progress = direction.progress
    val displayRouteName = formatRouteDisplayName(routeName, routeId?.takeIf { it.isNotEmpty() } ?: arrival?.routeId, true)
    val minutes = arrival?.let { etaMinutes(it.arrivalMs, now) }
    val eta = minutes?.let { formatCountdown(it) }
    val clockTime = arrival?.arrivalMs?.takeIf { it != 0L }?.let { formatBudapestHm(it) }
    val hasVehicle = progress.vehicleIndex >= 0 || direction.approxVehicle != null
    val stopsLeft = if (hasFrozenStopsLeft) frozenStopsLeft else progress.stopsLeft
    val approx = direction.approxVehicle != null
    val destination = arrival?.headSign?.takeIf { it.isNotEmpty() } ?: stops.lastOrNull()?.name.orEmpty()

    // Glass everywhere; the light theme's card depth outranks the glass
    // shadow on the lead card (dark keeps the glass shadow).
    val surface = if (lead && !c.isDark) {
        val depth = cardDepthShadows(false)
        Modifier
            .boxShadow(CardShape, depth)
            .background(c.glassBg, CardShape)
            .insetShadow(CardShape, depth)
            .specularBorder(CardShape, c.glassBorder, c.glassBorderSpecular)
    } else Modifier.glass(CardShape)

    Column(modifier.fillMaxWidth().then(surface).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                VText("Towards", style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textSecondary)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RouteBadge(displayRouteName, routeColor, routeTextColor, size = RouteBadgeSize.Sm)
                    VIcon(RemixIcons.ArrowRightLine, size = 14.dp, tint = c.textMuted)
                    VText(
                        destination,
                        Modifier.weight(1f, fill = false),
                        vtext(TypeScale.BodyLarge, Weight.Extrabold, Tracking.Tight),
                        c.textPrimary,
                        maxLines = 1,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (eta != null) {
                    val ink = when {
                        minutes <= 0 -> c.tints.emerald.text
                        minutes <= 2 -> c.tints.amber.text
                        else -> c.textPrimary
                    }
                    VText(eta, style = vtext(TypeScale.Headline, Weight.Black, Tracking.Tight, lineHeight = 24.sp, mono = true), color = ink)
                } else {
                    Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                        SleepingIcon(
                            Modifier.semantics { contentDescription = "No scheduled departure" },
                            size = 24.dp,
                            tint = if (c.isDark) Palette.Indigo300 else Palette.Indigo400,
                        )
                    }
                }
                if (clockTime != null) {
                    VText(clockTime, Modifier.padding(top = 6.dp), vtext(TypeScale.Caption), c.textSecondary)
                }
            }
        }

        StopsProgressVisualizer(
            stopsLeft = stopsLeft,
            liveProgress = progress,
            hasVehicle = hasVehicle,
            accent = accent,
            approx = approx,
        )

        if (direction.upcoming.isNotEmpty()) {
            Column(Modifier.padding(top = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(c.borderSubtle))
                    VText("NEXT", style = vtext(TypeScale.Micro, Weight.Bold, 0.14.em), color = c.textMuted)
                    Box(Modifier.weight(1f).height(1.dp).background(c.borderSubtle))
                }
                Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val cellShape = Radius.Panel
                    for (i in 0 until 3) {
                        val a = direction.upcoming.getOrNull(i)
                        if (a == null) {
                            Spacer(Modifier.weight(1f))
                            continue
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .background(c.surfaceOverlay, cellShape)
                                .border(1.dp, c.borderSubtle, cellShape)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            VText(formatBudapestHm(a.arrivalMs), style = vtext(14.sp to 14.sp, Weight.Extrabold, mono = true), color = c.textPrimary, maxLines = 1)
                            VText(formatCountdown(etaMinutes(a.arrivalMs, now)), style = vtext(11.sp to 11.sp, Weight.Bold, mono = true), color = c.textMuted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
