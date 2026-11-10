package dev.southstar21.vectra.features.transit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.BarLeading
import dev.southstar21.vectra.core.designsystem.components.BottomBar
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.ScreenColumn
import dev.southstar21.vectra.core.designsystem.components.ScrollBody
import dev.southstar21.vectra.core.designsystem.components.SheetInputHeight
import dev.southstar21.vectra.core.designsystem.components.SurfaceGutter
import dev.southstar21.vectra.core.designsystem.components.TopBar
import dev.southstar21.vectra.core.designsystem.components.TopBarSubtitle
import dev.southstar21.vectra.core.designsystem.components.VBackHandler
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VInput
import dev.southstar21.vectra.core.designsystem.components.VTabs
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.resolve
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.decodePolyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

private typealias PaneContent = @Composable ColumnScope.() -> Unit

private fun CatalogRoute.toFutarRoute() = FutarRoute(id = id, shortName = shortName, description = description, type = type, color = color, textColor = textColor)

private val PanePadding = PaddingValues(start = SurfaceGutter, end = SurfaceGutter, top = 8.dp, bottom = 24.dp)

/**
 * Add a tracker: browse lines or stops, then pick the stops (by line) or the line and
 * direction (by stop) to track. Back steps out of a pane before it leaves the screen.
 */
@Composable
fun AddTrackerScreen(repo: TransitRepository, onBack: () -> Unit) {
    val toaster = LocalToaster.current
    val haptics = Vectra.haptics
    val scope = rememberCoroutineScope()
    val reduceMotion = Vectra.reduceMotion
    val existing = repo.trackersQuery.collect(Unit).data.orEmpty()
    val draft = rememberTrackerDraft()
    val state = remember { AddTrackerState(repo, scope, toaster, draft) }
    var scrolled by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    // Focus the search box once the push animation has started.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { searchFocus.requestFocus() }
    }
    LaunchedEffect(state.catalogAttempt) { state.loadCatalog() }

    // The whole catalog on each keystroke: worked out off the main thread, and a newer
    // keystroke cancels the older pass.
    val searchable by produceState<List<SearchableRoute>?>(null, state.catalogRoutes) {
        val catalog = state.catalogRoutes ?: return@produceState
        value = withContext(Dispatchers.Default) { searchableRoutes(catalog.map { it.toFutarRoute() }) }
    }
    LaunchedEffect(searchable, state.routeQuery, state.routeTypeFilter) {
        val routes = searchable ?: return@LaunchedEffect
        state.filteredRoutes = withContext(Dispatchers.Default) { filterRoutes(routes, state.routeQuery, state.routeTypeFilter) }
    }

    val pane = state.pane
    val lineRoute = (pane as? AddTrackerPane.Line)?.route
    // Polled while the line view is open.
    val previewVehicles = rememberPreviewVehicles(repo, lineRoute?.id, draft.stops)

    // Deeper panes enter from the right, Back brings the previous one in from the left.
    val prevView = remember { arrayOf<Any>("", 0) }
    var viewDirection by remember { mutableIntStateOf(1) }
    if (prevView[0] != pane.key) {
        if (prevView[0] != "") viewDirection = if (pane.depth < prevView[1] as Int) -1 else 1
        prevView[0] = pane.key
        prevView[1] = pane.depth
    }

    val goBack: () -> Unit = { state.back(pane) }
    // Registered inside the screen, so it runs before the stack's own back.
    VBackHandler(enabled = pane != AddTrackerPane.Browse) { goBack() }

    val onScrolled: (Boolean) -> Unit = { scrolled = it }
    val paneContent = when (pane) {
        is AddTrackerPane.Line -> linePane(state, pane.route, existing, previewVehicles, onScrolled)
        is AddTrackerPane.Stop -> stopPane(state, pane.stop, existing, onScrolled)
        is AddTrackerPane.Metro -> metroPane(state, pane.group, onScrolled)
        AddTrackerPane.Browse -> browsePane(state, existing, onScrolled)
    }

    // Each pane's latest content, by key. A pane sliding out keeps the content it
    // last showed while the next one slides in; the lambdas capture plain values, not state.
    val paneCache = remember { mutableStateMapOf<String, PaneContent>() }
    paneCache[pane.key] = paneContent

    ScreenColumn {
        TopBar(
            title = when (pane) {
                is AddTrackerPane.Stop -> pane.stop.name
                is AddTrackerPane.Metro -> metroViewLabel(pane.group)
                AddTrackerPane.Browse -> "Add tracker"
                is AddTrackerPane.Line -> pane.route.shortName
            },
            leading = BarLeading.Back,
            onLeading = if (pane == AddTrackerPane.Browse) onBack else goBack,
            wrapTitle = lineRoute != null,
            titleContent = lineRoute?.let { route -> { LineTitle(route) } },
            subtitle = paneSubtitle(pane)?.let { text -> { TopBarSubtitle(text) } },
            scrolled = scrolled,
            accessory = if (pane == AddTrackerPane.Browse) {
                { BrowseAccessory(state, searchFocus) }
            } else null,
        )

        // No stretch overscroll: the line pane's map is a separate surface the stretch cannot distort.
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            // Each pane scrolls itself, so By line can be a lazy list and a new pane starts at the top.
            AnimatedContent(
                targetState = pane.key,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopStart,
                transitionSpec = {
                    val dir = viewDirection
                    val spec = tween<Float>(if (reduceMotion) 200 else 340, easing = VectraEasing.Sheet)
                    if (reduceMotion) {
                        fadeIn(spec) togetherWith fadeOut(spec)
                    } else {
                        val slide = tween<IntOffset>(340, easing = VectraEasing.Sheet)
                        (slideInHorizontally(slide) { w -> (dir * 0.28f * w).toInt() } + fadeIn(spec)) togetherWith
                            (slideOutHorizontally(slide) { w -> (dir * -0.16f * w).toInt() } + fadeOut(spec))
                    } using SizeTransform(clip = false) { _, _ -> snap() }
                },
                label = "addTrackerPane",
            ) { key -> Column(Modifier.fillMaxSize()) { paneCache[key]?.invoke(this) } }
        }

        val onSaved = {
            haptics.confirm()
            onBack()
        }
        when (pane) {
            is AddTrackerPane.Line -> LineSaveBar(state, pane.route, existing, onSaved)
            is AddTrackerPane.Stop -> BottomBar {
                SheetPrimaryButton(
                    onClick = { state.saveStop(pane.stop, existing, onSaved) },
                    Modifier.fillMaxWidth(),
                    pressedScale = 0.98f,
                    enabled = state.selectedRouteId != null && !state.isSaving,
                ) {
                    SheetPrimaryLabel(state.isSaving, state.selectedRoute?.let { "Track ${it.shortName} here" } ?: "Pick a line to track")
                }
            }
            else -> Unit
        }
    }
}

