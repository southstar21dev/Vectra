package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.designsystem.theme.plannerCard
import kotlin.time.Instant
import kotlinx.coroutines.coroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.ConfirmDialog
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.OfflineBanner
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.pulse
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.features.navbar.NavbarAction
import dev.southstar21.vectra.features.navbar.NavbarActionConfig
import dev.southstar21.vectra.features.navbar.NavbarActionVariant
import dev.southstar21.vectra.features.navbar.RegisterNavbarActions
import dev.southstar21.vectra.features.shell.VectraPage
import dev.southstar21.vectra.features.shell.PagePane
import dev.southstar21.vectra.features.shell.PaneSide
import dev.southstar21.vectra.core.designsystem.theme.ContentMaxWidth
import dev.southstar21.vectra.core.designsystem.theme.LocalWideLayout
import dev.southstar21.vectra.core.designsystem.theme.WideContentMaxWidth
import androidx.compose.runtime.key
import dev.southstar21.vectra.navigation.LocalNavigator
import dev.southstar21.vectra.navigation.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dev.southstar21.vectra.core.designsystem.theme.Radius
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The Schedule tab. On a wide screen a class opens beside the day list in [classPane] (the
 * Course Hub; passed in, since the schedule never imports the course tracker) instead of on a
 * screen of its own.
 */
@Composable
fun ScheduleScreen(
    repo: ScheduleRepository,
    tracker: ScheduleCourseTrackerHooks,
    classPane: (@Composable (courseKey: String, onClose: () -> Unit) -> Unit)? = null,
) {
    LaunchedEffect(repo) { repo.ensureFresh() }
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    val events by repo.events.collectAsStateWithLifecycle()
    val eventsLoading by repo.eventsLoading.collectAsStateWithLifecycle()
    val online by repo.isOnline.collectAsStateWithLifecycle()
    val itemsState by repo.itemsState.collectAsStateWithLifecycle()
    val itemSyncError by repo.itemSyncError.collectAsStateWithLifecycle()
    val eventsError by repo.eventsError.collectAsStateWithLifecycle()
    val conflicts by repo.conflicts.collectAsStateWithLifecycle()

    val items = itemsState.items
    val isOffline = !online
    val isLoading = if (isOffline) false else eventsLoading
    val clock = rememberMinuteClock()
    val todayYmd = nowInBudapest(Instant.fromEpochMilliseconds(clock)).ymd
    val semesters = remember(events) { scheduleSemesters(events) }
    val currentSemester = remember(todayYmd) { getCurrentSemester() }
    val needsAckIds = remember(conflicts) { conflicts.filter { it.needsAcknowledgment }.flatMap { listOfNotNull(it.itemId, it.otherItemId) }.toSet() }
    val syncConflictIds = remember(items) { items.filter { it.syncConflict != null }.map { it.id }.toSet() }

    var showNeptunNotice by remember { mutableStateOf(false) }

    val navigator = LocalNavigator.current
    val openImport = { navigator.push(Screen.NeptunImport) }
    val openNewClass = { navigator.push(Screen.ClassEditor()) }
    val openNewItem = { type: String -> navigator.push(Screen.ItemEditor(kind = type)) }

    // Sync and add live in the navbar's action bubble.
    RegisterNavbarActions(
        NavbarActionConfig(
            icon = RemixIcons.AddLine,
            actions = listOf(
                NavbarAction("sync", "Sync Neptun", RemixIcons.UploadLine, NavbarActionVariant.Blue) { openImport() },
                NavbarAction("add-task", "Add task", RemixIcons.TaskLine, NavbarActionVariant.Gray) { openNewItem(ScheduleItem.TYPE_TASK) },
                NavbarAction("add-shift", "Add work shift", RemixIcons.BriefcaseLine, NavbarActionVariant.Gray) { openNewItem(ScheduleItem.TYPE_SHIFT) },
                NavbarAction("add-class", "Add class", RemixIcons.AddLine, NavbarActionVariant.Primary) { openNewClass() },
            ),
        ),
    )

    val handleToggleTask: (ScheduleItem) -> Unit = { item ->
        scope.launch {
            try {
                repo.toggleTaskCompleted(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScheduleItemConflictException) {
                // Un-ticking hit a clash: acknowledge it in the editor, which saves the task as not done.
                navigator.push(Screen.ItemEditor(item.id, item.type, reopen = true))
            } catch (e: Throwable) {
                toaster.error(e.message ?: "Could not update task.")
            }
        }
    }

    val handleItemClick: (ScheduleItem) -> Unit = { item ->
        items.firstOrNull { it.id == item.id }?.let { stored -> navigator.push(Screen.ItemEditor(stored.id, stored.type)) }
    }

    val wide = LocalWideLayout.current && classPane != null
    // The class beside the day list (wide only): the one on now or next today until a tap
    // picks another; closing the pane leaves it empty until the next tap.
    var picked by remember { mutableStateOf<String?>(null) }
    var paneClosed by remember { mutableStateOf(false) }
    val upcoming = remember(events, clock) { upcomingTrackedClass(events, Instant.fromEpochMilliseconds(clock))?.let(tracker::courseKey) }
    val paneKey = picked ?: upcoming.takeIf { !paneClosed }

    val handleEventClick: (ScheduleEvent) -> Unit = { event ->
        if (event.source == "neptun") {
            // Neptun classes open their Course Hub; exams have no attendance, so say why they can't be edited.
            if (event.isExam()) {
                showNeptunNotice = true
            } else {
                val key = tracker.courseKey(event)
                if (wide) picked = key else navigator.openCourseHub(key)
            }
        } else {
            navigator.push(Screen.ClassEditor(event.id))
        }
    }

    VectraPage(
        spacing = 0,
        maxWidth = if (wide) WideContentMaxWidth else ContentMaxWidth,
        pane = classPane?.takeIf { wide }?.let { showClass ->
            PagePane(PaneSide.End, weight = 0.5f) {
                val course = paneKey
                if (course == null) {
                    ClassPanePlaceholder()
                } else {
                    key(course) {
                        showClass(course) {
                            picked = null
                            paneClosed = true
                        }
                    }
                }
            }
        },
        onRefresh = {
            coroutineScope {
                launch { repo.refreshEvents(force = true) }
                launch { repo.refreshItems(force = true) }
            }
        },
    ) {
        if (isOffline) OfflineBanner(message = "No internet connection: viewing cached offline schedule")
        eventsError?.takeIf { !isOffline }?.let { OfflineBanner(message = it, badgeLabel = "Not loaded") }
        if (itemsState.queue.isNotEmpty() && !isOffline) {
            OfflineBanner(message = itemSyncError ?: "Schedule item changes are waiting to sync.")
        }

        when {
            isLoading -> ScheduleSkeleton()
            else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                ScheduleView(
                    events = events,
                    tracker = tracker,
                    semesters = semesters,
                    currentSemester = currentSemester,
                    scheduleItems = items,
                    conflicts = conflicts,
                    externalNeedsAcknowledgmentIds = needsAckIds,
                    syncConflictIds = syncConflictIds,
                    onEventClick = handleEventClick,
                    onItemClick = handleItemClick,
                    onToggleTaskCompleted = handleToggleTask,
                    onResolveSyncConflict = { id, r -> repo.resolveSyncConflict(id, r) },
                    onLinkNeptun = openImport,
                    onAddClass = openNewClass,
                    selectedCourseKey = if (wide) paneKey else null,
                )
            }
        }
    }

    ConfirmDialog(
        open = showNeptunNotice,
        onClose = { showNeptunNotice = false },
        onConfirm = { showNeptunNotice = false },
        // Only a Neptun exam opens this (Neptun classes open their Course Hub).
        title = "Synced from Neptun",
        description = "This exam was automatically fetched from Neptun and cannot be manually edited or deleted. It will be updated automatically when you sync your calendar.",
        confirmText = "OK",
        hideCancel = true,
        destructive = false,
    )
}

