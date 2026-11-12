package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.specularBorder
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.getDayName
import dev.southstar21.vectra.core.time.getShortDayName
import dev.southstar21.vectra.core.time.monthName
import dev.southstar21.vectra.core.time.ymd
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlinx.datetime.plus

/** "9 – 15 November", or "30 November – 6 December" across a month. */
internal fun weekTitle(monday: LocalDate): String {
    val sunday = monday.plus(DatePeriod(days = 6))
    return if (monday.month == sunday.month) "${monday.day} – ${sunday.day} ${monthName(sunday.month.number)}"
    else "${monday.day} ${monthName(monday.month.number)} – ${sunday.day} ${monthName(sunday.month.number)}"
}

private val DaySquare = RoundedCornerShape(11.dp)

/**
 * The Schedule tab's top switchers, straight on the page: the week between two round arrows
 * with its semester week under it, then All and the seven days. Each day is its name above a
 * small square holding the date; the pick is filled with the accent and today is underlined.
 */
@Composable
internal fun ScheduleToolbar(
    nav: WeekNavResult,
    selectedDay: Int?,
    todayStr: String,
    onDayChange: (Int?) -> Unit,
) {
    val c = Vectra.colors
    val d = rememberDayColors()
    val haptics = Vectra.haptics
    val monday = nav.activeWeek.monday
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundArrow(RemixIcons.ArrowLeftSLine, "Previous week", d, nav.onPrev)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                VText(weekTitle(monday), style = vtext(18.sp to 24.sp, Weight.Extrabold, (-0.01).em).tnum(), color = d.text, maxLines = 1)
                nav.academicWeekInfo?.let { week ->
                    val ink = if (week.isStarted) c.accentPrimary else d.secondary
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        VIcon(RemixIcons.GraduationCapLine, size = 14.dp, tint = ink)
                        VText(week.label, style = vtext(12.sp to 16.sp, Weight.Bold, mono = true), color = ink, maxLines = 1)
                    }
                }
            }
            RoundArrow(RemixIcons.ArrowRightSLine, "Next week", d, nav.onNext)
        }
        if (!nav.isAtDefaultWeek) {
            // Away from the default week: one way back.
            VText(
                if (nav.semesterInfo.status == "upcoming") "Back to semester start" else "Back to this week",
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .tappable(CircleShape, onClickLabel = null, haptic = { tick() }, onClick = nav.onReset)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                vtext(12.sp to 16.sp, Weight.Bold),
                c.accentPrimary,
                maxLines = 1,
            )
        }
        val options = remember { listOf<Int?>(null) + DayOrder }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { i, day ->
                val date = if (day == null) null else monday.plus(DatePeriod(days = i - 1))
                val on = day == selectedDay
                val today = date != null && date.ymd() == todayStr
                DayPick(
                    label = if (day == null) "All" else getShortDayName(day),
                    number = date?.day?.toString() ?: "•",
                    description = if (day == null) "Whole week" else "${getDayName(day)} ${date!!.day}",
                    selected = on,
                    today = today,
                    d = d,
                    modifier = Modifier.weight(1f),
                ) {
                    if (!on) haptics.tick()
                    onDayChange(day)
                }
            }
        }
    }
}

@Composable
private fun RoundArrow(icon: ImageVector, label: String, d: DayColors, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(interaction)
            .size(40.dp)
            .boxShadow(CircleShape, d.stripShadow)
            .background(d.strip, CircleShape)
            .specularBorder(CircleShape, d.border, d.borderTop)
            .tappable(CircleShape, onClickLabel = label, interactionSource = interaction, haptic = { tick() }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { VIcon(icon, size = 18.dp, tint = d.room, contentDescription = label) }
}

/** One day: its name above a small square with the date; the pick is accent-filled, today underlined. */
@Composable
private fun DayPick(
    label: String,
    number: String,
    description: String,
    selected: Boolean,
    today: Boolean,
    d: DayColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = Vectra.colors.accentPrimary
    Column(
        modifier
            .heightIn(min = 44.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description + if (today) ", today" else ""
                this.selected = selected
            }
            .tappable(RoundedCornerShape(12.dp), role = Role.Tab, highlight = false, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        VText(label, style = vtext(11.sp to 14.sp, Weight.Bold), color = if (selected) accent else d.muted, maxLines = 1)
        Box(
            Modifier
                .size(38.dp)
                .then(
                    if (selected) Modifier.background(accent, DaySquare).specularBorder(DaySquare, Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.25f))
                    else Modifier.boxShadow(DaySquare, d.stripShadow).background(d.strip, DaySquare).specularBorder(DaySquare, d.border, d.borderTop),
                ),
            contentAlignment = Alignment.Center,
        ) {
            VText(number, style = vtext(16.sp to 20.sp, Weight.Extrabold).tnum(), color = if (selected) Color.White else d.text, maxLines = 1)
        }
        Box(Modifier.size(14.dp, 2.dp).background(if (today) accent else Color.Transparent, RoundedCornerShape(1.dp)))
    }
}
