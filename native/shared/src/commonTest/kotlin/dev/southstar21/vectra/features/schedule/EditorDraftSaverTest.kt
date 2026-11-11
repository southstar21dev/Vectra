package dev.southstar21.vectra.features.schedule

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import dev.southstar21.vectra.core.designsystem.components.LocalDateSaver
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The editors' drafts survive Android's activity recreation only if they go through a saved instance state. */
class EditorDraftSaverTest {
    /** Accepts only what an Android Bundle holds without a custom Parcelable. */
    private val bundleLike = SaverScope { it is String || it is Int || it is List<*> }

    private fun <T : Any> roundTrip(saver: Saver<T, Any>, value: T): T {
        val saved = with(saver) { bundleLike.save(value) }!!
        (saved as? List<*>)?.forEach { assertTrue(it == null || it is String || it is Int, "unsaveable $it") }
        return saver.restore(saved)!!
    }

    @Test fun aClassDraftSurvives() {
        val form = ClassForm(null).apply {
            courseName = "Analysis II"
            courseCode = "BMETE90AX22"
            attendanceType = "lab"
            instructor = "Dr. Kovács"
            location = "K.F.81"
            dayOfWeek = 4
            startTime = "12:15"
            endTime = "13:45"
            color = "#22c55e"
            semester = "2026/27/1"
            frequency = ScheduleFrequency.Odd
        }
        val back = roundTrip(ClassForm.Saver, form)
        assertEquals(
            listOf(form.courseName, form.courseCode, form.attendanceType, form.instructor, form.location, form.dayOfWeek,
                form.startTime, form.endTime, form.color, form.semester, form.frequency),
            listOf(back.courseName, back.courseCode, back.attendanceType, back.instructor, back.location, back.dayOfWeek,
                back.startTime, back.endTime, back.color, back.semester, back.frequency),
        )
    }

    @Test fun anItemDraftKeepsItsIdAndAcknowledgement() {
        val form = ItemForm(null, ScheduleItem.TYPE_SHIFT).apply {
            title = "Café"
            date = "2026-11-12"
            startTime = "08:00"
            endTime = "12:00"
            location = "Bartók Béla út"
            acknowledgedSignature = "sig"
        }
        val back = roundTrip(ItemForm.Saver, form)
        assertEquals(
            listOf(form.draftId, form.type, form.title, form.date, form.startTime, form.endTime, form.location, form.acknowledgedSignature),
            listOf(back.draftId, back.type, back.title, back.date, back.startTime, back.endTime, back.location, back.acknowledgedSignature),
        )
        assertEquals(null, roundTrip(ItemForm.Saver, ItemForm(null, ScheduleItem.TYPE_TASK)).acknowledgedSignature)
    }

    @Test fun aPickerMonthSurvives() {
        val saved = with(LocalDateSaver) { bundleLike.save(LocalDate(2026, 11, 1)) }!!
        assertEquals(LocalDate(2026, 11, 1), LocalDateSaver.restore(saved))
    }
}
