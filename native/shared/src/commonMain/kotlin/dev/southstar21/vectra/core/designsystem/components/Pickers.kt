package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.monthName
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.time.pad2
import dev.southstar21.vectra.core.time.parseHourMinute
import dev.southstar21.vectra.core.time.ymd
import dev.southstar21.vectra.core.preferences.TimeFormat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlin.math.abs
import dev.southstar21.vectra.core.designsystem.theme.Radius

private val WeekdayInitials = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
private val WeekdayShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Form-height field that shows a value and opens a picker sheet. */
@Composable
fun PickerField(text: String, placeholder: Boolean, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val shape = Radius.Control
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = SheetInputHeight)
            .glass(shape, hairlineInLight = true)
            .tappable(shape, pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VText(text, Modifier.weight(1f), vtext(TypeScale.BodyLarge), if (placeholder) c.textMuted else c.textPrimary, maxLines = 1)
        VIcon(icon, tint = c.textMuted)
    }
}

/** A date as `yyyy-MM-dd` (or empty), picked from a month calendar in a sheet. */
@Composable
fun DateField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Pick a date",
    onClear: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(value) }.getOrNull()
    PickerField(date?.let(::formatLongDate) ?: placeholder, date == null, RemixIcons.CalendarLine, { open = true }, modifier)
    DatePickerSheet(
        open = open,
        selected = date,
        onDismiss = { open = false },
        onPick = { onChange(it.ymd()); open = false },
        onClear = onClear?.let { clear -> { clear(); open = false } },
    )
}

/** "Fri, 30 Oct 2026". */
fun formatLongDate(date: LocalDate): String =
    "${WeekdayShort[date.dayOfWeek.isoDayNumber - 1]}, ${date.day} ${monthName(date.month.number).take(3)} ${date.year}"

