package dev.southstar21.vectra.features.transit

import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import dev.southstar21.vectra.core.maps.MapAttribution
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.abs
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.components.VBackHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.compose.map.MapState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.designsystem.components.SheetGrabber
import dev.southstar21.vectra.core.designsystem.components.SheetSize
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VSheet
import dev.southstar21.vectra.core.designsystem.components.VTabs
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.specularBorder
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.isValidLatLon
import dev.southstar21.vectra.navigation.AppNavigator
import dev.southstar21.vectra.navigation.LiveTrackerSheet
import dev.southstar21.vectra.navigation.Screen
import kotlinx.coroutines.CancellationException

/** The share of the window the sheet covers at its half-height rest. */
private const val HalfFraction = 0.56f


/**
 * The live tracker as a floating sheet over the tabs ([AppNavigator.liveTracker]): the route map
 * with the vehicles and their countdowns first, the stops when pulled up. Touching the map to look
 * at it (a tap, a pinch, a sideways pan) grows it to fill the sheet; back or its button shrinks it.
 * The sheet steps aside while a screen is pushed and comes back when that closes.
 */
@Composable
fun LiveTrackerSheetHost(repo: TransitRepository, deviceLocation: DeviceLocation, kv: KeyValueStore, navigator: AppNavigator) {
    val subject = navigator.liveTracker
    // The last subject stays while the sheet animates away.
    var shown by remember { mutableStateOf(subject) }
    if (subject != null && subject != shown) shown = subject
    val s = shown ?: return
    var mapExpanded by remember(s.trackerId) { mutableStateOf(false) }
    LaunchedEffect(subject == null) { if (subject == null) mapExpanded = false }
    val hint = rememberMapHint(kv, subject?.trackerId)
    val setExpanded: (Boolean) -> Unit = { grow ->
        if (grow) hint.learned()
        mapExpanded = grow
    }
    VSheet(
        open = subject != null && navigator.screens.size <= 1,
        onDismiss = navigator::closeLiveTracker,
        size = SheetSize.Expandable,
        dragHandle = false,
        title = "Live departures",
        floating = true,
        halfFraction = HalfFraction,
        onExited = { if (navigator.liveTracker == null) shown = null },
        pinnedOpen = mapExpanded,
    ) {
        key(s.trackerId) { LiveTrackerSheetContent(repo, deviceLocation, navigator, s, MapSheetState(mapExpanded, setExpanded, hint)) }
    }
}

@Composable
private fun ColumnScope.LiveTrackerSheetContent(
    repo: TransitRepository,
    deviceLocation: DeviceLocation,
    navigator: AppNavigator,
    subject: LiveTrackerSheet,
    mapSheet: MapSheetState,
) {
    val mapExpanded = mapSheet.expanded
    val trackerId = subject.trackerId
    val now = rememberNowMillis(1000)
    val tracker = rememberTracker(repo, trackerId, onMissing = navigator::closeLiveTracker)
    val trackerStops = remember(tracker) { tracker?.let(::getTrackerStops) ?: emptyList() }
    // Back shrinks a grown map before it closes the sheet.
    VBackHandler(enabled = mapExpanded) { mapSheet.onExpandedChange(false) }
    val t = tracker
    if (t == null) {
        Box(Modifier.fillMaxWidth().height(240.dp)) { MapLoadingOrb(show = true, label = "Loading route…") }
        return
    }
    val activeStopId = liveStopIdFor(t, subject.stopId)
    val activeStop = remember(t, trackerStops, activeStopId) { liveStopFor(t, trackerStops, activeStopId) }
    val live = rememberLiveRoute(repo, t, trackerStops, activeStop, now)
    val alerts = rememberStopAlerts(repo, t, activeStop, live, now)
    val offline = !rememberOnline(repo.connectivity)
    val freshness = liveFreshness(live.arrivalsQuery.dataUpdatedAt, live.vehiclesQuery.dataUpdatedAt, now, offline)
    val noDepartures = live.arrivalsQuery.isSuccess && live.arrivals.isEmpty()
    val notice = liveMapNotice(freshness, live.arrivalsQuery.dataUpdatedAt, live.arrivalsQuery.isError, noDepartures)

    // Each direction's vehicles, carrying that direction's countdown to your stop.
    val labelled = remember(live.enriched, freshness.countdownNow / 1000, freshness.arrivalsStale) {
        live.enriched.map { d -> labelledVehicles(d, freshness.countdownNow, live = !freshness.arrivalsStale) }
    }
    val secondary = remember(live.secondaryLines, labelled) {
        live.secondaryLines.mapIndexed { i, ln -> labelled.getOrNull(i + 1)?.let { ln.copy(vehicles = it) } ?: ln }
    }
    val location by deviceLocation.state.collectAsStateWithLifecycle()
    val here = location.takeIf { it.status == WatchLocationStatus.Tracking }?.let { LatLon(it.lat, it.lon) }?.takeIf(::isValidLatLon)

    val stopName = activeStop.stopName.takeIf { it.isNotEmpty() } ?: t.stopName

    GrowingMapLayout(mapExpanded) { sizes ->
        LiveMap(
            tracker = t,
            stopName = stopName,
            live = live,
            leadVehicles = labelled.firstOrNull() ?: live.leadVehicles,
            secondary = secondary,
            here = here,
            isLive = !freshness.arrivalsStale && !offline,
            notice = notice,
            sizes = sizes,
            mapSheet = mapSheet,
        )
        AlertsSection(alerts, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))
        if (trackerStops.size > 1) {
            TrackedStopTabs(trackerStops, activeStop.stopId, navigator::showLiveTrackerStop, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))
        }
        StopsSection(t, live.routeColor, activeStop.stopId, live.enriched, loading = !live.feedError && live.directions.isEmpty())
    }
}

