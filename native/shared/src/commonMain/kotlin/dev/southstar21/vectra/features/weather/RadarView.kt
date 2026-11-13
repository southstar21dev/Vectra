package dev.southstar21.vectra.features.weather

import dev.southstar21.vectra.core.maps.MapAttribution
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Weight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.GlassMapControls
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.MapControlButton
import dev.southstar21.vectra.core.maps.OpenFreeMapAttribution
import dev.southstar21.vectra.core.maps.easeTo
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapState

/** The radar's controls stay grouped on wide windows. */
private val RadarControlsMaxWidth = 576.dp

/** How long the sweep waits for the map and the live frame before it goes anyway. */
private const val ReadyCapMs = 6000L

/**
 * The live radar (M1): the map centred on you, the orb while it loads, one sweep around you that
 * uncovers the rain, then the header, a locate button and the timeline card. [grow] (0..1) is how
 * far the card it opened from has grown; [orbFrom] is where the orb starts (the card's art), px.
 * The map is created once [opened] (the grow is over), so the grow never waits on it.
 */
@Composable
internal fun RadarView(repo: WeatherRepository, grow: () -> Float, opened: Boolean, orbFrom: Offset?, onClose: () -> Unit) {
    val c = Vectra.colors
    val location by repo.location.collectAsStateWithLifecycle()
    val coords = location?.coords ?: BudapestCoords
    val timeline = rememberRadarTimeline(repo)
    val style = rememberRadarStyle(repo)
    val reduced = Vectra.reduceMotion
    val sweep = remember { RadarSweep(reduced) }
    var map by remember { mutableStateOf<MapState?>(null) }
    var mapShown by remember { mutableStateOf(false) }
    var layerOn by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val chrome by animateFloatAsState(if (opened) 1f else 0f, tween(if (reduced) 150 else 320), label = "chrome")

    BoxWithConstraints(Modifier.fillMaxSize().background(c.surfaceSecondary)) {
        val density = LocalDensity.current.density
        val w = constraints.maxWidth / density
        val h = constraints.maxHeight / density
        val live = rememberLiveTiles(repo, timeline, coords, w, h)
        val failed = timeline.error != null || (timeline.data != null && timeline.frames.isEmpty())
        LaunchedEffect(map) {
            val m = map ?: return@LaunchedEffect
            withTimeoutOrNull(4000) { m.events.first { it is MapEvent.Idle } }
            mapShown = true
        }
        LaunchedEffect(failed) { if (failed) sweep.finish() }
        RunSweep(sweep, opened, mapShown && live.ready, live.rainInView, map) { layerOn = true }
        if (opened && style != null && !failed) {
            WeatherRadarMap(
                style = style,
                center = coords,
                activeFrame = timeline.current,
                host = timeline.data?.host.orEmpty(),
                radarOpacity = if (layerOn) RadarOpacity else 0.01f,
                fadeTiles = sweep.phase == SweepPhase.Done,
                interactive = sweep.phase == SweepPhase.Done,
                showPin = sweep.phase == SweepPhase.Done,
                onState = { map = it },
            )
        }
        SweepCanvas(sweep, live.tiles, orbFrom, grow)
        SweepPin(sweep)
        LoadingCaption(opened, sweep)
        if (failed) RadarFailed(timeline.error) { repo.loadRadar(force = true) }
        RadarOverlays(repo, timeline, coords, chrome, sweep.phase == SweepPhase.Done, onClose) {
            scope.launch { runSuspendCatching { map?.easeTo(LatLon(coords.latitude, coords.longitude), RadarZoom.toDouble()) } }
        }
    }
}

/**
 * Your pin, in the middle from the moment the sweep starts: the line turns around it. The map's own
 * pin, in the same place, takes over once the sweep is done.
 */
@Composable
private fun BoxScope.SweepPin(sweep: RadarSweep) {
    val showing = sweep.phase == SweepPhase.Sweeping || sweep.phase == SweepPhase.HandingOver
    if (!showing) return
    val reduced = Vectra.reduceMotion
    val pop = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 420f)) }
    LocationPin(
        Modifier.align(Alignment.Center).graphicsLayer {
            val p = pop.value
            scaleX = 0.4f + 0.6f * p
            scaleY = 0.4f + 0.6f * p
            alpha = p.coerceIn(0f, 1f)
        },
    )
}

/** "Loading live radar…" under the orb while it waits. */
@Composable
private fun BoxScope.LoadingCaption(opened: Boolean, sweep: RadarSweep) {
    val show = opened && sweep.phase == SweepPhase.Loading
    val caption by animateFloatAsState(if (show) 1f else 0f, tween(200), label = "caption")
    if (caption <= 0f) return
    VText(
        "Loading live radar…",
        Modifier.align(Alignment.Center).offset(y = 52.dp).graphicsLayer { alpha = caption },
        vtext(TypeScale.Caption, Weight.Medium),
        Vectra.colors.textSecondary,
    )
}

