package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.maps.GlassMapControls
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.MapControlButton
import dev.southstar21.vectra.core.time.nowMs
import kotlinx.coroutines.delay
import org.maplibre.compose.map.MapState
import dev.southstar21.vectra.core.maps.position
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset

/** The island band at the top (status bar excluded): the pills plus their margin. */
private val IslandBand = 60.dp

/**
 * The nearby explorer (C1): the map with departure chips over its stops, the three-pill island
 * (where you are · live · close), a locate button, and the departures sheet at a low peek. A
 * picked stop's chip opens into its board; a picked departure follows its line. [opened] false
 * holds the map back (while the card it grows out of is still growing).
 */
@Composable
fun NearbyExplorerScreen(
    repo: TransitRepository,
    deviceLocation: DeviceLocation,
    auth: AuthRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    opened: Boolean = true,
    /** False drops the map itself (the rest stays), as the view shrinks away where it can't be clipped. */
    showMap: Boolean = true,
) {
    val explorer = rememberNearbyExplorer(repo, deviceLocation, auth)
    var cameraRequest by remember { mutableStateOf<ExplorerCameraRequest?>(null) }
    // Settled coverage goes through state (re-queries the map); per-frame coverage
    // is read only at layout time so a drag never recomposes the map or the feed.
    var sheetCovered by remember { mutableFloatStateOf(0f) }
    val sheetLive = remember { mutableFloatStateOf(0f) }
    var mapReady by remember { mutableStateOf(false) }
    // Never leave the cover stuck if readiness never arrives.
    LaunchedEffect(mapReady, opened) {
        if (mapReady || !opened) return@LaunchedEffect
        delay(3500)
        mapReady = true
    }
    val safeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var mapState by remember { mutableStateOf<MapState?>(null) }
    // The orb holds until the stops are in (or a moment after the map), so its dots have somewhere to land.
    var stopsWaitOver by remember { mutableStateOf(false) }
    LaunchedEffect(mapReady) {
        if (!mapReady) return@LaunchedEffect
        delay(2500)
        stopsWaitOver = true
    }
    val covering = !mapReady || (explorer.mapStops.isEmpty() && !stopsWaitOver)
    val density = LocalDensity.current.density
    val chrome = ChipChrome(top = safeTop + IslandBand, controlsBottom = safeTop + IslandBand + 52.dp, bottom = sheetCovered.dp)

    Box(modifier.fillMaxSize().background(Vectra.colors.surfacePrimary)) {
        if (opened && showMap) {
            ExplorerMap(explorer, cameraRequest, sheetCovered.dp, { sheetLive.floatValue.dp }, chrome, { mapState = it }) { mapReady = true }
        }
        // The orb while the map and its stops come in; it bursts away once they're there.
        // Where the dots land, taken once as the melt starts: stops that move or re-sort mid-melt
        // (the first view settling, departures arriving) would make the dots jump.
        val meltTargets = remember(covering) { if (covering) emptyList() else stopTargets(mapState, explorer.mapStops, density) }
        MapLoadingOrb(
            show = covering,
            label = "Finding stops near you…",
            routeColor = Vectra.colors.textSecondary,
            routePath = { meltTargets },
            intoPoints = true,
            maxDurationMs = 5000,
        )
        ExplorerChrome(explorer, safeTop, onBack) { target -> cameraRequest = ExplorerCameraRequest(target.lat, target.lon, 15.5, nowMs()) }
        // Held back until the grow is over (its glass and feed are the heavy part of the first
        // frame, and would make the grow jump), then it rises in.
        if (opened) {
            SheetEntrance {
                ExplorerSheet(explorer, onFly = { lat, lon -> cameraRequest = ExplorerCameraRequest(lat, lon, 15.5, nowMs()) }) { covered, settled ->
                    sheetLive.floatValue = covered
                    if (settled) sheetCovered = covered
                }
            }
        }
    }
}

