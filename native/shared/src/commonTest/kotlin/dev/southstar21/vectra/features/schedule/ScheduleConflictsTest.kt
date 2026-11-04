package dev.southstar21.vectra.features.schedule

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal fun freq(f: String): JsonElement = JsonObject(mapOf("frequency" to JsonPrimitive(f)))

internal fun shift(
    id: String = "shift-1",
    type: String = "shift",
    title: String = "Work",
    date: String = "2026-09-07",
    startTime: String = "09:00",
    endTime: String = "12:00",
    completed: Boolean? = null,
    acknowledgedConflictKeys: List<String> = emptyList(),
) = ScheduleItem(id, type, title, date, startTime, endTime, completed = completed, acknowledgedConflictKeys = acknowledgedConflictKeys)

internal fun scheduleEvent(
    id: String = "class-1",
    source: String? = "neptun",
    courseName: String = "Course",
    courseCode: String = "CS101-G01",
    dayOfWeek: Int = 1,
    startTime: String = "10:00",
    endTime: String = "11:00",
    semester: String = "2026/27/1",
    semesterStart: String? = "2026-09-07",
    semesterEnd: String? = "2026-12-14",
    eventType: String = "class",
    attendanceType: String? = null,
    csvMetadata: JsonElement? = JsonObject(emptyMap()),
) = ScheduleEvent(
    id = id, courseName = courseName, courseCode = courseCode, dayOfWeek = dayOfWeek, startTime = startTime,
    endTime = endTime, semester = semester, semesterStart = semesterStart, semesterEnd = semesterEnd,
    eventType = eventType, attendanceType = attendanceType, csvMetadata = csvMetadata, source = source,
)

class ScheduleConflictsTest {
    @Test fun touchingEndpointHalfOpen() {
        assertEquals(listOf(ScheduleInterval("2026-09-07", 720, 780)), getItemIntervals(shift(startTime = "12:00", endTime = "13:00")))
        assertEquals(1, getItemIntervals(shift(startTime = "11:59", endTime = "12:01")).size)
    }

    @Test fun splitsOvernightShifts() {
        assertEquals(
            listOf(ScheduleInterval("2026-09-07", 1350, 1440), ScheduleInterval("2026-09-08", 0, 75)),
            getItemIntervals(shift(startTime = "22:30", endTime = "01:15")),
        )
    }

    @Test fun rejectsInvalidIntervals() {
        assertEquals(emptyList(), getItemIntervals(shift(startTime = "09:00", endTime = "09:00")))
        assertEquals(emptyList(), getItemIntervals(shift(startTime = "25:00", endTime = "26:00")))
        assertEquals(emptyList(), getItemIntervals(shift(date = "2026-02-30")))
        assertEquals(emptyList(), getItemIntervals(shift(type = "task", startTime = "23:00", endTime = "01:00")))
    }

    @Test fun weekdayAndBounds() {
        val e = scheduleEvent(semesterStart = "2026-09-07", semesterEnd = "2026-09-21")
        assertTrue(classOccursOnDate(e, "2026-09-07", "2026-09-01"))
        assertTrue(classOccursOnDate(e, "2026-09-21", "2026-09-01"))
        assertTrue(classOccursOnDate(e, "2026-09-14", "2026-09-01"))
        assertFalse(classOccursOnDate(e, "2026-09-08", "2026-09-01"))
        assertFalse(classOccursOnDate(e, "2026-09-28", "2026-09-01"))
    }

    @Test fun oddEvenAnchor() {
        val e = scheduleEvent(semesterStart = "2026-09-08", semesterEnd = "2026-10-31", csvMetadata = freq("odd"))
        assertTrue(classOccursOnDate(e, "2026-09-14", "2026-09-01"))
        assertFalse(classOccursOnDate(e, "2026-09-21", "2026-09-01"))
        assertTrue(classOccursOnDate(e.copy(csvMetadata = freq("even")), "2026-09-21", "2026-09-01"))
    }

    @Test fun referenceMonthStartFallback() {
        val e = scheduleEvent(semesterStart = null, semesterEnd = null, csvMetadata = freq("odd"))
        assertTrue(classOccursOnDate(e, "2026-09-07", "2026-09-01"))
        assertFalse(classOccursOnDate(e, "2026-09-14", "2026-09-01"))
    }

