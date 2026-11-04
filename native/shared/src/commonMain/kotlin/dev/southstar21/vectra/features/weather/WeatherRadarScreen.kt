package dev.southstar21.vectra.features.weather

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.Spinner
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.BarButton
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.insetShadow
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.OpenFreeMapAttribution
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import dev.southstar21.vectra.core.designsystem.theme.consumeTouches
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** The radar's bottom controls stay grouped on wide windows. */
private val RadarControlsMaxWidth = 576.dp

/** Full-bleed live radar with a frame badge, playback controls and a precipitation legend. */
@Composable
fun WeatherRadarScreen(repo: WeatherRepository, onBack: () -> Unit) {
    val location by repo.location.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        RadarContent(repo, location?.coords ?: BudapestCoords, onBack)
    }
}

@Composable
private fun ColumnScope.RadarContent(repo: WeatherRepository, coords: WeatherCoords, onBack: () -> Unit) {
    val c = Vectra.colors
    val radar by repo.radar.collectAsStateWithLifecycle()
    var frameIndex by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var mapReady by remember { mutableStateOf(false) }
    // Snap to the live frame once per visit, not on every background refetch.
    var snapToLive by remember { mutableStateOf(true) }
    var prevData by remember { mutableStateOf<NormalizedRadarData?>(null) }

    LaunchedEffect(Unit) {
        mapReady = false
        snapToLive = true
        repo.loadRadar()
        while (true) {
            delay(WeatherRepository.RadarRefetchMs)
            repo.loadRadar(force = true)
        }
    }

    val data = radar.data
    val frames = data?.radarFrames.orEmpty()
    // Start on the latest past frame; a refetch keeps the frame on screen.
    if (data !== prevData || (snapToLive && data != null && frames.isNotEmpty())) {
        val previousFrame = prevData?.radarFrames?.getOrNull(frameIndex)
        prevData = data
        if (frames.isNotEmpty() && data != null) {
            if (snapToLive) {
                val latest = frames.indexOfFirst { it.time == data.latestPastTime }
                frameIndex = if (latest >= 0) latest else frames.lastIndex
                snapToLive = false
            } else {
                val same = previousFrame?.let { p -> frames.indexOfFirst { it.time == p.time } } ?: -1
                frameIndex = if (same >= 0) same else minOf(frameIndex, frames.lastIndex)
            }
        }
    }

    // Playback: 900 ms per frame, a longer hold on the live frame.
    LaunchedEffect(playing, frameIndex, frames.size) {
        if (!playing || frames.isEmpty()) return@LaunchedEffect
        delay(if (frameIndex == frames.lastIndex) 2200L else 900L)
        frameIndex = (frameIndex + 1) % frames.size
    }

    val currentFrame = frames.getOrNull(frameIndex)
    val label = if (currentFrame != null && data != null) formatRadarFrameLabel(currentFrame, data.latestPastTime) else null
    val isLoading = data == null && radar.error == null
    val isError = radar.error != null

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(c.surfaceSecondary),
    ) {
        val safeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val safeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        when {
            isLoading -> Unit
            isError || data == null -> RadarMessage(
                "Unable to load radar data",
                radar.error?.message?.takeIf { it.isNotBlank() } ?: "Please check your internet connection or try again.",
            ) {
                VButton(
                    onClick = { repo.loadRadar(force = true) },
                    modifier = Modifier.padding(top = 8.dp),
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Sm,
                ) {
                    VIcon(RemixIcons.ResetLeftLine, size = 14.dp)
                    VText("Retry")
                }
            }
            frames.isEmpty() -> RadarMessage(
                "No radar frames available",
                "Radar frames are currently updating. Please check back in a moment.",
            )
            else -> {
                WeatherRadarMap(
                    http = repo.httpClient,
                    center = coords,
                    activeFrame = currentFrame,
                    host = data.host,
                    modifier = Modifier.fillMaxSize(),
                    onReady = { mapReady = true },
                )
                if (label != null) {
                    FrameBadge(label, Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = max(12.dp, safeTop + 8.dp)))
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = max(12.dp, safeBottom))
                        .widthIn(max = RadarControlsMaxWidth)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    VText(
                        "$OpenFreeMapAttribution | $RainViewerAttribution",
                        style = vtext(9.sp to 11.25.sp, mono = true).copy(
                            shadow = Shadow(c.surfacePrimary.copy(alpha = 0.85f), Offset(0f, 1f), 2f),
                        ),
                        color = c.textMuted,
                        align = TextAlign.Center,
                    )
                    PlaybackPanel(
                        playing = playing,
                        onTogglePlay = { playing = !playing },
                        frameIndex = frameIndex,
                        frameCount = frames.size,
                        onScrub = { playing = false; frameIndex = it },
                    )
                }
            }
        }
        LoadingCover(
            show = isLoading || (!isError && frames.isNotEmpty() && !mapReady),
            label = "Loading live radar…",
        )
        BarButton(
            RemixIcons.ArrowLeftLine,
            "Back",
            onBack,
            Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = safeTop + 8.dp),
        )
    }
}

