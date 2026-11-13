package dev.southstar21.vectra.features.schedule

import kotlin.time.Instant
import kotlinx.coroutines.coroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.designsystem.components.DecorativeLoopFps
import dev.southstar21.vectra.core.designsystem.components.rememberLoop
import dev.southstar21.vectra.core.designsystem.components.rememberOnScreen
import dev.southstar21.vectra.core.designsystem.components.trackOnScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.ConfirmDialog
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.OfflineBanner
import dev.southstar21.vectra.core.designsystem.components.Overlay
import dev.southstar21.vectra.core.designsystem.components.Reveal
import dev.southstar21.vectra.core.designsystem.components.VBackHandler
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.pulse
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.pillDepth
import dev.southstar21.vectra.core.designsystem.theme.PillTone
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.rgba
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
import dev.southstar21.vectra.core.designsystem.theme.consumeTouches
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** Line length of the empty schedule's explanation. */
private val EmptyScheduleTextWidth = 448.dp

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
            // Neptun classes open their Course Hub; exams have no attendance, so never one.
            if (!event.isExam()) {
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
        if (isOffline) OfflineBanner(message = "No internet connection — Viewing cached offline schedule")
        eventsError?.takeIf { !isOffline }?.let { OfflineBanner(message = it, badgeLabel = "Not loaded") }
        if (itemsState.queue.isNotEmpty() && !isOffline) {
            OfflineBanner(message = itemSyncError ?: "Schedule item changes are waiting to sync.")
        }

        when {
            isLoading -> ScheduleSkeleton()
            events.isEmpty() && items.isEmpty() -> Reveal(0) {
                EmptyScheduleCard(onSync = openImport, onAddClass = openNewClass, onAddItem = openNewItem)
            }
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
                    selectedCourseKey = if (wide) paneKey else null,
                )
            }
        }
    }

    ConfirmDialog(
        open = showNeptunNotice,
        onClose = { showNeptunNotice = false },
        onConfirm = { showNeptunNotice = false },
        title = "Uneditable Class",
        description = "This class was automatically fetched from Neptun and cannot be manually edited or deleted. It will be updated automatically when you sync your calendar.",
        confirmText = "Okay",
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

/** The no-classes card: drifting calendar, copy, and the sync/add actions. */
@Composable
private fun EmptyScheduleCard(onSync: () -> Unit, onAddClass: () -> Unit, onAddItem: (String) -> Unit) {
    val c = Vectra.colors
    val shape = Radius.Card
    val onScreen = rememberOnScreen()
    val bob = rememberLoop(2000, VectraEasing.Breathe, reverse = true, enabled = onScreen.value, maxFps = DecorativeLoopFps)
    val reduce = Vectra.reduceMotion
    Column(
        Modifier
            .trackOnScreen(onScreen)
            .padding(top = 16.dp)
            .fillMaxWidth()
            .boxShadow(shape, listOf(BoxShadow(y = 20f, blur = 25f, spread = -5f, color = rgba(0, 0, 0, 0.1f)), BoxShadow(y = 8f, blur = 10f, spread = -6f, color = rgba(0, 0, 0, 0.1f))))
            .glass(shape)
            .clip(shape)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(0f to rgba(14, 165, 233, 0.2f), 0.7f to Color.Transparent, radius = size.minDimension / 2f),
                    radius = size.minDimension / 2f,
                    alpha = 0.7f,
                )
            }
            .padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val tile = Radius.Card
        Box(
            Modifier
                .size(80.dp)
                .boxShadow(tile, listOf(BoxShadow(blur = 30f, color = rgba(56, 189, 248, 0.25f))))
                .background(Brush.linearGradient(listOf(Palette.Sky500.copy(alpha = 0.2f), Palette.Indigo500.copy(alpha = 0.15f))), tile)
                .border(1.dp, Color.White.copy(alpha = 0.15f), tile),
            contentAlignment = Alignment.Center,
        ) {
            VText("📅", Modifier.graphicsLayer { translationY = if (reduce) 0f else -6f * bob.value * density }, vtext(TypeScale.Display))
        }
        Column(Modifier.widthIn(max = EmptyScheduleTextWidth), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VText("No classes scheduled", style = vtext(TypeScale.Title, Weight.Black, Tracking.Tight), color = c.textPrimary, align = TextAlign.Center)
            VText(
                "Import your Neptun calendar (.ics file or subscription URL) to automatically view and categorize your classes across all semesters.",
                style = vtext(TypeScale.Caption, Weight.Medium, lineHeight = 19.5.sp),
                color = c.textSecondary,
                align = TextAlign.Center,
            )
        }
        Box(Modifier.padding(top = 8.dp)) { ScheduleActionButtons(onSync, onAddClass, onAddItem) }
    }
}

/** The page's Sync + Add (menu) buttons. */
@Composable
private fun ScheduleActionButtons(onSync: () -> Unit, onAddClass: () -> Unit, onAddItem: (String) -> Unit) {
    val c = Vectra.colors
    var menuOpen by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val syncInteraction = remember { MutableInteractionSource() }
        Row(
            Modifier.height(36.dp).pressScale(syncInteraction).clip(CircleShape).tappable(role = null, highlight = false, interactionSource = syncInteraction, onClick = onSync).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VIcon(RemixIcons.UploadLine, size = 14.dp, tint = c.textSecondary)
            VText("Sync", Modifier.padding(start = 6.dp), vtext(TypeScale.Caption, Weight.Bold), c.textSecondary)
        }
        val addInteraction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .height(36.dp)
                .onGloballyPositioned { anchor = it.boundsInRoot() }
                .pressScale(addInteraction)
                .pillDepth(CircleShape, PillTone.Accent)
                .tappable(role = null, highlight = false, interactionSource = addInteraction) { menuOpen = !menuOpen }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VIcon(RemixIcons.AddLine, size = 14.dp, tint = Color.White)
            VText("Add", Modifier.padding(start = 6.dp), vtext(TypeScale.Caption, Weight.Bold), Color.White)
        }
    }
    if (menuOpen) {
        Overlay {
            VBackHandler { menuOpen = false }
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().consumeTouches { menuOpen = false }) {
                val width = 192.dp
                val x = with(density) { (anchor.right - width.toPx()).toInt() }
                val y = with(density) { (anchor.bottom + 8.dp.toPx()).toInt() }
                val shape = Radius.Panel
                Column(
                    Modifier
                        .offset { IntOffset(x, y) }
                        .width(width)
                        .boxShadow(shape, listOf(BoxShadow(y = 20f, blur = 25f, spread = -5f, color = rgba(0, 0, 0, 0.1f)), BoxShadow(y = 8f, blur = 10f, spread = -6f, color = rgba(0, 0, 0, 0.1f))))
                        .background(c.surfaceSecondary, shape)
                        .border(1.dp, c.borderSubtle, shape)
                        .padding(6.dp),
                ) {
                    listOf(
                        "Class" to onAddClass,
                        "Work shift" to { onAddItem(ScheduleItem.TYPE_SHIFT) },
                        "Task" to { onAddItem(ScheduleItem.TYPE_TASK) },
                    ).forEach { (label, action) ->
                        VText(
                            label,
                            Modifier
                                .fillMaxWidth()
                                .clip(Radius.Control)
                                .tappable(role = null) { action(); menuOpen = false }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            vtext(TypeScale.Caption, Weight.Semibold),
                            c.textPrimary,
                        )
                    }
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
        Modifier.fillMaxSize().glass(Radius.Card).padding(32.dp),
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