private fun paneSubtitle(pane: AddTrackerPane): String? = when (pane) {
    is AddTrackerPane.Line -> "Tick the stops to track"
    is AddTrackerPane.Stop -> "Pick a line serving this stop"
    is AddTrackerPane.Metro -> "Pick a stop"
    AddTrackerPane.Browse -> null
}

// The pane builders read their values now, so the lambda each returns freezes them.

@Composable
private fun linePane(
    state: AddTrackerState,
    route: FutarRoute,
    existing: List<TransitTracker>,
    vehicles: List<RoutePreviewVehicle>,
    onScrolled: (Boolean) -> Unit,
): PaneContent {
    val draft = state.draft
    val lineStops = draft.stops
    val polylines = draft.routePolylines
    val routeStops = draft.routeStops
    val stopQuery = state.stopQuery
    val loading = state.loadingRouteStops
    val error = state.routeStopsError
    val filtered = state.filteredRouteStops
    val headsigns = draft.routeHeadsigns
    return {
        LineStopPicker(
            browseRoute = route,
            selectedLineStops = lineStops,
            routePolylines = polylines,
            routeStops = routeStops,
            previewVehicles = vehicles,
            onToggleStop = state::toggleLineStop,
            stopQuery = stopQuery,
            setStopQuery = { state.stopQuery = it },
            loadingRouteStops = loading,
            routeStopsError = error,
            onRetry = { state.selectRoute(route, existing) },
            filteredRouteStops = filtered,
            routeHeadsigns = headsigns,
            onDirectionChange = draft::setDirection,
            onScrolledChange = onScrolled,
        )
    }
}