@Composable
private fun RadarMessage(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    val c = Vectra.colors
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VIcon(RemixIcons.CloudyLine, size = 40.dp, tint = c.textMuted)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VText(title, style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary, align = TextAlign.Center)
            VText(body, Modifier.padding(top = 4.dp).widthIn(max = 320.dp), vtext(TypeScale.Caption), c.textSecondary, align = TextAlign.Center)
        }
        action?.invoke()
    }
}

@Composable
private fun FrameBadge(label: RadarFrameLabel, modifier: Modifier) {
    val c = Vectra.colors
    val shape = CircleShape
    val isNow = label.relative == "Now"
    val shadows = listOf(
        BoxShadow(y = 10f, blur = 15f, spread = -3f, color = rgba(0, 0, 0, 0.1f)),
        BoxShadow(y = 4f, blur = 6f, spread = -4f, color = rgba(0, 0, 0, 0.1f)),
    )
    Row(
        modifier
            .boxShadow(shape, shadows)
            .background(c.surfacePrimary.copy(alpha = 0.85f), shape)
            .insetShadow(shape, c.glassShadow)
            .border(1.dp, c.borderSubtle, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) {
            if (isNow) {
                val reduce = Vectra.reduceMotion
                val ping = rememberInfiniteTransition(label = "nowPing")
                val t by ping.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)), RepeatMode.Restart), label = "t")
                Box(
                    Modifier.size(8.dp)
                        .graphicsLayer { if (!reduce) { val s = 1f + t; scaleX = s; scaleY = s; alpha = 1f - t } }
                        .background(Palette.Emerald500, CircleShape),
                )
            } else {
                Box(Modifier.size(8.dp).background(c.tints.blue.bg, CircleShape))
            }
        }
        VText(label.timeStr, style = vtext(TypeScale.Caption, Weight.Bold, mono = true), color = c.textPrimary)
        VText("(${label.relative})", style = vtext(TypeScale.Caption, Weight.Medium), color = c.textSecondary)
    }
}

@Composable
private fun PlaybackPanel(
    playing: Boolean,
    onTogglePlay: () -> Unit,
    frameIndex: Int,
    frameCount: Int,
    onScrub: (Int) -> Unit,
) {
    val c = Vectra.colors
    val shape = Radius.Panel
    val shadow2xl = listOf(BoxShadow(y = 25f, blur = 50f, spread = -12f, color = rgba(0, 0, 0, 0.25f)))
    Column(
        Modifier
            .fillMaxWidth()
            .boxShadow(shape, shadow2xl)
            .background(c.surfaceSecondary, shape)
            .insetShadow(shape, c.glassShadow)
            .border(1.dp, c.borderSubtle, shape)
            .consumeTouches()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .pressScale(interaction)
                    .size(32.dp)
                    .boxShadow(CircleShape, listOf(BoxShadow(y = 1f, blur = 2f, color = rgba(0, 0, 0, 0.05f))))
                    .background(c.surfaceSecondary, CircleShape)
                    .border(1.dp, c.borderSubtle, CircleShape)
                    .tappable(role = null, highlight = false, interactionSource = interaction, onClick = onTogglePlay)
                    .semantics { contentDescription = if (playing) "Pause radar animation" else "Play radar animation" },
                contentAlignment = Alignment.Center,
            ) {
                if (playing) VIcon(RemixIcons.PauseFill, size = 14.dp, tint = c.tints.amber.text)
                else VIcon(RemixIcons.PlayFill, Modifier.padding(start = 2.dp), size = 14.dp, tint = c.tints.amber.text)
            }
            RadarScrubber(frameIndex, frameCount, onScrub, Modifier.weight(1f).padding(horizontal = 4.dp))
        }
        Legend()
    }
}

