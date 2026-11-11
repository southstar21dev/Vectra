package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.designsystem.components.BarLeading
import dev.southstar21.vectra.core.designsystem.components.BottomBar
import dev.southstar21.vectra.core.designsystem.components.DateField
import dev.southstar21.vectra.core.designsystem.components.DeleteRow
import dev.southstar21.vectra.core.designsystem.components.FormField
import dev.southstar21.vectra.core.designsystem.components.FormGroup
import dev.southstar21.vectra.core.designsystem.components.HairlineDivider
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.PillRadio
import dev.southstar21.vectra.core.designsystem.components.ScreenColumn
import dev.southstar21.vectra.core.designsystem.components.ScrollBody
import dev.southstar21.vectra.core.designsystem.components.SectionTitle
import dev.southstar21.vectra.core.designsystem.components.SelectOption
import dev.southstar21.vectra.core.designsystem.components.SwitchRow
import dev.southstar21.vectra.core.designsystem.components.TimeField
import dev.southstar21.vectra.core.designsystem.components.TopBar
import dev.southstar21.vectra.core.designsystem.components.TopBarSubtitle
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.VToggle
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.formatMinuteOfDay
import dev.southstar21.vectra.core.time.formatTime
import dev.southstar21.vectra.core.time.isValidCalendarDate
import dev.southstar21.vectra.core.time.nowInBudapest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dev.southstar21.vectra.core.designsystem.theme.Radius

private val TypeOptions = listOf(SelectOption(ScheduleItem.TYPE_SHIFT, "Work shift"), SelectOption(ScheduleItem.TYPE_TASK, "Task"))

private val ClockTime = Regex("""^(\d{2}):(\d{2})$""")

@OptIn(ExperimentalUuidApi::class)
private fun newItemId(): String = Uuid.random().toString()

private fun timeToMinutes(time: String): Int? {
    val m = ClockTime.find(time) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    return if (h <= 23 && min <= 59) h * 60 + min else null
}

internal class ItemForm(item: ScheduleItem?, initialType: String) {
    var draftId by mutableStateOf(item?.id ?: newItemId())
    var type by mutableStateOf(item?.type ?: initialType)
    var title by mutableStateOf(item?.title.orEmpty())
    var date by mutableStateOf(item?.date ?: nowInBudapest().ymd)
    var startTime by mutableStateOf(item?.startTime ?: "09:00")
    var endTime by mutableStateOf(item?.endTime ?: "10:00")
    var location by mutableStateOf(item?.location.orEmpty())
    var acknowledgedSignature by mutableStateOf<String?>(null)

    companion object {
        /** Keeps the draft (and its id, so a retried save stays one item) when Android recreates the activity. */
        val Saver: Saver<ItemForm, Any> = listSaver(
            save = { listOf(it.draftId, it.type, it.title, it.date, it.startTime, it.endTime, it.location, it.acknowledgedSignature) },
            restore = { saved ->
                val v = saved.iterator()
                ItemForm(null, "").apply {
                    draftId = v.next() as String
                    type = v.next() as String
                    title = v.next() as String
                    date = v.next() as String
                    startTime = v.next() as String
                    endTime = v.next() as String
                    location = v.next() as String
                    acknowledgedSignature = v.next()
                }
            },
        )
    }
}

/**
 * Add a work shift or task ([kind]), or edit the item with [itemId]. Opened from the
 * schedule and the dashboard. [reopen] edits a completed task as not done: un-ticking it
 * hit a clash that has to be acknowledged here first.
 *
 * Every edit needs a fresh explicit clash acknowledgment; the toggle stores the exact
 * clash signature it approved, so any change to the clash set re-arms it.
 */
@Composable
internal fun ItemEditorScreen(repo: ScheduleRepository, itemId: String?, kind: String, reopen: Boolean, onBack: () -> Unit) {
    val itemsState by repo.itemsState.collectAsStateWithLifecycle()
    val itemsLoading by repo.itemsLoading.collectAsStateWithLifecycle()
    val exit = rememberExitOnce(onBack)
    val items = itemsState.items
    val stored = remember(items, itemId) { itemId?.let { id -> items.firstOrNull { it.id == id } } }
    val missing = itemId != null && stored == null

    LaunchedEffect(missing, itemsLoading) { if (missing && !itemsLoading) exit() }

    if (stored == null && itemId != null) {
        ScreenColumn { TopBar(if (kind == ScheduleItem.TYPE_SHIFT) "Edit work shift" else "Edit task", BarLeading.Back, exit) }
        return
    }
    val item = stored?.let { if (reopen) it.copy(completed = false) else it }?.toItem()
    ItemEditorContent(repo, item, items, kind, exit)
}

