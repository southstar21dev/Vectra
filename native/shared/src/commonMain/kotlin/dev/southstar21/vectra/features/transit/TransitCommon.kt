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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.southstar21.vectra.core.designsystem.theme.parseHexColor
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.features.transit.data.FutarArrivalsResponse
import dev.southstar21.vectra.features.transit.data.StopArrivalsEntry
import dev.southstar21.vectra.features.transit.data.TransitRepository
import dev.southstar21.vectra.features.transit.data.toEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Radius

// ---- colours ---------------------------------------------------------------

/** Departure-board display ink: amber = live, grey = timetable. */
@Immutable
internal data class MonitorInk(
    val live: Color,
    val sched: Color,
    val text: Color,
    val muted: Color,
    val rule: Color,
    val pressed: Color,
    val chip: Color,
)

internal fun monitorInk(c: VectraColors) = MonitorInk(
    live = if (c.isDark) Color(0xFFFFD24A) else Palette.Amber700,
    sched = if (c.isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
    text = c.textPrimary,
    muted = c.textSecondary,
    rule = c.borderSubtle,
    pressed = c.surfaceOverlay,
    chip = c.surfaceOverlay,
)

internal val Vectra.monitor: MonitorInk
    @Composable get() = monitorInk(colors)

// ---- clocks & queries -------------------------------------------------------

/** The current epoch-millis time, re-read every [intervalMs] (the holidays card has an [Instant] variant). */
@Composable
internal fun rememberNowMillis(intervalMs: Long = 1000): Long = rememberPageClock(intervalMs, ::nowMs)

@Composable
internal fun rememberOnline(connectivity: Connectivity): Boolean = connectivity.online.collectAsStateWithLifecycle().value

/** Observe one query key (null = disabled). */
@Composable
internal fun <K, T> LiveQuery<K, T>.collect(key: K?): QueryState<T> {
    val flow = remember(this, key) { if (key == null) flowOf(QueryState()) else observe(key) }
    return flow.collectAsStateWithLifecycle(initialValue = if (key == null) QueryState() else peek(key)).value
}

/** Arrivals for several stops: one shared entry per stop; the network side batches. */
@Composable
internal fun rememberMultiStopArrivals(repo: TransitRepository, stopIds: List<String?>, limit: Int? = null, enabled: Boolean = true): Map<String, StopArrivalsEntry> {
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

/**
 * Arrivals for several stops, all from one proxy request. When the set of stops changes (a map
 * pans), the new request starts empty: each stop keeps its last arrivals until it lands, so stops
 * still in view don't blank out and pop back.
 */
@Composable
internal fun rememberBatchStopArrivals(repo: TransitRepository, stopIds: List<String?>): Map<String, StopArrivalsEntry> {
    val ids = remember(stopIds) { stopIds.filterNotNull().filter { it.isNotEmpty() }.distinct() }
    val key = remember(ids) { ids.sorted().joinToString(",") }
    val q = repo.batchArrivals.collect(key.ifEmpty { null })
    val last = remember { LastArrivals() }
    return remember(ids, q) {
        val loading = isBatchLoading(q, ids.isNotEmpty())
        q.data?.responses?.forEach { (id, data) -> last.put(id, data, q.dataUpdatedAt) }
        ids.associateWith { id ->
            val fresh = q.data?.responses?.get(id)
            val kept = if (fresh == null) last[id] else null
            StopArrivalsEntry(
                data = fresh ?: kept?.first,
                isLoading = loading,
                isError = q.data?.errors?.containsKey(id) == true || (q.isError && fresh == null && kept == null),
                dataUpdatedAt = if (fresh != null) q.dataUpdatedAt else kept?.second ?: 0L,
            )
        }
    }
}

/**
 * Whether a batch is still on its way. A key that was just asked for starts as an empty
 * [QueryState] (not loading, not fetching) until its first fetch begins, and without this
 * the lists read that gap as "nothing is running" and flash their empty state (audit A-20).
 */
internal fun isBatchLoading(q: QueryState<*>, hasStops: Boolean): Boolean =
    q.isLoading || q.isFetching || (hasStops && !q.isFetched && q.data == null && !q.isError)

/** The last arrivals seen per stop (with when they landed), the oldest dropped past [MaxKeptStops]. */
private class LastArrivals {
    private val map = LinkedHashMap<String, Pair<FutarArrivalsResponse, Long>>()

    operator fun get(id: String): Pair<FutarArrivalsResponse, Long>? = map[id]

    fun put(id: String, data: FutarArrivalsResponse, at: Long) {
        map.remove(id)
        map[id] = data to at
        while (map.size > MaxKeptStops) map.remove(map.keys.first())
    }
}

private const val MaxKeptStops = 60

private val SpacedSlash = Regex("""\s*/\s*""")

// ---- route badge -------------------------------------------------------------

/** [Xs2] and [Xs] are round (the strips' vehicle-sized marks); [Tag] and [TagMd] are the same heights as rounded rectangles. */
internal enum class RouteBadgeSize { Xs2, Xs, Tag, TagMd, Sm, Md, Lg }

/**
 * Route chip with adaptive sizing — long names shrink their
 * font and truncate so they never overflow the badge box.
 */
@Composable
internal fun RouteBadge(
    name: String,
    color: String?,
    textColor: String?,
    modifier: Modifier = Modifier,
    size: RouteBadgeSize = RouteBadgeSize.Md,
    /** A glyph instead of the name, for a badge that stands for a group rather than a line. */
    icon: ImageVector? = null,
) {
    val c = Vectra.colors
    val formatted = name.replace(SpacedSlash, "/")
    val n = formatted.length
    val spec = routeBadgeSpec(size)
    val font = spec.fontFor(n)
    val display = if (n > 12) formatted.take(10) + "…" else formatted
    val lineColor = parseHexColor(color?.takeIf { it.isNotEmpty() })
    // Night lines are black: a neutral outline instead, so they read on a dark card as well as a light one.
    val outline = lineColor != null && isNightLineColor(lineColor)
    val bg = lineColor.takeUnless { outline }
    val ink = when {
        bg != null -> parseHexColor(textColor?.takeIf { it.isNotEmpty() } ?: "000000") ?: Color.Black
        else -> c.textPrimary
    }
    val shape = RoundedCornerShape(spec.radius)
    Box(
        modifier
            .boxShadow(shape, listOf(BoxShadow(y = 1f, blur = 2f, color = Color.Black.copy(alpha = 0.05f))))
            .background(bg ?: if (outline) Color.Transparent else c.surfaceOverlay, shape)
            .border(
                if (outline) 1.5.dp else 1.dp,
                if (outline) c.textSecondary else if (c.isDark) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.15f),
                shape,
            )
            .defaultMinSize(minWidth = spec.minW)
            .height(spec.h)
            .padding(horizontal = spec.padX),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            VIcon(icon, size = spec.h * 0.55f, tint = ink)
        } else {
            VText(
                display,
                style = vtext(font to font * 1.15f, Weight.Extrabold).tnum().copy(letterSpacing = (-0.2).sp),
                color = ink,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** A line coloured (near) black: BKK's night lines. */
internal fun isNightLineColor(color: Color): Boolean = color.luminance() < 0.03f

private data class BadgeSpec(
    val minW: Dp,
    val h: Dp,
    val padX: Dp,
    val radius: Dp,
    val fontShort: TextUnit,
    val fontMid: TextUnit,
    val fontLong: TextUnit,
) {
    fun fontFor(length: Int): TextUnit = when {
        length <= 4 -> fontShort
        length <= 6 -> fontMid
        else -> fontLong
    }
}

private fun routeBadgeSpec(size: RouteBadgeSize): BadgeSpec = when (size) {
    RouteBadgeSize.Xs2 -> BadgeSpec(24.dp, 24.dp, 5.dp, 999.dp, 8.5.sp, 7.5.sp, 7.sp)
    RouteBadgeSize.Xs -> BadgeSpec(28.dp, 28.dp, 5.dp, 999.dp, 10.sp, 8.5.sp, 8.sp)
    RouteBadgeSize.Tag -> BadgeSpec(26.dp, 20.dp, 6.dp, 6.dp, 10.5.sp, 9.5.sp, 9.sp)
    RouteBadgeSize.TagMd -> BadgeSpec(32.dp, 24.dp, 7.dp, 7.dp, 11.5.sp, 10.5.sp, 9.5.sp)
    RouteBadgeSize.Sm -> BadgeSpec(40.dp, 28.dp, 8.dp, 8.dp, 13.sp, 12.sp, 11.sp)
    RouteBadgeSize.Md -> BadgeSpec(44.dp, 32.dp, 9.dp, 9.dp, 15.sp, 13.5.sp, 12.sp)
    RouteBadgeSize.Lg -> BadgeSpec(56.dp, 40.dp, 11.dp, 11.dp, 18.sp, 16.sp, 14.sp)
}

// ---- small pieces --------------------------------------------------------------

private val WifiPaths = listOf("M12 20h.01", "M8.5 16.429a5 5 0 0 1 7 0", "M5 12.859a10 10 0 0 1 14 0", "M2 8.82a15 15 0 0 1 20 0")
private val WifiStreakDelaysMs = floatArrayOf(0f, 150f, 450f, 750f)
private val WifiStroke = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/**
 * Live-signal indicator: the wifi glyph rotated 45° whose streaks pulse outward
 * (wifi-streak 1.8s, staggered .15/.45/.75s). Static under reduced motion.
 */
@Composable
internal fun LiveSignalIcon(modifier: Modifier = Modifier, size: Dp = 12.dp, tint: Color = LocalContentColor.current, animate: Boolean = true) {
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
internal fun FormattedRouteTitle(
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
internal val MonitorShape = Radius.Row

@Composable
internal fun Modifier.monitorSurface(): Modifier = glass(MonitorShape)