/** The sheet rising into place and fading in, once, as it first appears. */
@Composable
private fun SheetEntrance(content: @Composable () -> Unit) {
    val reduced = Vectra.reduceMotion
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 260f)) }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val v = enter.value
            translationY = (1f - v) * 160.dp.toPx()
            alpha = v.coerceIn(0f, 1f)
        },
    ) { content() }
}

/** The departures sheet, at its low peek: the feed near you, or the picked stop's. */
@Composable
private fun ExplorerSheet(explorer: NearbyExplorerState, onFly: (Double, Double) -> Unit, onCoverage: (Float, Boolean) -> Unit) {
    NearbyDeparturesSheet(
        feed = explorer.feed,
        selection = explorer.selection?.let { NearbySelectionKey(it.stopId, it.routeId, it.headsign, it.shortName, it.color, it.textColor) },
        onSelectItem = { item ->
            explorer.handleSelectItem(item)
            val stop = explorer.stopsInView.firstOrNull { it.id == item.stopId }
            // Match the selection and marker: the departure's own physical stop first.
            val lat = item.platformLat ?: stop?.lat
            val lon = item.platformLon ?: stop?.lon
            if (lat != null && lon != null) onFly(lat, lon)
        },
        modeFilter = explorer.modeFilter,
        onToggleMode = explorer.handleToggleMode,
        // Until the map reports its first view, it's still finding the stops (not "zoom in").
        arrivalsEnabled = explorer.arrivalsEnabled || explorer.viewport == null,
        isLoading = explorer.isArrivalsLoading || explorer.viewport == null,
        visibleStopCount = explorer.visibleStopCount,
        polledStopCount = explorer.polledStopCount,
        title = explorer.focusedStop?.name ?: "Departures near you",
        subtitle = sheetSubtitle(explorer),
        pinned = explorer.selection?.let { sel ->
            { FollowedDeparture(sel, explorer.trackedCountdownMinutes ?: 0, explorer.clearSelection) }
        },
        onCoverageChange = onCoverage,
    )
}

/** How many stops the orb's dots fly to as it melts away. */
private const val MeltStops = 30

/**
 * Where the loading orb's dots land: each stop on screen as a dot-sized run (px), so the melt
 * gathers them on the stops instead of spraying them out.
 */
private fun stopTargets(map: MapState?, stops: List<ExplorerMapStop>, density: Float): List<List<Offset>> {
    if (map == null) return emptyList()
    return runCatching {
        stops.take(MeltStops).mapNotNull { s ->
            map.screenLocationFromPosition(LatLon(s.lat, s.lon).position)?.let {
                val p = Offset(it.x.value * density, it.y.value * density)
                listOf(p, p + Offset(2f, 0f))
            }
        }.sortedBy { it[0].x } // Left-hand dots go to left-hand stops: shorter, uncrossed flights.
    }.getOrDefault(emptyList())
}

/** How many stops are in view (nothing for a picked stop: its name is the heading). */
private fun sheetSubtitle(explorer: NearbyExplorerState): String? {
    if (explorer.focusedStop != null) return null
    val n = explorer.visibleStopCount
    return if (n > 0) "$n ${if (n == 1) "stop" else "stops"}" else null
}

