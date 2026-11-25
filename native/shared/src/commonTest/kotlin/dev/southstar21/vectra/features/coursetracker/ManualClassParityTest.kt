package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.features.schedule.ScheduleEvent
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManualClassParityTest {
    private fun manual(frequency: String): ScheduleEvent =
        event("Odd Lab", dayOfWeek = 1, startTime = "10:00", endTime = "12:00", semester = "2026/27/1", source = "manual")
            .copy(csvMetadata = JsonObject(mapOf("frequency" to JsonPrimitive(frequency))))

    private fun datesOn(today: String, frequency: String): List<String> {
        val e = manual(frequency)
        return generateSessionsFromScheduleEvents(
            courseName = e.courseName,
            semester = e.semester,
            scheduleEvents = listOf(e),
            today = LocalDate.parse(today),
        ).map { it.date }
    }

    @Test fun oddWeeksAreCountedFromSemesterWeekOneRegardlessOfMonth() {
        // Week one is Mon 31 Aug 2026, so odd weeks are 31 Aug, 14 Sep, ... 9 Nov, 23 Nov.
        val november = datesOn("2026-11-24", "odd")
        val december = datesOn("2026-12-03", "odd")
        assertEquals(november, december)
        assertTrue("2026-11-09" in november && "2026-11-23" in november)
        assertTrue("2026-11-02" !in november && "2026-11-16" !in november)
    }

    @Test fun weeklyManualClassStartsAtSemesterWeekOne() {
        assertEquals("2026-08-31", datesOn("2026-11-24", "weekly").first())
    }
}
