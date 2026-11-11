package dev.southstar21.vectra.features.coursetracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 04 #19: switching the assessment form between Points and Percent carries the score across. */
class AssessmentScoreModeTest {
    @Test fun eighteenOfTwentyIsNinetyPercentAndBack() {
        assertEquals("90", convertScore("18", "20", toPercent = true))
        assertEquals("18", convertScore("90", "20", toPercent = false))
    }

    @Test fun anAwkwardRatioRoundTripsToTheSamePoints() {
        val pct = convertScore("17", "30", toPercent = true)
        assertEquals("56.67", pct)
        assertEquals("17", convertScore(pct!!, "30", toPercent = false))
    }

    @Test fun unusableInputLeavesTheFieldAlone() {
        assertNull(convertScore("", "20", toPercent = true))
        assertNull(convertScore("18", "0", toPercent = true))
        assertNull(convertScore("18", "abc", toPercent = true))
    }
}
