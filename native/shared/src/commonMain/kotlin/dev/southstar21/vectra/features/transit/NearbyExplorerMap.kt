package dev.southstar21.vectra.features.transit

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.glass
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.MapAttribution
import dev.southstar21.vectra.core.maps.PlacedVehicle
import dev.southstar21.vectra.core.maps.TransitMap
import dev.southstar21.vectra.core.maps.VehicleVariant
import dev.southstar21.vectra.core.maps.accuracyCircleFeature
import dev.southstar21.vectra.core.maps.position
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.dp
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import dev.southstar21.vectra.core.maps.MapColors
import dev.southstar21.vectra.core.maps.SelectedStopLayers
import dev.southstar21.vectra.core.maps.UserLocationLayers
import dev.southstar21.vectra.core.maps.VehicleLayers
import dev.southstar21.vectra.core.maps.composeOverlaysTrackMap

@Immutable
data class ExplorerCameraRequest(val lat: Double, val lon: Double, val zoom: Double? = null, val nonce: Long)

@Immutable
data class ExplorerUser(val lat: Double, val lon: Double, val accuracy: Double)

/** The invisible tap radius around a stop dot (the dots are 4–5dp; a fingertip needs ~40dp). */
private val StopHitRadius = 14.dp
/** Keep edge stops discoverable when their label is visible at the map edge. */
private const val StopQueryMarginPx = 160f

private fun hex(c: Color): String {
    fun h(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#" + h(c.red) + h(c.green) + h(c.blue)
}

private fun pointFeature(lon: Double, lat: Double, props: JsonObject) = buildJsonObject {
    put("type", "Feature")
    put("properties", props)
    put("geometry", buildJsonObject {
        put("type", "Point")
        put("coordinates", buildJsonArray { add(JsonPrimitive(lon)); add(JsonPrimitive(lat)) })
    })
}

private fun collection(features: List<JsonObject>) = buildJsonObject {
    put("type", "FeatureCollection")
    put("features", JsonArray(features))
}.toString()

/** The tracker strips' stop rings, for the explorer's dots: the card's fill, a neutral ring, and the picked stop's thick ring. */
internal class StopRingInk(val card: Color, val ring: Color, val picked: Color)

/**
 * Stop dots for the viewport, as the tracker strips draw stops: hollow rings, the picked one a
 * thick ring. A latched route selection dims them rather than hiding them, so another stop stays
 * one tap away.
 */
private fun explorerStopsGeoJson(stops: List<ExplorerMapStop>, selectedStopId: String?, focusedStopId: String?, ink: StopRingInk): String {
    val dimmed = selectedStopId != null
    return collection(
        stops.filter { selectedStopId == null || it.id != selectedStopId }.map { s ->
            val focused = s.id == focusedStopId
            val platform = s.isPlatform == true
            val baseRadius = when {
                focused -> 7.0
                platform -> 3.5
                else -> 4.5
            }
            pointFeature(s.lon, s.lat, buildJsonObject {
                put("id", s.id)
                put("stationId", s.stationId)
                put("name", s.name)
                put("radius", if (dimmed) max(2.5, baseRadius - 1.5) else baseRadius)
                put("opacity", if (dimmed) 0.45 else 1.0)
                put("fillColor", hex(ink.card))
                put("strokeColor", hex(if (focused) ink.picked else ink.ring))
                put("strokeWidth", if (focused) 3.5 else if (platform) 1.5 else 2.0)
            })
        },
    )
}

private fun routeStopsGeoJson(routeStops: List<ExplorerRouteStop>, selectedStopId: String?, selectedPlatformStopId: String?, color: String): String =
    collection(
        routeStops
            // The pulse marker already marks the selected departure's own stop.
            .filter { it.id != selectedStopId && it.id != selectedPlatformStopId }
            .map { s -> pointFeature(s.lon, s.lat, buildJsonObject { put("id", s.id); put("name", s.name); put("color", color) }) },
    )

private fun routeGeoJson(polylines: List<List<LatLon>>, color: String): String =
    collection(
        polylines.filter { it.size >= 2 }.map { line ->
            buildJsonObject {
                put("type", "Feature")
                put("properties", buildJsonObject { put("color", color) })
                put("geometry", buildJsonObject {
                    put("type", "LineString")
                    put("coordinates", JsonArray(line.map { p -> buildJsonArray { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) } }))
                })
            }
        },
    )

