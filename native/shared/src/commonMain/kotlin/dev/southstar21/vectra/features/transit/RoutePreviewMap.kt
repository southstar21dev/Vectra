package dev.southstar21.vectra.features.transit

import androidx.compose.ui.text.style.TextAlign
import dev.southstar21.vectra.core.maps.MapAttribution
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.GeoJson
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.MapControlButton
import dev.southstar21.vectra.core.maps.MapControls
import dev.southstar21.vectra.core.maps.TransitMap
import dev.southstar21.vectra.core.maps.boundsOf
import dev.southstar21.vectra.core.maps.fitBounds
import dev.southstar21.vectra.core.maps.journeyFitKey
import dev.southstar21.vectra.core.maps.lineFeatureCollection
import dev.southstar21.vectra.core.maps.rememberProgrammaticGuard
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.dp
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import dev.southstar21.vectra.core.maps.MapColors
import dev.southstar21.vectra.core.designsystem.theme.Radius

private fun hashed(hex: String?): String? = hex?.let { withHash(it) }?.takeIf { parseHexColor(it) != null }

/** One Feature per direction. */
private fun polylinesGeoJson(polylines: List<List<LatLon>>): String = lineFeatureCollection(polylines).toString()

/** The route's stops, the selected one enlarged (the explorer map has its own stop styling). */
private fun previewStopsGeoJson(stops: List<RoutePreviewStop>): String = GeoJson.collection(
    stops.filter { it.lat != 0.0 || it.lon != 0.0 }.map { s ->
        val sel = s.selected == true
        GeoJson.point(
            s.lat, s.lon,
            buildJsonObject {
                put("id", s.id)
                put("name", s.name)
                put("selected", if (sel) 1 else 0)
                put("radius", if (sel) 7.0 else 4.5)
                put("strokeColor", if (sel) "#93c5fd" else "#a1a1aa")
                put("strokeWidth", if (sel) 2 else 1)
                put("fillColor", if (sel) "#2563eb" else "#52525b")
            },
        )
    },
)

/**
 * MapLibre preview of a whole route: every direction's polyline, the stop
 * dots, and the live positions of the vehicles currently running on it.
 * Self-contained so the tracker dialogs stay lightweight.
 *
 * [routeColor] is hex WITHOUT the leading #.
 */
@Composable
fun RoutePreviewMap(
    /** One polyline per direction variant — the whole route. */
    polylines: List<List<LatLon>>,
    stops: List<RoutePreviewStop>,
    vehicles: List<RoutePreviewVehicle>,
    routeColor: String,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    onExpand: (() -> Unit)? = null,
    /** Called with a stop id when its dot is tapped (null = dots inert). */
    onSelectStop: ((String) -> Unit)? = null,
    /** Fired once when the map has loaded and painted. */
    onReady: (() -> Unit)? = null,
) {
    val c = Vectra.colors
    var map by remember { mutableStateOf<MapState?>(null) }
    val guard = rememberProgrammaticGuard()
    var fittedKey by remember { mutableStateOf("") }
    val routeHex = hashed(routeColor) ?: "#3b82f6"
    val routeInk = parseHexColor(routeHex) ?: MapColors.RouteFallback

    val fitKey = remember(polylines) { journeyFitKey(polylines) }
    val allPoints = remember(polylines) { polylines.flatten() }

    val linesJson = remember(polylines) { polylinesGeoJson(polylines) }
    val stopsJson = remember(stops) { previewStopsGeoJson(stops) }
    val selectStop by rememberUpdatedState(onSelectStop)

    // Fit once per geometry key unless the user has moved the camera; new
    // geometry refits.
    LaunchedEffect(map, fitKey) {
        val m = map ?: return@LaunchedEffect
        if (fitKey.isEmpty() || guard.userMoved || fittedKey == fitKey) return@LaunchedEffect
        val bounds = boundsOf(allPoints) ?: return@LaunchedEffect
        fittedKey = fitKey
        guard.programmaticMove { m.fitBounds(bounds, 40.dp, maxZoom = 15.5, animate = false) }
    }

    val showWholeRoute = {
        val m = map
        val bounds = boundsOf(allPoints)
        if (m != null && bounds != null) {
            guard.resetUserMoved()
            fittedKey = fitKey
            guard.programmaticMove { m.fitBounds(bounds, 40.dp, maxZoom = 15.5, animate = true) }
        }
    }
    val controls = buildList {
        add(MapControlButton("whole-route", "Show whole route", RemixIcons.Focus3Line, showWholeRoute))
        if (onExpand != null) add(MapControlButton("expand", "Expand", RemixIcons.ExpandDiagonalLine, onExpand))
    }
    val ready by rememberUpdatedState(onReady)

    val shape = Radius.Panel
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceOverlay, shape)
            .border(1.dp, c.borderSubtle, shape),
    ) {
        TransitMap(
            initialCenter = LatLon(47.4979, 19.0402),
            initialZoom = 12.0,
            modifier = Modifier.fillMaxWidth().height(height),
            minZoom = 5.0,
            maxZoom = 19.0,
            attribution = false,
            onReady = { map = it },
            onLoaded = { ready?.invoke() },
            onUserMove = guard::handleUserMove,
            layers = {
                val lines = rememberGeoJsonSource(GeoJsonData.JsonString(linesJson))
                RouteLineLayers(
                    "route-lines-casing", "route-lines-color", lines,
                    casing = LineStroke(const(Color(0xFF1F2937)), 7.dp, 0.9f),
                    line = LineStroke(const(routeInk), 4.dp, 0.95f),
                )
                val stopsSrc = rememberGeoJsonSource(GeoJsonData.JsonString(stopsJson))
                StopDotsLayer(
                    id = "route-stops-layer", source = stopsSrc,
                    hitPadding = if (onSelectStop != null) 10.dp else 0.dp,
                    onClick = if (onSelectStop == null) null else { features ->
                        val id = (features.firstOrNull()?.properties?.get("id") as? JsonPrimitive)?.contentOrNull
                        val cb = selectStop
                        if (id != null && cb != null) {
                            cb(id)
                            ClickResult.Consume
                        } else ClickResult.Pass
                    },
                )
            },
            overlay = {
                val mapBearing = { map?.cameraPosition?.bearing ?: 0.0 }
                for (v in vehicles) {
                    if (v.lat == 0.0 && v.lon == 0.0) continue
                    key(v.id) {
                        PlacedVehicleMarker(
                            position = LatLon(v.lat, v.lon),
                            color = routeInk,
                            bearing = v.bearing?.takeIf { it.isFinite() } ?: 0.0,
                            mapBearing = mapBearing,
                        )
                    }
                }
            },
        ) {
            MapControls(controls, Modifier.align(Alignment.TopEnd))
        }
        MapAttribution(Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 6.dp), align = TextAlign.Center)
    }
}
