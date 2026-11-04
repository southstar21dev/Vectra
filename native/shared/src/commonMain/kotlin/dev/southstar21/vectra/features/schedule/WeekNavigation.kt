package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.time.formatDDMMYYYY
import dev.southstar21.vectra.core.time.monthName
import dev.southstar21.vectra.core.time.nowInBudapest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

/*
 * Pure week-navigation and day-filter logic for the schedule. Everything works
 * on plain calendar dates (LocalDate), never instants, so time zones can't shift a day.
 */

data class MonthWeek(
    val index: Int,
    val monday: LocalDate,
    val startStr: String,
    val endStr: String,
    val rangeLabel: String,
    val isCurrentWeek: Boolean,
)

data class AcademicWeekInfo(val weekNumber: Int, val label: String, val isStarted: Boolean)

private fun LocalDate.mondayOfWeek(): LocalDate = minus(DatePeriod(days = dayOfWeek.isoDayNumber - 1))

/** Short month and day: "Oct 6". */
fun formatShortMonthDay(d: LocalDate): String = "${monthName(d.month.number).take(3)} ${d.day}"

/** All Monday–Sunday weeks overlapping the month of [targetDate]. */
fun getWeeksInMonth(targetDate: LocalDate, todayYmd: String = nowInBudapest().ymd): List<MonthWeek> {
    val first = LocalDate(targetDate.year, targetDate.month, 1)
    val last = first.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
    var mon = first.mondayOfWeek()
    val weeks = mutableListOf<MonthWeek>()
    var index = 0
    while (mon <= last || (index > 0 && mon.month == targetDate.month && mon.year == targetDate.year)) {
        val sun = mon.plus(DatePeriod(days = 6))
        val start = mon.toString()
        val end = sun.toString()
        weeks += MonthWeek(
            index, mon, start, end,
            "${formatShortMonthDay(mon)} – ${formatShortMonthDay(sun)}",
            todayYmd in start..end,
        )
        index++
        mon = mon.plus(DatePeriod(days = 7))
    }
    return weeks
}

private val YmdRe = Regex("""^\d{4}-\d{2}-\d{2}$""")

/** The semester week of [dateStr]: the week containing the start is Week 1, earlier weeks Pre-semester. */
fun getAcademicWeekInfo(dateStr: String, semesterStartStr: String?): AcademicWeekInfo? {
    if (semesterStartStr == null || !YmdRe.matches(semesterStartStr) || !YmdRe.matches(dateStr)) return null
    val start = runCatching { LocalDate.parse(semesterStartStr) }.getOrNull() ?: return null
    val target = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return null
    val diffDays = target.mondayOfWeek().toEpochDays() - start.mondayOfWeek().toEpochDays()
    val weekNumber = (if (diffDays >= 0) diffDays / 7 else -((-diffDays + 6) / 7)).toInt() + 1
    return if (weekNumber < 1) AcademicWeekInfo(weekNumber, "Pre-semester", false)
    else AcademicWeekInfo(weekNumber, "Week $weekNumber", true)
}

/** Upcoming/past semesters open on their start week (at offset 0); active ones on the current week. */
fun resolveDefaultWeekIndex(monthWeeks: List<MonthWeek>, semesterStatus: String, startDate: String?, navDateOffset: Int = 0): Int {
    if (monthWeeks.isEmpty()) return 0
    if ((semesterStatus == "upcoming" || semesterStatus == "past") && startDate != null && navDateOffset == 0) {
        val idx = monthWeeks.indexOfFirst { startDate >= it.startStr && startDate <= it.endStr }
        if (idx != -1) return idx
    }
    val current = monthWeeks.indexOfFirst { it.isCurrentWeek }
    return if (current != -1) current else 0
}

/** First week after (or last before) the week starting on [startStr], for cross-month moves. */
fun resolveAdjacentWeekIndex(monthWeeks: List<MonthWeek>, direction: String, startStr: String): Int {
    if (monthWeeks.isEmpty()) return 0
    if (direction == "after") {
        val idx = monthWeeks.indexOfFirst { it.startStr > startStr }
        return if (idx == -1) monthWeeks.size - 1 else idx
    }
    for (i in monthWeeks.indices.reversed()) if (monthWeeks[i].startStr < startStr) return i
    return 0
}

/** Keep the user's week across a midnight recompute unless the context changed. */
fun resolveWeekIndexAfterRecompute(contextChanged: Boolean, current: Int?, previousDefault: Int, nextDefault: Int): Int =
    if (contextChanged || current == null || current == previousDefault) nextDefault else current

/** Semester status + the base date the month navigation anchors on. */
data class SemesterStatus(val info: SemesterInfo, val baseYmd: String)

