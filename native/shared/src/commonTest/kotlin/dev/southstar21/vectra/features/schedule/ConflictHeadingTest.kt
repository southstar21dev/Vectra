package dev.southstar21.vectra.features.schedule

import kotlin.test.Test
import kotlin.test.assertEquals

class ConflictHeadingTest {
    private fun clash(type: String) = ScheduleConflict(
        key = "k$type", itemId = "i", otherTitle = "t", otherType = type, date = "2026-11-25",
        startMinute = 0, endMinute = 60, needsAcknowledgment = true,
    )

    @Test fun namesWhatItClashesWith() {
        assertEquals("Clashes with a task", conflictHeading(listOf(clash("task"))))
        assertEquals("Clashes with a class", conflictHeading(listOf(clash("class"))))
        assertEquals("Clashes with a shift", conflictHeading(listOf(clash("shift"))))
        assertEquals("Clashes with 2 shifts", conflictHeading(listOf(clash("shift"), clash("shift"))))
        assertEquals("Clashes with 2 classes", conflictHeading(listOf(clash("class"), clash("exam"))))
    }

    @Test fun mixedKindsAreItems() {
        assertEquals("Clashes with 2 items", conflictHeading(listOf(clash("class"), clash("shift"))))
    }
}