@Composable
private fun ScheduleSkeleton() {
    val c = Vectra.colors
    Column(Modifier.fillMaxWidth().pulse(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(2) { Box(Modifier.height(36.dp).width(96.dp).background(c.surfaceOverlay, CircleShape)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(5) { i ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.fillMaxWidth().height(32.dp).background(c.surfaceOverlay, Radius.Control))
                    val card = Radius.Panel
                    Box(Modifier.fillMaxWidth().height(128.dp).background(c.surfaceOverlay, card).border(1.dp, c.borderSubtle, card))
                    if (i % 2 != 0) Box(Modifier.fillMaxWidth().height(112.dp).background(c.surfaceOverlay, card).border(1.dp, c.borderSubtle, card))
                }
            }
        }
    }
}

/** The wide layout's side pane before any class is open. */
@Composable
private fun ClassPanePlaceholder() {
    val c = Vectra.colors
    Column(
        Modifier.fillMaxSize().plannerCard().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        VIcon(RemixIcons.CalendarCheckLine, size = 28.dp, tint = c.textMuted)
        VText("Tap a class", style = vtext(TypeScale.Body, Weight.Bold), color = c.textPrimary, align = TextAlign.Center)
        VText(
            "Its attendance, grades and rules open here.",
            Modifier.widthIn(max = 260.dp),
            vtext(TypeScale.Caption),
            c.textSecondary,
            align = TextAlign.Center,
        )
    }
}

/** Today's next Neptun class you can check in to (the one on now counts), or null. */
internal fun upcomingTrackedClass(events: List<ScheduleEvent>, now: Instant): ScheduleEvent? {
    val wall = nowInBudapest(now)
    return scheduleTodayEvents(events, now)
        .filter { it.source == "neptun" && !it.isExam() && (minuteOf(it.endTime) ?: 0) > wall.minuteOfDay }
        .minByOrNull { minuteOf(it.startTime) ?: Int.MAX_VALUE }
}