/** [d]'s vehicles (and its estimated metro position) labelled with its countdown to your stop. */
private fun labelledVehicles(d: DirectionView, now: Long, live: Boolean): List<RouteMapVehicle> {
    val all = d.approxVehicle?.let { d.vehicles + it } ?: d.vehicles
    val ms = d.arrival?.arrivalMs?.takeIf { it > 0 } ?: return all
    val label = stripTimeLabel(ms, now)
    val soon = live && etaMinutes(ms, now) <= 1
    return all.map { it.copy(label = label, labelSoon = soon) }
}

/** Disruption alerts at [stop], from the arrivals feed; the runtime catalog names routes the feed leaves out. */
@Composable
private fun rememberStopAlerts(repo: TransitRepository, tracker: TransitTracker, stop: TrackerStop, live: LiveRoute, now: Long): List<TransitAlert> {
    val catalog by produceState<Map<String, CatalogRoute>?>(null, repo) {
        value = try {
            repo.catalog.routeMap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }
    return remember(live.arrivalsQuery.data, now / 60_000, tracker, stop, catalog) {
        val cm = catalog
        parseAlerts(
            live.arrivalsQuery.data,
            AlertContext(tracker.routeId, tracker.routeShortName, tracker.routeType, stop.stopId),
            now,
            cm?.let { m -> { id: String -> m[id]?.shortName } },
            cm?.let { m -> { id: String -> m[id]?.type } },
        )
    }
}

@Composable
private fun LiveMap(
    tracker: TransitTracker,
    stopName: String,
    live: LiveRoute,
    leadVehicles: List<RouteMapVehicle>,
    secondary: List<RouteMapSecondaryLine>,
    here: LatLon?,
    isLive: Boolean,
    notice: String?,
    sizes: MapSizes,
    mapSheet: MapSheetState,
) {
    val c = Vectra.colors
    val expanded = mapSheet.expanded
    val onExpandedChange = mapSheet.onExpandedChange
    var mapReady by remember { mutableStateOf(false) }
    var mapState by remember { mutableStateOf<MapState?>(null) }
    val reveal = remember { MapReveal() }
    val loading = !live.feedError && live.directions.isEmpty()
    val density = LocalDensity.current.density
    val line = live.enriched.firstOrNull()?.polyline.orEmpty()
    // The orb's dots spread over every line in view: this direction and the others.
    val allLines = remember(line, secondary) { listOf(line) + secondary.map { it.polyline }.filter { it.size >= 2 } }
    // The box grows; the map in it is always laid out at the sheet's full height (resizing a map
    // every frame stutters), its bottom hidden while small.
    Box(Modifier.fillMaxWidth().layoutHeight(sizes.shown).clipToBounds().background(c.mapSurface)) {
        if (live.feedError) {
            FeedUnavailable(Modifier.fillMaxSize())
        } else {
            // Small, a still view as maps inside iOS sheets are (up-and-down swipes move the sheet);
            // grown to fill the sheet, a map to pan and zoom.
            RouteMap(
                modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                interactive = expanded,
                expanded = expanded,
                onToggleExpand = { onExpandedChange(!expanded) },
                stops = live.enriched.firstOrNull()?.stops.orEmpty(),
                polyline = line,
                vehicles = leadVehicles,
                secondaryLines = secondary,
                routeColor = live.routeColor,
                routeTextColor = tracker.routeTextColor,
                height = sizes.full,
                viewportBottomInset = if (expanded) 0.dp else sizes.full - sizes.rest,
                attribution = false,
                userLocation = here,
                controlsPadding = PaddingValues(top = 16.dp, end = 10.dp),
                onReady = { mapReady = true },
                onLoadError = { mapReady = true },
                onMapState = { mapState = it },
                reveal = reveal,
            )
            // Laid over the small map, which keeps a touch handler even when still: reaching for the
            // map grows it, up-and-down swipes pass through to the sheet.
            if (!expanded) Box(Modifier.matchParentSize().mapExpandGestures { onExpandedChange(true) })
            MapAttribution(Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 4.dp), align = TextAlign.Center)
        }
        MapLoadingOrb(
            show = !live.feedError && (loading || !mapReady),
            label = "Loading route…",
            routeColor = parseHexColor(live.routeColor),
            routePath = { allLines.map { routeScreenPath(mapState, it, density) } },
            reveal = reveal,
        )
        TitlePlacard(tracker, stopName, isLive, Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 16.dp))
        if (!live.feedError && notice != null) NoticePill(notice, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp, start = 16.dp, end = 16.dp))
        // Once the map is in (and nothing else sits there), say that it opens up.
        MapHintSlot(mapSheet, ready = mapReady && reveal.done && notice == null && !live.feedError, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
        // Pinned open while grown: nothing to drag.
        if (!expanded) SheetGrabber(Modifier.align(Alignment.TopCenter))
    }
}