@Composable
private fun ExplorerMap(
    explorer: NearbyExplorerState,
    cameraRequest: ExplorerCameraRequest?,
    bottomInset: Dp,
    attributionBottom: () -> Dp,
    chrome: ChipChrome,
    onMapState: (MapState) -> Unit,
    onReady: () -> Unit,
) {
    val selection = explorer.selection
    val user = if (explorer.geoState.status == WatchLocationStatus.Tracking)
        remember(explorer.geoState.lat, explorer.geoState.lon, explorer.geoState.accuracy) {
            ExplorerUser(explorer.geoState.lat, explorer.geoState.lon, explorer.geoState.accuracy)
        }
    else null
    val focused = explorer.focusedStop
    NearbyExplorerMap(
        initialCenter = explorer.initialCenter,
        stops = explorer.mapStops,
        focusedStopId = explorer.focusedStopId,
        selectedStop = explorer.selectedStop,
        polylines = explorer.polylines,
        routeStops = explorer.routeStops,
        routeColor = selection?.color ?: "F7C600",
        routeTextColor = selection?.textColor ?: "FFFFFF",
        vehicles = explorer.vehicles,
        cameraRequest = cameraRequest,
        user = user,
        bottomInset = bottomInset,
        attributionBottom = attributionBottom,
        onReady = onReady,
        onViewportChange = explorer.handleViewportChange,
        onSelectStop = explorer.focusStop,
        modifier = Modifier.fillMaxSize(),
        // Following a line, the map is about that line: no other stops' chips over it.
        chips = if (selection != null) emptyList() else explorer.chips,
        focusedChipId = focused?.id,
        dimChips = selection != null,
        chipChrome = chrome,
        onChipTap = { chip -> explorer.focusStop(chip.stopId, null) },
        onMapState = onMapState,
    )
}

/**
 * The top is only what it needs: whether the times are live on the left, close and locate stacked
 * on the right (a followed departure lives in the sheet). Under them, a notice when there is one.
 */
@Composable
private fun BoxScope.ExplorerChrome(explorer: NearbyExplorerState, safeTop: Dp, onBack: () -> Unit, onRecenter: (LatLon) -> Unit) {
    val c = Vectra.colors
    ExplorerTopBar(
        feedLiveness(explorer.arrivalsEnabled || explorer.viewport == null, explorer.isArrivalsLoading || explorer.viewport == null, explorer.feed),
        onBack,
        Modifier.align(Alignment.TopCenter).padding(top = safeTop + 8.dp, start = 12.dp, end = 12.dp),
    )
    GlassMapControls(
        listOf(
            MapControlButton("locate", "Recenter on your location", RemixIcons.Focus3Line, {
                onRecenter(explorer.anchor?.let { LatLon(it.lat, it.lon) } ?: explorer.initialCenter)
                if (explorer.geoState.status != WatchLocationStatus.Tracking) explorer.refreshLocation()
            }),
        ),
        Modifier.align(Alignment.TopEnd).padding(top = safeTop + IslandBand, end = 12.dp),
    )
    Column(
        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = safeTop + IslandBand, start = 12.dp, end = 68.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PinnedStopBoard(explorer)
        if (explorer.anchor?.outsideHungary == true) {
            IslandNotice("Outside Hungary — showing Budapest center.", c.tints.amber.text)
        }
        if (explorer.geoState.status == WatchLocationStatus.Error) {
            IslandNotice(explorer.geoState.errorMessage.orEmpty(), c.tints.red.text, action = "Retry" to explorer.refreshLocation)
        }
    }
}


/**
 * The picked stop's board, pinned under the island (the map pans its stop just below it). It
 * keeps its stop while it fades away, so it never empties as it goes.
 */
@Composable
private fun PinnedStopBoard(explorer: NearbyExplorerState) {
    val stop = explorer.focusedStop.takeIf { explorer.selection == null }
    var last by remember { mutableStateOf(stop) }
    if (stop != null) last = stop
    val reduced = Vectra.reduceMotion
    AnimatedVisibility(
        stop != null,
        enter = if (reduced) fadeIn(tween(150)) else fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 420f), 0.92f, TransformOrigin(0.5f, 0f)),
        exit = if (reduced) fadeOut(tween(150)) else fadeOut(tween(140)) + scaleOut(tween(160), 0.94f, TransformOrigin(0.5f, 0f)),
    ) {
        val shown = last ?: return@AnimatedVisibility
        val feed = explorer.feed
        val groups = remember(feed, shown.id) { groupDepartures(feed.filter { it.stopId == shown.id }) }
        StopBoard(shown.name, groups, explorer.clearFocus)
    }
}
