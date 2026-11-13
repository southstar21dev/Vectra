package dev.southstar21.vectra.features.transit

import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.data.LiveQuery
import dev.southstar21.vectra.core.data.QueryState
import dev.southstar21.vectra.core.designsystem.components.DecorativeLoopFps
import dev.southstar21.vectra.core.designsystem.components.rememberLoop
import dev.southstar21.vectra.core.designsystem.components.rememberOnScreen
import dev.southstar21.vectra.core.designsystem.components.rememberPageClock
import dev.southstar21.vectra.core.designsystem.components.trackOnScreen
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.LocalContentColor
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraColors
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.time.nowMs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Radius

// ---- colours ---------------------------------------------------------------

/** `#rrggbb` / `rrggbb` / `rrggbbaa` (with or without hash) → Color; null when unparsable. */
fun parseHexColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    val v = when (h.length) {
        6 -> h.toLongOrNull(16)?.let { 0xFF000000 or it }
        8 -> h.toLongOrNull(16)?.let { ((it and 0xFF) shl 24) or (it shr 8) }
        3 -> h.map { "$it$it" }.joinToString("").toLongOrNull(16)?.let { 0xFF000000 or it }
        else -> null
    } ?: return null
    return Color(v)
}

/** Departure-board display ink: bright amber = live, dim amber = timetable. */
@Immutable
data class MonitorInk(
    val live: Color,
    val sched: Color,
    val text: Color,
    val muted: Color,
    val rule: Color,
    val pressed: Color,
    val chip: Color,
)

fun monitorInk(c: VectraColors) = MonitorInk(
    live = if (c.isDark) Color(0xFFFFD24A) else Palette.Amber700,
    sched = if (c.isDark) Color(0xFFA08A4A) else Color(0xFF8A6D3B),
    text = c.textPrimary,
    muted = c.textSecondary,
    rule = c.borderSubtle,
    pressed = c.surfaceOverlay,
    chip = c.surfaceOverlay,
)

val Vectra.monitor: MonitorInk
    @Composable get() = monitorInk(colors)

// ---- clocks & queries -------------------------------------------------------

/** The current epoch-millis time, re-read every [intervalMs] (the holidays card has an [Instant] variant). */
@Composable
fun rememberNowMillis(intervalMs: Long = 1000): Long = rememberPageClock(intervalMs, ::nowMs)

@Composable
fun rememberOnline(connectivity: Connectivity): Boolean = connectivity.online.collectAsStateWithLifecycle().value

/** Observe one query key (null = disabled). */
@Composable
fun <K, T> LiveQuery<K, T>.collect(key: K?): QueryState<T> {
    val flow = remember(this, key) { if (key == null) flowOf(QueryState()) else observe(key) }
    return flow.collectAsStateWithLifecycle(initialValue = if (key == null) QueryState() else peek(key)).value
}

/** Arrivals for several stops: one shared entry per stop; the network side batches. */
@Composable
fun rememberMultiStopArrivals(repo: TransitRepository, stopIds: List<String?>, limit: Int? = null, enabled: Boolean = true): Map<String, StopArrivalsEntry> {
    val ids = remember(stopIds) { stopIds.filterNotNull().filter { it.isNotEmpty() }.distinct() }
    val flow: Flow<Map<String, QueryState<FutarArrivalsResponse>>> = remember(repo, ids, limit, enabled) {
        if (!enabled) flowOf(emptyMap())
        else if (limit != null) {
            combine(ids.map { id -> repo.deepArrivals.observe(id to limit) }.ifEmpty { listOf(flowOf(QueryState())) }) { arr ->
                if (ids.isEmpty()) emptyMap() else ids.zip(arr.toList()).toMap()
            }
        } else repo.arrivals.observeMany(ids)
    }
    val states = flow.collectAsStateWithLifecycle(initialValue = emptyMap()).value
    return remember(ids, states) { ids.associateWith { (states[it] ?: QueryState()).toEntry() } }
}

