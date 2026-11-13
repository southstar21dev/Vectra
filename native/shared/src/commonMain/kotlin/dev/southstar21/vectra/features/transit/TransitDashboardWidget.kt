package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.navigation.LocalNavigator
import dev.southstar21.vectra.navigation.Screen

/** From this many pinned trackers the dashboard shows slim cards. */
private const val SlimCardsFrom = 4

/**
 * The dashboard's transit section: a "Transit" header and one line-strip card per starred
 * tracker, straight on the page (slim cards from four pinned up). Without pins, the lines at
 * the nearest stop as strips, and a link to pin your own.
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
    val navigator = LocalNavigator.current
    val openNearby = { navigator.push(Screen.NearbyExplorer) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hasPinned) {
            TransitSectionHeader("Transit", "Nearby", onAction = openNearby)
            val slim = favorites.size >= SlimCardsFrom
            favorites.forEachIndexed { i, tracker ->
                key(tracker.id) {
                    if (slim) {
                        LineStripSlimCard(repo, tracker, nearby.position, onOpenTracker)
                    } else {
                        LineStripCard(repo, tracker, nearby.position, onOpenTracker, onAskLocation = if (i == 0) nearby.refreshLocation else null)
                    }
                }
            }
        } else {
            val first = nearby.radarItems.firstOrNull()
            TransitSectionHeader("Nearby", first?.stopName, onAction = openNearby)
            val lines = remember(nearby.radarItems, first?.stopId) { first?.let { nearbyTrackers(nearby.radarItems, it.stopId) }.orEmpty() }
            if (lines.isEmpty()) {
                Box(Modifier.fillMaxWidth().stripCardSurface().padding(14.dp)) { NearbyDeparturesList(nearby.radarItems, nearby.status) }
            } else {
                lines.forEach { t -> key(t.id) { LineStripCard(repo, t, nearby.position, onOpen = { openNearby() }) } }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 44.dp)
                    .tappable(role = null, onClick = onOpenTransit)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VIcon(RemixIcons.StarLine, size = 15.dp, tint = c.tints.amber.text)
                VText("Star a tracker to pin it here", Modifier.weight(1f), vtext(TypeScale.Caption, Weight.Semibold), c.textSecondary)
                VIcon(RemixIcons.ArrowRightSLine, size = 15.dp, tint = c.textSecondary)
            }
        }
    }
}

/** The lines at the nearest stop as stand-in trackers (all directions), at most two. */
internal fun nearbyTrackers(items: List<RadarDepartureItem>, stopId: String): List<TransitTracker> =
    groupByLine(items, stopId).map { g ->
        val first = g.directions.first()
        TransitTracker(
            id = "nearby:${g.routeId}:$stopId",
            stopId = stopId,
            stopName = first.stopName,
            routeId = g.routeId,
            routeShortName = g.shortName,
            routeType = first.routeType,
            routeColor = g.color,
            routeTextColor = g.textColor,
        )
    }

/** A section title with an optional link at the right ("Nearby ›"). */
@Composable
internal fun TransitSectionHeader(title: String, action: String?, modifier: Modifier = Modifier, onAction: (() -> Unit)? = null) {
    val c = Vectra.colors
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(title, Modifier.semantics { heading() }, vtext(24.sp to 30.sp, Weight.Extrabold, (-0.02).em), c.textPrimary, maxLines = 1)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (action != null) {
                val linkModifier = if (onAction != null) Modifier.tappable(role = Role.Button, onClick = onAction).padding(vertical = 8.dp) else Modifier
                Row(linkModifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    VText(action, Modifier.weight(1f, fill = false), vtext(14.sp to 18.sp, Weight.Medium), c.textSecondary, maxLines = 1)
                    if (onAction != null) VIcon(RemixIcons.ArrowRightSLine, size = 16.dp, tint = c.textSecondary)
                }
            }
        }
    }
}

/** The dashboard's starred trackers, in their saved order. */
@Composable
fun rememberFavoriteTrackers(repo: TransitRepository): List<TransitTracker> {
    val state = repo.trackersQuery.collect(Unit)
    val trackers = state.data.orEmpty()
    DemoSeedEffect(repo, trackers, state.isFetched && state.data != null)
    return remember(trackers) { trackers.filter { it.isFavorite } }
}
