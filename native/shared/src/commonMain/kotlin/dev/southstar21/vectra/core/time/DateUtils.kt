package dev.southstar21.vectra.core.time

import dev.southstar21.vectra.core.preferences.TimeFormat
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Schedule and transit data are Budapest
 * wall-clock, so "now"/"today" always resolve in Europe/Budapest regardless of
 * the device time zone.
 */
val Budapest: TimeZone = TimeZone.of("Europe/Budapest")

fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

data class BudapestNow(
    val year: Int,
    val month: Int,
    val day: Int,
    /** 0 = Sunday … 6 = Saturday (see [daysSinceSunday]). */
    val dayOfWeek: Int,
    val hours: Int,
    val minutes: Int,
    val ymd: String,
) {
    val date: LocalDate get() = LocalDate(year, month, day)
    val minuteOfDay: Int get() = hours * 60 + minutes
}

fun nowInBudapest(at: Instant = Clock.System.now()): BudapestNow {
    val t = at.toLocalDateTime(Budapest)
    return BudapestNow(t.year, t.month.number, t.day, t.dayOfWeek.daysSinceSunday, t.hour, t.minute, t.date.ymd())
}

/** Day index counted from Sunday: Sunday = 0 … Saturday = 6. */
val DayOfWeek.daysSinceSunday: Int get() = isoDayNumber % 7

fun pad2(n: Int) = n.toString().padStart(2, '0')

fun LocalDate.ymd(): String = "$year-${pad2(month.number)}-${pad2(day)}"

fun parseYmd(value: String?): LocalDate? =
    if (value == null || !isYmdString(value)) null else runCatching { LocalDate.parse(value) }.getOrNull()

/** "08:00:00" → "08:00" */
fun formatHHMM(time: String?): String {
    if (time.isNullOrEmpty()) return ""
    val parts = time.split(':')
    return if (parts.size >= 2) "${parts[0].padStart(2, '0')}:${parts[1].padStart(2, '0')}" else time
}

fun formatTime(time: String?, format: TimeFormat = TimeFormat.H24): String {
    if (time.isNullOrEmpty()) return ""
    val clean = formatHHMM(time)
    if (format == TimeFormat.H24) return clean
    val (h, m) = clean.split(':').map { it.toIntOrNull() ?: 0 }
    val period = if (h >= 12) "PM" else "AM"
    val display = (h % 12).let { if (it == 0) 12 else it }
    return "$display:${pad2(m)} $period"
}

private val DayNames = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

fun getDayName(dayOfWeek: Int): String = DayNames.getOrElse(dayOfWeek) { "" }
fun getShortDayName(dayOfWeek: Int): String = DayNames.getOrNull(dayOfWeek)?.take(3).orEmpty()

/** Budapest `HH:MM` (24h) for an epoch-millis timestamp. */
fun formatBudapestHm(ms: Long): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(Budapest)
    return "${pad2(t.hour)}:${pad2(t.minute)}"
}

/** Budapest wall clock honouring the 12h/24h preference ("8:05 AM" / "08:05"). */
fun formatClock(ms: Long, format: TimeFormat = TimeFormat.H24): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(Budapest)
    return if (format == TimeFormat.H24) "${pad2(t.hour)}:${pad2(t.minute)}"
    else "${(t.hour % 12).let { if (it == 0) 12 else it }}:${pad2(t.minute)} ${if (t.hour >= 12) "PM" else "AM"}"
}

fun formatDDMMYYYY(date: String?): String {
    if (date.isNullOrEmpty()) return ""
    if (isYmdString(date)) {
        val (y, m, d) = date.split('-')
        return "$d/$m/$y"
    }
    return runCatching { Instant.parse(date).toLocalDateTime(Budapest).date.let { "${pad2(it.day)}/${pad2(it.month.number)}/${it.year}" } }
        .getOrDefault(date)
}

private val MonthNames = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
fun monthName(month: Int): String = MonthNames.getOrElse(month - 1) { "" }

/** `12 October` from a YYYY-MM-DD date. */
fun formatDayMonth(ymd: String): String {
    val d = parseYmd(ymd) ?: return ymd
    return "${d.day} ${monthName(d.month.number)}"
}

fun isValidCalendarDate(value: String): Boolean = isYmdString(value) && runCatching { LocalDate.parse(value) }.isSuccess

private val YmdPattern = Regex("""^\d{4}-\d{2}-\d{2}$""")

fun isYmdString(value: String?): Boolean = value != null && YmdPattern.matches(value)

/** `y-m-d` with numeric parts; null for zero parts or an invalid date. */
fun parseIsoDate(iso: String): LocalDate? {
    val parts = iso.split('-')
    if (parts.size < 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    if (y == 0 || m == 0 || d == 0) return null
    return runCatching { LocalDate(y, m, d) }.getOrNull()
}

/** `HH:MM` from minutes since midnight; ≥ 1440 folds to `24:00`. */
fun formatMinuteOfDay(minute: Int): String {
    if (minute >= 1440) return "24:00"
    val n = minute.coerceAtLeast(0)
    return "${pad2(n / 60)}:${pad2(n % 60)}"
}

/** "HH:MM[:SS]" → hour and minute as written, unchecked, or null. */
fun parseHourMinute(time: String?): Pair<Int, Int>? {
    val parts = time?.split(':') ?: return null
    if (parts.size < 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return h to m
}

fun LocalDateTime.toBudapestInstant(): Instant = toInstant(Budapest)

/** Time-of-day greeting for [hour]. */
fun greeting(hour: Int = nowInBudapest().hours): String = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}
