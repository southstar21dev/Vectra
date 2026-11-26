package dev.southstar21.vectra.features.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetBudgetTest {
    private val midnight = 1_000 * 24 * HourMs

    private fun at(h: Double) = midnight + (h * HourMs).toLong()

    private fun session(title: String, from: Double, to: Double, kind: WidgetKind = WidgetKind.Lecture) =
        WidgetSession(title, "C.203", kind, at(from), at(to), "2026-11-10")

    @Test
    fun linesGrowWithTheFontScale() {
        val title = "Introduction to Microeconomics and Public Policy"
        assertEquals(2, Budget(1.0f).lines(title, 14f, 200f, max = 2))
        assertEquals(2, Budget(1.3f).lines(title, 14f, 200f, max = 2))
        assertEquals(1, Budget(1.0f).lines("Statistics", 14f, 200f, max = 2))
        assertTrue(Budget(1.3f).line(14f) > Budget(1.0f).line(14f))
    }

    @Test
    fun linesNeverExceedTheMaximumOrDropBelowOne() {
        val b = Budget(1.3f)
        assertEquals(2, b.lines("x".repeat(500), 14f, 120f, max = 2))
        assertEquals(1, b.lines("", 14f, 120f))
        assertEquals(1, b.lines("Long title", 14f, 0f, max = 1))
    }

    @Test
    fun fitRowsCountsWhatFitsWholeAndStopsAtTheFirstThatDoesNot() {
        assertEquals(2, fitRows(listOf(10f, 10f, 10f), 25f))
        assertEquals(3, fitRows(listOf(10f, 10f, 10f), 30f))
        assertEquals(0, fitRows(listOf(10f), 5f))
        assertEquals(0, fitRows(emptyList(), 5f))
    }

    @Test
    fun nameWidthIsWhatTheRowLayoutLeaves() {
        // 12 + 15 padding, then 40 + 9 + 11 + 9 of columns and gaps.
        assertEquals(360f - 27f - 69f, WidgetGeometry.todayNameWidth(360f))
        assertEquals(170f - 21f, WidgetGeometry.todayRowSpace(170f))
        // A later day's label sits at the node gap, as on iOS (DayWidgets.swift pads it 60).
        assertEquals(60f, WidgetGeometry.TodayDayLabelOffset)
        // 16 + 18 padding; a row spends 72 before its name.
        assertEquals(360f - 34f, WidgetGeometry.agendaRowWidth(360f))
        assertEquals(360f - 34f - 72f, WidgetGeometry.agendaNameWidth(WidgetGeometry.agendaRowWidth(360f)))
    }

    @Test
    fun aLiveRowIsTallerThanARowToCome() {
        val b = Budget(1.0f)
        val s = session("Microeconomics", 10.0, 11.0)
        val width = WidgetGeometry.todayNameWidth(360f)
        assertTrue(todayRowHeight(b, s, at(10.5), width) > todayRowHeight(b, s, at(9.0), width))
    }

    @Test
    fun aLargerFontScaleNeverFitsMoreRows() {
        val sessions = (0 until 6).map { session("Introduction to Microeconomics $it", 8.0 + it, 8.5 + it) }
        val width = WidgetGeometry.todayNameWidth(360f)
        fun fit(scale: Float): Int {
            val b = Budget(scale)
            val heights = sessions.map { todayRowHeight(b, it, at(7.0), width) + WidgetGeometry.TodayRowGap }
            return fitRows(heights, WidgetGeometry.todayRowSpace(170f))
        }
        assertTrue(fit(1.3f) <= fit(1.0f))
        assertTrue(fit(1.0f) in 1..sessions.size)
    }

    @Test
    fun pickCutShowsEverythingWhenThereIsRoom() {
        val rows = listOf(SpineRow.Next(session("Microeconomics", 10.0, 11.0)), SpineRow.Next(session("Statistics", 12.0, 13.0)))
        val cuts = agendaCuts(rows)
        val pick = pickCut(cuts, null, null, Budget(1.0f), WidgetGeometry.agendaRowWidth(360f), 600f)
        assertEquals(cuts.first().shown, pick.cut.shown)
        assertEquals(false, pick.compact)
    }

    @Test
    fun pickCutFitsTheSpaceAtBothFontScales() {
        val sessions = (0 until 6).map { session("Introduction to Microeconomics $it", 10.0 + it, 10.5 + it) }
        val cuts = agendaCuts(sessions.map { SpineRow.Next(it) })
        val width = WidgetGeometry.agendaRowWidth(360f)
        for (scale in listOf(1.0f, 1.3f)) {
            val pick = pickCut(cuts, null, null, Budget(scale), width, 220f)
            assertTrue(pick.heights.sum() <= 220f || pick.cut === cuts.last(), "scale $scale")
            assertEquals(pick.cut.shown.size, pick.heights.size)
        }
        val roomy = pickCut(cuts, null, null, Budget(1.0f), width, 220f)
        val large = pickCut(cuts, null, null, Budget(1.3f), width, 220f)
        assertTrue(large.cut.shown.size <= roomy.cut.shown.size)
    }
}
