package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.specularBorder
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.preferences.TimeFormat
import dev.southstar21.vectra.core.time.daysSinceSunday
import dev.southstar21.vectra.core.time.formatDayMonth
import dev.southstar21.vectra.core.time.getDayName
import dev.southstar21.vectra.core.time.parseYmd
import dev.southstar21.vectra.core.time.ymd
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** A break between cards; the one you're in fills up and shows the time left. */
@Composable
internal fun BreakStrip(row: DayRow.Break, nowMinute: Int?, d: DayColors, timeFormat: TimeFormat) {
    val accent = Vectra.colors.accentPrimary
    val now = row.state == BreakState.Now && nowMinute != null
    val left = if (now) row.end - nowMinute else 0
    val fraction = if (now) ((nowMinute - row.start).toFloat() / row.minutes).coerceIn(0f, 1f) else 0f
    val (rightText, rightColor) = when (row.state) {
        BreakState.Now -> "${durationLabel(left)} left" to d.timeLeft(timeLeftTone(left))
        BreakState.Next -> durationLabel(row.minutes) to d.text
        BreakState.Later -> durationLabel(row.minutes) to d.secondary
    }
    val edge = if (now) d.nowBorder else d.border
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .boxShadow(StripShape, d.stripShadow)
            .background(d.strip, StripShape)
            .specularBorder(StripShape, edge, edge)
            .clip(StripShape)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.CenterStart,
    ) {
        if (fraction > 0f) {
            // The elapsed part of the break: a neutral fill growing from the left.
            Box(
                Modifier.matchParentSize().drawBehind {
                    val w = size.width * fraction
                    drawRect(d.nowFill, size = Size(w, size.height))
                    drawLine(d.nowEdge, Offset(w, 0f), Offset(w, size.height), 1.dp.toPx())
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VText(if (now) "Break now" else "Break", Modifier.weight(1f), vtext(13.sp to 18.sp, Weight.Bold), accent, maxLines = 1)
            VText(rangeLabel(row.start, row.end, timeFormat), style = vtext(12.sp to 16.sp, Weight.Bold, mono = true).tnum(), color = d.room, maxLines = 1)
            VText(rightText, Modifier.weight(1f), vtext(13.sp to 18.sp, Weight.Semibold), rightColor, align = TextAlign.End, maxLines = 1)
        }
    }
}

@Composable
internal fun DayDoneCard(summary: String, d: DayColors) {
    Row(
        Modifier
            .fillMaxWidth()
            .boxShadow(CardShape, d.cardShadow)
            .background(d.card, CardShape)
            .specularBorder(CardShape, d.border, d.borderTop)
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DoneDisc()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            VText("All done for today", style = vtext(18.sp to 24.sp, Weight.Extrabold, (-0.01).em), color = d.text)
            VText(summary, style = vtext(13.sp to 18.sp, Weight.Medium), color = d.secondary)
        }
    }
}

/** "Nothing scheduled" with when things start again ([backOn] is e.g. "Monday" or "tomorrow"). */
@Composable
fun NothingScheduledCard(backOn: String?, backAt: String?, modifier: Modifier = Modifier) {
    val d = rememberDayColors()
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .boxShadow(shape, d.cardShadow)
            .background(d.card, shape)
            .specularBorder(shape, d.border, d.borderTop)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        VText("Nothing scheduled", style = vtext(20.sp to 26.sp, Weight.Extrabold, (-0.01).em), color = d.text, align = TextAlign.Center)
        if (backOn != null && backAt != null) {
            val text = buildAnnotatedString {
                append(if (backOn == "tomorrow") "Back tomorrow at " else "Back on $backOn at ")
                withStyle(SpanStyle(fontFamily = Vectra.fonts.mono, fontWeight = Weight.Bold, color = d.room)) { append(backAt) }
            }
            VText(text, style = vtext(13.sp to 18.sp), color = d.secondary, align = TextAlign.Center)
        }
    }
}