/** The sweep, centred on the view (your spot); the orb glides there from [orbFrom] as the card grows. */
@Composable
private fun SweepCanvas(sweep: RadarSweep, tiles: List<PlacedTile>, orbFrom: Offset?, grow: () -> Float) {
    val c = Vectra.colors
    val ink = SweepInk(c.surfaceSecondary, c.isDark, if (c.isDark) Palette.Zinc200 else Palette.Zinc900)
    Canvas(Modifier.fillMaxSize()) {
        val pivot = Offset(size.width / 2, size.height / 2)
        val start = orbFrom ?: pivot
        val g = grow()
        val orbAt = Offset(start.x + (pivot.x - start.x) * g, start.y + (pivot.y - start.y) * g)
        drawRadarSweep(sweep, ink, orbAt, pivot, tiles)
    }
}

/**
 * Plays [sweep] once [opened] and [ready] (or after [ReadyCapMs] regardless); at the end
 * [onLayerOn] shows the map's own layer and the drawn rain drops once [map] has drawn it.
 */
@Composable
private fun RunSweep(sweep: RadarSweep, opened: Boolean, ready: Boolean, rainInView: Boolean, map: MapState?, onLayerOn: () -> Unit) {
    val openNow by rememberUpdatedState(opened)
    val readyNow by rememberUpdatedState(ready)
    val rainNow by rememberUpdatedState(rainInView)
    val mapNow by rememberUpdatedState(map)
    val layerOn by rememberUpdatedState(onLayerOn)
    LaunchedEffect(sweep) {
        var capped = false
        launch {
            snapshotFlow { openNow }.first { it }
            delay(ReadyCapMs)
            capped = true
        }
        sweep.run(isReady = { openNow && (readyNow || capped) }, hasRain = { rainNow }) {
            layerOn()
            // One frame for the change to reach the map, then the first frame it draws with the
            // layer (its tiles are in already, loaded at 1 %): the drawn rain drops on that frame.
            withFrameNanos { }
            mapNow?.let { m -> withTimeoutOrNull(400) { m.events.first { it is MapEvent.FrameRendered } } }
        }
    }
}

/** The header, the locate button and the timeline card, fading in with [chrome]; [settled] once the sweep is over. */
@Composable
private fun RadarOverlays(
    repo: WeatherRepository,
    timeline: RadarTimelineState,
    coords: WeatherCoords,
    chrome: Float,
    settled: Boolean,
    onClose: () -> Unit,
    onLocate: () -> Unit,
) {
    val c = Vectra.colors
    val weather by repo.weather.collectAsStateWithLifecycle()
    val safeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val safeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize()) {
        RadarHeader(
            weather.data,
            timeline.label,
            onClose,
            Modifier.align(Alignment.TopCenter).padding(top = safeTop + 8.dp, start = 12.dp, end = 12.dp).widthIn(max = RadarControlsMaxWidth)
                .graphicsLayer { alpha = chrome; translationY = (1 - chrome) * -12.dp.toPx() },
        )
        val controls by animateFloatAsState(if (settled) 1f else 0f, tween(260), label = "controls")
        if (controls > 0f) {
            GlassMapControls(
                listOf(MapControlButton("locate", "Centre on ${if (coords == BudapestCoords) "Budapest" else "your location"}", RemixIcons.Focus3Line, onLocate)),
                Modifier.align(Alignment.TopEnd).padding(top = safeTop + 64.dp, end = 12.dp).graphicsLayer { alpha = controls },
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = max(12.dp, safeBottom + 8.dp))
                .widthIn(max = RadarControlsMaxWidth).fillMaxWidth()
                .graphicsLayer { alpha = chrome; translationY = (1 - chrome) * 24.dp.toPx() },
        ) {
            RadarTimeline(
                TimelineState(timeline.rain, timeline.index, timeline.label, timeline.playing, enabled = settled),
                onTogglePlay = timeline::togglePlay,
                onPick = timeline::pick,
            )
            MapAttribution(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
                text = "$OpenFreeMapAttribution · $RainViewerAttribution",
                align = TextAlign.Center,
            )
        }
    }
}

/** The live frame's tiles for a [w]×[h] dp view centred on [coords], and whether the sweep can use them yet. */
internal class LiveTiles(val tiles: List<PlacedTile>, val ready: Boolean, val rainInView: Boolean = false)

