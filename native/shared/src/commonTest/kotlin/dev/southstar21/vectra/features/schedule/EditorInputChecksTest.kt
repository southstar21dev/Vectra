package dev.southstar21.vectra.features.schedule

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Inputs the class editor and the Neptun settings refuse before saving (audit 04 #23). */
class EditorInputChecksTest {
    @Test fun aClassMustEndAfterItStarts() {
        assertTrue(classTimesValid("09:00", "10:30"))
        assertTrue(classTimesValid("08:15", "08:20"))
        assertFalse(classTimesValid("10:30", "09:00"))
        assertFalse(classTimesValid("10:00", "10:00"))
        assertFalse(classTimesValid("", "10:00"))
        assertFalse(classTimesValid("24:00", "25:00"))
    }

    @Test fun onlyACalendarLinkIsConnected() {
        assertTrue(isCalendarLink("https://neptun3r.web.uni-corvinus.hu/Hallgatoi/api/Calendar/abc?x=1"))
        assertTrue(isCalendarLink("  HTTPS://neptun.example.hu/cal.ics "))
        assertFalse(isCalendarLink(""))
        assertFalse(isCalendarLink("my neptun code"))
        assertFalse(isCalendarLink("neptun3r.web.uni-corvinus.hu/Hallgatoi/api/Calendar"))
        assertFalse(isCalendarLink("https://localhost/cal"))
        assertFalse(isCalendarLink("https://neptun.hu/a b"))
        assertFalse(isCalendarLink("ftp://neptun.hu/cal.ics"))
    }
}
