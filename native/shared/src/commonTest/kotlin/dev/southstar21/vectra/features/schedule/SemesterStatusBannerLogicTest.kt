package dev.southstar21.vectra.features.schedule

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemesterStatusBannerLogicTest {
    private fun status(s: String) = SemesterInfo(s)

    @Test fun showsWhileUpcoming() = assertTrue(shouldShowSemesterStatus(status("upcoming"), "2026/27/1"))
    @Test fun showsForPickedPastSemester() = assertTrue(shouldShowSemesterStatus(status("past"), "2025/26/2"))
    @Test fun hidesPastInAllView() = assertFalse(shouldShowSemesterStatus(status("past"), "all"))
    @Test fun hidesForCurrent() {
        assertFalse(shouldShowSemesterStatus(status("current"), "2026/27/1"))
        assertFalse(shouldShowSemesterStatus(status("active"), "2026/27/1"))
    }
}
