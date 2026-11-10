package dev.southstar21.vectra.features.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.southstar21.vectra.features.shell.LocalTabSelected
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.PileCard
import dev.southstar21.vectra.core.designsystem.components.ScrollPile
import dev.southstar21.vectra.core.designsystem.components.LocalAppPresented
import dev.southstar21.vectra.core.designsystem.components.PostageStamp
import dev.southstar21.vectra.core.designsystem.components.Reveal
import dev.southstar21.vectra.core.designsystem.components.fadeLayer
import dev.southstar21.vectra.core.designsystem.components.StatusLabel
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VTabs
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.ContentMaxWidth
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.mixTransparent
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.formatDDMMYYYY
import dev.southstar21.vectra.core.time.formatDayMonth
import dev.southstar21.vectra.core.time.getDayName
import dev.southstar21.vectra.core.time.getShortDayName
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.designsystem.theme.tnum
import kotlin.time.Instant
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/**
 * Week navigation as Compose state: month offset + the user's week
 * (null until they navigate, so the first paint already shows the default week).
 */
@Stable
internal class WeekNavigation {
    var navDateOffset by mutableIntStateOf(0)
    var userWeekIndex by mutableStateOf<Int?>(null)
    var target: Pair<String, String>? = null
    var lastResetKey: String? = null
    var lastDefault: Int = 0
}

internal data class WeekNavResult(
    val semesterInfo: SemesterInfo,
    val monthWeeks: List<MonthWeek>,
    val defaultWeekIndex: Int,
    val selectedWeekIndex: Int,
    val activeWeek: MonthWeek,
    val isAtDefaultWeek: Boolean,
    val academicWeekInfo: AcademicWeekInfo?,
    val onPrev: () -> Unit,
    val onNext: () -> Unit,
    val onReset: () -> Unit,
)

@Composable
internal fun rememberWeekNavigation(events: List<ScheduleEvent>, selectedSemester: String, todayStr: String): WeekNavResult {
    val nav = remember { WeekNavigation() }
    val status = remember(events, selectedSemester, todayStr) { computeSemesterStatus(events, selectedSemester, todayStr) }
    val info = status.info
    val activeMonth = remember(status.baseYmd, nav.navDateOffset) { activeMonthFor(status.baseYmd, nav.navDateOffset) }
    val monthWeeks = remember(activeMonth, todayStr) { getWeeksInMonth(activeMonth, todayStr) }
    val defaultIndex = remember(monthWeeks, info.status, info.startDate, nav.navDateOffset) {
        resolveDefaultWeekIndex(monthWeeks, info.status, info.startDate, nav.navDateOffset)
    }
    val resetKey = "$activeMonth|${info.status}|${info.startDate.orEmpty()}"
    LaunchedEffect(monthWeeks, defaultIndex, resetKey) {
        val previousDefault = nav.lastDefault
        nav.lastDefault = defaultIndex
        val contextChanged = nav.lastResetKey != resetKey
        nav.lastResetKey = resetKey
        val t = nav.target
        if (t != null) {
            nav.target = null
            nav.userWeekIndex = resolveAdjacentWeekIndex(monthWeeks, t.first, t.second)
            return@LaunchedEffect
        }
        nav.userWeekIndex = resolveWeekIndexAfterRecompute(contextChanged, nav.userWeekIndex, previousDefault, defaultIndex)
    }
    val resolved = nav.userWeekIndex ?: defaultIndex
    val activeWeek = monthWeeks.getOrNull(resolved) ?: monthWeeks.firstOrNull()
        ?: MonthWeek(0, activeMonth, "", "", "", false)
    val academic = remember(info.startDate, activeWeek, events, selectedSemester) {
        computeAcademicWeekInfo(events, selectedSemester, info.startDate, activeWeek)
    }
    return WeekNavResult(
        semesterInfo = info,
        monthWeeks = monthWeeks,
        defaultWeekIndex = defaultIndex,
        selectedWeekIndex = resolved,
        activeWeek = activeWeek,
        isAtDefaultWeek = nav.navDateOffset == 0 && resolved == defaultIndex,
        academicWeekInfo = academic,
        onPrev = {
            if (resolved > 0) nav.userWeekIndex = resolved - 1
            else {
                nav.target = "before" to activeWeek.startStr
                nav.navDateOffset -= 1
            }
        },
        onNext = {
            if (resolved < monthWeeks.size - 1) nav.userWeekIndex = resolved + 1
            else {
                nav.target = "after" to activeWeek.startStr
                nav.navDateOffset += 1
            }
        },
        onReset = {
            nav.target = null
            nav.navDateOffset = 0
            nav.userWeekIndex = defaultIndex
        },
    )
}

