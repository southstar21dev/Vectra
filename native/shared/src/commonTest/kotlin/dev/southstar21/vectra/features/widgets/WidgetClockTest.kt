package dev.southstar21.vectra.features.widgets

import dev.southstar21.vectra.features.reminders.budapestMs
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WidgetClockTest {
    private val day = LocalDate(2026, 11, 10)

    private fun at(time: String) = assertNotNull(budapestMs(day, time))

    @Test
    fun twentyFourHourLabelsKeepTheirLeadingZero() {
        assertEquals("08:05", clockLabel(at("08:05")))
        assertEquals("22:45", clockLabel(at("22:45"), hour12 = false))
        assertEquals("00:00", clockLabel(at("00:00")))
    }

    @Test
    fun twelveHourLabelsUseAmAndPm() {
        assertEquals("8:05 AM", clockLabel(at("08:05"), hour12 = true))
        assertEquals("10:45 PM", clockLabel(at("22:45"), hour12 = true))
        assertEquals("12:00 AM", clockLabel(at("00:00"), hour12 = true))
        assertEquals("12:30 PM", clockLabel(at("12:30"), hour12 = true))
    }

    @Test
    fun theHourAxisDropsThePeriod() {
        assertEquals("08", hourAxisLabel(8, hour12 = false))
        assertEquals("8", hourAxisLabel(8, hour12 = true))
        assertEquals("12", hourAxisLabel(12, hour12 = true))
        assertEquals("2", hourAxisLabel(14, hour12 = true))
        assertEquals("12", hourAxisLabel(0, hour12 = true))
    }

    @Test
    fun anOldSnapshotWithoutTheKeyStaysOn24Hour() {
        val old = """{"version":1,"generatedAtMs":1,"signedIn":true,"sessions":[]}"""
        assertFalse(assertNotNull(decodeWidgetSnapshot(old)).hour12)
    }

    @Test
    fun theTimeFormatSurvivesTheSnapshotJson() {
        val snap = WidgetSnapshot(generatedAtMs = 1, signedIn = true, sessions = emptyList(), hour12 = true)
        assertTrue(assertNotNull(decodeWidgetSnapshot(encodeWidgetSnapshot(snap))).hour12)
        assertTrue(signedOutSnapshot(1, hour12 = true).hour12)
        assertTrue(updateRequiredSnapshot(1, hour12 = true).hour12)
        assertTrue(buildWidgetSnapshot(emptyList(), 1, hour12 = true).hour12)
        assertFalse(buildWidgetSnapshot(emptyList(), 1).hour12)
    }

    @Test
    fun afterRowsAndFootNotesFollowTheSnapshotFormat() {
        val s = WidgetSession("Micro", "C.203", WidgetKind.Lecture, at("10:00"), at("11:30"), "2026-11-10")
        val later = WidgetSession("Law", "C.1", WidgetKind.Lecture, at("22:45"), at("23:30"), "2026-11-10")
        val twelve = WidgetSnapshot(generatedAtMs = 1, signedIn = true, sessions = listOf(s, later), hour12 = true)
        assertEquals("10:45 PM", twelve.afterRows(s, at("10:30")).first().time)
        assertEquals("10:45 PM", thenNote(listOf(later), hour12 = true)?.time)
        assertEquals("22:45", thenNote(listOf(later))?.time)
    }

    @Test
    fun twelveHourTimeColumnsGainRoom() {
        assertEquals(40f, WidgetGeometry.todayTimeColumn(false))
        assertTrue(WidgetGeometry.todayTimeColumn(true) > 40f)
        assertTrue(WidgetGeometry.agendaTimeColumn(true) > WidgetGeometry.agendaTimeColumn(false))
        assertTrue(WidgetGeometry.todayNameWidth(360f, hour12 = true) < WidgetGeometry.todayNameWidth(360f))
        assertTrue(WidgetGeometry.agendaNameWidth(300f, hour12 = true) < WidgetGeometry.agendaNameWidth(300f))
    }
}
