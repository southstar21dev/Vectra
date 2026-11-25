package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.features.schedule.ScheduleEvent
import dev.southstar21.vectra.features.schedule.classOccursOnDate
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

    @Test fun aClassWithoutSemesterFollowsTheTimetablesOfficialWeekOne() {
        // Neptun's term starts on Mon 7 Sep, a week after the nominal 1 Sep, and the timetable counts
        // a semester-less class from that official start.
        val neptun = event("Micro", dayOfWeek = 3, startTime = "08:00", endTime = "09:30", semester = "2026/27/1",
            semesterStart = "2026-09-07", semesterEnd = "2026-12-12", source = "neptun")
        val odd = event("Odd Lab", dayOfWeek = 1, startTime = "10:00", endTime = "12:00", source = "manual")
            .copy(csvMetadata = JsonObject(mapOf("frequency" to JsonPrimitive("odd"))))
        val all = listOf(neptun, odd)
        val generated = generateSessionsFromScheduleEvents(
            courseName = odd.courseName,
            scheduleEvents = all,
            today = LocalDate.parse("2026-11-24"),
        ).map { it.date }.filter { it.startsWith("2026-11") }
        val timetable = listOf("2026-11-02", "2026-11-09", "2026-11-16", "2026-11-23", "2026-11-30")
            .filter { classOccursOnDate(odd, it, all) }
        assertEquals(listOf("2026-11-02", "2026-11-16", "2026-11-30"), timetable)
        assertEquals(timetable, generated)
    }

    @Test fun weeklyManualClassStartsAtSemesterWeekOne() {
        assertEquals("2026-08-31", datesOn("2026-11-24", "weekly").first())
    }
}