@Composable
private fun ItemEditorContent(
    repo: ScheduleRepository,
    item: ScheduleItem?,
    items: List<StoredScheduleItem>,
    initialType: String,
    exit: () -> Unit,
) {
    val c = Vectra.colors
    val haptics = Vectra.haptics
    val toaster = LocalToaster.current
    val events by repo.events.collectAsStateWithLifecycle()
    val isSubmitting by repo.isSavingItem.collectAsStateWithLifecycle()
    val timeFormat = Vectra.appearance.timeFormat
    val form = rememberSaveable(saver = ItemForm.Saver) { ItemForm(item, initialType) }
    var deleting by remember { mutableStateOf(false) }
    var scrolled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val candidate = ScheduleItem(
        id = form.draftId,
        type = form.type,
        title = form.title.trim().ifEmpty { "Untitled item" },
        date = form.date,
        startTime = form.startTime,
        endTime = form.endTime,
        location = if (form.type == ScheduleItem.TYPE_SHIFT) form.location.trim().ifEmpty { null } else null,
        completed = if (form.type == ScheduleItem.TYPE_TASK) (item?.completed ?: false) else null,
        acknowledgedConflictKeys = emptyList(),
    )
    val currentConflicts = remember(candidate, events, items) {
        getScheduleConflicts(listOf(candidate) + items.filter { it.id != candidate.id }.map { it.toItem() }, events)
            .filter { it.itemId == candidate.id || it.otherItemId == candidate.id }
            .map { it.copy(needsAcknowledgment = true) }
    }
    val conflictKeys = currentConflicts.map { it.key }.distinct().sorted()
    val unackKeys = currentConflicts.filter { it.needsAcknowledgment }.map { it.key }.sorted()
    // The signature includes the overlapping window, so nudging onto another part of a class re-arms it.
    val signature = currentConflicts.filter { it.needsAcknowledgment }
        .map { "[\"${it.key}\",\"${it.date}\",${it.startMinute},${it.endMinute}]" }
        .sorted().joinToString(",", "[", "]")
    val hasExisting = currentConflicts.isNotEmpty()
    val hasUnacknowledged = hasExisting && form.acknowledgedSignature != signature
    val s = timeToMinutes(form.startTime)
    val e = timeToMinutes(form.endTime)
    val timeValid = s != null && e != null && s != e && (form.type == ScheduleItem.TYPE_SHIFT || e > s)
    val dateValid = isValidCalendarDate(form.date)
    val canSave = form.title.isNotBlank() && dateValid && timeValid && !hasUnacknowledged && !isSubmitting && !deleting
    val noun = if (form.type == ScheduleItem.TYPE_SHIFT) "work shift" else "task"
    val crossesMidnight = form.type == ScheduleItem.TYPE_SHIFT && timeValid && form.endTime < form.startTime

    ScreenColumn {
        TopBar(
            title = if (item != null) "Edit $noun" else "Add $noun",
            leading = BarLeading.Back,
            onLeading = exit,
            subtitle = item?.title?.ifEmpty { null }?.let { title -> { TopBarSubtitle(title) } },
            scrolled = scrolled,
        )
        ScrollBody(
            scrollState = rememberScrollState(),
            onScrolledChange = { scrolled = it },
            fadeBottom = false,
            contentPadding = EditorBodyPadding,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                SheetSection {
                    SectionTitle(if (form.type == ScheduleItem.TYPE_SHIFT) "Shift" else "Task")
                    val rows = mutableListOf<@Composable () -> Unit>()
                    if (item == null) rows += { FormField("Kind") { PillRadio(form.type, TypeOptions, { form.type = it }) } }
                    rows += {
                        FormField("Title") {
                            EditorTextInput(form.title, { form.title = it }, if (form.type == ScheduleItem.TYPE_SHIFT) "Evening shift" else "Study for accounting", 200)
                        }
                    }
                    if (form.type == ScheduleItem.TYPE_SHIFT) {
                        rows += { FormField("Workplace", hint = "Optional.") { EditorTextInput(form.location, { form.location = it }, "Café on Ráday utca", 200) } }
                    }
                    FormGroup(rows = rows)
                }
                SheetSection {
                    SectionTitle("When")
                    val rows = mutableListOf<@Composable () -> Unit>(
                        { FormField("Date", error = if (dateValid) null else "Pick a real date.") { DateField(form.date, { form.date = it }) } },
                        {
                            TwoColumns(
                                { FormField("Starts") { TimeField(form.startTime, { form.startTime = it }) } },
                                { FormField("Ends") { TimeField(form.endTime, { form.endTime = it }) } },
                            )
                        },
                    )
                    if (crossesMidnight || !timeValid) {
                        rows += {
                            VText(
                                when {
                                    timeValid -> "Ends the next day at ${formatTime(form.endTime, timeFormat)}."
                                    form.type == ScheduleItem.TYPE_TASK -> "A task has to end later on the same day it starts."
                                    else -> "Start and end need to be different, valid times."
                                },
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                vtext(TypeScale.Caption, if (timeValid) Weight.Normal else Weight.Semibold),
                                if (timeValid) c.textSecondary else c.tints.red.text,
                            )
                        }
                    }
                    FormGroup(rows = rows)
                }
                if (currentConflicts.isNotEmpty()) {
                    SheetSection {
                        SectionTitle(
                            if (currentConflicts.size == 1) "Clashes with a class" else "Clashes with ${currentConflicts.size} classes",
                            color = c.tints.red.text,
                            icon = RemixIcons.AlertLine,
                        )
                        val shape = Radius.Card
                        Column(Modifier.border(1.dp, Palette.Rose500.copy(alpha = 0.3f), shape)) {
                            FormGroup(rows = listOf(
                                {
                                    Column {
                                        currentConflicts.forEachIndexed { i, cf ->
                                            if (i > 0) HairlineDivider()
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                VText(cf.otherTitle, Modifier.weight(1f), vtext(TypeScale.Body, Weight.Semibold), c.textPrimary, maxLines = 1)
                                                VText(
                                                    "${formatTime(formatMinuteOfDay(cf.startMinute), timeFormat)}–${formatTime(formatMinuteOfDay(cf.endMinute), timeFormat)}",
                                                    style = vtext(TypeScale.Caption),
                                                    color = c.textSecondary,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                },
                                {
                                    Box(
                                        Modifier.tappable(Radius.Card, role = null, haptic = { toggle(hasUnacknowledged) }) {
                                            form.acknowledgedSignature = if (hasUnacknowledged) signature else null
                                        },
                                    ) {
                                        SwitchRow("I know, save it anyway", description = "Saving stays off until you acknowledge the clash.") {
                                            VToggle(
                                                checked = !hasUnacknowledged,
                                                onCheckedChange = { checked -> form.acknowledgedSignature = if (checked) signature else null },
                                                label = "I understand and acknowledge these schedule clashes",
                                            )
                                        }
                                    }
                                },
                            ))
                        }
                    }
                }
                if (item != null) {
                    DeleteRow(
                        label = "Delete $noun",
                        onConfirm = {
                            deleting = true
                            scope.launch {
                                try {
                                    repo.deleteItem(item.id)
                                    toaster.success("Schedule item deleted")
                                    exit()
                                } catch (ex: CancellationException) {
                                    throw ex
                                } catch (_: Throwable) {
                                    // Failed to delete: the editor stays open.
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
                val existing = item?.acknowledgedConflictKeys.orEmpty().filter { it in conflictKeys }
                val acks = (existing + unackKeys).distinct()
                val saved = candidate.copy(acknowledgedConflictKeys = acks)
                scope.launch {
                    try {
                        repo.saveItem(saved)
                        toaster.success(if (saved.isShift) "Work shift saved" else "Task saved")
                        haptics.confirm()
                        exit()
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Throwable) {
                        // The form stays open with everything typed.
                        haptics.reject()
                        toaster.error(ex.message ?: "Could not save this schedule item.")
                    }
                }
            }) { SubmitLabel(isSubmitting, if (item != null) "Save changes" else "Add $noun") }
        }
    }
}
