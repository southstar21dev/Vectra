package dev.southstar21.vectra.features.coursetracker

import androidx.compose.ui.draw.clip
import dev.southstar21.vectra.core.designsystem.theme.Radius
import dev.southstar21.vectra.core.designsystem.theme.glass
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.designsystem.components.BarLeading
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.ScrollBody
import dev.southstar21.vectra.core.designsystem.components.SurfaceGutter
import dev.southstar21.vectra.core.designsystem.components.TopBar
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VTabs
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.time.parseIsoDate
import dev.southstar21.vectra.features.schedule.ScheduleEvent
import dev.southstar21.vectra.features.schedule.ScheduleRepository
import dev.southstar21.vectra.features.schedule.TrackerCourses
import dev.southstar21.vectra.features.schedule.isExamEvent
import dev.southstar21.vectra.features.schedule.normalizeTime
import dev.southstar21.vectra.features.schedule.rememberExitOnce
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.BarButton
import dev.southstar21.vectra.core.designsystem.components.Spinner
import dev.southstar21.vectra.core.designsystem.components.SheetSize
import dev.southstar21.vectra.core.designsystem.components.VSheet
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.features.schedule.parseOpaqueHexColor
import dev.southstar21.vectra.navigation.AppNavigator
import kotlinx.coroutines.launch

private enum class TrackerTab(val label: String) { Attendance("Attendance"), Grades("Grades"), Rules("Rules") }

/** Order-independent identity of a session list (slot + mark). */
private fun sessionsSignature(sessions: List<AttendanceSession>): String =
    sessions.map { "${it.date}|${it.startTime.orEmpty()}|${it.endTime.orEmpty()}|${it.sessionType.wire}|${it.status.name}" }.sorted().joinToString("\n")

/** The class the Course Hub is about (the timetable fields it reconciles against). */
data class CourseTrackerCourse(
    val courseName: String,
    val courseCode: String = "",
    val semester: String = "",
    val instructor: String? = null,
    val location: String? = null,
    val color: String? = null,
    val dayOfWeek: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val eventType: String? = null,
) {
    companion object {
        fun from(event: ScheduleEvent) = CourseTrackerCourse(
            courseName = event.courseName,
            courseCode = event.courseCode,
            semester = event.semester,
            instructor = event.instructor.nonEmpty(),
            location = event.location.nonEmpty(),
            color = event.color.nonEmpty(),
            dayOfWeek = event.dayOfWeek,
            startTime = event.startTime.nonEmpty(),
            endTime = event.endTime.nonEmpty(),
            eventType = event.eventType.nonEmpty(),
        )

        fun from(tracker: CourseTracker) = CourseTrackerCourse(
            courseName = tracker.courseName,
            courseCode = tracker.courseCode,
            semester = tracker.semester,
            instructor = tracker.instructor.nonEmpty(),
            location = tracker.location.nonEmpty(),
            color = tracker.color.nonEmpty(),
        )
    }
}

/**
 * The Course Hub for the course stored under [courseKey] (see [resolveCourseKey]):
 * Attendance / Grades / Rules as swipeable pages under a tab row. The course's details
 * come from the timetable, or from the tracker itself once the class has left the
 * timetable; with neither, the hub closes.
 *
 * It opens ([AppNavigator.openCourseHub]) as a floating sheet over whatever is showing, resting
 * at about half height with the course, the tab row and the top of the page in view; pulled up,
 * it settles onto the window's edges.
 */
@Composable
fun CourseHubSheetHost(repo: CourseTrackerRepository, schedule: ScheduleRepository, connectivity: Connectivity, navigator: AppNavigator) {
    val subject = navigator.courseHub
    // The last course stays while the sheet animates away.
    var shown by remember { mutableStateOf(subject) }
    if (subject != null && subject != shown) shown = subject
    val courseKey = shown ?: return
    VSheet(
        open = subject != null,
        onDismiss = navigator::closeCourseHub,
        size = SheetSize.Expandable,
        title = "Course Hub",
        floating = true,
        halfFraction = SheetHalfFraction,
        onExited = { if (navigator.courseHub == null) shown = null },
    ) {
        key(courseKey) {
            // Only closes the hub it belongs to, never one opened after it.
            CourseHubFor(repo, schedule, connectivity, courseKey, { if (navigator.courseHub == courseKey) navigator.closeCourseHub() }, HubSurface.Sheet)
        }
    }
}

/** Share of the window the hub sheet covers at rest: the course, the tabs and the page's first cards. */
private const val SheetHalfFraction = 0.62f

/** Where the hub is drawn: a sheet over the app, or the wide Schedule tab's side pane. */
private enum class HubSurface { Sheet, Pane }

/**
 * The Course Hub as the wide Schedule tab's side pane: the same pages on a rounded surface,
 * with a close X instead of back. [onClose] empties the pane.
 */
@Composable
fun CourseHubPane(repo: CourseTrackerRepository, schedule: ScheduleRepository, connectivity: Connectivity, courseKey: String, onClose: () -> Unit) {
    CourseHubFor(repo, schedule, connectivity, courseKey, onClose, HubSurface.Pane)
}

