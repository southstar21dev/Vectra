package dev.southstar21.vectra.features.coursetracker

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.ConfirmDialog
import dev.southstar21.vectra.core.designsystem.components.PillRadio
import dev.southstar21.vectra.core.designsystem.components.SelectOption
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.LocalAppearance
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.components.SectionTitle
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.features.holidays.rememberNowInstant
import dev.southstar21.vectra.features.schedule.getWeekBeforeStart
import dev.southstar21.vectra.features.schedule.isIntensiveClass
import kotlinx.coroutines.delay
import dev.southstar21.vectra.core.designsystem.theme.Radius

internal data class TypeInfo(val label: String, val plural: String, val tone: StatusTone)

private val SessionTypes = mapOf(
    SessionType.Lecture to TypeInfo("Lecture", "Lectures", StatusTone.Sky),
    SessionType.Seminar to TypeInfo("Seminar", "Seminars", StatusTone.Emerald),
    SessionType.Lab to TypeInfo("Lab", "Labs", StatusTone.Purple),
    SessionType.Consultation to TypeInfo("Consultation", "Consultations", StatusTone.Amber),
    SessionType.Exam to TypeInfo("Exam", "Exams", StatusTone.Violet),
    SessionType.Other to TypeInfo("Session", "Other", StatusTone.Neutral),
)

/** Ledger order of the type filter and the semester rows. */
private val TypeOrder = listOf(SessionType.Lecture, SessionType.Seminar, SessionType.Lab, SessionType.Consultation, SessionType.Exam, SessionType.Other)

private val IntensiveInfo = TypeInfo("Intensive", "Intensive", StatusTone.Amber)

/** Who teaches a session and where, from the timetable row it came from. */
data class SessionMeta(val instructor: String?, val location: String?)

/**
 * The attendance tab: today's class, the standing card with the semester as
 * dots, and the full ledger (filterable by type) with add/delete and undo.
 */
