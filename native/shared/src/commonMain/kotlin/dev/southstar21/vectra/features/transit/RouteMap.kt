package dev.southstar21.vectra.features.transit

import org.maplibre.compose.map.MapEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.MapControlButton
import dev.southstar21.vectra.core.maps.GlassMapControls
import dev.southstar21.vectra.core.maps.UserLocationLayers
import dev.southstar21.vectra.core.maps.MapFontStack
import dev.southstar21.vectra.core.maps.composeOverlaysTrackMap
import dev.southstar21.vectra.core.maps.MapStop
import dev.southstar21.vectra.core.maps.MapStopKind
import dev.southstar21.vectra.core.maps.TransitMap
import dev.southstar21.vectra.core.maps.VehicleVariant
import dev.southstar21.vectra.core.maps.boundsContain
import dev.southstar21.vectra.core.maps.boundsOf
import dev.southstar21.vectra.core.maps.fitBounds
import dev.southstar21.vectra.core.maps.easeTo
import dev.southstar21.vectra.core.maps.jumpTo
import dev.southstar21.vectra.core.maps.labelPriority
import dev.southstar21.vectra.core.maps.lineFeatureCollection
import dev.southstar21.vectra.core.maps.rememberProgrammaticGuard
import dev.southstar21.vectra.core.maps.samePoints
import dev.southstar21.vectra.core.maps.stopFeatureCollection
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.or
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.maps.MapColors

/* The live-position route map. */

private const val CloseZoom = 16.0

private fun stopKind(stop: RouteMapStop, isTerminus: Boolean): MapStopKind = when {
    stop.isSelected == true -> MapStopKind.Selected
    stop.isTracked -> MapStopKind.Tracked
    isTerminus -> MapStopKind.Terminus
    else -> MapStopKind.Regular
}

/** Mutable auto-fit bookkeeping (plain fields, so reads never recompose). */
private class FitRefs {
    var tripKey: List<LatLon>? = null
    var selectedStopKey: String? = null
    var fitted = false
    /** The next fit was asked for (re-centre, the map shrinking back): glide there, don't jump. */
    var animateNext = false
}

/**
 * The tracked route with its live vehicles: primary line + secondary
 * directions, stop dots and labels (tracked/selected labels always win
 * collisions), and round vehicle tokens gliding along the line with their countdowns. Auto-fits on the
 * tracked stop plus the vehicles until the user moves the camera.
 *
 * [routeColor] is hex with or without the leading #.
 */