@Composable
private fun stopPane(
    state: AddTrackerState,
    stop: PickedStop,
    existing: List<TransitTracker>,
    onScrolled: (Boolean) -> Unit,
): PaneContent {
    val timeFormat = Vectra.appearance.timeFormat
    val loading = state.loadingRoutes
    val error = state.routesError
    val routes = state.routes
    val selectedRouteId = state.selectedRouteId
    val selectedRoute = state.selectedRoute
    val selectedHeadsign = state.selectedHeadsign
    val directions = state.directions
    val allDeparturesCount = directions.sumOf { it.times.size }
    return {
        ScrollBody(onScrolledChange = onScrolled, fadeBottom = false, contentPadding = PanePadding) {
            StopRoutePicker(
                loadingRoutes = loading,
                routesError = error,
                onRetry = { state.selectStop(stop.id) },
                routes = routes,
                selectedRouteId = selectedRouteId,
                onSelectRoute = { id ->
                    state.selectedRouteId = id
                    state.selectedHeadsign = ""
                },
                existing = existing,
                selectedRoute = selectedRoute,
                selectedHeadsign = selectedHeadsign,
                onSelectHeadsign = { state.selectedHeadsign = it },
                allDeparturesCount = allDeparturesCount,
                directions = directions,
                timeFormat = timeFormat,
            )
        }
    }
}

@Composable
private fun metroPane(state: AddTrackerState, group: MetroView, onScrolled: (Boolean) -> Unit): PaneContent = {
    ScrollBody(onScrolledChange = onScrolled, contentPadding = PanePadding) {
        CuratedStopList(group, state::selectStop)
    }
}

@Composable
private fun browsePane(state: AddTrackerState, existing: List<TransitTracker>, onScrolled: (Boolean) -> Unit): PaneContent {
    val mode = state.browseMode
    val searchQuery = state.searchQuery
    val filteredStops = state.filteredStops
    val loaded = state.catalogLoaded
    val catalogError = state.catalogError
    val typeFilter = state.routeTypeFilter
    val filteredRoutes = state.filteredRoutes
    val routeQuery = state.routeQuery
    return {
        if (mode == BrowseMode.Stop) {
            ScrollBody(onScrolledChange = onScrolled, contentPadding = PanePadding) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StopBrowser(
                        searchQuery = searchQuery,
                        filteredStops = filteredStops,
                        catalogLoaded = loaded,
                        catalogError = catalogError,
                        onRetryCatalog = state::retryCatalog,
                        onSelectStop = state::selectStop,
                        onOpenGroup = { state.metroView = it },
                    )
                }
            }
        } else {
            LineBrowser(
                routeTypeFilter = typeFilter,
                setRouteTypeFilter = { state.routeTypeFilter = it },
                catalogLoaded = loaded,
                catalogError = catalogError,
                onRetryCatalog = state::retryCatalog,
                filteredRoutes = filteredRoutes,
                routeQuery = routeQuery,
                existing = existing,
                onSelectRoute = { state.selectRoute(it, existing) },
                onScrolledChange = onScrolled,
            )
        }
    }
}

/** Both termini wrap instead of truncating: at phone width a long line title would otherwise lose both ends. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineTitle(route: FutarRoute) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        RouteBadge(formatRouteDisplayName(route.shortName, route.id), route.color, route.textColor, size = RouteBadgeSize.Sm)
        WrappingRouteTitle(splitRouteTitle(route.description.ifEmpty { route.shortName }))
    }
}

@Composable
private fun BrowseAccessory(state: AddTrackerState, searchFocus: FocusRequester) {
    val byLine = state.browseMode == BrowseMode.Line
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VTabs(
            selected = state.browseMode,
            options = listOf(BrowseMode.Line, BrowseMode.Stop),
            onSelect = { state.browseMode = it },
            labelOf = { if (it == BrowseMode.Line) "By line" else "By stop" },
            modifier = Modifier.fillMaxWidth(),
        )
        VInput(
            value = if (byLine) state.routeQuery else state.searchQuery,
            onValueChange = { if (byLine) state.routeQuery = it else state.searchQuery = it },
            modifier = Modifier.focusRequester(searchFocus),
            placeholder = if (byLine) "Search lines… 4, 9, M3, 47" else "Search stops… Teleki László tér",
            icon = RemixIcons.SearchLine,
            height = SheetInputHeight,
            textSize = TypeScale.BodyLarge,
        )
    }
}

@Composable
private fun LineSaveBar(state: AddTrackerState, route: FutarRoute, existing: List<TransitTracker>, onSaved: () -> Unit) {
    BottomBar {
        val n = state.draft.stops.size
        val tracked = existing.any { isRouteMatch(it.routeId, route.id) }
        SheetPrimaryButton(
            onClick = { state.saveLine(route, existing, onSaved) },
            Modifier.fillMaxWidth(),
            pressedScale = 0.98f,
            enabled = n > 0 && !state.isSaving,
        ) {
            SheetPrimaryLabel(
                state.isSaving,
                when {
                    n == 0 -> "Tick a stop to track"
                    tracked -> "Update line · ${stopCountLabel(n)}"
                    else -> "Track line · ${stopCountLabel(n)}"
                },
            )
        }
    }
}

/** A line title whose termini wrap, with ⇆ glyphs between them. */
@Composable
private fun WrappingRouteTitle(parts: List<String>) {
    val c = Vectra.colors
    val density = LocalDensity.current
    val iconSize = 14.dp
    val slot = with(density) { (iconSize + 8.dp).toSp() }
    val height = with(density) { iconSize.toSp() }
    val text = buildAnnotatedString {
        parts.forEachIndexed { i, part ->
            if (i > 0) appendInlineContent("arrow", " to ")
            append(part)
        }
    }
    val inline = mapOf(
        "arrow" to InlineTextContent(Placeholder(slot, height, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                VIcon(RemixIcons.ArrowLeftRightLine, size = iconSize, tint = c.textMuted.copy(alpha = 0.6f))
            }
        },
    )
    BasicText(
        text,
        style = vtext(TypeScale.Body, Weight.Extrabold, Tracking.Tight).resolve().copy(color = c.textPrimary, textAlign = TextAlign.Center),
        inlineContent = inline,
    )
}

