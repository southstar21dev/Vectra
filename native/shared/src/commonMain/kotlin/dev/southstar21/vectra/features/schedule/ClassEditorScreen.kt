package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.designsystem.components.BarLeading
import dev.southstar21.vectra.core.designsystem.components.BottomBar
import dev.southstar21.vectra.core.designsystem.components.DeleteRow
import dev.southstar21.vectra.core.designsystem.components.FormField
import dev.southstar21.vectra.core.designsystem.components.FormGroup
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.PillRadio
import dev.southstar21.vectra.core.designsystem.components.ScreenColumn
import dev.southstar21.vectra.core.designsystem.components.ScrollBody
import dev.southstar21.vectra.core.designsystem.components.SectionTitle
import dev.southstar21.vectra.core.designsystem.components.SelectOption
import dev.southstar21.vectra.core.designsystem.components.TimeField
import dev.southstar21.vectra.core.designsystem.components.TopBar
import dev.southstar21.vectra.core.designsystem.components.TopBarSubtitle
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VSelect
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.formatHHMM
import dev.southstar21.vectra.core.time.parseHourMinute
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.navigation.LocalNavigator
import dev.southstar21.vectra.navigation.Screen
import kotlinx.coroutines.launch

/** Monday-first, the way a Budapest timetable reads. */
private val DayOptions = listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 0 to "Sun")
    .map { SelectOption(it.first, it.second) }

private val AttendanceOptions = listOf(
    SelectOption("lecture", "Lecture"),
    SelectOption("seminar", "Seminar"),
    SelectOption("lab", "Lab"),
    SelectOption("unknown", "Not sure"),
)

private val AttendanceHint = mapOf(
    "lecture" to "Not mandatory, so a clash with it is only noted.",
    "seminar" to "Mandatory. A clash with it is flagged on the schedule.",
    "lab" to "Mandatory. A clash with it is flagged on the schedule.",
    "unknown" to "Treated as mandatory until you say otherwise.",
)

private val FrequencyOptions = listOf(
    SelectOption(ScheduleFrequency.Weekly, "Every week"),
    SelectOption(ScheduleFrequency.Even, "Even weeks"),
    SelectOption(ScheduleFrequency.Odd, "Odd weeks"),
)

private val ColorOptions = listOf(
    "#3b82f6", "#a855f7", "#22c55e", "#ef4444", "#f59e0b",
    "#06b6d4", "#ec4899", "#14b8a6", "#eab308", "#8b5cf6",
)

/** Colours a new class can be given at random. */
private val GeneratedColors = listOf(
    "#4A90D9", "#7B61FF", "#50C878", "#FF6B6B", "#FFB347",
    "#87CEEB", "#DDA0DD", "#98D8C8", "#F7DC6F", "#BB8FCE",
    "#85C1E9", "#82E0AA", "#F8C471", "#D98880", "#AED6F1",
)

fun generateEventColor(): String = GeneratedColors.random()

/** A class's `HH:mm` times are real clock times and it ends later the same day (classes never cross midnight). */
internal fun classTimesValid(start: String, end: String): Boolean {
    val s = parseHourMinute(start)?.takeIf { (h, m) -> h in 0..23 && m in 0..59 }
    val e = parseHourMinute(end)?.takeIf { (h, m) -> h in 0..23 && m in 0..59 }
    return s != null && e != null && (e.first * 60 + e.second) > (s.first * 60 + s.second)
}

internal class ClassForm(event: ScheduleEvent?) {
    var courseName by mutableStateOf(event?.courseName.orEmpty())
    var courseCode by mutableStateOf(event?.courseCode.orEmpty())
    var attendanceType by mutableStateOf(event?.attendanceType?.ifEmpty { null } ?: "unknown")
    var instructor by mutableStateOf(event?.instructor.orEmpty())
    var location by mutableStateOf(event?.location.orEmpty())
    var dayOfWeek by mutableIntStateOf(event?.dayOfWeek ?: 1)
    var startTime by mutableStateOf(formatHHMM(event?.startTime).ifEmpty { "09:00" })
    var endTime by mutableStateOf(formatHHMM(event?.endTime).ifEmpty { "10:30" })
    var color by mutableStateOf(event?.color?.ifEmpty { null } ?: generateEventColor())
    var semester by mutableStateOf(event?.semester.orEmpty())
    var frequency by mutableStateOf(getEventFrequency(event?.csvMetadata))

