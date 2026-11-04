package dev.southstar21.vectra.features.holidays

import dev.southstar21.vectra.core.time.nowInBudapest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlin.time.Clock
import kotlin.time.Instant

data class Holiday(val month: Int, val day: Int, val name: String, val hungarian: String)

data class UpcomingHoliday(val holiday: Holiday, val daysUntil: Int)

/** Easter Sunday (Gauss algorithm) for the given year. */
internal fun easterSunday(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = ((h + l - 7 * m + 114) % 31) + 1
    return LocalDate(year, month, day)
}

/** Fixed-date Hungarian public holidays; Easter-based ones are computed per year. */
private val FixedHolidays = listOf(
    Holiday(1, 1, "New Year's Day", "Újév"),
    Holiday(3, 15, "Revolution of 1848", "Nemzeti ünnep"),
    Holiday(5, 1, "Labour Day", "A munka ünnepe"),
    Holiday(8, 20, "State Foundation Day", "Szent István ünnepe"),
    Holiday(10, 23, "Revolution of 1956", "Nemzeti ünnep"),
    Holiday(11, 1, "All Saints' Day", "Mindenszentek"),
    Holiday(12, 25, "Christmas Day", "Karácsony"),
    Holiday(12, 26, "Second Day of Christmas", "Karácsony másnapja"),
)

private fun easterBasedHolidays(year: Int): List<Holiday> {
    val easter = easterSunday(year)
    fun at(days: Int, name: String, hungarian: String): Holiday {
        val d = easter.plus(DatePeriod(days = days))
        return Holiday(d.month.number, d.day, name, hungarian)
    }
    return listOf(
        at(-2, "Good Friday", "Nagypéntek"),
        at(0, "Easter Sunday", "Húsvétvasárnap"),
        at(1, "Easter Monday", "Húsvéthétfő"),
        at(49, "Whit Sunday", "Pünkösdvasárnap"),
        at(50, "Whit Monday", "Pünkösdhétfő"),
    )
}

fun getHungarianHolidays(year: Int): List<Holiday> = FixedHolidays + easterBasedHolidays(year)

private val YmdPattern = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

/**
 * Whether a Budapest calendar date (YYYY-MM-DD) is a Hungarian public holiday
 * (munkaszüneti nap): no classes are held, so weekly classes skip it.
 */
fun isHungarianPublicHoliday(ymd: String): Boolean {
    val match = YmdPattern.matchEntire(ymd) ?: return false
    val (y, m, d) = match.destructured
    val month = m.toInt()
    val day = d.toInt()
    return getHungarianHolidays(y.toInt()).any { it.month == month && it.day == day }
}

/**
 * Every holiday within [maxDaysAhead] days, chronologically. "Today" is the
 * Budapest calendar date of [now], not the device's.
 */
fun getUpcomingHolidays(now: Instant = Clock.System.now(), maxDaysAhead: Int = 14): List<UpcomingHoliday> {
    val today = nowInBudapest(now).date
    return listOf(today.year, today.year + 1)
        .flatMap { year ->
            getHungarianHolidays(year).mapNotNull { h ->
                runCatching { LocalDate(year, h.month, h.day) }.getOrNull()?.let { h to it }
            }
        }
        .map { (h, date) -> Triple(h, date, today.daysUntil(date)) }
        .filter { it.third in 0..maxDaysAhead }
        .sortedBy { it.second }
        .map { UpcomingHoliday(it.first, it.third) }
}

/** Today's holiday (daysUntil = 0) or the nearest one within the window, else null. */
fun getNextHoliday(now: Instant = Clock.System.now(), maxDaysAhead: Int = 14): UpcomingHoliday? =
    getUpcomingHolidays(now, maxDaysAhead).firstOrNull()