/** Whole-route stops, headsigns and geometry from route-details (both editors share it). */
internal data class RouteDetailsParsed(
    val headsigns: List<String>,
    val polylines: List<List<LatLon>>,
    val stops: List<FutarStop>,
)

/**
 * A line's route details, fetched and parsed on [work]: the editors call this from the main
 * thread, and decoding every variant's polyline is the heavy part (audit 03 #16).
 */
internal suspend fun FutarApi.fetchParsedRouteDetails(routeId: String, work: CoroutineContext = Dispatchers.Default): RouteDetailsParsed =
    withContext(work) { parseRouteDetails(fetchCombinedRouteDetails(routeId)) }

internal fun parseRouteDetails(res: FutarRouteDetailsResponse): RouteDetailsParsed {
    val refs = res.data?.references?.stops.orEmpty()
    val variants = res.data?.entry?.variants.orEmpty()
    val headsigns = LinkedHashSet<String>()
    for (v in variants) v.headsign?.takeIf { it.isNotEmpty() }?.let { headsigns.add(it) }
    val polylines = variants.mapNotNull { v ->
        val pts = v.polyline?.points?.takeIf { it.isNotEmpty() }?.let { decodePolyline(it) }.orEmpty()
        pts.takeIf { it.size >= 2 }
    }
    val seen = HashSet<String>()
    val stops = mutableListOf<FutarStop>()
    for (v in variants) {
        for (id in v.stopIds.orEmpty()) {
            val ref = refs[id]
            val areaId = ref?.parentStationId?.takeIf { it.isNotEmpty() } ?: normalizeStopIdToStation(id)
            if (!seen.add(areaId)) continue
            stops += FutarStop(
                id = areaId,
                name = refs[areaId]?.name ?: ref?.name ?: id,
                lat = ref?.lat ?: 0.0,
                lon = ref?.lon ?: 0.0,
                parentStationId = ref?.parentStationId?.takeIf { it.isNotEmpty() } ?: areaId,
            )
        }
    }
    return RouteDetailsParsed(headsigns.toList(), polylines, stops)
}

/** The earliest departure's trip at each chosen stop — the vehicles worth drawing. */
internal fun relevantTripIdsFor(
    routeId: String,
    stops: List<TrackerStop>,
    arrivals: Map<String, StopArrivalsEntry>,
): Set<String> {
    val ids = HashSet<String>()
    if (routeId.isEmpty()) return ids
    val members = getCombinedRouteIds(routeId)
    val memberIds = if (members != null && members.size > 1) members else listOf(routeId)
    for (stop in stops) {
        val data = stop.stopId.takeIf { it.isNotEmpty() }?.let { arrivals[it]?.data } ?: continue
        var best: Arrival? = null
        for (member in memberIds) {
            val next = parseStopArrivals(data, member, stop.headsign?.takeIf { it.isNotEmpty() }).firstOrNull()
            if (next != null && (best == null || next.arrivalMs < best.arrivalMs)) best = next
        }
        best?.tripId?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
    }
    return ids
}

internal fun previewVehiclesFrom(data: FutarVehiclesForRouteResponse?, relevantTripIds: Set<String>): List<RoutePreviewVehicle> =
    data?.data?.list.orEmpty()
        .filter { v -> !v.tripId.isNullOrEmpty() && v.tripId in relevantTripIds }
        .mapNotNull { v ->
            v.location?.takeIf { it.lat != 0.0 && it.lon != 0.0 }?.let { RoutePreviewVehicle(v.vehicleId, it.lat, it.lon, v.bearing) }
        }