/** Month calendar (Monday first). Swipe sideways or use the arrows to change month. */
@Composable
fun DatePickerSheet(
    open: Boolean,
    selected: LocalDate?,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val c = Vectra.colors
    val haptics = Vectra.haptics
    val today = nowInBudapest().date
    var month by remember(open) { mutableStateOf((selected ?: today).let { LocalDate(it.year, it.month, 1) }) }
    val swipeThreshold = with(LocalDensity.current) { 56.dp.toPx() }
    fun shift(months: Int) {
        haptics.tick()
        month = if (months > 0) month.plus(DatePeriod(months = months)) else month.minus(DatePeriod(months = -months))
    }
    VSheet(open, onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = SurfaceGutter, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton(RemixIcons.ArrowLeftSLine, "Previous month", { shift(-1) })
                VText(
                    "${monthName(month.month.number)} ${month.year}",
                    Modifier.weight(1f),
                    vtext(TypeScale.BodyLarge, Weight.Bold),
                    c.textPrimary,
                    align = TextAlign.Center,
                )
                BarButton(RemixIcons.ArrowRightSLine, "Next month", { shift(1) })
            }
            Row(Modifier.fillMaxWidth()) {
                WeekdayInitials.forEach {
                    VText(it, Modifier.weight(1f), vtext(TypeScale.Caption, Weight.Semibold), c.textMuted, align = TextAlign.Center)
                }
            }
            val lead = month.dayOfWeek.isoDayNumber - 1
            val start = month.minus(DatePeriod(days = lead))
            Column(
                Modifier.pointerInput(Unit) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = { if (abs(dx) > swipeThreshold) shift(if (dx < 0) 1 else -1) },
                    ) { _, amount -> dx += amount }
                },
            ) {
                for (week in 0 until 6) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        for (d in 0 until 7) {
                            val date = start.plus(DatePeriod(days = week * 7 + d))
                            val inMonth = date.month == month.month
                            val isSelected = date == selected
                            val isToday = date == today
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .then(if (isSelected) Modifier.background(c.accentPrimary, CircleShape) else Modifier)
                                    .then(if (isToday && !isSelected) Modifier.border(1.dp, c.accentPrimary, CircleShape) else Modifier)
                                    .tappable(CircleShape, role = Role.Button, haptic = { tick() }) { onPick(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                VText(
                                    date.day.toString(),
                                    style = vtext(TypeScale.Body, if (isSelected) Weight.Bold else Weight.Medium),
                                    color = when {
                                        isSelected -> Color.White
                                        inMonth -> c.textPrimary
                                        else -> c.textMuted.copy(alpha = 0.5f)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (onClear != null) VButton("Clear", onClear, variant = ButtonVariant.Ghost)
                VButton("Today", { onPick(today) }, variant = ButtonVariant.Outline)
            }
        }
    }
}

/** A time as `HH:mm` (or empty), picked on wheels in a sheet. Follows the app's 12/24h setting. */
@Composable
fun TimeField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, minuteStep: Int = 1) {
    var open by remember { mutableStateOf(false) }
    val parsed = parseHourMinute(value)?.takeIf { (h, m) -> h in 0..23 && m in 0..59 }
    val use12h = Vectra.appearance.timeFormat == TimeFormat.H12
    val text = parsed?.let { (h, m) -> formatHm(h, m, use12h) } ?: "--:--"
    PickerField(text, parsed == null, RemixIcons.TimeLine, { open = true }, modifier)
    TimePickerSheet(
        open = open,
        hour = parsed?.first ?: 9,
        minute = parsed?.second ?: 0,
        use12h = use12h,
        minuteStep = minuteStep,
        onDismiss = { open = false },
        onPick = { h, m -> onChange("${pad2(h)}:${pad2(m)}"); open = false },
    )
}

private fun formatHm(h: Int, m: Int, use12h: Boolean): String =
    if (!use12h) "${pad2(h)}:${pad2(m)}" else "${(h + 11) % 12 + 1}:${pad2(m)} ${if (h < 12) "AM" else "PM"}"

@Composable
fun TimePickerSheet(
    open: Boolean,
    hour: Int,
    minute: Int,
    use12h: Boolean,
    onDismiss: () -> Unit,
    onPick: (hour: Int, minute: Int) -> Unit,
    minuteStep: Int = 1,
    title: String = "Time",
) {
    val minutes = remember(minuteStep) { (0 until 60 step minuteStep).toList() }
    var h by remember(open) { mutableIntStateOf(hour) }
    var mIndex by remember(open) { mutableIntStateOf(minutes.indexOfLast { it <= minute }.coerceAtLeast(0)) }
    VSheet(open, onDismiss) {
        SheetHeader(title, onDismiss)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SurfaceGutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (use12h) {
                Wheel(12, (h + 11) % 12, { "${it + 1}" }, { i -> h = (i + 1) % 12 + if (h >= 12) 12 else 0 })
            } else {
                Wheel(24, h, { pad2(it) }, { h = it })
            }
            VText(":", Modifier.padding(horizontal = 8.dp), vtext(TypeScale.Headline, Weight.Bold), Vectra.colors.textMuted)
            Wheel(minutes.size, mIndex, { pad2(minutes[it]) }, { mIndex = it })
            if (use12h) {
                Box(Modifier.width(12.dp))
                Wheel(2, if (h < 12) 0 else 1, { if (it == 0) "AM" else "PM" }, { pm -> h = h % 12 + if (pm == 1) 12 else 0 })
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = SurfaceGutter, vertical = 12.dp)) {
            VButton("Done", { onPick(h, minutes[mIndex]) }, Modifier.fillMaxWidth(), size = ButtonSize.Lg)
        }
    }
}

private val WheelItemHeight = 44.dp
private const val WheelVisibleItems = 5

/** A snapping scroll wheel; the centred row is the value. Ticks as each row passes the centre. */
@Composable
private fun Wheel(count: Int, selected: Int, label: (Int) -> String, onSelected: (Int) -> Unit) {
    val c = Vectra.colors
    val haptics = Vectra.haptics
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, count - 1))
    val fling = rememberSnapFlingBehavior(state)
    val latest by rememberUpdatedState(onSelected)
    val pad = WheelItemHeight * (WheelVisibleItems / 2)
    val centred by remember {
        derivedStateOf { state.layoutInfo.visibleItemsInfo.minByOrNull { abs(it.offset) }?.index ?: selected }
    }
    LaunchedEffect(state) {
        snapshotFlow { centred }.distinctUntilChanged().drop(1).collect {
            haptics.frequentTick()
            latest(it)
        }
    }
    Box(Modifier.width(72.dp).height(WheelItemHeight * WheelVisibleItems), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(WheelItemHeight).background(c.surfaceOverlay, Radius.Control))
        LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = pad),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(count) { i ->
                val distance = abs(i - centred)
                Box(
                    Modifier.height(WheelItemHeight).fillMaxWidth().graphicsLayer { alpha = (1f - distance * 0.3f).coerceAtLeast(0.15f) },
                    contentAlignment = Alignment.Center,
                ) {
                    VText(
                        label(i),
                        style = vtext(TypeScale.Title, if (i == centred) Weight.Bold else Weight.Medium),
                        color = if (i == centred) c.textPrimary else c.textSecondary,
                    )
                }
            }
        }
    }
}
