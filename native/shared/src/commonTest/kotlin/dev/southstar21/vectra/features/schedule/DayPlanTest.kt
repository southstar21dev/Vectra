package dev.southstar21.vectra.features.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun cls(id: String, start: String, end: String, name: String = id, cancelled: Boolean = false, eventType: String = "class") =
    DayEntry.Class(scheduleEvent(id = id, courseName = name, startTime = start, endTime = end, eventType = eventType), minuteOf(start)!!, minuteOf(end)!!, cancelled)

private fun item(id: String, start: String, end: String, type: String = "shift", title: String = id): DayEntry.Item {
    val occ = ScheduleItemOccurrence(shift(id = id, type = type, title = title, startTime = start, endTime = end), "2026-09-07", start, end, false)
    return DayEntry.Item(occ, minuteOf(start)!!, minuteOf(end)!!)
}

private fun DayPlan.shape(): List<String> = rows.map {
    when (it) {
        is DayRow.Break -> "break ${it.start}-${it.end} ${it.state}"
        is DayRow.Entry -> it.entry.title
    }
}

/** The example day from the mockup: 08:00 Calculus, 09:45 Data Structures, 12:30 Micro, 16:00 shift. */
private val Day = listOf(
    cls("calc", "08:00", "09:30"),
    cls("ds", "09:45", "11:15"),
    cls("micro", "12:30", "14:00"),
    item("cafe", "16:00", "20:00"),
)

class DayPlanTest {
    @Test fun minuteOfParsesClockTimes() {
        assertEquals(0, minuteOf("00:00"))
        assertEquals(9 * 60 + 45, minuteOf("09:45:00"))
        assertEquals(1440, minuteOf("24:00"))
        assertNull(minuteOf("24:30"))
        assertNull(minuteOf("noon"))
        assertNull(minuteOf(null))
    }

    @Test fun anotherDayShowsEverythingWithLaterBreaks() {
        val plan = buildDayPlan(Day, nowMinute = null, collapseFinished = true)
        assertTrue(plan.finished.isEmpty())
        assertEquals(
            listOf("calc", "break 570-585 Later", "ds", "break 675-750 Later", "micro", "break 840-960 Later", "cafe"),
            plan.shape(),
        )
        assertNull(plan.focusKey)
    }

    @Test fun beforeTheFirstClassTheFirstIsFocusedAndTheFirstBreakIsNext() {
        val plan = buildDayPlan(Day, nowMinute = 7 * 60 + 20, collapseFinished = true)
        assertTrue(plan.finished.isEmpty())
        assertEquals("break 570-585 Next", plan.shape()[1])
        assertEquals(Day[0].key, plan.focusKey)
    }

    @Test fun finishedClassesFoldIntoTheStackAndTheirBreaksGoWithThem() {
        // 10:20: Calculus is over, Data Structures is on.
        val plan = buildDayPlan(Day, nowMinute = 10 * 60 + 20, collapseFinished = true)
        assertEquals(listOf("calc"), plan.finished.map { it.title })
        assertEquals(listOf("ds", "break 675-750 Next", "micro", "break 840-960 Later", "cafe"), plan.shape())
        assertEquals(Day[1].key, plan.focusKey)
    }

    @Test fun duringABreakItIsNowAndTheNextClassIsFocused() {
        val plan = buildDayPlan(Day, nowMinute = 11 * 60 + 40, collapseFinished = true)
        assertEquals(listOf("calc", "ds"), plan.finished.map { it.title })
        // The break opens from the last finished class, above the next card.
        assertEquals(listOf("break 675-750 Now", "micro", "break 840-960 Later", "cafe"), plan.shape())
        assertEquals(Day[2].key, plan.focusKey)
    }

    @Test fun withoutCollapsingPastEntriesStayInTheList() {
        val plan = buildDayPlan(Day, nowMinute = 11 * 60 + 40, collapseFinished = false)
        assertTrue(plan.finished.isEmpty())
        assertEquals("break 675-750 Now", plan.shape()[3])
    }

    @Test fun theDayIsOverOnceEverythingHasEnded() {
        val plan = buildDayPlan(Day, nowMinute = 20 * 60 + 30, collapseFinished = true)
        assertTrue(plan.dayIsOver)
        assertTrue(plan.rows.isEmpty())
        assertEquals(4, plan.entryCount)
        assertFalse(buildDayPlan(Day, nowMinute = 19 * 60, collapseFinished = true).dayIsOver)
    }