    @Test fun earliestMatchingRowAnchors() {
        val monday = scheduleEvent(id = "monday", dayOfWeek = 1, courseCode = "CS101-G01", semesterStart = "2026-09-07", csvMetadata = freq("odd"))
        val friday = scheduleEvent(
            id = "friday", dayOfWeek = 5, courseCode = "CS101-G01", startTime = "14:00", endTime = "15:00",
            semesterStart = "2026-09-11", csvMetadata = freq("odd"),
        )
        assertTrue(classOccursOnDate(friday, "2026-09-11", "2026-09-24", listOf(monday, friday)))
        assertFalse(classOccursOnDate(friday, "2026-09-18", "2026-09-24", listOf(monday, friday)))
    }

    @Test fun examIsIndividualDate() {
        val exam = scheduleEvent(eventType = "exam", semesterStart = "2026-09-07", semesterEnd = "2026-09-07")
        assertTrue(classOccursOnDate(exam, "2026-09-07", "2026-09-01"))
        assertFalse(classOccursOnDate(exam, "2026-09-14", "2026-09-01"))
    }

    @Test fun englishTitledLegacyExam() {
        val legacy = scheduleEvent(
            courseName = "Elements of Accounting Midterm Exam", courseCode = "", dayOfWeek = 1,
            semesterStart = "2026-11-02", semesterEnd = "2026-11-02",
        )
        assertTrue(classOccursOnDate(legacy, "2026-11-02", "2026-11-02"))
        assertFalse(classOccursOnDate(legacy, "2026-11-09", "2026-11-02"))
    }

    @Test fun manualAttendanceType() {
        assertFalse(isConflictRelevantClass(scheduleEvent(source = "manual", attendanceType = "lecture")))
        assertTrue(isConflictRelevantClass(scheduleEvent(source = "manual", attendanceType = "seminar")))
        assertTrue(isConflictRelevantClass(scheduleEvent(source = "manual", attendanceType = "lab")))
        assertTrue(isConflictRelevantClass(scheduleEvent(source = "manual", attendanceType = "unknown")))
        assertTrue(isConflictRelevantClass(scheduleEvent(source = "manual", attendanceType = null, courseCode = "E01")))
    }

    @Test fun legacyWithoutSource() =
        assertTrue(isConflictRelevantClass(scheduleEvent(source = null, attendanceType = null, courseCode = "CS101-E01")))

    @Test fun manualNotInferredAsLecture() {
        assertTrue(isConflictRelevantClass(scheduleEvent(source = "manual", attendanceType = null, courseCode = "CS101-E01")))
        assertTrue(isConflictRelevantClass(scheduleEvent(source = "manual", attendanceType = "unknown", courseCode = "CS101-E01")))
    }

    @Test fun neptunInference() {
        assertFalse(isConflictRelevantClass(scheduleEvent(courseCode = "CS101-E01")))
        assertTrue(isConflictRelevantClass(scheduleEvent(courseCode = "CS101-G01")))
        assertTrue(isConflictRelevantClass(scheduleEvent(courseCode = "CS101-L01")))
        assertTrue(isConflictRelevantClass(scheduleEvent(eventType = "exam", courseCode = "")))
        assertTrue(isConflictRelevantClass(scheduleEvent(courseCode = "UNKNOWN")))
        assertTrue(isConflictRelevantClass(scheduleEvent(courseName = "Elements of Accounting Midterm Exam", courseCode = "E01")))
    }

    @Test fun classOverlapExcludesLecture() {
        val item = shift()
        val seminar = scheduleEvent()
        val lecture = scheduleEvent(id = "lecture", courseCode = "CS101-E01")
        val c = getScheduleConflicts(listOf(item), listOf(seminar, lecture), "2026-09-07")
        assertEquals(1, c.size)
        assertEquals(item.id, c[0].itemId)
        assertEquals(seminar.id, c[0].classId)
        assertEquals(600, c[0].startMinute)
        assertEquals(660, c[0].endMinute)
    }

