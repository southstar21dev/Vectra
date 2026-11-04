package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.layout.fillMaxSize
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
import dev.southstar21.vectra.core.maps.MapControls
import dev.southstar21.vectra.core.maps.MapFontStack
import dev.southstar21.vectra.core.maps.MapStop
import dev.southstar21.vectra.core.maps.MapStopKind
import dev.southstar21.vectra.core.maps.TransitMap
import dev.southstar21.vectra.core.maps.VehicleVariant
import dev.southstar21.vectra.core.maps.boundsContain
import dev.southstar21.vectra.core.maps.boundsOf
import dev.southstar21.vectra.core.maps.fitBounds
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
}

/**
 * The tracked route with its live vehicles: primary line + secondary
 * directions, stop dots and labels (tracked/selected labels always win
 * collisions), and teardrop vehicles gliding along the line. Auto-fits on the
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
    onFullscreen: (() -> Unit)? = null,
    fillHeight: Boolean = false,
    /** Fired once when the map has loaded and painted. */
    onReady: (() -> Unit)? = null,
    /** Basemap style failed to load; the map shows its own unavailable notice. */
    onLoadError: (() -> Unit)? = null,
) {
    val c = Vectra.colors
    val isLight = !c.isDark
    val colors = remember(c) { liveLineColors(c) }
    var map by remember { mutableStateOf<MapState?>(null) }
    var followActive by remember { mutableStateOf(true) }
    var fitNonce by remember { mutableIntStateOf(0) }
    val guard = rememberProgrammaticGuard(onUserMove = { followActive = false })
    val refs = remember { FitRefs() }

    val labelColor = c.textSecondary
    val labelHalo = if (isLight) Color.White.copy(alpha = 0.95f) else Color.Black.copy(alpha = 0.95f)
    val routeInk = parseHexColor(withHash(routeColor)) ?: MapColors.RouteFallback

    val primaryJson = remember(polyline) { lineFeatureCollection(listOf(polyline)).toString() }
    val visibleSecondary = remember(secondaryLines, polyline) {
        secondaryLines.filter { it.polyline.size >= 2 && !samePoints(polyline, it.polyline) }
    }
    val secondaryJson = remember(visibleSecondary) { lineFeatureCollection(visibleSecondary.map { it.polyline }).toString() }
    val stopsJson = remember(stops, visibleSecondary) {
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
        stopFeatureCollection(primary + secondary).toString()
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
        if (anchors.size == 1) {
            if (!refs.fitted) guard.programmaticMove { m.jumpTo(anchors[0], CloseZoom) }
            refs.fitted = true
            return@LaunchedEffect
        }
        val bounds = boundsOf(anchors) ?: return@LaunchedEffect
        if (refs.fitted && anchors.all { m.boundsContain(it) }) return@LaunchedEffect
        guard.programmaticMove { m.fitBounds(bounds, 50.dp, maxZoom = CloseZoom, animate = false) }
        refs.fitted = true
    }

    val showWholeRoute = {
        val m = map
        val bounds = boundsOf(polyline + secondaryLines.flatMap { it.polyline })
        if (m != null && bounds != null) {
            guard.userMoved = true
            followActive = false
            guard.programmaticMove { m.fitBounds(bounds, 40.dp, animate = true) }
        }
    }
    val resetView: () -> Unit = {
        guard.resetUserMoved()
        followActive = true
        refs.fitted = false
        fitNonce++
    }
    val controls = buildList {
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
        if (onFullscreen != null) add(MapControlButton("fullscreen", "Fullscreen map", RemixIcons.FullscreenLine, onFullscreen))
    }
    val ready by rememberUpdatedState(onReady)
    val loadError by rememberUpdatedState(onLoadError)

    val isPriority = remember {
        (feature["kind"].asString() eq const("tracked")) or (feature["kind"].asString() eq const("selected"))
    }

    TransitMap(
        initialCenter = LatLon(47.4979, 19.0402),
        initialZoom = 13.0,
        modifier = if (fillHeight) modifier.fillMaxSize() else modifier.fillMaxWidth().height(height),
        minZoom = 6.0,
        dragRotate = false,
        onReady = { map = it },
        onLoaded = { ready?.invoke() },
        onLoadError = { loadError?.invoke() },
        onUserMove = guard::handleUserMove,
        layers = {
            val primary = rememberGeoJsonSource(GeoJsonData.JsonString(primaryJson))
            RouteLineLayers(
                "live-line-casing", "live-line-base", primary,
                casing = LineStroke(const(colors.casing), 7.dp, 0.9f),
                line = LineStroke(const(routeInk), 4.dp, 1f),
            )
            val secondary = rememberGeoJsonSource(GeoJsonData.JsonString(secondaryJson))
            RouteLineLayers(
                "live-secondary-casing", "live-secondary-base", secondary,
                casing = LineStroke(const(colors.casingSecondary), 5.dp, 0.85f),
                line = LineStroke(const(routeInk), 3.dp, 0.7f),
            )
            val stopsSrc = rememberGeoJsonSource(GeoJsonData.JsonString(stopsJson))
            val kind = feature["kind"].asString()
            CircleLayer(
                id = "live-stops-halo", source = stopsSrc,
                filter = isPriority,
                radius = switch(kind, case("selected", const(10.dp)), fallback = const(8.dp)),
                color = const(Palette.Blue500),
                opacity = switch(kind, case("selected", const(0.25f)), fallback = const(0.12f)),
                strokeWidth = const(1.dp),
                strokeColor = const(Palette.Blue500),
                strokeOpacity = switch(kind, case("selected", const(0.35f)), fallback = const(0.2f)),
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
            )
        },
        overlay = {
            // Per-frame state is read only here, so gliding never recomposes the layers.
            val frames = rememberVehicleAnimations(animVehicles)
            AnimatedVehicleMarkers(frames, mapBearing = { map?.cameraPosition?.bearing ?: 0.0 })
        },
    ) {
        MapControls(controls, Modifier.align(Alignment.TopEnd))
    }
}
