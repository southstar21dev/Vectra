package dev.southstar21.vectra.features.transit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.LazyScrollBody
import dev.southstar21.vectra.core.designsystem.components.SectionTitle
import dev.southstar21.vectra.core.designsystem.components.SheetInputHeight
import dev.southstar21.vectra.core.designsystem.components.StatusLabel
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.designsystem.components.SurfaceGutter
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VInput
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.insetShadow
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** Let a row run past the sheet gutter on both sides (negative margin, with the gutter added back as padding). */
internal fun Modifier.bleedHorizontally(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = (gutter * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun TypeChip(type: String, active: Boolean, onClick: () -> Unit) {
    val c = Vectra.colors
    val shape = CircleShape
    val interaction = remember { MutableInteractionSource() }
    val ink by animateColorAsState(if (active) Color.White else c.textSecondary, tween(150), label = "chipInk")
    val icon = if (type == "ALL") RemixIcons.Apps2Line else routeTypeMeta(type).icon
    val label = if (type == "ALL") "All" else routeTypeMeta(type).label
    Row(
        Modifier
            .height(36.dp)
            .pressScale(interaction)
            .then(
                if (active) {
                    Modifier
                        .background(c.accentPrimary, shape)
                        .insetShadow(shape, listOf(BoxShadow(y = 1f, color = Color.White.copy(alpha = 0.3f), inset = true)))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), shape)
                } else {
                    Modifier.glass(shape)
                },
            )
            .tappable(role = Role.RadioButton, highlight = false, interactionSource = interaction) { onClick() }
            .semantics { selected = active }
            .padding(start = 12.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(icon, Modifier.alpha(if (active) 0.9f else 1f), tint = if (active) ink else c.textMuted)
        VText(label, style = vtext(TypeScale.Body, if (active) Weight.Bold else Weight.Semibold), color = ink, maxLines = 1)
    }
}

/** A catalog line with its search text folded once, so typing never re-folds the catalog. */
internal class SearchableRoute(val route: FutarRoute, val typeGroup: String, val searchText: List<String>)

/** The catalog in display order, ready for [filterRoutes]. One row per id, which a lazy list needs. */
internal fun searchableRoutes(catalog: List<FutarRoute>): List<SearchableRoute> =
    sortRoutes(catalog.distinctBy { it.id }).map { r ->
        SearchableRoute(
            route = r,
            typeGroup = if (r.type == "SUBURBAN_RAILWAY") "RAIL" else r.type,
            searchText = listOf(r.shortName, r.description, r.type).map(::normalizeSearchText),
        )
    }

internal fun filterRoutes(routes: List<SearchableRoute>, query: String, typeFilter: String): List<FutarRoute> {
    val q = normalizeSearchText(query.trim())
    return routes
        .filter { r -> (typeFilter == "ALL" || r.typeGroup == typeFilter) && (q.isEmpty() || r.searchText.any { it.contains(q) }) }
        .map { it.route }
}

/** Root of By line: a type filter strip and the catalog as one list. */
@Composable
fun ColumnScope.LineBrowser(
    routeTypeFilter: String,
    setRouteTypeFilter: (String) -> Unit,
    catalogLoaded: Boolean,
    catalogError: Boolean,
    onRetryCatalog: () -> Unit,
    /** Null until the first filter pass finishes. */
    filteredRoutes: List<FutarRoute>?,
    routeQuery: String,
    existing: List<TransitTracker>,
    onSelectRoute: (FutarRoute) -> Unit,
    onScrolledChange: (Boolean) -> Unit,
) {
    val list = rememberLazyListState()
    // Lives out here: the strip leaves composition when it scrolls off.
    val strip = rememberScrollState()
    LaunchedEffect(filteredRoutes) { list.scrollToItem(0) }
    LazyScrollBody(
        state = list,
        onScrolledChange = onScrolledChange,
        contentPadding = PaddingValues(start = SurfaceGutter, end = SurfaceGutter, top = 8.dp, bottom = 24.dp),
    ) {
        item(key = "filters", contentType = "filters") {
            // The strip bleeds to the sheet edge so the last chip scrolls out
            // under the gutter instead of clipping on it.
            Row(
                Modifier
                    .padding(bottom = 16.dp)
                    .bleedHorizontally(SurfaceGutter)
                    .horizontalScroll(strip)
                    .padding(horizontal = SurfaceGutter)
                    .semantics { contentDescription = "Line type" },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RouteTypeFilters.forEach { t -> TypeChip(t, routeTypeFilter == t) { setRouteTypeFilter(t) } }
            }
        }
        when {
            !catalogLoaded && catalogError -> item(key = "status", contentType = "status") {
                ListStatus {
                    SheetMutedText("Couldn’t load the line catalog")
                    SheetRetryButton(onRetryCatalog)
                }
            }
            !catalogLoaded || filteredRoutes == null -> item(key = "status", contentType = "status") { ListStatus { SheetListSpinner() } }
            filteredRoutes.isEmpty() -> item(key = "status", contentType = "status") {
                ListStatus {
                    SheetMutedText(
                        if (routeQuery.trim().isNotEmpty()) "No lines match “${routeQuery.trim()}”" else "No lines of this type.",
                    )
                }
            }
            else -> itemsIndexed(filteredRoutes, key = { _, route -> route.id }, contentType = { _, _ -> "route" }) { i, route ->
                ClippedFormRow(first = i == 0, last = i == filteredRoutes.lastIndex) {
                    RouteRow(route, tracked = existing.any { isRouteMatch(it.routeId, route.id) }) { onSelectRoute(route) }
                }
            }
        }
    }
}

@Composable
private fun RouteRow(route: FutarRoute, tracked: Boolean, onClick: () -> Unit) {
    val c = Vectra.colors
    val meta = routeTypeMeta(route.type)
    ListRow(
        route.description.ifEmpty { meta.label },
        onClick = onClick,
        navigates = true,
        leading = {
            RouteBadge(
                formatRouteDisplayName(route.shortName, route.id),
                route.color,
                route.textColor,
                size = RouteBadgeSize.Sm,
            )
        },
        titleContent = {
            if (route.description.isNotEmpty()) {
                FormattedRouteTitle(
                    route.description,
                    style = vtext(TypeScale.Body, Weight.Semibold),
                    color = c.textPrimary,
                    iconSize = 12.dp,
                )
            } else {
                VText(meta.label, style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary, maxLines = 1)
            }
            Row(
                Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VIcon(meta.icon, size = 12.dp, tint = c.textMuted)
                VText(meta.label, style = vtext(TypeScale.Caption), color = c.textSecondary, maxLines = 1)
                if (tracked) TrackedChip()
            }
        },
    )
}

/** Stops shared with another tram line, called out next to the stop name. */
private fun stopNote(name: String): String? = when {
    name.contains("Szerémi") || name.contains("Újbuda-központ") -> "Tram 4"
    name.contains("Karinthy") || name.contains("Móricz") -> "Tram 6"
    name.contains("Izraelita") -> "Tram 28"
    else -> null
}

private val MapHeight = 208.dp

/** After a line is picked: the route on the map, then its stops to tick. */
@Composable
fun ColumnScope.LineStopPicker(
    browseRoute: FutarRoute,
    selectedLineStops: List<TrackerStop>,
    routePolylines: List<List<LatLon>>,
    routeStops: List<FutarStop>?,
    previewVehicles: List<RoutePreviewVehicle>,
    onToggleStop: (FutarStop) -> Unit,
    stopQuery: String,
    setStopQuery: (String) -> Unit,
    loadingRouteStops: Boolean,
    routeStopsError: String?,
    onRetry: () -> Unit,
    filteredRouteStops: List<FutarStop>,
    routeHeadsigns: List<String>,
    onDirectionChange: (stopId: String, headsign: String) -> Unit,
    onScrolledChange: (Boolean) -> Unit,
) {
    val c = Vectra.colors
    var routeMapReady by remember { mutableStateOf(false) }
    val atLimit = selectedLineStops.size >= MaxTrackerStops
    val gutter = Modifier.padding(horizontal = SurfaceGutter)

    LazyScrollBody(onScrolledChange = onScrolledChange, fadeBottom = false, contentPadding = PaddingValues(bottom = 24.dp)) {
        // The route leads, edge to edge, the way the trip sheet opens on its map.
        item(key = "map", contentType = "map") {
            // Pinned: scrolled away it stays composed, or coming back would rebuild the map.
            val pinnable = LocalPinnableContainer.current
            DisposableEffect(pinnable) {
                val handle = pinnable?.pin()
                onDispose { handle?.release() }
            }
            Box(Modifier.fillMaxWidth().height(MapHeight)) {
                val previewStops = remember(routeStops, selectedLineStops) {
                    routeStops.orEmpty().map { s ->
                        RoutePreviewStop(s.id, s.name, s.lat, s.lon, selected = selectedLineStops.any { it.stopId == s.id })
                    }
                }
                RoutePreviewMap(
                    polylines = routePolylines,
                    stops = previewStops,
                    vehicles = previewVehicles,
                    routeColor = browseRoute.color.ifEmpty { "3b82f6" },
                    height = MapHeight,
                    onReady = { routeMapReady = true },
                    onSelectStop = { stopId ->
                        routeStops.orEmpty().firstOrNull { it.id == stopId }?.let(onToggleStop)
                    },
                )
                MapLoadingOrb(show = loadingRouteStops || !routeMapReady, label = "Loading route map…")
                MapBottomFade()
            }
        }

        item(key = "heading", contentType = "heading") {
            Column(
                Modifier.padding(start = SurfaceGutter, end = SurfaceGutter, top = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("Stops to track", action = { StopCountPill(selectedLineStops.size) })
                VInput(
                    value = stopQuery,
                    onValueChange = setStopQuery,
                    placeholder = "Search stops on ${browseRoute.shortName}…",
                    icon = RemixIcons.SearchLine,
                    height = SheetInputHeight,
                    textSize = TypeScale.BodyLarge,
                )
            }
        }

        when {
            loadingRouteStops -> item(key = "status", contentType = "status") { ListStatus(gutter) { SheetListSpinner() } }
            routeStopsError != null -> item(key = "status", contentType = "status") {
                ListStatus(gutter) {
                    SheetMutedText("Could not load stops: $routeStopsError", Modifier.padding(horizontal = 8.dp), c.tints.red.text)
                    SheetRetryButton(onRetry)
                }
            }
            filteredRouteStops.isEmpty() -> item(key = "status", contentType = "status") {
                ListStatus(gutter) { SheetMutedText("No stops match “${stopQuery.trim()}”") }
            }
            else -> itemsIndexed(filteredRouteStops, key = { _, stop -> stop.id }, contentType = { _, _ -> "stop" }) { i, stop ->
                ClippedFormRow(first = i == 0, last = i == filteredRouteStops.lastIndex, modifier = gutter) {
                    val selectedEntry = selectedLineStops.firstOrNull { it.stopId == stop.id }
                    LineStopRow(
                        stop = stop,
                        selectedEntry = selectedEntry,
                        blocked = selectedEntry == null && atLimit,
                        routeHeadsigns = routeHeadsigns,
                        onToggle = { onToggleStop(stop) },
                        onDirectionChange = { h -> onDirectionChange(stop.id, h) },
                    )
                }
            }
        }
        if (atLimit && !loadingRouteStops && routeStopsError == null) {
            item(key = "footnote", contentType = "footnote") {
                Box(gutter.padding(top = 12.dp)) {
                    SheetFootnote("That’s the most stops a line can track. Untick one to swap it.")
                }
            }
        }
    }
}

@Composable
private fun LineStopRow(
    stop: FutarStop,
    selectedEntry: TrackerStop?,
    blocked: Boolean,
    routeHeadsigns: List<String>,
    onToggle: () -> Unit,
    onDirectionChange: (String) -> Unit,
) {
    val c = Vectra.colors
    val isSelected = selectedEntry != null
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val note = stopNote(stop.name)
    val boxBg by animateColorAsState(if (isSelected) c.accentPrimary else c.surfaceSecondary, tween(150), label = "checkBg")
    val boxBorder by animateColorAsState(if (isSelected) c.accentPrimary else c.borderSubtle, tween(150), label = "checkBorder")
    val checkInk by animateColorAsState(if (isSelected) Color.White else Color.Transparent, tween(150), label = "checkInk")
    Column(Modifier.fillMaxWidth().background(if (isSelected) c.accentPrimary.copy(alpha = 0.08f) else Color.Transparent)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (pressed && !blocked) c.surfaceOverlay else Color.Transparent)
                .tappable(enabled = !blocked, role = Role.Checkbox, highlight = false, interactionSource = interaction, onClick = onToggle)
                .semantics { toggleableState = ToggleableState(isSelected); stateDescription = if (isSelected) "Tracked" else "Not tracked" }
                .alpha(if (blocked) 0.5f else 1f)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shape = Radius.Tag
            Box(
                Modifier.size(20.dp).background(boxBg, shape).border(1.dp, boxBorder, shape),
                contentAlignment = Alignment.Center,
            ) { VIcon(RemixIcons.CheckLine, size = 14.dp, tint = checkInk) }
            VText(stop.name, Modifier.weight(1f), vtext(TypeScale.Body, Weight.Semibold), c.textPrimary, maxLines = 1)
            if (note != null) StatusLabel(note, tone = StatusTone.Amber)
        }
        if (selectedEntry != null && routeHeadsigns.isNotEmpty()) {
            Box(Modifier.padding(start = 48.dp, end = 16.dp, bottom = 12.dp)) {
                DirectionSelect(selectedEntry.headsign.orEmpty(), routeHeadsigns, onDirectionChange, Modifier.fillMaxWidth())
            }
        }
    }
}