/** Arrivals for several stops, all from one proxy request. */
@Composable
fun rememberBatchStopArrivals(repo: TransitRepository, stopIds: List<String?>): Map<String, StopArrivalsEntry> {
    val ids = remember(stopIds) { stopIds.filterNotNull().filter { it.isNotEmpty() }.distinct() }
    val key = remember(ids) { ids.sorted().joinToString(",") }
    val q = repo.batchArrivals.collect(key.ifEmpty { null })
    return remember(ids, q) {
        val loading = q.isLoading || q.isFetching
        ids.associateWith { id ->
            StopArrivalsEntry(
                data = q.data?.responses?.get(id),
                isLoading = loading,
                isError = q.data?.errors?.containsKey(id) == true || (q.isError && q.data?.responses?.get(id) == null),
                dataUpdatedAt = q.dataUpdatedAt,
            )
        }
    }
}

private val SpacedSlash = Regex("""\s*/\s*""")

// ---- route badge -------------------------------------------------------------

/** [Xs2] and [Xs] are round (the strips' vehicle-sized marks); [Tag] and [TagMd] are the same heights as rounded rectangles. */
enum class RouteBadgeSize { Xs2, Xs, Tag, TagMd, Sm, Md, Lg }

/**
 * Route chip with adaptive sizing — long names shrink their
 * font and truncate so they never overflow the badge box.
 */
@Composable
fun RouteBadge(
    name: String,
    color: String?,
    textColor: String?,
    modifier: Modifier = Modifier,
    size: RouteBadgeSize = RouteBadgeSize.Md,
) {
    val c = Vectra.colors
    val formatted = name.replace(SpacedSlash, "/")
    val n = formatted.length
    val (minW, h, padX, radius) = when (size) {
        RouteBadgeSize.Xs2 -> Quad(24.dp, 24.dp, 6.dp, 999.dp)
        RouteBadgeSize.Xs -> Quad(28.dp, 28.dp, 4.dp, 999.dp)
        RouteBadgeSize.Tag -> Quad(24.dp, 22.dp, 5.dp, 6.dp)
        RouteBadgeSize.TagMd -> Quad(28.dp, 26.dp, 5.dp, 7.dp)
        RouteBadgeSize.Sm -> Quad(32.dp, 32.dp, 6.dp, 8.dp)
        RouteBadgeSize.Lg -> Quad(48.dp, 48.dp, 8.dp, 12.dp)
        RouteBadgeSize.Md -> Quad(36.dp, 36.dp, 6.dp, 8.dp)
    }
    val font: TextUnit = when {
        n <= 3 -> when (size) {
            RouteBadgeSize.Xs2 -> 8.sp; RouteBadgeSize.Xs -> 10.sp; RouteBadgeSize.Sm -> 12.sp
            RouteBadgeSize.Tag -> 10.sp; RouteBadgeSize.TagMd -> 11.sp
            RouteBadgeSize.Lg -> 18.sp; RouteBadgeSize.Md -> 14.sp
        }
        n <= 5 -> when (size) {
            RouteBadgeSize.Xs2 -> 7.5.sp; RouteBadgeSize.Xs -> 9.sp; RouteBadgeSize.Sm -> 10.sp
            RouteBadgeSize.Tag -> 9.sp; RouteBadgeSize.TagMd -> 10.sp
            RouteBadgeSize.Lg -> 14.sp; RouteBadgeSize.Md -> 11.sp
        }
        else -> when (size) {
            RouteBadgeSize.Xs2, RouteBadgeSize.Xs -> 7.sp; RouteBadgeSize.Sm -> 9.sp
            RouteBadgeSize.Tag, RouteBadgeSize.TagMd -> 8.sp
            RouteBadgeSize.Lg -> 12.sp; RouteBadgeSize.Md -> 10.sp
        }
    }
    val display = if (n > 12) formatted.take(10) + "…" else formatted
    val bg = parseHexColor(color?.takeIf { it.isNotEmpty() })
    val ink = if (bg != null) parseHexColor(textColor?.takeIf { it.isNotEmpty() } ?: "000000") ?: Color.Black else c.textPrimary
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .boxShadow(shape, listOf(BoxShadow(y = 1f, blur = 2f, color = Color.Black.copy(alpha = 0.05f))))
            .background(bg ?: c.surfaceOverlay, shape)
            .border(1.dp, if (c.isDark) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.15f), shape)
            .defaultMinSize(minWidth = minW)
            .height(h)
            .padding(horizontal = padX),
        contentAlignment = Alignment.Center,
    ) {
        VText(display, style = vtext(font to font * 1.25f, Weight.Black, mono = true), color = ink, maxLines = 1, softWrap = false)
    }
}

