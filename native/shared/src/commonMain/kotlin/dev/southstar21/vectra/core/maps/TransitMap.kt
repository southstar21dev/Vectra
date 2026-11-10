package dev.southstar21.vectra.core.maps

import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.designsystem.components.LocalMapLifecycleOwner
import dev.southstar21.vectra.core.designsystem.components.LocalPageActive
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import dev.southstar21.vectra.core.designsystem.theme.NoIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.platform.Connectivity
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Radius

/* The shared basemap: OpenFreeMap style loading, the map composable and vehicle markers. */

/** What every map needs from the app; screens deep in a page shouldn't have to pass it down. */
class MapEnvironment(val http: HttpClient, val connectivity: Connectivity)

val LocalMapEnvironment = staticCompositionLocalOf<MapEnvironment> { error("MapEnvironment missing") }

/** maplibre position of a [LatLon]. */
val LatLon.position: Position get() = Position(longitude = lon, latitude = lat)

/** Fetches + sanitizes the OpenFreeMap basemap style (memoized per theme and day). */
object MapStyles {
    private val lock = Mutex()
    private val cache = HashMap<String, String>()

    suspend fun openFreeMap(http: HttpClient, isLight: Boolean): String {
        val url = openFreeMapStyleUrl(isLight)
        lock.withLock { cache[url]?.let { return it } }
        val res = http.get(url)
        if (!res.status.isSuccess()) error("OpenFreeMap style request failed (${res.status.value})")
        val out = prepareOpenFreeMapStyle(res.bodyAsText(), isLight)
        lock.withLock { cache[url] = out }
        return out
    }
}

/** The glyph stack stop labels use (OpenFreeMap hosts Noto Sans). */
val MapFontStack = listOf("Noto Sans Regular")

/**
 * The shared OpenFreeMap vector basemap with a theme-aware
 * style. [layers] declares the app's sources and layers (maplibre-compose
 * composables); [overlay] places Compose markers with `Modifier.placedAt`;
 * [content] draws chrome above the map.
 *
 * When the style cannot be fetched (offline, provider down) the map shows a
 * "Map unavailable" note and retries when the connection returns.
 */
@Composable
fun TransitMap(
    initialCenter: LatLon,
    initialZoom: Double,
    modifier: Modifier = Modifier,
    minZoom: Double = 5.0,
    maxZoom: Double = 19.0,
    dragRotate: Boolean = true,
    /** False: the map ignores input (static peek maps behind a tap target). */
    interactive: Boolean = true,
    attribution: Boolean = true,
    onReady: (MapState) -> Unit = {},
    /** First complete render: style + visible tiles. */
    onLoaded: () -> Unit = {},
    onLoadError: () -> Unit = {},
    /** A user gesture moved the camera (feed the programmatic-move guard). */
    onUserMove: () -> Unit = {},
    layers: @Composable @MaplibreComposable () -> Unit = {},
    overlay: @Composable MapOverlayScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit = {},
) {
    val c = Vectra.colors
    val isLight = !c.isDark
    val environment = LocalMapEnvironment.current
    var styleJson by remember { mutableStateOf<String?>(null) }
    var styleTheme by remember { mutableStateOf<Boolean?>(null) }
    var failed by remember { mutableStateOf(false) }
    val online by environment.connectivity.online.collectAsStateWithLifecycle()
    val loadError by rememberUpdatedState(onLoadError)
    LaunchedEffect(isLight, online) {
        // Theme flips swap the basemap in place; the declared layers carry over.
        if (styleJson != null && styleTheme == isLight) return@LaunchedEffect
        try {
            styleJson = MapStyles.openFreeMap(environment.http, isLight)
            styleTheme = isLight
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            if (styleJson == null) {
                failed = true
                loadError()
            }
        }
    }
    val baseStyle = styleJson?.let { BaseStyle.Json(it) } ?: BaseStyle.Empty
    val latestLayers by rememberUpdatedState(layers)
    val state = rememberMapState(
        baseStyle = baseStyle,
        initialCameraPosition = CameraPosition(center = initialCenter.position, zoom = initialZoom),
    ) {
        if (styleJson != null) latestLayers()
    }
    val ready by rememberUpdatedState(onReady)
    val loaded by rememberUpdatedState(onLoaded)
    LaunchedEffect(state) { ready(state) }
    LaunchedEffect(state, styleJson != null) {
        if (styleJson == null) return@LaunchedEffect
        // 'load' ≈ the first idle after the style; a map that never idles
        // (slow tiles) still reports after a grace period.
        withTimeoutOrNull(4_000) { state.events.first { it is MapEvent.Idle } }
        loaded()
    }
    val userMove by rememberUpdatedState(onUserMove)
    val interactions = remember(dragRotate, interactive) {
        if (!interactive) MapInteractions.None
        else MapInteractions {
            camera {
                pan { onStart { userMove() } }
                zoom { onStart { userMove() } }
                rotate { enabled = dragRotate; onStart { userMove() } }
                pitch { enabled = dragRotate; onStart { userMove() } }
            }
        }
    }
    val constraints = remember(minZoom, maxZoom) {
        CameraConstraints { this.minZoom = minZoom; this.maxZoom = maxZoom }
    }
    val ui = remember(c.mapSurface, interactive) {
        if (!interactive) MapUiOptions(MapUiOptions.None) { loadColor = c.mapSurface; drawInPage() }
        else MapUiOptions { loadColor = c.mapSurface; drawInPage() }
    }
    // Create the native map after the page's first frames, so a tab
    // switch or page entrance never waits on MapLibre surface setup.
    // Pre-composed hidden tabs wait until they are first shown.
    var mounted by remember { mutableStateOf(false) }
    val pageActive = LocalPageActive.current
    LaunchedEffect(pageActive) {
        if (!pageActive || mounted) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        mounted = true
    }
    Box(modifier.background(c.mapSurface)) {
        if (styleJson != null && mounted) {
            // The map's input layer focuses and reports a press on the first touch, and the app's
            // press highlight would draw that as a wash over the whole map. Markers on top get the
            // page's indication back.
            val pageIndication = LocalIndication.current
            CompositionLocalProvider(
                LocalLifecycleOwner provides (LocalMapLifecycleOwner.current ?: LocalLifecycleOwner.current),
                LocalIndication provides NoIndication,
            ) {
                MaplibreMap(
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    cameraConstraints = constraints,
                    interactions = interactions,
                    uiOptions = ui,
                    overlay = {
                        val scope = this
                        CompositionLocalProvider(LocalIndication provides pageIndication) { scope.overlay() }
                    },
                )
            }
        }
        if (attribution) MapAttribution(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp))
        if (failed && styleJson == null) {
            Box(Modifier.matchParentSize().padding(16.dp), contentAlignment = Alignment.Center) {
                VText("Map unavailable. It will load when you're back online.", style = vtext(TypeScale.Body), color = c.textMuted, align = TextAlign.Center)
            }
        }
        content()
    }
}

