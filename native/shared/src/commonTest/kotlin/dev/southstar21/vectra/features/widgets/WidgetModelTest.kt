package dev.southstar21.vectra.features.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WidgetModelTest {
    private val hour = 60 * MinuteMs

    // Tuesday 10 Nov 2026; times are hours since an arbitrary midnight, which is all the model compares.
    private val today = "2026-11-10"
    private val midnight = 1_000 * 24 * hour

    private fun at(h: Double) = midnight + (h * hour).toLong()

    private fun session(title: String, from: Double, to: Double, kind: WidgetKind = WidgetKind.Lecture, day: String = today) =
        WidgetSession(title, "C.203", kind, at(from), at(to), day)

    private fun snapshot(vararg sessions: WidgetSession) =
        WidgetSnapshot(generatedAtMs = midnight, signedIn = true, sessions = sessions.toList())

    private val stats = session("Business Statistics", 8.0, 9.5)
    private val micro = session("Microeconomics", 10.0, 11.5)
    private val task = session("Problem set 3", 14.0, 14.0 + 1.0 / 60, WidgetKind.Task)
    private val exam = session("Accounting midterm", 14.0 + 48, 16.0 + 48, WidgetKind.Exam, day = "2026-11-12")

    @Test
    fun leadsWithTheClassInProgress() {
        val up = snapshot(stats, micro).nextUp(at(10.5), today)
        assertEquals(micro, assertIs<NextUp.InClass>(up).session)
    }

    @Test
    fun leadsWithTheNextClassBetweenClasses() {
        val up = snapshot(stats, micro).nextUp(at(9.6), today)
        assertEquals(micro, assertIs<NextUp.Upcoming>(up).session)
    }

    @Test
    fun tasksAreNeverNowOrNext() {
        val up = snapshot(stats, task).nextUp(at(13.0), today)
        assertIs<NextUp.DayDone>(up)
    }

    @Test
    fun anExamThisWeekTakesOverOnceTheDayIsDone() {
        val up = snapshot(stats, exam).nextUp(at(12.0), today)
        assertEquals(exam, assertIs<NextUp.ExamSoon>(up).exam)
    }

    @Test
    fun aFinishedDayPointsAtTheNextOne() {
        val tomorrow = session("Corporate Finance", 8.0 + 24, 9.5 + 24, WidgetKind.Seminar, day = "2026-11-11")
        val up = assertIs<NextUp.DayDone>(snapshot(stats, tomorrow).nextUp(at(12.0), today))
        assertEquals(true, up.hadToday)
        assertEquals(tomorrow, up.next)
    }

    @Test
    fun signedOutHasNoData() {
        val snap = snapshot(stats).copy(signedIn = false)
        assertIs<NextUp.NoData>(snap.nextUp(at(7.0), today))
        assertIs<NextUp.NoData>(null.nextUp(at(7.0), today))
    }

    @Test
    fun laterTodaySkipsTheFeaturedSessionAndWhatsOver() {
        val later = snapshot(stats, micro, task).laterOn(today, at(10.5), shown = micro)
        assertEquals(listOf(task), later)
    }

    @Test
    fun weekCoversMondayToFridayAndMarksToday() {
        val days = snapshot(stats, exam).week(today)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri"), days.map { it.weekday })
        assertEquals(listOf(false, true, false, false, false), days.map { it.isToday })
        assertEquals(listOf(WidgetKind.Exam), days.first { it.date == "2026-11-12" }.kinds)
        assertEquals(1, days.classCount())
    }

    @Test
    fun theWeekendLooksAheadToNextWeek() {
        val monday = session("Marketing", 8.0 + 6 * 24, 9.5 + 6 * 24, day = "2026-11-16")
        val days = snapshot(monday).week("2026-11-15")
        assertEquals("2026-11-16", days.first().date)
        assertEquals(listOf(WidgetKind.Lecture), days.first().kinds)
        assertEquals(false, showsNextWeek(today))
    }

    @Test
    fun aDayWithoutClassesFeaturesTheNextOne() {
        val tomorrow = session("Corporate Finance", 8.0 + 24, 9.5 + 24, WidgetKind.Seminar, day = "2026-11-11")
        assertEquals(tomorrow, snapshot(tomorrow).nextUp(at(9.0), today).featured)
    }

    @Test
    fun refreshesAtTheNextBoundaryOrMidnight() {
        val snap = snapshot(micro)
        assertEquals(at(10.0), snap.nextRefreshMs(at(9.99), midnightMs = at(24.0)))
        // Four hours out: nothing to count down yet, so the next redraw is the class itself.
        assertEquals(at(10.0), snap.nextRefreshMs(at(6.0), midnightMs = at(24.0)))
        assertEquals(at(24.0), snap.nextRefreshMs(at(12.0), midnightMs = at(24.0)))
        // Counting down: every five minutes.
        assertEquals(at(9.0) + 5 * MinuteMs, snap.nextRefreshMs(at(9.0), midnightMs = at(24.0)))
    }

    @Test
    fun labels() {
        assertEquals("in 25 min", countdownLabel(at(9.0), at(9.0) + 25 * MinuteMs))
        assertEquals("in 1 h 40 min", countdownLabel(at(9.0), at(9.0) + 100 * MinuteMs))
        assertEquals("1 h 10 min left", remainingLabel(at(10.0), at(10.0) + 70 * MinuteMs))
        assertEquals("45 min left", remainingLabel(at(10.0), at(10.0) + 45 * MinuteMs))
        assertEquals("Tomorrow", dayDistanceLabel(today, "2026-11-11"))
        assertEquals("In 2 days", dayDistanceLabel(today, "2026-11-12"))
        assertEquals("Tue 10 Nov", dayHeading(today))
        assertNull(breakMinutes(stats, session("x", 9.6, 10.0)))
        assertEquals(30, breakMinutes(stats, session("x", 10.0, 11.0)))
    }

    @Test
    fun snapshotRoundTrips() {
        val snap = snapshot(stats, exam)
        assertEquals(snap, decodeWidgetSnapshot(encodeWidgetSnapshot(snap)))
        assertNull(decodeWidgetSnapshot("{\"version\":99}"))
        assertNull(decodeWidgetSnapshot(""))
    }
}