    @Test fun gapsUnderFiveMinutesGetNoStrip() {
        val plan = buildDayPlan(listOf(cls("a", "08:00", "09:30"), cls("b", "09:34", "10:00"), cls("c", "10:05", "11:00")), null, false)
        assertEquals(listOf("a", "b", "break 600-605 Later", "c"), plan.shape())
    }

    @Test fun aCancelledClassMergesTheBreakAroundIt() {
        val day = listOf(cls("ds", "09:45", "11:15"), cls("micro", "12:30", "14:00", cancelled = true), item("cafe", "16:00", "20:00"))
        val plan = buildDayPlan(day, nowMinute = 9 * 60 + 50, collapseFinished = true)
        // One strip from 11:15 to 16:00, placed before the cancelled card that sits in its own slot.
        assertEquals(listOf("ds", "break 675-960 Next", "micro", "cafe"), plan.shape())
        assertTrue(plan.overlaps.isEmpty())
    }

    @Test fun onlyTheLaterOfTwoClashingEntriesIsMarked() {
        val day = listOf(cls("calc", "08:00", "09:30"), cls("ds", "09:30", "11:00"), cls("micro", "10:30", "12:00", name = "Microeconomics"))
        val plan = buildDayPlan(day, null, false)
        assertEquals(setOf(day[2].key), plan.overlaps.keys)
        assertEquals(Overlap("ds", 630, 660), plan.overlaps[day[2].key])
        // Back to back: no strip; the overlap opens no break either.
        assertEquals(listOf("calc", "ds", "Microeconomics"), plan.shape())
    }

    @Test fun tasksNeverClash() {
        val day = listOf(cls("calc", "08:00", "09:30"), item("report", "08:00", "23:59", type = "task"))
        assertTrue(buildDayPlan(day, null, false).overlaps.isEmpty())
    }

    @Test fun examsAndCancelledClassesAreNeverFocused() {
        val day = listOf(cls("exam", "09:00", "11:00", eventType = "exam"), cls("x", "11:00", "12:00", cancelled = true), cls("y", "13:00", "14:00"))
        assertEquals(day[2].key, buildDayPlan(day, 8 * 60, true).focusKey)
    }

    @Test fun durationsReadAsWords() {
        assertEquals("5 min", durationLabel(5))
        assertEquals("1 h 15 min", durationLabel(75))
        assertEquals("2 h", durationLabel(120))
    }

    @Test fun timeLeftTurnsAmberThenRed() {
        assertEquals(TimeLeftTone.Plenty, timeLeftTone(31))
        assertEquals(TimeLeftTone.Soon, timeLeftTone(30))
        assertEquals(TimeLeftTone.Soon, timeLeftTone(11))
        assertEquals(TimeLeftTone.Urgent, timeLeftTone(10))
    }

    @Test fun summaryOfAFinishedDay() {
        assertEquals("4 classes and a shift, all checked in", daySummary(4, 1, 0, 4, 4))
        assertEquals("2 classes, 2 shifts and a task, 1 of 2 checked in", daySummary(2, 2, 1, 1, 2))
        assertEquals("1 class, checked in", daySummary(1, 0, 0, 1, 1))
        assertEquals("A shift", daySummary(0, 1, 0, 0, 0))
    }

    @Test fun nextStartSkipsEmptyDays() {
        // 2026-09-12 is a Saturday; the Monday class is the next thing on.
        val monday = scheduleEvent(id = "m", dayOfWeek = 1, startTime = "08:00", endTime = "09:30")
        assertEquals("Monday" to "08:00", nextScheduledStart(listOf(monday), emptyList(), "2026-09-12"))
        // From Sunday it is tomorrow.
        assertEquals("tomorrow" to "08:00", nextScheduledStart(listOf(monday), emptyList(), "2026-09-13"))
        assertNull(nextScheduledStart(emptyList(), emptyList(), "2026-09-13"))
    }

    @Test fun statusCycleWrapsAround() {
        var s = AttendanceStatus.Unmarked
        val seen = mutableListOf<AttendanceStatus>()
        repeat(5) { s = nextStatus(s); seen += s }
        assertEquals(
            listOf(AttendanceStatus.Present, AttendanceStatus.Absent, AttendanceStatus.Excused, AttendanceStatus.Cancelled, AttendanceStatus.Unmarked),
            seen,
        )
    }
}
