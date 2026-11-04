package dev.southstar21.vectra.features.schedule

import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleOccurrencesTest {
    private fun item(
        id: String = "11111111-1111-4111-8111-111111111111",
        type: String = "shift",
        title: String = "Work",
        date: String = "2026-09-07",
        startTime: String = "22:00",
        endTime: String = "02:00",
        completed: Boolean? = null,
    ) = ScheduleItem(id, type, title, date, startTime, endTime, completed = completed)

    @Test fun oneOffOnlyOnOwnDate() {
        val task = item(type = "task", startTime = "09:00", endTime = "10:00")
        assertEquals(
            listOf(ScheduleItemOccurrence(task, "2026-09-07", "09:00", "10:00", false)),
            getScheduleItemOccurrences(listOf(task), "2026-09-07", "2026-09-13"),
        )
    }

    @Test fun overnightOnBothDates() {
        val overnight = item()
        assertEquals(
            listOf(
                ScheduleItemOccurrence(overnight, "2026-09-07", "22:00", "24:00", false),
                ScheduleItemOccurrence(overnight, "2026-09-08", "00:00", "02:00", true),
            ),
            getScheduleItemOccurrences(listOf(overnight), "2026-09-07", "2026-09-08"),
        )
        val first = getScheduleItemOccurrencesForDate(listOf(overnight), "2026-09-08")[0]
        assertEquals("2026-09-08", first.date)
        assertEquals(true, first.continuation)
    }

    @Test fun completedVisibleAndSorted() {
        val late = item(id = "late", type = "task", title = "Done", date = "2026-09-08", startTime = "12:00", endTime = "13:00", completed = true)
        val early = item(id = "early", date = "2026-09-08", startTime = "08:00", endTime = "09:00")
        assertEquals(listOf("early", "late"), getScheduleItemOccurrences(listOf(late, early), "2026-09-08", "2026-09-08").map { it.item.id })
    }

    @Test fun rejectsInvalidRanges() {
        assertEquals(emptyList(), getScheduleItemOccurrences(listOf(item()), "2026-02-30", "2026-03-01"))
        assertEquals(emptyList(), getScheduleItemOccurrences(listOf(item()), "2026-09-08", "2026-09-07"))
    }
}