/**
 * The explorer's interactive map: catalog stop dots (tap to focus), the
 * selected route's line, stops and tracked vehicle, your position with its
 * accuracy circle, and the selected stop's beacon.
 *
 * [bottomInset] is the settled sheet coverage (re-queries the viewport);
 * [attributionBottom] is read per frame so the attribution rides on the sheet.
 */
@Composable
internal fun NearbyExplorerMap(
    initialCenter: LatLon,
    stops: List<ExplorerMapStop>,
    focusedStopId: String?,
    selectedStop: ExplorerSelectedStop?,
    polylines: List<List<LatLon>>,
    routeStops: List<ExplorerRouteStop>,
    routeColor: String,
    vehicles: List<PlacedVehicle>,
    cameraRequest: ExplorerCameraRequest?,
    user: ExplorerUser?,
    bottomInset: Dp,
    onViewportChange: (ExplorerViewport) -> Unit,
    onSelectStop: (stopId: String, stationId: String?) -> Unit,
    modifier: Modifier = Modifier,
    attributionBottom: () -> Dp = { 0.dp },
    onReady: () -> Unit = {},
    routeTextColor: String = "FFFFFF",
    /** Departure chips (C1); drawn where Compose overlays track the map (not on iOS yet). */
    chips: List<NearbyChip> = emptyList(),
    focusedChipId: String? = null,
    dimChips: Boolean = false,
    chipChrome: ChipChrome = ChipChrome(0.dp, 0.dp, 0.dp),
    onChipTap: (NearbyChip) -> Unit = {},
    board: (@Composable (stopId: String) -> Unit)? = null,
    /** Hands out the map once it exists (its projection, for the loading orb's melt). */
    onMapState: (MapState) -> Unit = {},
) {
    val latestOnMapState by rememberUpdatedState(onMapState)
    val c = Vectra.colors
    var map by remember { mutableStateOf<MapState?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var userMoved by remember { mutableStateOf(false) }
    val ready by rememberUpdatedState(onReady)
    val emit by rememberUpdatedState(onViewportChange)
    val select by rememberUpdatedState(onSelectStop)
    val inset by rememberUpdatedState(bottomInset)

    // One-shot: report once the map has loaded and a frame has painted.
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        withFrameNanos { }
        ready()
    }

    val isLight = !c.isDark
    val colors = remember(isLight) { explorerRouteColors(isLight) }
    val lineColors = liveLineColors(c)

    val selectedStopId = selectedStop?.id
    val selectedPlatformStopId = selectedStop?.platformStopId

    fun emitViewport(m: MapState) {
        val vp = m.viewport ?: return
        val width = vp.size.width.value
        val height = vp.size.height.value
        val bottom = clampBottomInset(inset.value.toDouble(), height.toDouble()).toFloat()
        val uncovered = height - bottom
        if (width <= 0f || uncovered <= 0f) return
        val tl = m.positionFromScreenLocation(DpOffset(0.dp, (-StopQueryMarginPx).dp)) ?: return
        val br = m.positionFromScreenLocation(DpOffset(width.dp, (height + StopQueryMarginPx).dp)) ?: return
        val ctr = m.positionFromScreenLocation(DpOffset((width / 2f).dp, (uncovered / 2f).dp)) ?: return
        emit(
            viewportFromUncoveredRect(
                UnprojectedPoint(tl.latitude, tl.longitude),
                UnprojectedPoint(br.latitude, br.longitude),
                UnprojectedPoint(ctr.latitude, ctr.longitude),
                m.cameraPosition.zoom,
            ),
        )
    }

    // The stop query spans the whole canvas; its center stays on the uncovered area.
    LaunchedEffect(map, loaded) {
        val m = map ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        emitViewport(m)
        m.events.filterIsInstance<MapEvent.CameraMoveEnded>().collectLatest {
            delay(400)
            emitViewport(m)
        }
    }
    // Re-emit once the sheet's coverage settles.
    LaunchedEffect(bottomInset) {
        val m = map ?: return@LaunchedEffect
        delay(150)
        if (loaded) emitViewport(m)
    }

    // Follow a location fix that arrives after mount, until the user moves the camera.
    LaunchedEffect(map, loaded, initialCenter.lat, initialCenter.lon) {
        val m = map ?: return@LaunchedEffect
        if (!loaded || userMoved) return@LaunchedEffect
        m.animateCamera(CameraUpdate(center = initialCenter.position), CameraAnimation.Ease { duration = 300.milliseconds })
    }

    // Camera requests (recenter / fly to a selected stop).
    LaunchedEffect(map, loaded, cameraRequest) {
        val m = map ?: return@LaunchedEffect
        val req = cameraRequest ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        userMoved = true
        m.animateCamera(
            CameraUpdate(center = LatLon(req.lat, req.lon).position, zoom = req.zoom ?: max(m.cameraPosition.zoom, 15.5)),
            CameraAnimation.Fly { speed = 1.4 },
        )
    }

    PanToBoard(map, loaded, chips, focusedChipId, chipChrome.top, bottomInset)
    val shownChips = rememberThinnedChips(map, loaded, chips, ChipChrome(chipChrome.top, chipChrome.controlsBottom, bottomInset))
    val routeHex = withHash(routeColor)
    val animatedVehicles = remember(vehicles, polylines) {
        vehicles.map { v ->
            AnimatedVehicleInput(v.id, polylines.firstOrNull().orEmpty(), LatLon(v.lat, v.lon), VehicleVariant.Primary, v.bearing)
        }
    }

    val routeJson = remember(polylines, routeHex) { routeGeoJson(polylines, routeHex) }
    val accuracyJson = remember(user) {
        val f = user?.takeIf { it.accuracy > 0 }?.let { accuracyCircleFeature(LatLon(it.lat, it.lon), it.accuracy) }
        collection(listOfNotNull(f))
    }
    val ringInk = StopRingInk(c.surfaceSecondary, if (c.isDark) Palette.Slate400 else Palette.Slate500, c.accentPrimary)
    val stopsJson = remember(stops, selectedStopId, focusedStopId, ringInk.card, ringInk.ring, ringInk.picked) {
        explorerStopsGeoJson(stops, selectedStopId, focusedStopId, ringInk)
    }
    // The followed line's vehicles and stop, as the tracker strips and the tracker map draw them.
    val strip = stripColors(routeColor, routeTextColor, c.surfaceSecondary)
    val tokenStyle = remember(strip.marker, strip.markerInk) { TokenStyle(strip.marker, strip.markerInk) }
    val routeStopsJson = remember(routeStops, routeHex, selectedStopId, selectedPlatformStopId) {
        routeStopsGeoJson(routeStops, selectedStopId, selectedPlatformStopId, routeHex)
    }
    TransitMap(
        initialCenter = initialCenter,
        initialZoom = 15.5,
        modifier = modifier,
        minZoom = 10.0,
        maxZoom = 19.0,
        dragRotate = false,
        attribution = false,
        onReady = { map = it; latestOnMapState(it) },
        onLoaded = { loaded = true },
        onUserMove = { userMoved = true },
        layers = {
            val route = rememberGeoJsonSource(GeoJsonData.JsonString(routeJson))
            RouteLineLayers(
                "explorer-route-casing", "explorer-route-line", route,
                casing = LineStroke(const(lineColors.casing), 7.dp, 0.9f),
                line = LineStroke(feature["color"].convertToColor(), 4.dp, 1f),
            )
            val accuracy = rememberGeoJsonSource(GeoJsonData.JsonString(accuracyJson))
            FillLayer(id = "explorer-accuracy-fill", source = accuracy, color = const(MapColors.Location), opacity = const(0.12f))
            LineLayer(id = "explorer-accuracy-stroke", source = accuracy, color = const(MapColors.Location), width = const(1.5.dp), opacity = const(0.35f))
            val stopsSource = rememberGeoJsonSource(GeoJsonData.JsonString(stopsJson))
            StopDotsLayer(
                id = "explorer-stops-layer",
                source = stopsSource,
                opacity = feature["opacity"].asNumber(),
                // A generous invisible hit target around each stop (radius + 14dp).
                hitPadding = StopHitRadius,
                onClick = { features ->
                    val props = features.firstOrNull()?.properties
                    val id = (props?.get("id") as? JsonPrimitive)?.contentOrNull
                    if (!id.isNullOrEmpty()) {
                        val station = (props["stationId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        select(id, station)
                        ClickResult.Consume
                    } else {
                        // Empty taps intentionally do nothing.
                        ClickResult.Pass
                    }
                },
            )
            val routeStopsSource = rememberGeoJsonSource(GeoJsonData.JsonString(routeStopsJson))
            CircleLayer(
                id = "explorer-route-stops-layer",
                source = routeStopsSource,
                radius = const(4.5.dp),
                color = const(c.surfaceSecondary),
                strokeColor = const(strip.line),
                strokeWidth = const(2.5.dp),
            )
            if (!composeOverlaysTrackMap) {
                // iOS: markers live in the map so they stay pinned while it pans.
                RoundVehicleLayers("explorer", rememberVehicleAnimations(animatedVehicles), tokenStyle, emptyMap())
                if (user != null) UserLocationLayers("explorer", LatLon(user.lat, user.lon))
                if (selectedStop != null) {
                    SelectedStopLayers("explorer", LatLon(selectedStop.lat, selectedStop.lon), selectedStop.name, colors.isLight)
                }
            }
        },
        overlay = {
            if (!composeOverlaysTrackMap) return@TransitMap
            if (user != null) {
                Box(Modifier.placedAt(LatLon(user.lat, user.lon).position)) { NearbyUserDot(hidePingOnReducedMotion = false) }
            }
            NearbyChipOverlay(chips, shownChips, focusedChipId, dimChips, onChipTap, board)
            // Read here so a glide recomposes the markers, not the map and its layers.
            val frames = rememberVehicleAnimations(animatedVehicles)
            if (selectedStop != null) {
                Box(Modifier.placedAt(LatLon(selectedStop.lat, selectedStop.lon).position, Alignment.BottomCenter)) {
                    SelectedStopBeacon(selectedStop.name, strip)
                }
            }
            RoundVehicleMarkers(frames, mapBearing = { map?.cameraPosition?.bearing ?: 0.0 }, style = tokenStyle, callouts = emptyMap())
        },
    ) {
        // The attribution rides 8dp above the departures sheet.
        MapAttribution(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 8.dp)
                .offset { IntOffset(0, -((attributionBottom() + 8.dp).roundToPx())) },
            align = TextAlign.Center,
        )
    }
}

/**
 * The followed departure's stop, as a tracker strip draws your stop: a thick ring in the line's
 * colour on the card's halo, its name in a glass tag above (anchor: bottom).
 */
@Composable
private fun SelectedStopBeacon(name: String, strip: StripColors) {
    val c = Vectra.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        VText(
            name,
            Modifier.glass(RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            vtext(11.sp to 14.sp, Weight.Bold),
            c.textPrimary,
            maxLines = 1,
            softWrap = false,
        )
        Canvas(Modifier.size(28.dp)) {
            val r = 10.dp.toPx()
            drawCircle(strip.card, r + 4.dp.toPx(), center)
            drawCircle(strip.line, r, center)
            drawCircle(strip.you, r - 5.dp.toPx(), center)
        }
    }
}