@Composable
private fun rememberLiveTiles(repo: WeatherRepository, timeline: RadarTimelineState, coords: WeatherCoords, w: Float, h: Float): LiveTiles {
    val data = timeline.data
    val live = timeline.live
    var result by remember { mutableStateOf(LiveTiles(emptyList(), ready = false)) }
    LaunchedEffect(live?.path, coords, w, h) {
        if (data == null || live == null) return@LaunchedEffect
        val loader = RadarTileLoader(repo.httpClient, data.host)
        val keys = tilesInView(coords, w, h)
        val frame = loader.liveTiles(live, keys, coords, w, h)
        frame.here?.let { timeline.setRain(live, it) }
        val placed = keys.mapNotNull { (x, y) -> frame.images[x to y]?.let { PlacedTile(x, y, tileTopLeft(x, y, coords, w, h), it) } }
        result = LiveTiles(placed, ready = true, rainInView = frame.rainInView)
        // The rest of the timeline, newest first: the bars fill in towards −2 h.
        coroutineScope {
            timeline.frames.filter { it != live }.forEach { f ->
                launch { loader.rainAt(f, coords)?.let { timeline.setRain(f, it) } }
            }
        }
    }
    return result
}

/** The basemap style, fetched as soon as the radar opens (the map itself waits for the grow). */
@Composable
private fun rememberRadarStyle(repo: WeatherRepository): String? {
    val isLight = !Vectra.colors.isDark
    var style by remember(isLight) { mutableStateOf<String?>(null) }
    LaunchedEffect(isLight) { style = runSuspendCatching { fetchRadarBaseStyle(repo.httpClient, isLight) }.getOrNull() }
    return style
}

/** Frames, the one showing, playback, and each frame's rain at your spot. */
internal class RadarTimelineState {
    var data by mutableStateOf<NormalizedRadarData?>(null)
    var error by mutableStateOf<Throwable?>(null)
    var index by mutableIntStateOf(0)
    var playing by mutableStateOf(false)
    private val samples = mutableStateListOf<RainSample?>()

    val frames: List<RadarFrame> get() = data?.radarFrames.orEmpty()
    val current: RadarFrame? get() = frames.getOrNull(index)
    val live: RadarFrame? get() = data?.let { d -> frames.firstOrNull { it.time == d.latestPastTime } ?: frames.lastOrNull() }
    val label: RadarFrameLabel? get() = current?.let { f -> data?.let { formatRadarFrameLabel(f, it.latestPastTime) } }
    val rain: List<RainSample?> get() = samples

    fun togglePlay() { playing = !playing }

    fun pick(i: Int) {
        playing = false
        index = i
    }

    fun setRain(frame: RadarFrame, sample: RainSample?) {
        val i = frames.indexOf(frame)
        if (i in samples.indices) samples[i] = sample
    }

    /** New data: keep the frame on screen if it's still there, else go to the live one (always on first load). */
    fun accept(next: NormalizedRadarData?, snapToLive: Boolean) {
        if (next == null || next === data) return
        val shown = current
        data = next
        samples.clear()
        repeat(next.radarFrames.size) { samples += null }
        val same = shown?.let { s -> next.radarFrames.indexOfFirst { it.time == s.time } } ?: -1
        index = if (snapToLive || same < 0) next.radarFrames.indexOf(live).coerceAtLeast(0) else same
    }
}

@Composable
private fun rememberRadarTimeline(repo: WeatherRepository): RadarTimelineState {
    val state = remember { RadarTimelineState() }
    val radar by repo.radar.collectAsStateWithLifecycle()
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(radar.data) {
        state.accept(radar.data, snapToLive = first)
        if (radar.data != null) first = false
    }
    LaunchedEffect(radar.error) { state.error = radar.error.takeIf { radar.data == null } }
    LaunchedEffect(Unit) {
        repo.loadRadar()
        while (true) {
            delay(WeatherRepository.RadarRefetchMs)
            repo.loadRadar(force = true)
        }
    }
    // Playback: 900 ms a frame, a longer hold on the live one.
    LaunchedEffect(state.playing, state.index, state.frames.size) {
        val n = state.frames.size
        if (!state.playing || n == 0) return@LaunchedEffect
        delay(if (state.index == n - 1) 2200L else 900L)
        state.index = (state.index + 1) % n
    }
    return state
}

@Composable
private fun RadarFailed(error: Throwable?, onRetry: () -> Unit) {
    RadarMessage(
        "Unable to load radar data",
        error?.message?.takeIf { it.isNotBlank() } ?: "Please check your internet connection or try again.",
    ) {
        VButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp), variant = ButtonVariant.Outline, size = ButtonSize.Sm) {
            VIcon(RemixIcons.ResetLeftLine, size = 14.dp)
            VText("Retry")
        }
    }
}