/** First load with nothing cached: two cards in the real shape, no shimmer. */
@Composable
fun DayListLoading(modifier: Modifier = Modifier) {
    val d = rememberDayColors()
    Column(modifier.fillMaxWidth().semantics { contentDescription = "Loading today's schedule" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlaceholderCard(d)
        Box(Modifier.fillMaxWidth().height(44.dp).background(d.placeholderStrip, StripShape))
        PlaceholderCard(d)
    }
}

@Composable
private fun PlaceholderCard(d: DayColors) {
    val bar = RoundedCornerShape(6.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(d.card, CardShape)
            .specularBorder(CardShape, d.border, d.border)
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(64.dp, 10.dp).background(d.placeholder, bar))
                Box(Modifier.fillMaxWidth(0.7f).height(16.dp).background(d.placeholder, bar))
                Box(Modifier.fillMaxWidth(0.45f).height(12.dp).background(d.placeholder, bar))
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(44.dp, 14.dp).background(d.placeholder, bar))
                Box(Modifier.size(44.dp, 14.dp).background(d.placeholder, bar))
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxWidth(0.55f).height(12.dp).background(d.placeholder, bar))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(36.dp).background(d.placeholder, RoundedCornerShape(50)))
        }
    }
}

/** A day's title ("Today", "Monday") with its date on the right, above the day's cards. */
@Composable
fun DayListHeader(title: String, date: String, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp).semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        VText(title, Modifier.alignByBaseline(), vtext(24.sp to 30.sp, Weight.Extrabold, (-0.02).em), c.textPrimary, maxLines = 1)
        VText(date, Modifier.alignByBaseline(), vtext(13.sp to 18.sp, Weight.Medium).tnum(), c.textSecondary, maxLines = 1)
    }
}

/** One-line wrap-up of a finished day: "4 classes and a shift, all checked in". */
internal fun daySummary(classes: Int, shifts: Int, tasks: Int, checkedIn: Int, trackable: Int): String {
    fun count(n: Int, one: String, many: String, article: String) = if (n == 1) "$article $one" else "$n $many"
    val parts = buildList {
        if (classes > 0) add(if (classes == 1) "1 class" else "$classes classes")
        if (shifts > 0) add(count(shifts, "shift", "shifts", "a"))
        if (tasks > 0) add(count(tasks, "task", "tasks", "a"))
    }
    val what = when (parts.size) {
        0 -> "Nothing left"
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }.replaceFirstChar { it.uppercase() }
    return when {
        trackable == 0 -> what
        checkedIn == trackable -> if (trackable == 1) "$what, checked in" else "$what, all checked in"
        else -> "$what, $checkedIn of $trackable checked in"
    }
}

/**
 * When things start again after an empty day: ("tomorrow" or a weekday name, "HH:mm") for the
 * first class or item in the next two weeks, or null when there is none.
 */
fun nextScheduledStart(events: List<ScheduleEvent>, items: List<ScheduleItem>, today: String): Pair<String, String>? {
    val start = parseYmd(today) ?: return null
    val index = ScheduleEventIndex(events)
    return (1..14).asSequence().mapNotNull { offset -> firstStartOn(start.plus(DatePeriod(days = offset)), offset, events, items, index) }.firstOrNull()
}

/** The first start on [date] as ("tomorrow" / weekday name, "HH:mm"), or null when nothing is on. */
private fun firstStartOn(date: LocalDate, offset: Int, events: List<ScheduleEvent>, items: List<ScheduleItem>, index: ScheduleEventIndex): Pair<String, String>? {
    val ymd = date.ymd()
    val dow = date.dayOfWeek.daysSinceSunday
    val classTimes = events.filter { it.dayOfWeek == dow && classOccursOnDate(it, ymd, index) }.mapNotNull { e -> minuteOf(e.startTime)?.let { it to e.startTime } }
    val itemTimes = getScheduleItemOccurrencesForDate(items, ymd).filter { !it.continuation }.mapNotNull { o -> minuteOf(o.startTime)?.let { it to o.startTime } }
    val first = (classTimes + itemTimes).minByOrNull { it.first } ?: return null
    val day = when {
        offset == 1 -> "tomorrow"
        offset < 7 -> getDayName(dow)
        else -> "${getDayName(dow)} ${formatDayMonth(ymd)}"
    }
    return day to first.second
}
