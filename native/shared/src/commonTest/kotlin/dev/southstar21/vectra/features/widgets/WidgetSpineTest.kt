package dev.southstar21.vectra.features.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WidgetSpineTest {
    private val hour = 60 * MinuteMs
    private val today = "2026-11-10"
    private val midnight = 1_000 * 24 * hour

    private fun at(h: Double) = midnight + (h * hour).toLong()

    private fun session(title: String, from: Double, to: Double, kind: WidgetKind = WidgetKind.Lecture, day: String = today) =
        WidgetSession(title, "C.203", kind, at(from), at(to), day)

    private fun snapshot(vararg sessions: WidgetSession) =
        WidgetSnapshot(generatedAtMs = midnight, signedIn = true, sessions = sessions.toList())

    private val calc = session("Calculus II", 8.0, 9.5)
    private val ds = session("Data Structures", 9.5, 11.0)
    private val micro = session("Microeconomics", 12.5, 14.0, WidgetKind.Seminar)
    private val task = session("Problem set 3", 23.9, 23.95, WidgetKind.Task)

    @Test
    fun spineMarksPastNowNextAndBreaks() {
        val rows = snapshot(calc, ds, micro, task).spine(today, at(10.0))
        assertIs<SpineRow.Past>(rows[0])
        assertIs<SpineRow.Now>(rows[1])
        assertEquals(90, assertIs<SpineRow.Break>(rows[2]).minutes)
        assertIs<SpineRow.Next>(rows[3])
        assertEquals(4, rows.size)
    }

    @Test
    fun countsDoneClassesAndOpenTasks() {
        val snap = snapshot(calc, ds, micro, task)
        assertEquals(1 to 3, snap.doneOf(today, at(10.0)))
        assertEquals(listOf(task), snap.openTasks(today, at(10.0)))
    }

    @Test
    fun anEmptyDayShowsTheNextOne() {
        val tomorrow = session("Algorithms", 8.0 + 24, 9.5 + 24, day = "2026-11-11")
        assertEquals("2026-11-11", snapshot(tomorrow).dayShown(today))
        assertEquals(today, snapshot(calc).dayShown(today))
    }

    @Test
    fun ringLabelsScaleFromMinutesToDays() {
        assertEquals("55" to "MIN", ringLabel(55 * MinuteMs, "LEFT"))
        assertEquals("2:25" to "TO GO", ringLabel(145 * MinuteMs, "TO GO"))
        assertEquals("2" to "DAYS", ringLabel(40 * hour, "LEFT"))
    }

    @Test
    fun abbreviatesCourseNames() {
        assertEquals("DS", courseAbbrev("Data Structures"))
        assertEquals("MIC", courseAbbrev("Microeconomics"))
        assertEquals("CAL", courseAbbrev("Calculus II"))
        assertEquals("SL", courseAbbrev("Statistics Lab"))
        assertEquals("HE", courseAbbrev("History of Economics"))
    }

    @Test
    fun weekGridSpansAtLeastSixHours() {
        val grid = snapshot(ds).weekGrid(today, at(10.0)) { ms -> (((ms - midnight) % (24 * hour)) / MinuteMs).toInt() }
        assertEquals(9, grid.fromHour)
        assertEquals(15, grid.toHour)
        val tuesday = grid.columns.first { it.day.date == today }
        assertEquals(listOf(WeekBlock(WidgetKind.Lecture, 570, 660, "DS", false)), tuesday.blocks)
    }

    @Test
    fun agendaCutsDropFinishedClassesFirstThenTheLastOnes() {
        val rows = snapshot(calc, ds, micro).spine(today, at(10.0))
        val cuts = agendaCuts(rows)
        assertEquals(rows, cuts[0].shown)
        // Calculus (finished) goes first, then Microeconomics from the end with its break.
        assertEquals(listOf<SpineRow>(SpineRow.Now(ds), SpineRow.Break(90), SpineRow.Next(micro)), cuts[1].shown)
        assertEquals(listOf<SpineRow>(SpineRow.Now(ds)), cuts[2].shown)
        assertEquals(listOf(micro), cuts[2].hidden)
        assertEquals(3, cuts.size)
    }

    @Test
    fun aFinishedDayKeepsItsLastThreeClasses() {
        val late = session("Marketing", 16.0, 17.5, WidgetKind.Seminar)
        val cuts = agendaCuts(snapshot(calc, ds, micro, late).spine(today, at(20.0)))
        assertEquals(listOf(ds, micro, late), cuts[1].shown.mapNotNull { (it as? SpineRow.Past)?.session })
    }

    @Test
    fun laterDaysFollowTheDayAndLeaveFirst() {
        val tomorrow = session("Algorithms", 8.0 + 24, 9.5 + 24, day = "2026-11-11")
        val rows = snapshot(calc, ds, tomorrow).agendaRows(today, at(20.0))
        assertEquals(listOf<SpineRow>(SpineRow.Past(calc), SpineRow.Past(ds), SpineRow.Day("2026-11-11"), SpineRow.Next(tomorrow)), rows)
        val cuts = agendaCuts(rows)
        assertEquals(listOf<SpineRow>(SpineRow.Past(calc), SpineRow.Past(ds)), cuts[1].shown)
    }

    @Test
    fun laterDaysLeaveOneRowAtATime() {
        val a = session("Algorithms", 8.0 + 24, 9.5 + 24, day = "2026-11-11")
        val b = session("Marketing", 12.0 + 24, 13.5 + 24, WidgetKind.Seminar, day = "2026-11-11")
        val cuts = agendaCuts(snapshot(ds, a, b).agendaRows(today, at(10.0)))
        assertEquals(listOf<SpineRow>(SpineRow.Now(ds), SpineRow.Day("2026-11-11"), SpineRow.Next(a)), cuts[1].shown)
        assertEquals(listOf<SpineRow>(SpineRow.Now(ds)), cuts[2].shown)
    }
}