/** Tiny mono credit on a translucent chip. */
@Composable
fun MapAttribution(modifier: Modifier = Modifier) {
    val c = Vectra.colors
    VText(
        OpenFreeMapAttribution,
        modifier.background(c.mapAttributionBg, Radius.Hairline).padding(horizontal = 4.dp, vertical = 1.dp),
        vtext(9.sp to 11.sp, mono = true),
        c.textMuted,
        maxLines = 1,
    )
}

// ---- markers -------------------------------------------------------------------

enum class VehicleVariant { Primary, Secondary, Approximate }

/** Meaning colours: the tracked line, other lines on the map, estimated positions. */
fun VehicleVariant.color(): Color = when (this) {
    VehicleVariant.Primary -> Palette.Emerald500
    VehicleVariant.Secondary -> Palette.Violet500
    VehicleVariant.Approximate -> Palette.Pink500
}

private const val TeardropPath = "M22 7.5C22 7.5 31.5 17 31.5 22A9.5 9.5 0 0 1 12.5 22C12.5 17 22 7.5 22 7.5Z"
private val teardrop by lazy { PathParser().parsePathString(TeardropPath).toPath() }

/**
 * The vehicle teardrop in a 44×44 box: round body centred on (22,22) — the
 * position and rotation pivot — tip pointing along [bearing] (degrees, 0 = north).
 */
fun DrawScope.drawVehicleTeardrop(color: Color, bearing: Float = 0f) {
    val s = size.minDimension / 44f
    rotate(bearing, pivot = Offset(size.width / 2f, size.height / 2f)) {
        translate((size.width - 44f * s) / 2f, (size.height - 44f * s) / 2f) {
            scale(s, s, Offset.Zero) {
                // Soft drop shadow, approximated by an offset darker copy.
                translate(0f, 1f) { drawPath(teardrop, Color.Black.copy(alpha = 0.25f), style = Stroke(4f, join = StrokeJoin.Round)) }
                drawPath(teardrop, color)
                drawPath(teardrop, Color.White, style = Stroke(2f, join = StrokeJoin.Round))
                drawCircle(Color.White.copy(alpha = 0.85f), radius = 3f, center = Offset(22f, 22f))
            }
        }
    }
}

/** A live vehicle marker as a Compose element (place with `Modifier.placedAt`). */
@Composable
fun VehicleMarker(color: Color, bearing: Float, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Canvas(modifier.size(size)) { drawVehicleTeardrop(color, bearing) }
}

// ---- GeoJSON building -------------------------------------------------------------

/** Minimal GeoJSON builders; sources take the string via `GeoJsonData.JsonString`. */
object GeoJson {
    fun point(lat: Double, lon: Double, properties: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
        put("type", "Feature")
        put("properties", properties)
        put("geometry", buildJsonObject {
            put("type", "Point")
            put("coordinates", buildJsonArray { add(JsonPrimitive(lon)); add(JsonPrimitive(lat)) })
        })
    }

    fun line(points: List<LatLon>, properties: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
        put("type", "Feature")
        put("properties", properties)
        put("geometry", buildJsonObject {
            put("type", "LineString")
            put("coordinates", JsonArray(points.map { buildJsonArray { add(JsonPrimitive(it.lon)); add(JsonPrimitive(it.lat)) } }))
        })
    }

    fun collection(features: List<JsonObject>): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }.toString()

    const val Empty = """{"type":"FeatureCollection","features":[]}"""
}
