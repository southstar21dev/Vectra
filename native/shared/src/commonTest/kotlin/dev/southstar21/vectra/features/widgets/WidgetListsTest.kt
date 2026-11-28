package dev.southstar21.vectra.features.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetListsTest {
    private val today = "2026-11-10"
    private val tomorrow = "2026-11-11"
    private val midnight = 1_000 * 24 * HourMs

    // 2026-11-10 07:00 in Budapest: the day the snapshot was written, so it still covers `today`.
    private val generated = 1_794_290_400_000L

    private fun at(h: Double) = midnight + (h * HourMs).toLong()

    private fun session(title: String, from: Double, to: Double, kind: WidgetKind = WidgetKind.Lecture, day: String = today) =
        WidgetSession(title, "C.203", kind, at(from), at(to), day)

    private fun snapshot(vararg sessions: WidgetSession) =
        WidgetSnapshot(generatedAtMs = generated, signedIn = true, sessions = sessions.toList())

    private val done = session("Statistics", 8.0, 9.0)
    private val micro = session("Microeconomics", 10.0, 11.0)
    private val law = session("Business Law", 12.0, 13.0)
    private val nextDay = session("Accounting", 9.0 + 24, 10.0 + 24, day = tomorrow)

    @Test
    fun todayEntriesListWhatIsLeftThenLaterDaysUnderTheirName() {
        val entries = snapshot(done, micro, nextDay).todayEntries(today, at(9.5))
        assertEquals(micro, assertIs<TodayEntry.Session>(entries[0]).session)
        assertTrue(entries[1] is TodayEntry.Day)
        assertEquals(nextDay, assertIs<TodayEntry.Session>(entries[2]).session)
        assertEquals(3, entries.size)
    }

    @Test
    fun todayEntriesStopAddingDaysOnceFull() {
        val many = (0 until 9).map { session("C$it", 10.0 + it / 10.0, 11.0 + it / 10.0) }
        val entries = snapshot(*many.toTypedArray(), nextDay).todayEntries(today, at(9.0))
        assertEquals(9, entries.size)
    }

    @Test
    fun thenNoteNamesTheFirstHiddenSessionAndCountsTheRest() {
        assertNull(thenNote(emptyList()))
        val note = thenNote(listOf(micro, law))!!
        assertEquals("Then Microeconomics and 1 more", note.text)
        assertEquals(FootNoteType.Then, note.type)
    }

    @Test
    fun nextDayNoteNamesTheNextDay() {
        val note = snapshot(micro, nextDay).nextDayNote(today, today)!!
        assertEquals(FootNoteType.NextDay, note.type)
        assertTrue(note.text.endsWith("Accounting"))
        assertNull(snapshot(micro).nextDayNote(today, today))
    }

    @Test
    fun footNotesShowTheNextDayOnlyWhenNothingLaterFits() {
        val next = FootNote(FootNoteType.NextDay, null, "Tomorrow, Accounting", "09:00")
        val rows = listOf(SpineRow.Next(micro))
        assertEquals(listOf(next), footNotes(AgendaCut(rows, emptyList()), null, next))
        assertEquals(1, footNotes(AgendaCut(rows, listOf(law)), null, next).size)
    }

    @Test
    fun afterRowsContinueWithTheRestOfTheDayThenLaterDays() {
        val rows = snapshot(micro, law, nextDay).afterRows(micro, at(10.5))
        assertEquals(listOf("Business Law", "Accounting"), rows.map { it.title })
        assertTrue(rows[1].time.startsWith(weekdayLabel(tomorrow)))
    }
}