@Composable
private fun CourseHubFor(
    repo: CourseTrackerRepository,
    schedule: ScheduleRepository,
    connectivity: Connectivity,
    courseKey: String,
    onBack: () -> Unit,
    surface: HubSurface,
) {
    val online by connectivity.online.collectAsStateWithLifecycle()
    val events by schedule.events.collectAsStateWithLifecycle()
    val eventsLoading by schedule.eventsLoading.collectAsStateWithLifecycle()
    val data by repo.data.collectAsStateWithLifecycle()
    val trackerLoading by repo.isLoading.collectAsStateWithLifecycle()
    val exit = rememberExitOnce(onBack)

    val course = remember(events, data, courseKey) {
        val matching = events.filter { resolveCourseKey(it) == courseKey }
        val event = matching.firstOrNull { !isExamEvent(it.eventType, it.courseName) } ?: matching.firstOrNull()
        event?.let(CourseTrackerCourse::from) ?: data.trackers[courseKey]?.let(CourseTrackerCourse::from)
    }
    val missing = course == null || course.courseName.isEmpty()
    LaunchedEffect(missing, eventsLoading, trackerLoading) { if (missing && !eventsLoading && !trackerLoading) exit() }

    if (course == null || missing) {
        if (surface == HubSurface.Sheet) Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { Spinner() }
        return
    }
    CourseHub(repo, course, events, online, exit, surface)
}

