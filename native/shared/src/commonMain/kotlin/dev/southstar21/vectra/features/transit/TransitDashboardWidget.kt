package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.navigation.LocalNavigator
import dev.southstar21.vectra.navigation.Screen
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/**
 * The dashboard's transit card: the map around you (pinned lines' nearest stops
 * on it) over the starred trackers as stop monitors; without pins, the lines at
 * the closest stop. The map opens the nearby explorer.
 *
 * [favorites]: starred trackers in their saved order. [onOpenTracker]: open a
 * tracker's live view. [onOpenTransit]: go to the Transit tab ("Star a tracker
 * to pin it here").
 */
@Composable
fun TransitDashboardWidget(
    repo: TransitRepository,
    deviceLocation: DeviceLocation,
    auth: AuthRepository,
    favorites: List<TransitTracker>,
    onOpenTracker: (TransitTracker) -> Unit,
    onOpenTransit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Vectra.colors
    val profile by auth.profile.collectAsStateWithLifecycle()
    val modeFilter = remember(profile?.preferences) { parseModeFilter(profile?.preferences) }
    val hasPinned = favorites.isNotEmpty()
    val nearby = rememberNearbyData(repo, deviceLocation, modeFilter, departures = !hasPinned)
    val pinnedRows = rememberPinnedRows(repo, favorites, nearby.position)
    val navigator = LocalNavigator.current

    val peekStops = remember(nearby.stops) { nearby.stops.take(8).map { PeekStop(it.id, it.lat, it.lon) } }
    val pins = remember(pinnedRows) {
        pinnedRows.mapNotNull { r ->
            r.primaryAt?.let { PeekPin(r.tracker.id, it.lat, it.lon, r.tracker.routeShortName.ifEmpty { r.tracker.routeId }, r.tracker.routeColor, r.tracker.routeTextColor) }
        }
    }
    val shape = Radius.Card
    Column(modifier.fillMaxWidth().glass(shape).clip(shape)) {
        NearbyMapStrip(
            status = nearby.status,
            position = nearby.position,
            stops = peekStops,
            pins = pins,
            highlightStopId = if (hasPinned) null else nearby.radarItems.firstOrNull()?.stopId,
            onOpen = { navigator.push(Screen.NearbyExplorer) },
            onLocate = nearby.refreshLocation,
        )
        if (hasPinned) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pinnedRows.forEach { row ->
                    key(row.tracker.id) {
                        TransitCard(repo, row.tracker, onOpenTracker, showFavoriteMark = false)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                NearbyDeparturesList(nearby.radarItems, nearby.status)
                val rule = c.borderSubtle
                Row(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .drawBehind { drawLine(rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                        .tappable(role = null, onClick = onOpenTransit)
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VIcon(RemixIcons.StarLine, size = 14.dp, tint = c.tints.amber.text)
                    VText("Star a tracker to pin it here", Modifier.weight(1f), vtext(TypeScale.Caption, Weight.Semibold), c.textSecondary)
                    VIcon(RemixIcons.ArrowRightSLine, size = 14.dp, tint = c.textSecondary)
                }
            }
        }
    }
}

/** The dashboard's starred trackers, in their saved order. */
@Composable
fun rememberFavoriteTrackers(repo: TransitRepository): List<TransitTracker> {
    val trackers = repo.trackersQuery.collect(Unit).data.orEmpty()
    return remember(trackers) { trackers.filter { it.isFavorite } }
}