    @Test fun endpointContactAndCompletedTasks() {
        val item = shift(endTime = "10:00")
        val seminar = scheduleEvent(startTime = "10:00", endTime = "11:00")
        val done = shift(id = "task", type = "task", completed = true, startTime = "09:30", endTime = "10:30")
        assertEquals(0, getScheduleConflicts(listOf(item, done), listOf(seminar), "2026-09-07").size)
    }

    @Test fun itemPairStableKey() {
        val a = shift(id = "a", startTime = "09:00", endTime = "11:00")
        val b = shift(id = "b", type = "task", startTime = "10:00", endTime = "12:00")
        val conflict = getScheduleConflicts(listOf(a, b), emptyList(), "2026-09-07")[0]
        assertEquals("a", conflict.itemId)
        assertEquals("b", conflict.otherItemId)
        assertFalse(conflictIsAcknowledged(conflict, listOf(a, b)))
        assertTrue(conflictIsAcknowledged(conflict, listOf(a, b.copy(acknowledgedConflictKeys = listOf(conflict.key)))))
    }

    @Test fun stableNeptunKeys() {
        val item = shift()
        val original = getScheduleConflicts(listOf(item), listOf(scheduleEvent(id = "old-neptun-row")), "2026-09-07")[0]
        val after = getScheduleConflicts(listOf(item), listOf(scheduleEvent(id = "new-neptun-row")), "2026-09-07")[0]
        val moved = getScheduleConflicts(listOf(item), listOf(scheduleEvent(id = "moved", startTime = "10:30")), "2026-09-07")[0]
        assertEquals(original.key, after.key)
        assertNotEquals(original.key, moved.key)
    }

    @Test fun shiftTailNextDay() {
        val overnight = shift(startTime = "23:00", endTime = "02:00")
        val next = scheduleEvent(id = "next-day", dayOfWeek = 2, startTime = "01:00", endTime = "01:30", semesterStart = "2026-09-07")
        assertEquals(1, getScheduleConflicts(listOf(overnight), listOf(next), "2026-09-07").size)
    }

    @Test fun laterRefreshNeedsAck() {
        val c = getScheduleConflicts(listOf(shift()), listOf(scheduleEvent(id = "new-class")), "2026-09-07")
        assertEquals(1, c.size)
        assertTrue(c[0].needsAcknowledgment)
    }

    @Test fun eitherSideAckSuffices() {
        val first = shift(id = "first")
        val second = shift(id = "second", type = "task", startTime = "10:00", endTime = "11:00")
        val conflict = getScheduleConflicts(listOf(first, second), emptyList(), "2026-09-07")[0]
        val refreshed = getScheduleConflicts(listOf(first.copy(acknowledgedConflictKeys = listOf(conflict.key)), second), emptyList(), "2026-09-07")[0]
        assertFalse(refreshed.needsAcknowledgment)
    }

    @Test fun aSharedIndexAgreesWithTheListForm() {
        val monday = scheduleEvent(id = "m", dayOfWeek = 1)
        val friday = scheduleEvent(id = "f", dayOfWeek = 5)
        val events = listOf(monday, friday, monday.copy(id = "m2", semesterStart = "2026-09-01"))
        val index = ScheduleEventIndex(events)
        for (date in listOf("2026-09-07", "2026-09-11", "2026-09-14", "2026-09-18", "2026-12-25", "not-a-date")) {
            for (event in events) {
                assertEquals(classOccursOnDate(event, date, "2026-09-01", events), classOccursOnDate(event, date, "2026-09-01", index), "${event.id} on $date")
            }
        }
    }

    @Test fun aToggleInProgressShowsWithoutTouchingTheQueue() {
        val task = StoredScheduleItem(id = "t", type = "task", title = "x", date = "2026-09-07", startTime = "10:00", endTime = "11:00", completed = false)
        val other = task.copy(id = "o")
        val state = createEmptyScheduleItemsState().copy(items = listOf(task, other))
        val shown = applyCompletionOverrides(state, mapOf("t" to CompletionOverride(true)))
        assertEquals(listOf(true, false), shown.items.map { it.completed })
        assertEquals(state.queue, shown.queue)
        assertSame(state, applyCompletionOverrides(state, emptyMap()))
    }
}