fun computeSemesterStatus(events: List<ScheduleEvent>, selectedSemester: String, todayStr: String): SemesterStatus {
    val relevant = if (selectedSemester == "all") events else events.filter { it.semester == selectedSemester }
    val starts = relevant.mapNotNull { it.semesterStart?.ifEmpty { null } }
    val ends = relevant.mapNotNull { it.semesterEnd?.ifEmpty { null } }
    if (starts.isEmpty()) return SemesterStatus(SemesterInfo("current", null, 0), todayStr)
    val minStart = starts.min()
    val maxEnd = if (ends.isNotEmpty()) ends.max() else minStart
    if (todayStr in minStart..maxEnd) return SemesterStatus(SemesterInfo("active", minStart, 0), todayStr)
    if (todayStr < minStart) {
        val days = runCatching { LocalDate.parse(minStart).toEpochDays() - LocalDate.parse(todayStr).toEpochDays() }.getOrDefault(1L)
        return SemesterStatus(SemesterInfo("upcoming", minStart, maxOf(1, days.toInt())), minStart)
    }
    return SemesterStatus(SemesterInfo("past", minStart, 0), minStart)
}

/** The week label, renamed "Intensive Week" when the pre-start week hosts an intensive block. */
fun computeAcademicWeekInfo(
    events: List<ScheduleEvent>,
    selectedSemester: String,
    semesterStartDate: String?,
    activeWeek: MonthWeek,
): AcademicWeekInfo? {
    if (semesterStartDate == null) return null
    val semesterEvents = if (selectedSemester == "all") events else events.filter { it.semester == selectedSemester }
    val officialStart = ScheduleCourses.officialSemesterStart(semesterEvents) ?: return null
    val info = getAcademicWeekInfo(activeWeek.monday.toString(), officialStart) ?: return null
    if (info.weekNumber == 0) {
        val keys = ScheduleCourses.intensiveCourseKeys(semesterEvents)
        val hasIntensive = semesterEvents.any { e ->
            val s = e.semesterStart
            s != null && s >= activeWeek.startStr && s <= activeWeek.endStr && e.scheduleCourseKey() in keys
        }
        if (hasIntensive) return info.copy(label = "Intensive Week")
    }
    return info
}

/** The month the navigation shows: always the 1st, offset from [baseYmd]. */
fun activeMonthFor(baseYmd: String, navDateOffset: Int): LocalDate {
    val base = LocalDate.parse(baseYmd)
    return LocalDate(base.year, base.month, 1).plus(DatePeriod(months = navDateOffset))
}

// ---- Day filters --------------------------------------------------------------

/** Mon … Sun as stored day indices (0 = Sunday). */
val DayOrder = listOf(1, 2, 3, 4, 5, 6, 0)

data class DayDateInfo(val formattedDate: String, val isToday: Boolean, val dateStr: String)

fun getFormattedDateForDay(dayOfWeek: Int, monday: LocalDate, todayYmd: String = nowInBudapest().ymd): DayDateInfo {
    val offset = (if (dayOfWeek == 0) 7 else dayOfWeek) - 1
    val target = monday.plus(DatePeriod(days = offset))
    val ymd = target.toString()
    return DayDateInfo(formatDDMMYYYY(ymd), todayYmd == ymd, ymd)
}

/** `selectedDay`: null = all days. Manual classes without a semester show in every semester. */
fun filterScheduleEvents(
    events: List<ScheduleEvent>,
    selectedSemester: String,
    selectedDay: Int?,
    weekStart: String,
    weekEnd: String,
): List<ScheduleEvent> = events.filter { e ->
    val matchesSemester = selectedSemester == "all" || e.semester == selectedSemester || e.semester.isEmpty()
    val matchesDay = selectedDay == null || e.dayOfWeek == selectedDay
    var inRange = true
    val s = e.semesterStart?.ifEmpty { null }
    val en = e.semesterEnd?.ifEmpty { null }
    if (s != null && weekEnd.isNotEmpty() && weekEnd < s) inRange = false
    if (en != null && weekStart.isNotEmpty() && weekStart > en) inRange = false
    matchesSemester && matchesDay && inRange
}

fun groupEventsByDay(events: List<ScheduleEvent>): Map<Int, List<ScheduleEvent>> =
    (0..6).associateWith { day -> events.filter { it.dayOfWeek == day }.sortedBy { it.startTime } }

/** The view's semester: the current one when it has events, else the newest, else 'all'. */
fun resolveSelectedSemester(semesters: List<String>, currentSemester: String?): String = when {
    currentSemester != null && currentSemester in semesters -> currentSemester
    semesters.isNotEmpty() -> semesters.last()
    else -> "all"
}