@Composable
fun RouteMap(
    stops: List<RouteMapStop>,
    polyline: List<LatLon>,
    vehicles: List<RouteMapVehicle>,
    routeColor: String,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    secondaryLines: List<RouteMapSecondaryLine> = emptyList(),
    /** Grows the map to fill its sheet or shrinks it back (see [expanded]); null shows no button for it. */
    onToggleExpand: (() -> Unit)? = null,
    /** The map fills its sheet: the toggle shrinks it. */
    expanded: Boolean = false,
    /**
     * How much of the map's bottom is out of sight (a map laid out taller than the box showing it,
     * so growing that box never resizes the map): fits keep your stop and the vehicles above it.
     */
    viewportBottomInset: Dp = 0.dp,
    /** False: the caller shows the basemap credit (where it stays in sight). */
    attribution: Boolean = true,
    fillHeight: Boolean = false,
    /** Fired once when the map has loaded and painted. */
    onReady: (() -> Unit)? = null,
    /** Basemap style failed to load; the map shows its own unavailable notice. */
    onLoadError: (() -> Unit)? = null,
    /** The line's text colour: the vehicle drawn on each token. */
    routeTextColor: String? = null,
    /** Where you are, as a blue dot (null: not shown). */
    userLocation: LatLon? = null,
    /** Room around the control column (clear of whatever is laid over the map's top). */
    controlsPadding: PaddingValues = PaddingValues(top = 8.dp, end = 8.dp),
    /** The map, once created: for projecting the route onto the screen (the loading orb's melt). */
    onMapState: ((MapState) -> Unit)? = null,
    /** Plays the map's entrance in step with its loading orb's melt; null shows everything at once. */
    reveal: MapReveal? = null,
    /**
     * False: a still view that takes no touches (inside a sheet, where swipes move the sheet), with
     * only the expand button; the caller decides which touches expand it.
     */
    interactive: Boolean = true,
) {
    val c = Vectra.colors
    val isLight = !c.isDark
    val colors = remember(c) { liveLineColors(c) }
    var map by remember { mutableStateOf<MapState?>(null) }
    var followActive by remember { mutableStateOf(true) }
    var fitNonce by remember { mutableIntStateOf(0) }
    val guard = rememberProgrammaticGuard(onUserMove = { followActive = false })
    val refs = remember { FitRefs() }
    val inset by rememberUpdatedState(viewportBottomInset)

    val labelColor = c.textSecondary
    val labelHalo = if (isLight) Color.White.copy(alpha = 0.95f) else Color.Black.copy(alpha = 0.95f)
    val routeInk = parseHexColor(withHash(routeColor)) ?: MapColors.RouteFallback
    val strip = stripColors(routeColor, routeTextColor, c.surfaceSecondary)
    val tokenStyle = remember(strip.marker, strip.markerInk) { TokenStyle(strip.marker, strip.markerInk) }
    val callouts = remember(vehicles, secondaryLines) { calloutsFor(vehicles + secondaryLines.flatMap { it.vehicles }) }

    val primaryJson = remember(polyline) { lineFeatureCollection(listOf(polyline)).toString() }
    val visibleSecondary = remember(secondaryLines, polyline) {
        secondaryLines.filter { it.polyline.size >= 2 && !samePoints(polyline, it.polyline) }
    }
    val secondaryJson = remember(visibleSecondary) { lineFeatureCollection(visibleSecondary.map { it.polyline }).toString() }
    val mapStops = remember(stops, visibleSecondary) {
        val primary = stops.mapIndexed { i, s ->
            MapStop(name = s.name, lat = s.lat, lon = s.lon, kind = stopKind(s, i == 0 || i == stops.size - 1))
        }
        val secondary = visibleSecondary.flatMap { ln ->
            ln.stops.mapIndexed { j, s ->
                MapStop(
                    name = s.name, lat = s.lat, lon = s.lon,
                    kind = if (j == 0 || j == ln.stops.size - 1) MapStopKind.SecondaryTerminus else MapStopKind.Secondary,
                )
            }
        }
        primary + secondary
    }
    val stopsJson = remember(mapStops) { stopFeatureCollection(mapStops).toString() }
    val revealOrder = remember(stops, mapStops) { stopRevealOrder(stops, mapStops.size) }
    val revealLines = remember(polyline, visibleSecondary, colors, routeInk) {
        listOf(RevealLine(polyline, colors.casing, 7.dp, 0.9f, routeInk, 4.dp, 1f)) +
            visibleSecondary.map { RevealLine(it.polyline, colors.casingSecondary, 5.dp, 0.85f, routeInk, 3.dp, 0.7f) }
    }

    val animVehicles = remember(vehicles, polyline, secondaryLines) {
        buildList {
            for (v in vehicles) {
                add(
                    AnimatedVehicleInput(
                        id = v.vehicleId, polyline = polyline, location = v.location,
                        variant = if (v.isApproximate == true) VehicleVariant.Approximate else VehicleVariant.Primary,
                        bearing = v.bearing,
                    ),
                )
            }
            for (ln in secondaryLines) {
                val linePoly = if (ln.polyline.size >= 2) ln.polyline else polyline
                for (v in ln.vehicles) {
                    add(
                        AnimatedVehicleInput(
                            id = v.vehicleId, polyline = linePoly, location = v.location,
                            variant = if (v.isApproximate == true) VehicleVariant.Approximate else VehicleVariant.Secondary,
                            bearing = v.bearing,
                        ),
                    )
                }
            }
        }
    }

    // Auto-fit: centre on the tracked/selected stop (plus vehicles when
    // present) until the user moves the camera.
    LaunchedEffect(map, stops, polyline, vehicles, secondaryLines, fitNonce) {
        if (refs.tripKey != polyline) {
            refs.fitted = false
            refs.tripKey = polyline
        }
        val m = map ?: return@LaunchedEffect
        if (stops.isEmpty() || guard.userMoved) return@LaunchedEffect
        val tracked = stops.firstOrNull { it.isSelected == true } ?: stops.firstOrNull { it.isTracked } ?: return@LaunchedEffect
        if (tracked.lat == 0.0 && tracked.lon == 0.0) return@LaunchedEffect

        val stopKey = "${tracked.lat},${tracked.lon},${tracked.name}"
        if (refs.selectedStopKey != stopKey) {
            refs.selectedStopKey = stopKey
            refs.fitted = false
        }

        val anchors = buildList {
            add(LatLon(tracked.lat, tracked.lon))
            for (v in vehicles) if (v.location.lat != 0.0 || v.location.lon != 0.0) add(v.location)
            for (ln in secondaryLines) for (v in ln.vehicles) if (v.location.lat != 0.0 || v.location.lon != 0.0) add(v.location)
        }
        val animate = refs.animateNext
        refs.animateNext = false
        if (anchors.size == 1) {
            if (!refs.fitted) {
                guard.programmaticMove {
                    if (animate) m.easeTo(anchors[0], CloseZoom, inset) else m.jumpTo(anchors[0], CloseZoom, inset)
                }
            }
            refs.fitted = true
            return@LaunchedEffect
        }
        val bounds = boundsOf(anchors) ?: return@LaunchedEffect
        if (refs.fitted && anchors.all { m.boundsContain(it) }) return@LaunchedEffect
        guard.programmaticMove { m.fitBounds(bounds, 50.dp, maxZoom = CloseZoom, animate = animate, bottomInset = inset) }
        refs.fitted = true
    }

    val showWholeRoute = {
        val m = map
        val bounds = boundsOf(polyline + secondaryLines.flatMap { it.polyline })
        if (m != null && bounds != null) {
            guard.userMoved = true
            followActive = false
            guard.programmaticMove { m.fitBounds(bounds, 40.dp, animate = true, bottomInset = inset) }
        }
    }
    val resetView: () -> Unit = {
        guard.resetUserMoved()
        followActive = true
        refs.fitted = false
        refs.animateNext = true
        fitNonce++
    }
    // Shrunk back into its sheet: the small map shows your stop and the vehicles again.
    LaunchedEffect(expanded) { if (!expanded && guard.userMoved) resetView() }
    val toggle = onToggleExpand?.let {
        if (expanded) MapControlButton("expand", "Shrink the map", RemixIcons.FullscreenExitLine, it)
        else MapControlButton("expand", "Expand the map", RemixIcons.FullscreenLine, it)
    }
    val controls = buildList {
        if (!interactive) {
            toggle?.let(::add)
            return@buildList
        }
        add(
            MapControlButton(
                id = "recenter",
                title = if (followActive) "Centered on your stop and the vehicle" else "Re-center on your stop and the vehicle",
                icon = RemixIcons.Focus3Line,
                onClick = resetView,
                active = !followActive,
            ),
        )
        add(MapControlButton("whole-route", "Show the whole route", RemixIcons.RouteLine, showWholeRoute))
        toggle?.let(::add)
    }
    val ready by rememberUpdatedState(onReady)
    val loadError by rememberUpdatedState(onLoadError)
    // The look-alikes leave only once the map has drawn the real layers (it applies them a little late).
    LaunchedEffect(reveal, reveal?.layersOn, map) {
        val r = reveal ?: return@LaunchedEffect
        if (!r.layersOn || r.done) return@LaunchedEffect
        map?.let { m ->
            var drew = false
            withTimeoutOrNull(HandOverWaitMs) {
                m.events.first { e ->
                    if (e is MapEvent.FrameRendered) drew = true
                    drew && e is MapEvent.Idle
                }
            }
        }
        r.done = true
    }

    val isPriority = remember {
        (feature["kind"].asString() eq const("tracked")) or (feature["kind"].asString() eq const("selected"))
    }

    TransitMap(
        initialCenter = LatLon(47.4979, 19.0402),
        initialZoom = 13.0,
        modifier = if (fillHeight) modifier.fillMaxSize() else modifier.fillMaxWidth().height(height),
        minZoom = 6.0,
        dragRotate = false,
        interactive = interactive,
        attribution = attribution,
        onReady = { map = it; onMapState?.invoke(it) },
        onLoaded = { ready?.invoke() },
        onLoadError = { loadError?.invoke() },
        onUserMove = guard::handleUserMove,
        layers = {
            // During the entrance look-alikes play over the map (RouteReveal) and these wait.
            val lineA = if (reveal?.layersOn != false) 1f else 0f
            val stopsA = lineA
            val labelsA = if (reveal?.done != false) 1f else 0f
            val primary = rememberGeoJsonSource(GeoJsonData.JsonString(primaryJson))
            RouteLineLayers(
                "live-line-casing", "live-line-base", primary,
                casing = LineStroke(const(colors.casing), 7.dp, 0.9f * lineA),
                line = LineStroke(const(routeInk), 4.dp, 1f * lineA),
            )
            val secondary = rememberGeoJsonSource(GeoJsonData.JsonString(secondaryJson))
            RouteLineLayers(
                "live-secondary-casing", "live-secondary-base", secondary,
                casing = LineStroke(const(colors.casingSecondary), 5.dp, 0.85f * lineA),
                line = LineStroke(const(routeInk), 3.dp, 0.7f * lineA),
            )
            val stopsSrc = rememberGeoJsonSource(GeoJsonData.JsonString(stopsJson))
            val kind = feature["kind"].asString()
            CircleLayer(
                id = "live-stops-halo", source = stopsSrc,
                filter = isPriority,
                radius = switch(kind, case("selected", const(10.dp)), fallback = const(8.dp)),
                color = const(Palette.Blue500),
                opacity = switch(kind, case("selected", const(0.25f * stopsA)), fallback = const(0.12f * stopsA)),
                strokeWidth = const(1.dp),
                strokeColor = const(Palette.Blue500),
                strokeOpacity = switch(kind, case("selected", const(0.35f * stopsA)), fallback = const(0.2f * stopsA)),
            )
            CircleLayer(
                id = "live-stops-dots", source = stopsSrc,
                radius = switch(
                    kind,
                    case("selected", const(6.5.dp)), case("tracked", const(5.5.dp)), case("terminus", const(5.dp)),
                    case("secondary", const(3.dp)), case("secondary-terminus", const(4.dp)),
                    fallback = const(4.dp),
                ),
                color = switch(
                    kind,
                    case("selected", const(Palette.Blue500)), case("tracked", const(Palette.Blue600)), case("terminus", const(Palette.Zinc600)),
                    case("secondary", const(Palette.Zinc700)), case("secondary-terminus", const(Palette.Zinc700)),
                    fallback = const(Palette.Zinc600),
                ),
                strokeColor = switch(
                    kind,
                    case("selected", const(Palette.Blue300)), case("tracked", const(Palette.Blue400)), case("terminus", const(Palette.Zinc400)),
                    case("secondary", const(Palette.Zinc500)), case("secondary-terminus", const(Palette.Zinc500)),
                    fallback = const(Palette.Zinc500),
                ),
                strokeWidth = switch(
                    kind,
                    case("selected", const(2.5.dp)), case("tracked", const(2.dp)),
                    fallback = const(1.dp),
                ),
                opacity = const(stopsA),
                strokeOpacity = const(stopsA),
            )
            SymbolLayer(
                id = "live-stop-labels", source = stopsSrc,
                filter = !isPriority,
                sortKey = feature["labelPriority"].asNumber(),
                textField = feature["name"].asString(),
                textFont = const(MapFontStack),
                textSize = const(10.sp),
                textAnchor = const(SymbolAnchor.Bottom),
                textOffset = textOffset(0.em, (-1.1).em),
                textOptional = const(true),
                textColor = const(labelColor),
                textHaloColor = const(labelHalo),
                textHaloWidth = const(1.2.dp),
                textOpacity = const(labelsA),
            )
            SymbolLayer(
                id = "live-stop-labels-priority", source = stopsSrc,
                filter = isPriority,
                textField = feature["name"].asString(),
                textFont = const(MapFontStack),
                textSize = const(10.sp),
                textAnchor = const(SymbolAnchor.Bottom),
                textOffset = textOffset(0.em, (-1.1).em),
                textAllowOverlap = const(true),
                textIgnorePlacement = const(true),
                textColor = const(labelColor),
                textHaloColor = const(labelHalo),
                textHaloWidth = const(1.2.dp),
                textOpacity = const(labelsA),
            )
            userLocation?.let { UserLocationLayers("live", it, scale = stopsA) }
            // iOS: vehicles live in the map so they stay pinned while it pans.
            if (!composeOverlaysTrackMap) {
                RoundVehicleLayers("live", rememberVehicleAnimations(animVehicles), tokenStyle, callouts, scale = stopsA)
            }
        },
        overlay = {
            if (reveal != null) RouteReveal(reveal, revealLines, mapStops, revealOrder, userLocation) { map }
            if (!composeOverlaysTrackMap) return@TransitMap
            // Per-frame state is read only here, so gliding never recomposes the layers.
            val frames = rememberVehicleAnimations(animVehicles)
            RoundVehicleMarkers(
                frames, mapBearing = { map?.cameraPosition?.bearing ?: 0.0 }, tokenStyle, callouts,
                scale = { i -> revealVehicle(reveal?.progress ?: 1f, i) },
            )
        },
    ) {
        GlassMapControls(
            controls,
            Modifier.align(Alignment.TopEnd).padding(controlsPadding).graphicsLayer { alpha = revealControls(reveal?.progress ?: 1f) },
        )
    }
}