private data class Quad(val a: Dp, val b: Dp, val c: Dp, val d: Dp)

// ---- small pieces --------------------------------------------------------------

private val WifiPaths = listOf("M12 20h.01", "M8.5 16.429a5 5 0 0 1 7 0", "M5 12.859a10 10 0 0 1 14 0", "M2 8.82a15 15 0 0 1 20 0")
private val WifiStreakDelaysMs = floatArrayOf(0f, 150f, 450f, 750f)
private val WifiStroke = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/**
 * Live-signal indicator: the wifi glyph rotated 45° whose streaks pulse outward
 * (wifi-streak 1.8s, staggered .15/.45/.75s). Static under reduced motion.
 */
@Composable
fun LiveSignalIcon(modifier: Modifier = Modifier, size: Dp = 12.dp, tint: Color = LocalContentColor.current, animate: Boolean = true) {
    val paths = remember { WifiPaths.map { PathParser().parsePathString(it).toPath() } }
    val reduce = Vectra.reduceMotion
    val onScreen = rememberOnScreen()
    val loop = rememberLoop(1800, enabled = animate && !reduce && onScreen.value, maxFps = DecorativeLoopFps)
    // Its own layer: without one, every tick re-records the whole card around the icon.
    Canvas(modifier.size(size).trackOnScreen(onScreen).graphicsLayer { }) {
        val clock = loop.value * 1800f
        val s = this.size.minDimension / 24f
        rotate(45f) {
            scale(s, s, Offset.Zero) {
                paths.forEachIndexed { i, p ->
                    val alpha = when {
                        i == 0 -> 0.7f
                        !animate || reduce -> 1f
                        else -> {
                            val delayMs = WifiStreakDelaysMs[i]
                            val phase = (((clock - delayMs) % 1800f) + 1800f) % 1800f / 1800f
                            // 0%,100% → .25 ; 50% → 1 with ease-in-out.
                            val tri = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                            val eased = tri * tri * (3f - 2f * tri)
                            0.25f + 0.75f * eased
                        }
                    }
                    drawPath(p, tint.copy(alpha = tint.alpha * alpha), style = WifiStroke)
                }
            }
        }
    }
}

/**
 * Titles with several termini ('|' separated)
 * render with a bidirectional arrow between the parts.
 */
@Composable
fun FormattedRouteTitle(
    title: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = vtext(TypeScale.Body),
    color: Color = Color.Unspecified,
    iconSize: Dp = 14.dp,
    iconTint: Color = Vectra.colors.textMuted.copy(alpha = 0.6f),
) {
    if (title.isNullOrEmpty()) return
    val parts = splitRouteTitle(title)
    if (parts.size <= 1) {
        VText(parts.firstOrNull() ?: title, modifier, style, color, maxLines = 1)
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, part ->
            if (i > 0) VIcon(RemixIcons.ArrowLeftRightLine, Modifier.padding(horizontal = 6.dp), size = iconSize, tint = iconTint)
            VText(part, Modifier.weight(1f, fill = false), style, color, maxLines = 1)
        }
    }
}

/** The transit card surface: glass, 14dp radius. */
val MonitorShape = Radius.Row

@Composable
fun Modifier.monitorSurface(): Modifier = glass(MonitorShape)