    companion object {
        /** Keeps the draft when Android recreates the activity (dark mode, font scale, rotation). */
        val Saver: Saver<ClassForm, Any> = listSaver(
            save = {
                listOf(
                    it.courseName, it.courseCode, it.attendanceType, it.instructor, it.location, it.dayOfWeek,
                    it.startTime, it.endTime, it.color, it.semester, it.frequency.key,
                )
            },
            restore = { saved ->
                val v = saved.iterator()
                ClassForm(null).apply {
                    courseName = v.next() as String
                    courseCode = v.next() as String
                    attendanceType = v.next() as String
                    instructor = v.next() as String
                    location = v.next() as String
                    dayOfWeek = v.next() as Int
                    startTime = v.next() as String
                    endTime = v.next() as String
                    color = v.next() as String
                    semester = v.next() as String
                    val key = v.next()
                    frequency = ScheduleFrequency.entries.firstOrNull { f -> f.key == key } ?: ScheduleFrequency.Weekly
                }
            },
        )
    }
}

/**
 * Add a manual class, or edit the one with [eventId]. Pops after a save or delete, and on
 * its own when the class disappears from the timetable.
 */
@Composable
internal fun ClassEditorScreen(repo: ScheduleRepository, tracker: ScheduleCourseTrackerHooks, eventId: String?, onBack: () -> Unit) {
    val events by repo.events.collectAsStateWithLifecycle()
    val eventsLoading by repo.eventsLoading.collectAsStateWithLifecycle()
    val exit = rememberExitOnce(onBack)
    val event = remember(events, eventId) { eventId?.let { id -> events.firstOrNull { it.id == id } } }
    val missing = eventId != null && event == null

    LaunchedEffect(missing, eventsLoading) { if (missing && !eventsLoading) exit() }

    if (missing) {
        // Still loading after a restore, or deleted: hold the bar until the lookup settles.
        ScreenColumn { TopBar("Edit class", BarLeading.Back, exit) }
        return
    }
    ClassEditorContent(repo, tracker, event, events, exit)
}