/** The frame scrubber: an 8dp overlay track with an amber thumb. */
@Composable
private fun RadarScrubber(value: Int, count: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    val c = Vectra.colors
    val accent = c.tints.amber.text
    val max = (count - 1).coerceAtLeast(0)
    val fraction by animateFloatAsState(if (max == 0) 0f else value.toFloat() / max, tween(120), label = "scrub")
    fun pick(x: Float, width: Float) {
        if (max == 0 || width <= 0f) return
        val thumb = 16f
        val f = ((x - thumb / 2f) / (width - thumb)).coerceIn(0f, 1f)
        onChange((f * max).roundToInt())
    }
    Box(
        modifier
            .height(24.dp)
            .semantics { contentDescription = "Radar timeline frame selector" }
            .pointerInput(max) {
                detectTapGestures { pick(it.x / density, size.width / density) }
            }
            .pointerInput(max) {
                detectDragGestures { change, _ -> change.consume(); pick(change.position.x / density, size.width / density) }
            }
            .drawBehind {
                val trackH = 8.dp.toPx()
                val top = (size.height - trackH) / 2f
                drawRoundRect(c.surfaceOverlay, Offset(0f, top), Size(size.width, trackH), CornerRadius(8.dp.toPx()))
                val r = 8.dp.toPx()
                val x = r + (size.width - 2 * r) * fraction
                drawCircle(accent, r, Offset(x, size.height / 2f))
            },
    )
}

@Composable
private fun Legend() {
    val c = Vectra.colors
    val line = c.borderSubtle.copy(alpha = c.borderSubtle.alpha * 0.4f)
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText("-2h", style = vtext(10.sp to 14.sp, Weight.Medium, mono = true), color = c.textMuted)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            LegendItem("Rain", Palette.Emerald400, 36, listOf(Color(0xFF15803D), Color(0xFF22C55E), Color(0xFFEAB308), Palette.Orange500, Palette.Red500))
            LegendItem("Mix", Palette.Pink400, 28, listOf(Palette.Pink400, Color(0xFFBE185D)))
            LegendItem("Snow", Palette.Sky400, 28, listOf(Palette.Sky400, Color(0xFF1D4ED8)))
        }
        VText("Now", style = vtext(10.sp to 14.sp, Weight.Medium, mono = true), color = c.textMuted, align = TextAlign.End)
    }
}

@Composable
private fun LegendItem(label: String, ink: Color, width: Int, stops: List<Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        VText(label, style = vtext(10.sp to 14.sp, Weight.Semibold), color = ink)
        Box(
            Modifier
                .width(width.dp)
                .height(6.dp)
                .boxShadow(CircleShape, listOf(BoxShadow(y = 1f, blur = 2f, color = rgba(0, 0, 0, 0.05f))))
                .background(Brush.horizontalGradient(stops), CircleShape),
        )
    }
}

/**
 * An opaque cover that fades out over the loaded content;
 * released after [maxDurationMs] even if readiness never arrives.
 */
@Composable
internal fun LoadingCover(show: Boolean, label: String = "Loading…", maxDurationMs: Long = 3000) {
    val c = Vectra.colors
    var expired by remember(show) { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) {
            delay(maxDurationMs)
            expired = true
        }
    }
    val visible = show && !expired
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(300), label = "cover")
    if (alpha <= 0f) return
    Column(
        Modifier.fillMaxSize().alpha(alpha).background(c.surfaceSecondary),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spinner()
        VText(label, style = vtext(TypeScale.Caption, Weight.Medium), color = c.textSecondary)
    }
}
