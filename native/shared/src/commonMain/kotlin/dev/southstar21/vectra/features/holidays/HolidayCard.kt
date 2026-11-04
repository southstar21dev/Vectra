package dev.southstar21.vectra.features.holidays

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.rememberPageClock
import dev.southstar21.vectra.core.designsystem.components.StatusLabel
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.insetShadow
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.nowInBudapest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlin.time.Instant
import dev.southstar21.vectra.core.designsystem.theme.Radius

/**
 * A clock that re-reads the time every [intervalMs] so
 * countdowns and "today" roll over at midnight while the app is open.
 * Unlike the transit `rememberNowMillis`, it yields an [Instant] and ticks once a minute.
 */
@Composable
internal fun rememberNowInstant(intervalMs: Long = 60_000): Instant =
    Instant.fromEpochMilliseconds(rememberPageClock(intervalMs))

private val ShortWeekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val ShortMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Short holiday date: `Fri, Oct 23`. */
internal fun formatHolidayDate(date: LocalDate): String =
    "${ShortWeekdays[date.dayOfWeek.ordinal]}, ${ShortMonths[date.month.number - 1]} ${date.day}"

private enum class HolidayGlyph { Tree, Goblet, Cross, Crown, Flag, Bank }

private fun holidayGlyph(holiday: Holiday): HolidayGlyph {
    val name = holiday.name.lowercase()
    val hu = holiday.hungarian.lowercase()
    return when {
        "christmas" in name || "karácsony" in hu -> HolidayGlyph.Tree
        "new year" in name || "újév" in hu -> HolidayGlyph.Goblet
        listOf("easter", "friday", "whit", "pentecost", "saints").any { it in name } ||
            listOf("húsvét", "péntek", "pünkösd", "mindenszentek").any { it in hu } -> HolidayGlyph.Cross
        "foundation" in name || "istván" in hu -> HolidayGlyph.Crown
        listOf("revolution", "national", "flag").any { it in name } || "nemzeti" in hu -> HolidayGlyph.Flag
        else -> HolidayGlyph.Bank
    }
}

/** Upcoming Hungarian holidays within two weeks; renders nothing otherwise. */
@Composable
fun HolidayCard(modifier: Modifier = Modifier) {
    // Minute clock so the countdown rolls over at midnight while the app is open.
    val now = rememberNowInstant(60_000)
    val upcoming = remember(now) { getUpcomingHolidays(now) }
    if (upcoming.isEmpty()) return
    // The Budapest date the countdown is measured from (the device may be elsewhere).
    val today = nowInBudapest(now).date
    Column(modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (item in upcoming) {
            HolidayRow(item, today.plus(DatePeriod(days = item.daysUntil)))
        }
    }
}

@Composable
private fun HolidayRow(item: UpcomingHoliday, date: LocalDate) {
    val c = Vectra.colors
    val shape = Radius.Panel
    val holiday = item.holiday
    // Oct 23 only — 1956 flies the bare hole-flag, larger and without the chip.
    val isRevolution = holiday.month == 10 && holiday.day == 23
    Row(
        Modifier
            .fillMaxWidth()
            .boxShadow(shape, c.glassShadow)
            .background(c.glassBg, shape)
            .insetShadow(shape, c.glassShadow)
            .border(1.dp, c.borderSubtle, shape)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isRevolution) {
                RevolutionFlag(Modifier.size(40.dp, 26.67.dp))
            } else {
                val chip = Radius.Control
                Box(
                    Modifier
                        .size(32.dp)
                        .boxShadow(chip, listOf(BoxShadow(y = 1f, blur = 2f, color = rgba(0, 0, 0, 0.05f))))
                        .background(c.tints.amber.bg, chip)
                        .border(1.dp, c.tints.amber.border, chip),
                    contentAlignment = Alignment.Center,
                ) { HolidayIcon(holidayGlyph(holiday), c.tints.amber.text) }
            }
            Column(Modifier.weight(1f)) {
                VText(holiday.name, style = vtext(TypeScale.Body, Weight.Bold), color = c.textPrimary, maxLines = 1)
                VText(
                    formatHolidayDate(date),
                    Modifier.padding(top = 2.dp),
                    vtext(TypeScale.Caption, Weight.Medium),
                    c.textSecondary,
                    maxLines = 1,
                )
            }
        }
        Box(Modifier.padding(start = 8.dp), contentAlignment = Alignment.CenterEnd) {
            if (item.daysUntil == 0) {
                StatusLabel("Today", tone = StatusTone.Emerald)
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    VText(
                        item.daysUntil.toString(),
                        style = vtext(26.sp to 26.sp, Weight.Bold, Tracking.Tight).copy(fontFeatureSettings = "tnum"),
                        color = c.textPrimary,
                        align = TextAlign.End,
                    )
                    VText(
                        if (item.daysUntil == 1) "day to go" else "days to go",
                        Modifier.padding(top = 4.dp),
                        vtext(11.sp to 11.sp, Weight.Semibold),
                        c.textMuted,
                        align = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
private fun HolidayIcon(glyph: HolidayGlyph, tint: Color, size: Dp = 16.dp) {
    when (glyph) {
        HolidayGlyph.Tree -> VIcon(RemixIcons.TreeLine, size = size, tint = tint)
        HolidayGlyph.Goblet -> VIcon(RemixIcons.GobletLine, size = size, tint = tint)
        HolidayGlyph.Crown -> VIcon(RemixIcons.VipCrownLine, size = size, tint = tint)
        HolidayGlyph.Flag -> VIcon(RemixIcons.FlagLine, size = size, tint = tint)
        HolidayGlyph.Bank -> VIcon(RemixIcons.BankLine, size = size, tint = tint)
        HolidayGlyph.Cross -> ChristianCross(Modifier.size(size), tint)
    }
}

/** The inline cross: `M12 2v20M6.5 8h11`, stroke 2.25, round caps, 24-unit box. */
@Composable
private fun ChristianCross(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val u = size.width / 24f
        val path = Path().apply {
            moveTo(12f * u, 2f * u); lineTo(12f * u, 22f * u)
            moveTo(6.5f * u, 8f * u); lineTo(17.5f * u, 8f * u)
        }
        drawPath(path, tint, style = Stroke(2.25f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Oct 23, 1956 — the hole-flag: tricolour with the emblem cut out (18×12 box, rx 2). */
@Composable
private fun RevolutionFlag(modifier: Modifier) {
    Canvas(modifier) {
        scale(size.width / 18f, size.height / 12f, pivot = Offset.Zero) {
            val clip = Path().apply {
                addRoundRect(RoundRect(0f, 0f, 18f, 12f, CornerRadius(2f)))
            }
            clipPath(clip) {
                drawRect(Color(0xFFCD2A37), Offset(0f, 0f), Size(18f, 4f))
                drawRect(Color.White, Offset(0f, 4f), Size(18f, 4f))
                drawRect(Color(0xFF008751), Offset(0f, 8f), Size(18f, 4f))
                drawCircle(Color.White, 2.4f, Offset(9f, 6f))
                drawCircle(rgba(0, 0, 0, 0.25f), 2.4f, Offset(9f, 6f), style = Stroke(0.6f))
            }
        }
    }
}