@Composable
internal fun AttendanceTab(
    tracker: CourseTracker,
    semesterStart: String?,
    sessionMeta: (AttendanceSession) -> SessionMeta,
    onSetStatus: (String, AttendanceStatus) -> Unit,
    onAddSession: (AttendanceSession) -> Unit,
    onRemoveSession: (AttendanceSession) -> Unit,
    onRestoreSession: (AttendanceSession) -> Unit,
    toast: UndoToastState,
    scroll: ScrollState,
    /** Root-space top of the scrolling body's viewport (to centre a ledger row). */
    viewportTop: () -> Float,
) {
    val c = Vectra.colors
    val timeFormat = LocalAppearance.current.timeFormat
    var showAdd by remember { mutableStateOf(false) }
    // The ledger always opens on seminars — the sessions attendance is usually counted on.
    var typeFilter by remember { mutableStateOf<SessionType?>(SessionType.Seminar) }
    var pendingDelete by remember { mutableStateOf<AttendanceSession?>(null) }
    var flashId by remember { mutableStateOf<String?>(null) }
    var scrollTarget by remember { mutableStateOf<String?>(null) }
    /** Root-space centre line of each rendered ledger row. */
    val rowCentres = remember { mutableStateMapOf<String, Float>() }

    // Sessions are Budapest dates; "today" rolls over at midnight while open.
    val now = rememberNowInstant(60_000)
    val todayIso = nowInBudapest(now).ymd

    val sessions = remember(tracker.attendance.sessions) { deduplicateSessions(tracker.attendance.sessions).sortedWith(SessionOrder) }
    val presentTypes = TypeOrder.filter { t -> sessions.any { it.sessionType == t } }

    // Fall back to "all" when the starting filter has nothing to show.
    if (sessions.isNotEmpty() && typeFilter != null && sessions.none { it.sessionType == typeFilter }) typeFilter = null

    val stats = calculateAttendanceStats(sessions, tracker.attendance.maxAbsencesAllowed, null, todayIso)
    val risk = getAttendanceRiskTier(stats.absentCount, tracker.attendance.maxAbsencesAllowed, stats.attendancePercent, null, stats.activeCount)

    // An intensive week class happens only in the week right before classes start.
    val isIntensive = remember(tracker.courseName, tracker.courseCode, semesterStart, sessions) {
        if (isIntensiveClass(tracker.courseName, tracker.courseCode)) true
        else {
            val pre = getWeekBeforeStart(semesterStart)
            val dates = sessions.map { it.date }.filter { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) }
            pre != null && dates.isNotEmpty() && dates.all { it >= pre.startStr && it <= pre.endStr }
        }
    }

    fun typeInfo(type: SessionType) = if (isIntensive && type == SessionType.Seminar) IntensiveInfo else SessionTypes.getValue(type)

    // Generic "Week N" titles get the academic week label (a mid-week start can
    // shift them); intensive pre-semester sessions read "Intensive Week".
    fun displayTitle(s: AttendanceSession): String {
        val clean = s.title.replace(Regex("\\s+(lecture|seminar|lab|practice|exam|session|consultation)$", RegexOption.IGNORE_CASE), "")
        val week = getAcademicWeekInfo(s.date, semesterStart)
        return if (week != null && Regex("^week\\s+\\d+$", RegexOption.IGNORE_CASE).matches(clean.trim())) {
            if (isIntensive && week.weekNumber == 0) "Intensive Week" else week.label
        } else clean
    }

    fun describe(s: AttendanceSession): String {
        val mark = if (s.status == AttendanceStatus.Unmarked) "Pending" else StatusLabels.getValue(s.status)
        return "${typeInfo(s.sessionType).label}, ${displayTitle(s)}, ${formatSessionDate(s.date, todayIso)}: $mark"
    }

    val todaySessions = sessions.filter { it.date == todayIso }
    val strips = presentTypes.map { type ->
        val of = sessions.filter { it.sessionType == type }
        val info = typeInfo(type)
        StandingStrip(info.plural, info.tone, calculateAttendanceStats(of, null, null, todayIso), of)
    }
    val filtered = if (typeFilter == null) sessions else sessions.filter { it.sessionType == typeFilter }

    // A dot jumps to its row: widen the filter if needed, scroll it into view and wash it.
    fun selectSession(s: AttendanceSession) {
        if (typeFilter != null && s.sessionType != typeFilter) typeFilter = null
        flashId = s.id
        scrollTarget = s.id
    }
    LaunchedEffect(scrollTarget, rowCentres[scrollTarget ?: ""]) {
        val id = scrollTarget ?: return@LaunchedEffect
        val centre = rowCentres[id] ?: return@LaunchedEffect
        scroll.animateScrollBy(centre - (viewportTop() + scroll.viewportSize / 2f))
        scrollTarget = null
    }
    LaunchedEffect(flashId) {
        if (flashId != null) {
            delay(1200)
            flashId = null
        }
    }

    fun performDelete(s: AttendanceSession) {
        onRemoveSession(s)
        val title = displayTitle(s)
        toast.show("Session deleted", "${formatSessionDate(s.date, todayIso)}${if (title.isNotEmpty()) " · $title" else ""}") { onRestoreSession(s) }
    }

    fun requestDelete(s: AttendanceSession) {
        if (s.status != AttendanceStatus.Unmarked) pendingDelete = s else performDelete(s)
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (sessions.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().glassDepth(Radius.Card).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VIcon(RemixIcons.CalendarLine, size = 32.dp, tint = c.textMuted)
                VText("No sessions yet", Modifier.padding(top = 12.dp), vtext(TypeScale.BodyLarge, Weight.Extrabold, Tracking.Tight), c.textPrimary, align = TextAlign.Center)
                VText(
                    "Sessions appear here once this course is in your timetable. You can also add one by hand.",
                    Modifier.padding(top = 4.dp).widthIn(max = 320.dp),
                    vtext(TypeScale.Body),
                    c.textSecondary,
                    align = TextAlign.Center,
                )
                TrackerButton(onClick = { showAdd = true }, modifier = Modifier.padding(top = 16.dp)) { ink ->
                    VIcon(RemixIcons.AddLine, size = 14.dp, tint = ink)
                    VText("Add a session", style = vtext(TypeScale.Caption, Weight.Bold), color = ink)
                }
            }
        } else {
            for (s in todaySessions) {
                val meta = sessionMeta(s)
                TodaySessionCard(
                    session = s,
                    typeLabel = typeInfo(s.sessionType).label,
                    weekTitle = displayTitle(s),
                    instructor = meta.instructor,
                    location = meta.location,
                    timeFormat = timeFormat,
                    onSetStatus = onSetStatus,
                )
            }
            AttendanceStandingCard(
                maxAbsences = tracker.attendance.maxAbsencesAllowed,
                stats = stats,
                riskTier = risk,
                strips = strips,
                todayIso = todayIso,
                describe = ::describe,
                onSelectSession = ::selectSession,
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Sessions", action = { AddPill { showAdd = true } })
                val options = listOf(SelectOption<SessionType?>(null, "All")) + presentTypes.map { SelectOption<SessionType?>(it, typeInfo(it).plural) }
                if (options.size > 2) {
                    PillRadio(typeFilter, options, { typeFilter = it }, columns = minOf(4, options.size))
                }
                Box(Modifier.fillMaxWidth().clip(Radius.Card).glassDepth(Radius.Card)) {
                    Column(Modifier.fillMaxWidth()) {
                        Divided(
                            filtered.map { s ->
                                @Composable {
                                    Box(Modifier.onGloballyPositioned { rowCentres[s.id] = it.positionInRoot().y + it.size.height / 2f }) {
                                        SessionLedgerRow(
                                            session = s,
                                            title = displayTitle(s),
                                            badgeLabel = typeInfo(s.sessionType).label,
                                            badgeTone = typeInfo(s.sessionType).tone,
                                            todayIso = todayIso,
                                            timeFormat = timeFormat,
                                            flash = flashId == s.id,
                                            onSetStatus = onSetStatus,
                                            onRequestDelete = ::requestDelete,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    AddSessionDialog(open = showAdd, onDismiss = { showAdd = false }, onAddSession = { onAddSession(it); showAdd = false })

    // Destructive confirmation for marked sessions (the undo toast covers the rest).
    val pd = pendingDelete
    ConfirmDialog(
        open = pd != null,
        onClose = { pendingDelete = null },
        onConfirm = { pd?.let(::performDelete) },
        title = "Delete this session?",
        description = if (pd != null) "This removes the ${formatSessionDate(pd.date, todayIso)} session marked “${StatusLabels.getValue(pd.status)}”. You can undo right after." else "",
        confirmText = "Delete session",
        cancelText = "Keep session",
    )
}
