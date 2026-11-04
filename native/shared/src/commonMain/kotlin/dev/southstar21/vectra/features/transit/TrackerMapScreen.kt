package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.BarLeading
import dev.southstar21.vectra.core.designsystem.components.ScreenColumn
import dev.southstar21.vectra.core.designsystem.components.SurfaceGutter
import dev.southstar21.vectra.core.designsystem.components.TopBar
import dev.southstar21.vectra.core.designsystem.components.TopBarSubtitle
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Radius

/**
 * The live route map, filling the screen. It mounts its own map (fitted to the route on
 * mount like the small one) on the same live data, so the vehicle poll keeps moving the
 * markers. A stop picked here carries back to the live screen underneath.
 */
@Composable
fun TrackerMapScreen(repo: TransitRepository, stopPicks: TrackerStopPicks, trackerId: String, stopId: String?, onBack: () -> Unit) {
    val now = rememberNowMillis(10_000)
    val tracker = rememberTracker(repo, trackerId, onMissing = onBack)
    val trackerStops = remember(tracker) { tracker?.let(::getTrackerStops) ?: emptyList() }
    var pickedStopId by rememberSaveable { mutableStateOf(stopId) }
    val activeStopId = tracker?.let { liveStopIdFor(it, pickedStopId) }.orEmpty()
    val activeStop = remember(tracker, trackerStops, activeStopId) { tracker?.let { liveStopFor(it, trackerStops, activeStopId) } }
    val live = rememberLiveRoute(repo, tracker, trackerStops, activeStop, now)
    var mapReady by remember { mutableStateOf(false) }

    val t = tracker
    if (t == null) {
        ScreenColumn { TopBar("", BarLeading.Back, onBack) }
        return
    }
    val c = Vectra.colors
    val stopName = activeStop?.stopName?.takeIf { it.isNotEmpty() } ?: t.stopName
    val lead = live.enriched.firstOrNull()
    ScreenColumn {
        TopBar(
            title = stopName,
            leading = BarLeading.Back,
            onLeading = onBack,
            titleContent = { LiveTrackerTitle(t, stopName) },
            subtitle = { TopBarSubtitle("Live map") },
            accessory = if (trackerStops.size > 1) {
                {
                    TrackedStopTabs(trackerStops, activeStop?.stopId ?: activeStopId, { id ->
                        pickedStopId = id
                        stopPicks.put(trackerId, id)
                    })
                }
            } else null,
        )

        // The map takes everything under the bar as one rounded, bordered box,
        // ending clear of the navigation bar.
        val safeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val shape = Radius.Panel
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = SurfaceGutter, end = SurfaceGutter, bottom = safeBottom + 16.dp)
                .clip(shape)
                .background(c.surfaceOverlay, shape)
                .border(1.dp, c.borderSubtle, shape),
        ) {
            RouteMap(
                stops = lead?.stops.orEmpty(),
                polyline = lead?.polyline.orEmpty(),
                vehicles = live.leadVehicles,
                secondaryLines = live.secondaryLines,
                routeColor = live.routeColor,
                fillHeight = true,
                onReady = { mapReady = true },
            )
            LoadingOverlay(show = !mapReady, label = "Loading map…")
        }
    }
}