/** The line and stop in a glass label over the map, with whether the times are live. */
@Composable
private fun TitlePlacard(tracker: TransitTracker, stopName: String, isLive: Boolean, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .widthIn(max = 260.dp)
            // A hit target of its own: a drag on the title moves the sheet, not the map under it.
            .pointerInput(Unit) {}
            .boxShadow(shape, c.glassShadow.filter { !it.inset })
            .background(c.glassBgElevated.copy(alpha = 0.86f), shape)
            .specularBorder(shape, c.glassBorder, c.glassBorderSpecular)
            .padding(start = 7.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteBadge(formatRouteDisplayName(tracker.routeShortName, tracker.routeId), tracker.routeColor, tracker.routeTextColor, size = RouteBadgeSize.Sm)
        Column(Modifier.weight(1f, fill = false)) {
            VText(stopName, style = vtext(15.sp to 19.sp, Weight.Bold), color = c.textPrimary, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isLive) {
                    LiveSignalIcon(size = 10.dp, tint = c.tints.emerald.text)
                    VText("Live", style = vtext(12.sp to 15.sp, Weight.Semibold), color = c.tints.emerald.text)
                } else {
                    VText("Not live", style = vtext(12.sp to 15.sp, Weight.Semibold), color = c.tints.amber.text)
                }
            }
        }
    }
}

@Composable
private fun NoticePill(text: String, modifier: Modifier = Modifier) {
    VText(
        text,
        modifier
            .background(Color.Black.copy(alpha = 0.78f), CircleShape)
            .border(1.dp, Vectra.colors.tints.amber.border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        vtext(TypeScale.Caption, Weight.Semibold),
        Vectra.colors.tints.amber.text,
        maxLines = 1,
    )
}

@Composable
private fun FeedUnavailable(modifier: Modifier = Modifier) {
    val c = Vectra.colors
    Column(
        modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        VIcon(RemixIcons.WifiOffLine, size = 20.dp, tint = c.tints.red.text.copy(alpha = 0.7f))
        VText("Live feed unavailable", style = vtext(TypeScale.Body, Weight.Semibold), color = c.textSecondary)
        VText("Retrying automatically…", style = vtext(TypeScale.Caption), color = c.textMuted)
    }
}

/** "Stops", a switch between the tracked directions, and the chosen one's timeline. */
@Composable
private fun StopsSection(tracker: TransitTracker, routeColor: String, stopId: String, directions: List<DirectionView>, loading: Boolean) {
    val c = Vectra.colors
    var picked by rememberSaveable(tracker.id, stopId) { mutableIntStateOf(0) }
    var showAll by rememberSaveable(tracker.id) { mutableStateOf(false) }
    val dirIndex = picked.coerceIn(0, (directions.size - 1).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VText("Stops", style = vtext(20.sp to 24.sp, Weight.Bold), color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            if (directions.size > 1) {
                VTabs(
                    selected = dirIndex,
                    options = directions.indices.toList(),
                    onSelect = { picked = it },
                    modifier = Modifier.widthIn(max = 230.dp),
                ) { i, _ -> VText(directionLabel(directions[i]), maxLines = 1) }
            }
        }
        val d = directions.getOrNull(dirIndex)
        when {
            d != null -> LiveStopTimeline(
                direction = d,
                colors = timelineColors(routeColor, tracker.routeTextColor, c.glassBgElevated),
                showAll = showAll,
                onToggleShowAll = { showAll = !showAll },
                modifier = Modifier.padding(top = 8.dp),
            )
            loading -> ListStatus { SheetListSpinner() }
            else -> ListStatus { SheetMutedText("No stops to show right now") }
        }
    }
}