@Composable
internal fun ScheduleToolbar(
    nav: WeekNavResult,
    selectedDay: Int?,
    onDayChange: (Int?) -> Unit,
) {
    val c = Vectra.colors
    Column(
        Modifier.fillMaxWidth().flushGlass(Radius.Card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(40.dp).glass(CircleShape).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(RemixIcons.ArrowLeftSLine, "Previous Week", nav.onPrev)
                Row(
                    Modifier.weight(1f).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VText(
                        nav.activeWeek.rangeLabel,
                        Modifier.weight(1f, fill = false),
                        vtext(TypeScale.Caption, Weight.Bold, mono = true),
                        c.textPrimary,
                        maxLines = 1,
                    )
                    if (!nav.isAtDefaultWeek) {
                        Box(
                            Modifier.size(20.dp).tappable(
                                CircleShape,
                                role = null,
                                onClickLabel = if (nav.semesterInfo.status == "upcoming") "Jump to semester start" else "Jump to current week",
                                onClick = nav.onReset,
                            ),
                            contentAlignment = Alignment.Center,
                        ) { VIcon(RemixIcons.ResetLeftLine, size = 12.dp, tint = c.tints.blue.text) }
                    }
                }
                RoundIconButton(RemixIcons.ArrowRightSLine, "Next Week", nav.onNext)
            }
            nav.academicWeekInfo?.let { week ->
                val ink = if (week.isStarted) c.accentPrimary else c.textSecondary
                Row(
                    Modifier.height(40.dp).padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VIcon(RemixIcons.GraduationCapLine, size = 14.dp, tint = ink)
                    VText(week.label, style = vtext(TypeScale.Caption, Weight.Bold, mono = true), color = ink, maxLines = 1)
                }
            }
        }

        val options = remember { listOf<Int?>(null) + DayOrder }
        VTabs(
            selected = selectedDay,
            options = options,
            onSelect = onDayChange,
            modifier = Modifier.fillMaxWidth(),
            columns = options.size,
            triggerHeight = 32.dp,
        ) { day, _ ->
            VText(
                if (day == null) "All" else getShortDayName(day),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Vectra.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(interaction).size(32.dp).tappable(role = null, onClickLabel = label, highlight = false, interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { VIcon(icon, size = 16.dp, tint = c.textSecondary, contentDescription = label) }
}

@Composable
internal fun SemesterStatusBanner(
    info: SemesterInfo,
    selectedSemester: String,
    activeWeekRangeLabel: String,
    isAtDefaultWeek: Boolean,
    onReset: () -> Unit,
) {
    if (!shouldShowSemesterStatus(info, selectedSemester)) return
    val c = Vectra.colors
    val upcoming = info.status == "upcoming"
    val tint = if (upcoming) c.tints.blue else c.tints.purple
    val shape = Radius.Card
    Column(
        Modifier.fillMaxWidth().flushGlass(shape).background(tint.bg, shape).border(1.dp, tint.border, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val chip = Radius.Control
            Box(
                Modifier.padding(top = 2.dp).size(32.dp).background(tint.bg, chip).border(1.dp, tint.border, chip),
                contentAlignment = Alignment.Center,
            ) { VIcon(if (upcoming) RemixIcons.CalendarLine else RemixIcons.ResetLeftLine, size = 16.dp, tint = tint.text) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusLabel(if (upcoming) "Upcoming semester" else "Past semester", tone = if (upcoming) StatusTone.Blue else StatusTone.Purple)
                    VText(
                        if (upcoming) info.startDate?.let { "Starts ${formatDDMMYYYY(it)}" } ?: "Upcoming" else "Semester $selectedSemester",
                        style = vtext(TypeScale.Caption, Weight.Bold),
                        color = c.textPrimary,
                    )
                }
                val body = if (upcoming) "Classes start in ${info.daysUntil} days. Showing schedule for: $activeWeekRangeLabel."
                else (info.startDate?.let { "Semester started on ${formatDDMMYYYY(it)}. " } ?: "") + "Showing schedule for: $activeWeekRangeLabel."
                VText(body, Modifier.padding(top = 4.dp), vtext(11.sp to 17.875.sp), c.textSecondary)
            }
        }
        if (!isAtDefaultWeek) {
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .pressScale(interaction)
                    .background(c.surfaceOverlay, CircleShape)
                    .border(1.dp, c.borderSubtle, CircleShape)
                    .tappable(role = null, highlight = false, interactionSource = interaction, onClick = onReset)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VIcon(RemixIcons.ResetLeftLine, Modifier.padding(end = 4.dp), size = 14.dp, tint = tint.text)
                VText("Jump to semester start", style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textSecondary, maxLines = 1)
            }
        }
    }
}

/** The schedule: toolbar, semester banner and the week's day cards. */
@Composable
internal fun ScheduleView(
    events: List<ScheduleEvent>,
    tracker: ScheduleCourseTrackerHooks,
    semesters: List<String>,
    currentSemester: String,
    scheduleItems: List<StoredScheduleItem>,
    conflicts: List<ScheduleConflict>,
    externalNeedsAcknowledgmentIds: Set<String>,
    syncConflictIds: Set<String>,
    onEventClick: (ScheduleEvent) -> Unit,
    onItemClick: (ScheduleItem) -> Unit,
    onToggleTaskCompleted: (ScheduleItem) -> Unit,
    onResolveSyncConflict: (String, String) -> Unit,
) {
    val c = Vectra.colors
    val clock = rememberMinuteClock()
    val todayStr = nowInBudapest(Instant.fromEpochMilliseconds(clock)).ymd
    // No semester picker: the current semester, or the newest when it has no events yet.
    val selectedSemester = remember(semesters, currentSemester) { resolveSelectedSemester(semesters, currentSemester) }
    val nav = rememberWeekNavigation(events, selectedSemester, todayStr)
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    // Leaving the tab brings the next visit back to this week with every day shown.
    val tabSelected = LocalTabSelected.current
    val latestNav by rememberUpdatedState(nav)
    LaunchedEffect(tabSelected) {
        if (tabSelected) return@LaunchedEffect
        selectedDay = null
        if (!latestNav.isAtDefaultWeek) latestNav.onReset()
    }
    val hasAnyScheduleItems = events.isNotEmpty() || scheduleItems.isNotEmpty()
    val plainItems = remember(scheduleItems) { scheduleItems.map { it.toItem() } }
    val intensiveKeys = remember(events) { ScheduleCourses.intensiveCourseKeys(events) }
    val byEventDate = remember(conflicts) {
        conflicts.filter { it.classId != null }.groupBy { "${it.date}:${it.classId}" }
    }
    val byItemDate = remember(conflicts) {
        val out = LinkedHashMap<String, MutableList<ScheduleConflict>>()
        for (cf in conflicts) for (id in listOfNotNull(cf.itemId, cf.otherItemId)) out.getOrPut("${cf.date}:$id") { mutableListOf() } += cf
        out
    }
    val needsAck = remember(conflicts, externalNeedsAcknowledgmentIds) {
        conflicts.filter { it.needsAcknowledgment }.flatMap { listOfNotNull(it.itemId, it.otherItemId) }.toSet() + externalNeedsAcknowledgmentIds
    }
    val viewSyncIds = remember(scheduleItems, syncConflictIds) { syncConflictIds + scheduleItems.filter { it.syncConflict != null }.map { it.id } }

    val week = nav.activeWeek
    val ring = remember { listOf<Int?>(null) + DayOrder }
    // Remembered: daySwipe keys its detector on this lambda. It reads selectedDay's state when called.
    val swipe: (Int) -> Unit = remember {
        { step ->
            val i = ring.indexOf(selectedDay)
            if (i != -1) selectedDay = ring[(i + step + ring.size) % ring.size]
        }
    }

    Column(Modifier.fillMaxWidth().widthIn(max = ContentMaxWidth), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Reveal(0) { ScheduleToolbar(nav, selectedDay) { selectedDay = it } }
        if (shouldShowSemesterStatus(nav.semesterInfo, selectedSemester)) {
            Reveal(1) {
                SemesterStatusBanner(nav.semesterInfo, selectedSemester, week.rangeLabel, nav.isAtDefaultWeek, nav.onReset)
            }
        }

        val presented = LocalAppPresented.current
        val rise = with(LocalDensity.current) { 10.dp.roundToPx() }
        AnimatedContent(
            targetState = week,
            contentKey = { it.startStr },
            // Faded by hand below in a fadeLayer: the built-in fade's layer clips the cards' outer
            // shadows until it ends. No size animation: the height swaps in one step.
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished using null },
            label = "agendaWeek",
        ) { shownWeek ->
            val spec: @Composable Transition.Segment<EnterExitState>.() -> FiniteAnimationSpec<Float> = {
                if (targetState == EnterExitState.Visible) tween(200, delayMillis = 200) else tween(200)
            }
            val fade by transition.animateFloat(spec, label = "agendaFade") { if (it == EnterExitState.Visible) 1f else 0f }
            val shift by transition.animateFloat(spec, label = "agendaShift") {
                when (it) {
                    EnterExitState.PreEnter -> 1f
                    EnterExitState.Visible -> 0f
                    EnterExitState.PostExit -> -1f
                }
            }
            // Built once per week, so the outgoing week keeps its own days while it fades out.
            val agenda = remember(shownWeek.startStr, events, selectedSemester, selectedDay, plainItems, todayStr) {
                buildAgenda(shownWeek, events, selectedSemester, selectedDay, plainItems, todayStr)
            }
            // The outgoing week keeps its reset button as it was, like AnimatePresence's exiting element.
            val atDefault = remember { booleanArrayOf(nav.isAtDefaultWeek) }
            if (shownWeek.startStr == week.startStr) atDefault[0] = nav.isAtDefaultWeek
            val activeDays = agenda.filterNot { it.isEmpty }
            Column(
                Modifier.fillMaxWidth().fadeLayer {
                    alpha = if (presented) fade else 0f
                    translationY = shift * rise
                },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (activeDays.isEmpty()) {
                    EmptyWeekCard(
                        title = when {
                            selectedDay != null -> "Nothing scheduled on ${getDayName(selectedDay!!)}"
                            events.isNotEmpty() -> "No classes this week"
                            hasAnyScheduleItems -> "Nothing scheduled this week"
                            else -> "You don't have any schedule items"
                        },
                        showReset = !atDefault[0],
                        resetLabel = if (nav.semesterInfo.status == "upcoming") "Jump to semester start" else "Jump to current week",
                        onReset = nav.onReset,
                        modifier = Modifier.daySwipe(swipe),
                    )
                } else {
                    val days = if (selectedDay != null) agenda else activeDays
                    // Each day piles under the next as the page scrolls; the 24dp above a day's glass is its stamp.
                    ScrollPile(
                        spacing = 16.dp,
                        cards = days.mapNotNull { agendaDay ->
                            val day = agendaDay.day
                            val info = agendaDay.info
                            when {
                                agendaDay.isEmpty && selectedDay == null -> null
                                agendaDay.isEmpty -> PileCard(day, getDayName(day)) {
                                    Column(
                                        Modifier.fillMaxWidth().daySwipe(swipe).flushGlass(Radius.Card).padding(32.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        VText(
                                            if (hasAnyScheduleItems) "Nothing scheduled on ${getDayName(day)}" else "You don't have any schedule items",
                                            style = vtext(TypeScale.Caption, Weight.Bold), color = c.textSecondary, align = TextAlign.Center,
                                        )
                                        VText("Swipe left or right to browse other days", Modifier.padding(top = 8.dp), vtext(TypeScale.Micro), c.textMuted, align = TextAlign.Center)
                                    }
                                }
                                else -> PileCard(day, getDayName(day), topInset = 24.dp) {
                                    DayCard(
                                        day = day,
                                        info = info,
                                        modifier = Modifier.daySwipe(swipe),
                                    ) {
                                        ScheduleDayTimeline(
                                            events = agendaDay.events,
                                            tracker = tracker,
                                            items = agendaDay.items,
                                            dateStr = info.dateStr,
                                            onEventClick = onEventClick,
                                            onItemClick = onItemClick,
                                            onToggleTaskCompleted = onToggleTaskCompleted,
                                            onResolveSyncConflict = onResolveSyncConflict,
                                            syncConflictIds = viewSyncIds,
                                            needsAcknowledgmentIds = needsAck,
                                            itemConflictsById = byItemDate,
                                            classConflictsByEventId = byEventDate,
                                            intensiveCourses = intensiveKeys,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private class AgendaDay(val day: Int, val info: DayDateInfo, val events: List<ScheduleEvent>, val items: List<ScheduleItemOccurrence>) {
    val isEmpty get() = events.isEmpty() && items.isEmpty()
}

/** The displayed days of one week with their classes and item occurrences. */
private fun buildAgenda(
    week: MonthWeek,
    events: List<ScheduleEvent>,
    selectedSemester: String,
    selectedDay: Int?,
    items: List<ScheduleItem>,
    todayStr: String,
): List<AgendaDay> {
    val byDay = groupEventsByDay(filterScheduleEvents(events, selectedSemester, selectedDay, week.startStr, week.endStr))
    val occurrences = getScheduleItemOccurrences(items, week.startStr, week.endStr)
    val index = ScheduleEventIndex(events)
    val days = if (selectedDay == null) DayOrder else listOf(selectedDay)
    return days.map { day ->
        val info = getFormattedDateForDay(day, week.monday, todayStr)
        AgendaDay(
            day,
            info,
            byDay[day].orEmpty().filter { classOccursOnDate(it, info.dateStr, index) },
            occurrences.filter { it.date == info.dateStr },
        )
    }
}

/** A day of the agenda: postage-stamp title stuck over the card's top edge. */
@Composable
private fun DayCard(day: Int, info: DayDateInfo, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Vectra.colors
    val shape = Radius.Card
    Box(modifier.fillMaxWidth().padding(top = 24.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (info.isToday) Modifier.boxShadow(shape, listOf(BoxShadow(blur = 24f, color = rgba(56, 189, 248, 0.15f)))) else Modifier)
                .flushGlass(shape)
                .then(if (info.isToday) Modifier.border(1.dp, c.accentPrimary.copy(alpha = 0.4f), shape) else Modifier)
                .padding(start = 12.dp, end = 12.dp, top = 32.dp, bottom = 12.dp),
        ) { content() }
        PostageStamp(
            Modifier
                .padding(start = 16.dp)
                .offset(y = (-20).dp)
                .rotate(if (day % 2 == 0) 2f else -3f)
                .then(
                    if (info.isToday) Modifier.boxShadow(
                        Radius.Tag,
                        listOf(BoxShadow(blur = 22f, color = c.accentPrimary.mixTransparent(55f))),
                    ) else Modifier,
                ),
            innerModifier = Modifier
                .then(if (info.isToday) Modifier.border(1.5.dp, c.accentPrimary, Radius.Hairline) else Modifier)
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
        ) {
            VText(getDayName(day), style = vtext(TypeScale.BodyLarge, Weight.Extrabold, Tracking.Tight, lineHeight = 16.sp), color = c.textPrimary, maxLines = 1)
            VText(formatDayMonth(info.dateStr), style = vtext(11.sp to 11.sp, Weight.Semibold).tnum(), color = c.accentPrimary, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyWeekCard(title: String, showReset: Boolean, resetLabel: String, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    Column(
        modifier.fillMaxWidth().flushGlass(Radius.Card).padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val tile = Radius.Panel
        Box(
            Modifier.size(48.dp).background(c.surfaceOverlay, tile).border(1.dp, c.borderSubtle, tile),
            contentAlignment = Alignment.Center,
        ) { VText("📅", style = vtext(TypeScale.Title)) }
        VText(title, style = vtext(TypeScale.Body, Weight.Bold), color = c.textPrimary, align = TextAlign.Center)
        VText("Swipe left or right to browse other days", style = vtext(11.sp to 16.sp), color = c.textMuted, align = TextAlign.Center)
        if (showReset) {
            ChipButton(Modifier.padding(top = 8.dp), onClick = onReset) {
                VIcon(RemixIcons.ResetLeftLine, size = 14.dp, tint = c.tints.blue.text)
                VText(resetLabel, style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textSecondary, maxLines = 1)
            }
        }
    }
}
