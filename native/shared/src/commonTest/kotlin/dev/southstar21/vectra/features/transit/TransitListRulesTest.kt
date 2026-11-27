package dev.southstar21.vectra.features.transit

import androidx.compose.ui.graphics.Color
import dev.southstar21.vectra.features.transit.live.directionTabsStacked
import dev.southstar21.vectra.features.transit.strip.StripTimeKind
import dev.southstar21.vectra.features.transit.strip.TimeTone
import dev.southstar21.vectra.features.transit.strip.timeTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules the transit lists share: termini, minute colours, night badges, direction tabs. */
class TransitListRulesTest {
    @Test
    fun aTitleSplitsIntoWhereItStartsAndWhereItGoes() {
        assertEquals("Megyer" to "Újpest-központ M", splitTermini("Megyer | Újpest-központ M"))
        assertEquals("A" to "B / C", splitTermini("A | B | C"))
        assertEquals("Only" to null, splitTermini("Only"))
        assertEquals("" to null, splitTermini(null))
        assertEquals("" to null, splitTermini(""))
    }

    @Test
    fun liveMinutesAreAmberAndTimetableOnesGrey() {
        assertEquals(TimeTone.Live, timeTone(StripTimeKind.Live, arriving = false, label = "5'"))
        assertEquals(TimeTone.Scheduled, timeTone(StripTimeKind.Scheduled, arriving = false, label = "5'"))
    }

    @Test
    fun arrivingIsGreenForNowAndOneMinute() {
        assertEquals(TimeTone.Arriving, timeTone(StripTimeKind.Live, arriving = false, label = "1'"))
        assertEquals(TimeTone.Arriving, timeTone(StripTimeKind.Live, arriving = false, label = "NOW"))
        assertEquals(TimeTone.Arriving, timeTone(StripTimeKind.Scheduled, arriving = true, label = "3'"))
        // A timetable 1' is only a time: it is not on its way in.
        assertEquals(TimeTone.Scheduled, timeTone(StripTimeKind.Scheduled, arriving = false, label = "1'"))
    }

    @Test
    fun outOfDateTimesFade() {
        assertEquals(TimeTone.Faded, timeTone(StripTimeKind.Live, arriving = true, label = "NOW", faded = true))
    }

    @Test
    fun onlyBlackLinesAreNightLines() {
        assertTrue(isNightLineColor(Color(0xFF000000)))
        assertFalse(isNightLineColor(Color(0xFFE41F18)))
        assertFalse(isNightLineColor(Color(0xFFFFD800)))
    }

    @Test
    fun longDirectionNamesStackTheTabs() {
        assertFalse(directionTabsStacked(listOf("Blaha", "Teleki")))
        assertTrue(directionTabsStacked(listOf("Gyöngyösi utca M", "Apor Vilmos tér")))
        assertFalse(directionTabsStacked(emptyList()))
        assertNull(splitTermini("X").second)
    }
}