@Composable
private fun CourseHub(
    repo: CourseTrackerRepository,
    course: CourseTrackerCourse,
    events: List<ScheduleEvent>,
    online: Boolean,
    onBack: () -> Unit,
    surface: HubSurface,
) {
    val c = Vectra.colors
    val data by repo.data.collectAsStateWithLifecycle()
    val isLoading by repo.isLoading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val appToaster = LocalToaster.current
    val toast = remember(appToaster) { UndoToastState(appToaster) }
    val tabs = TrackerTab.entries
    val pager = rememberPagerState { tabs.size }
    // One scroll position per page, so swiping back finds a page where it was left.
    val scrolls = remember { tabs.map { ScrollState(0) } }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    val reduceMotion = Vectra.reduceMotion

    // Refetch when the hub opens and whenever the app comes back to the foreground (30 s staleness).
    LaunchedEffect(Unit) { repo.refreshIfStale() }
    LifecycleResumeEffect(repo) {
        repo.refreshIfStale()
        onPauseOrDispose { }
    }

    val identity = remember(course) {
        CourseIdentity(course.courseCode, course.courseName, course.semester, course.instructor, course.location, course.color)
    }
    val tracker = remember(data, identity) { findOrCreateTracker(data, identity) }

    // Academic week numbers count from the official semester start of this
    // course's semester (an intensive week before it is not Week 1).
    val semesterStart = remember(events, course.semester) {
        val sem = course.semester.lowercase().trim()
        TrackerCourses.officialSemesterStart(if (sem.isNotEmpty()) events.filter { it.semester.lowercase().trim() == sem } else events)
    }

    // Reconcile the sessions with the timetable whenever the generated list
    // differs from the stored one; `lastReconciled` stops a non-idempotent
    // regeneration from writing in a loop.
    val lastReconciled = remember { arrayOf<String?>(null) }
    LaunchedEffect(isLoading, tracker, events) {
        if (isLoading || events.isEmpty()) return@LaunchedEffect
        val current = tracker.attendance.sessions
        val generated = generateSessionsFromScheduleEvents(
            courseName = course.courseName,
            courseCode = course.courseCode,
            semester = course.semester,
            dayOfWeek = course.dayOfWeek,
            startTime = course.startTime,
            endTime = course.endTime,
            eventType = course.eventType,
            scheduleEvents = events,
            existingSessions = current,
            removedSessionKeys = tracker.attendance.removedSessionKeys,
        )
        val next = sessionsSignature(generated)
        if (generated.isNotEmpty() && next != sessionsSignature(current) && next != lastReconciled[0]) {
            lastReconciled[0] = next
            repo.replaceSessions(identity, generated)
        }
    }

    // Who teaches a session and where: the timetable row it was generated from (the one whose
    // dates cover it, when a room change split the slot), else the course the hub is about.
    val sessionMeta: (AttendanceSession) -> SessionMeta = { s ->
        val weekday = parseIsoDate(s.date)?.dayOfWeek?.let { d -> (d.ordinal + 1) % 7 } ?: -1
        val start = normalizeTime(s.startTime)
        val rows = events.filter { e ->
            TrackerCourses.isSameCourse(e.courseName, e.courseCode, course.courseName, course.courseCode) &&
                e.dayOfWeek == weekday &&
                (start == null || normalizeTime(e.startTime) == start) &&
                getEventSessionType(e) == s.sessionType
        }
        val match = rows.firstOrNull { e ->
            (e.semesterStart.isNullOrEmpty() || s.date >= e.semesterStart) && (e.semesterEnd.isNullOrEmpty() || s.date <= e.semesterEnd)
        } ?: rows.firstOrNull()
        SessionMeta(match?.instructor.nonEmpty() ?: course.instructor, match?.location.nonEmpty() ?: course.location)
    }

    // The pill follows the page a swipe is heading for, not only where it settles.
    val selected = tabs[pager.targetPage]
    // Derived, so scrolling recomposes only when the bar's hairline flips.
    val scrolled by remember { derivedStateOf { scrolls[pager.currentPage].value > 2 } }
    val sheet = surface == HubSurface.Sheet
    val tabRow: @Composable (Modifier) -> Unit = { modifier ->
        VTabs(selected, tabs, { next ->
            scope.launch {
                if (reduceMotion) pager.scrollToPage(next.ordinal)
                else pager.animateScrollToPage(next.ordinal, animationSpec = VectraSprings.settle())
            }
        }, modifier) { t, _ ->
            VIcon(
                when (t) {
                    TrackerTab.Attendance -> RemixIcons.CalendarCheckLine
                    TrackerTab.Grades -> RemixIcons.AwardLine
                    TrackerTab.Rules -> RemixIcons.EqualizerLine
                },
            )
            VText(t.label, maxLines = 1)
        }
    }

    // The sheet's own glass shows through; the pane is a rounded card beside the day list.
    Column(
        if (sheet) Modifier.fillMaxSize()
        else Modifier.fillMaxSize().glass(Radius.Card).clip(Radius.Card).background(c.surfacePrimary),
    ) {
        if (sheet) {
            HubSheetHeader(tracker, onClose = onBack) { tabRow(it) }
        } else {
            TopBar(
                title = tracker.courseName,
                leading = BarLeading.Close,
                onLeading = onBack,
                scrolled = scrolled,
                background = c.surfacePrimary,
                wrapTitle = true,
                accessory = { tabRow(Modifier.fillMaxWidth()) },
            )
        }
        val rule by animateColorAsState(if (sheet && scrolled) c.borderSubtle else Color.Transparent, tween(200, easing = VectraEasing.Fade), label = "hubRule")
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth().then(if (sheet) Modifier.topRule(rule) else Modifier.background(c.surfacePrimary)),
            beyondViewportPageCount = 1,
        ) { page ->
            val scroll = scrolls[page]
            Column(Modifier.fillMaxSize()) {
                ScrollBody(
                    modifier = Modifier.onGloballyPositioned { viewportTop = it.positionInRoot().y },
                    scrollState = scroll,
                    contentPadding = PaddingValues(start = SurfaceGutter, end = SurfaceGutter, top = 16.dp, bottom = 32.dp),
                ) {
                    when (tabs[page]) {
                        TrackerTab.Attendance -> AttendanceTab(
                            tracker = tracker,
                            semesterStart = semesterStart,
                            sessionMeta = sessionMeta,
                            onSetStatus = { id, status ->
                                repo.setAttendanceStatus(identity, id, status)
                            },
                            // By identity, not key: the tracker may still sit under a legacy key.
                            onAddSession = { repo.addAttendanceSession(identity, it) },
                            onRemoveSession = { repo.removeAttendanceSession(identity, it) },
                            onRestoreSession = { repo.restoreAttendanceSession(identity, it) },
                            toast = toast,
                            scroll = scroll,
                            viewportTop = { viewportTop },
                        )
                        TrackerTab.Grades -> PointsTab(
                            tracker = tracker,
                            online = online,
                            onAddAssessment = { repo.addAssessment(identity, it) },
                            onUpdateAssessment = { id, transform -> repo.updateAssessment(identity, id, transform) },
                            onRemoveAssessment = { repo.removeAssessment(identity, it) },
                            toast = toast,
                        )
                        TrackerTab.Rules -> SettingsTab(
                            tracker = tracker,
                            online = online,
                            onUpdateLimits = { repo.updateLimits(identity, it) },
                            onUpdateGradingScale = { repo.updateGradingScale(identity, it) },
                            onConfirmSettings = { repo.confirmTrackerSettings(identity) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The hub sheet's top: the course in large type with its code and teacher under a dot in the
 * class's colour, a close button, and the page [tabs]. Drags on it move the sheet.
 */
@Composable
private fun HubSheetHeader(tracker: CourseTracker, onClose: () -> Unit, tabs: @Composable (Modifier) -> Unit) {
    val c = Vectra.colors
    val details = listOfNotNull(tracker.courseCode.nonEmpty(), tracker.instructor.nonEmpty()).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp, end = 12.dp)) {
                VText(
                    tracker.courseName,
                    Modifier.semantics { heading() },
                    style = vtext(21.sp to 26.sp, Weight.Bold),
                    color = c.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (details.isNotEmpty()) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(parseOpaqueHexColor(tracker.color, c.accentPrimary), CircleShape))
                        VText(details, style = vtext(TypeScale.Caption, Weight.Medium), color = c.textSecondary, maxLines = 1)
                    }
                }
            }
            BarButton(RemixIcons.CloseLine, "Close", onClose)
        }
        tabs(Modifier.fillMaxWidth().padding(top = 14.dp, end = 6.dp))
    }
}