@Composable
private fun ClassEditorContent(repo: ScheduleRepository, tracker: ScheduleCourseTrackerHooks, event: ScheduleEvent?, events: List<ScheduleEvent>, exit: () -> Unit) {
    val c = Vectra.colors
    val haptics = Vectra.haptics
    val navigator = LocalNavigator.current
    val isSubmitting by repo.isCreatingEvent.collectAsStateWithLifecycle()
    val availableSemesters = remember(events) { scheduleSemesters(events) }
    val form = rememberSaveable(saver = ClassForm.Saver) { ClassForm(event) }
    var deleting by remember { mutableStateOf(false) }
    var scrolled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val timeValid = classTimesValid(form.startTime, form.endTime)
    val canSave = !isSubmitting && !deleting && form.courseName.isNotBlank() && timeValid

    ScreenColumn {
        TopBar(
            title = if (event != null) "Edit class" else "Add class",
            leading = BarLeading.Back,
            onLeading = exit,
            subtitle = event?.courseName?.ifEmpty { null }?.let { name -> { TopBarSubtitle(name) } },
            scrolled = scrolled,
            trailing = if (event != null) {
                {
                    Row(
                        Modifier
                            .height(40.dp)
                            .background(c.surfaceOverlay, CircleShape)
                            .tappable(CircleShape, onClickLabel = "Open Course Hub", pressedScale = 0.95f) {
                                navigator.push(Screen.CourseHub(tracker.courseKey(event)))
                            }
                            .padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VIcon(RemixIcons.BookOpenLine, size = 14.dp, tint = c.textPrimary)
                        VText("Hub", style = vtext(TypeScale.Body, Weight.Bold), color = c.textPrimary)
                    }
                }
            } else null,
        )
        ScrollBody(
            scrollState = rememberScrollState(),
            onScrolledChange = { scrolled = it },
            fadeBottom = false,
            contentPadding = EditorBodyPadding,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                SheetSection {
                    SectionTitle("Class")
                    FormGroup(rows = listOf(
                        { FormField("Course name") { EditorTextInput(form.courseName, { form.courseName = it }, "Analysis II") } },
                        {
                            FormField("Attendance", hint = AttendanceHint[form.attendanceType]) {
                                PillRadio(form.attendanceType, AttendanceOptions, { form.attendanceType = it }, columns = 4)
                            }
                        },
                        {
                            TwoColumns(
                                { FormField("Course code") { EditorTextInput(form.courseCode, { form.courseCode = it }, "BMETE90AX22") } },
                                { FormField("Instructor") { EditorTextInput(form.instructor, { form.instructor = it }, "Dr. Kovács") } },
                            )
                        },
                        { FormField("Room") { EditorTextInput(form.location, { form.location = it }, "K.F.81") } },
                    ))
                }
                SheetSection {
                    SectionTitle("When")
                    val rows = mutableListOf<@Composable () -> Unit>(
                        { FormField("Day") { PillRadio(form.dayOfWeek, DayOptions, { form.dayOfWeek = it }, columns = 7) } },
                        {
                            TwoColumns(
                                { FormField("Starts") { TimeField(form.startTime, { form.startTime = it }, minuteStep = 5) } },
                                { FormField("Ends") { TimeField(form.endTime, { form.endTime = it }, minuteStep = 5) } },
                            )
                        },
                        { FormField("Repeats") { PillRadio(form.frequency, FrequencyOptions, { form.frequency = it }, columns = 3) } },
                    )
                    if (!timeValid) {
                        rows.add(2) {
                            VText(
                                "A class has to end later on the same day it starts.",
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                vtext(TypeScale.Caption, Weight.Semibold),
                                c.tints.red.text,
                            )
                        }
                    }
                    if (availableSemesters.isNotEmpty()) {
                        rows += {
                            FormField("Semester") {
                                VSelect(
                                    form.semester,
                                    listOf(SelectOption("", "All semesters")) + availableSemesters.map { SelectOption(it, it) },
                                    { form.semester = it },
                                    Modifier.fillMaxWidth().height(44.dp),
                                )
                            }
                        }
                    }
                    FormGroup(rows = rows)
                }
                SheetSection {
                    SectionTitle("Colour")
                    FormGroup(rows = listOf({
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ColorOptions.forEach { hex ->
                                val active = form.color == hex
                                Box(
                                    Modifier
                                        .scale(if (active) 1.1f else 1f)
                                        .alpha(if (active) 1f else 0.8f)
                                        .size(28.dp)
                                        .then(
                                            if (active) Modifier
                                                .border(2.dp, c.textPrimary, CircleShape)
                                                .padding(2.dp)
                                                .border(2.dp, c.surfaceSecondary, CircleShape)
                                            else Modifier,
                                        )
                                        .background(parseOpaqueHexColor(hex), CircleShape)
                                        .tappable(CircleShape, role = null, onClickLabel = "Colour $hex", highlight = false, haptic = { if (!active) tick() }) {
                                            form.color = hex
                                        },
                                )
                            }
                        }
                    }))
                }
                if (event != null) {
                    DeleteRow(
                        label = "Delete class",
                        onConfirm = {
                            deleting = true
                            scope.launch {
                                try {
                                    runSuspendCatching { repo.deleteEvent(event.id) }
                                        .onSuccess { exit() }
                                        .onFailure { e ->
                                            // The editor stays open.
                                            haptics.reject()
                                            toaster.error(e.message ?: "Could not delete this class.")
                                        }
                                } finally {
                                    deleting = false
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        busy = deleting,
                    )
                }
            }
        }
        BottomBar {
            EditorSubmitButton(canSave, onClick = {
                if (!canSave) return@EditorSubmitButton
                val data = ScheduleFormData(
                    courseName = form.courseName,
                    attendanceType = form.attendanceType,
                    courseCode = form.courseCode,
                    instructor = form.instructor,
                    location = form.location,
                    dayOfWeek = form.dayOfWeek,
                    startTime = form.startTime,
                    endTime = form.endTime,
                    color = form.color,
                    semester = form.semester,
                    frequency = form.frequency,
                )
                scope.launch {
                    runSuspendCatching { if (event != null) repo.updateEvent(event.id, data) else repo.createEvent(data) }
                        .onSuccess {
                            haptics.confirm()
                            exit()
                        }
                        .onFailure { e ->
                            // Keep the form so nothing typed is lost.
                            haptics.reject()
                            toaster.error(e.message ?: "Could not save this class.")
                        }
                }
            }) { SubmitLabel(isSubmitting, if (event != null) "Save changes" else "Add class") }
        }
    }
}
