package dev.southstar21.vectra.features.coursetracker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import dev.southstar21.vectra.core.designsystem.components.BarLeading
import dev.southstar21.vectra.core.designsystem.components.BottomBar
import dev.southstar21.vectra.core.designsystem.components.DateField
import dev.southstar21.vectra.core.designsystem.components.ScrollBody
import dev.southstar21.vectra.core.designsystem.components.SectionTitle
import dev.southstar21.vectra.core.designsystem.components.SurfaceGutter
import dev.southstar21.vectra.core.designsystem.components.TopBar
import dev.southstar21.vectra.core.designsystem.components.TimeField
import dev.southstar21.vectra.core.designsystem.components.FormField
import dev.southstar21.vectra.core.designsystem.components.FormGroup
import dev.southstar21.vectra.core.designsystem.components.PillRadio
import dev.southstar21.vectra.core.designsystem.components.SelectOption
import dev.southstar21.vectra.core.designsystem.components.SheetInputHeight
import dev.southstar21.vectra.core.designsystem.components.VSheet
import dev.southstar21.vectra.core.designsystem.components.SheetSize
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VInput
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

private val SessionTypeOptions = listOf(
    SelectOption(SessionType.Lecture, "Lecture"),
    SelectOption(SessionType.Seminar, "Seminar"),
    SelectOption(SessionType.Lab, "Lab"),
    SelectOption(SessionType.Consultation, "Consultation"),
    SelectOption(SessionType.Exam, "Exam"),
    SelectOption(SessionType.Other, "Other"),
)

/** The sheet shared by the add-session and assessment forms: full height, footer docked. */
@Composable
internal fun TrackerSheetDialog(open: Boolean, onDismiss: () -> Unit, onExited: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    VSheet(open = open, onDismiss = onDismiss, size = SheetSize.Full, onExited = onExited) {
        Column(Modifier.fillMaxWidth().weight(1f), content = content)
    }
}

/**
 * A custom session (make-up lab, extra consultation).
 * The form is keyed to each opening, so every sheet starts from clean defaults.
 */
@Composable
internal fun AddSessionDialog(open: Boolean, onDismiss: () -> Unit, onAddSession: (AttendanceSession) -> Unit) {
    val generation = rememberOpenGeneration(open)
    TrackerSheetDialog(open, onDismiss) {
        key(generation) { AddSessionForm(onDismiss, onAddSession) }
    }
}

@Composable
private fun ColumnScope.AddSessionForm(onCancel: () -> Unit, onSubmit: (AttendanceSession) -> Unit) {
    val c = Vectra.colors
    var scrolled by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("Extra consultation") }
    var date by remember { mutableStateOf(nowInBudapest().ymd) }
    var start by remember { mutableStateOf("10:00") }
    var end by remember { mutableStateOf("11:30") }
    var type by remember { mutableStateOf(SessionType.Consultation) }
    var status by remember { mutableStateOf(AttendanceStatus.Present) }
    var notes by remember { mutableStateOf("") }
    val canSubmit = title.trim().isNotEmpty() && date.isNotEmpty()

    TopBar("Add session", BarLeading.Close, onCancel, scrolled = scrolled)
    ScrollBody(
        scrollState = rememberScrollState(),
        onScrolledChange = { scrolled = it },
        fadeBottom = false,
        contentPadding = PaddingValues(start = SurfaceGutter, end = SurfaceGutter, top = 12.dp, bottom = 24.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Session")
            FormGroup(rows = listOf(
                {
                    FormField("Title") {
                        VInput(title, { title = it }, placeholder = "Make-up lab, midterm prep…", height = SheetInputHeight, textSize = TypeScale.BodyLarge)
                    }
                },
                {
                    FormField("Type") { PillRadio(type, SessionTypeOptions, { type = it }, columns = 3) }
                },
            ))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("When")
            FormGroup(rows = listOf(
                { FormField("Date") { DateField(date, { date = it }) } },
                {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Box(Modifier.weight(1f)) { FormField("Starts") { TimeField(start, { start = it }) } }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderSubtle))
                        Box(Modifier.weight(1f)) { FormField("Ends") { TimeField(end, { end = it }) } }
                    }
                },
            ))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Attendance")
            FormGroup(rows = listOf(
                {
                    FormField("Mark it as", hint = "Leave it unmarked to decide after the session.") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusButtons.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { btn ->
                                        StatusRadio(
                                            selected = status == btn.status,
                                            onClick = { status = btn.status },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            VIcon(statusIcon(btn.status), size = 14.dp, tint = btn.tone.ink())
                                            VText(btn.label, style = vtext(TypeScale.Body, Weight.Semibold), color = if (status == btn.status) c.textPrimary else c.textSecondary)
                                        }
                                    }
                                }
                            }
                            val unmarked = status == AttendanceStatus.Unmarked
                            StatusRadio(unmarked, { status = AttendanceStatus.Unmarked }, Modifier.fillMaxWidth(), accent = true) {
                                VText("Not marked yet", style = vtext(TypeScale.Body, Weight.Semibold), color = if (unmarked) c.accentPrimary else c.textSecondary)
                            }
                        }
                    }
                },
            ))
        }
        FormGroup(rows = listOf({
            FormField("Notes") {
                VInput(notes, { notes = it }, placeholder = "Chapter 4 review, room 204…", height = SheetInputHeight, textSize = TypeScale.BodyLarge)
            }
        }))
      }
    }
    BottomBar {
        TrackerButton(
            onClick = {
                if (!canSubmit) return@TrackerButton
                onSubmit(
                    AttendanceSession(
                        id = genId(),
                        title = title.trim(),
                        date = date,
                        startTime = start.nonEmpty(),
                        endTime = end.nonEmpty(),
                        sessionType = type,
                        status = status,
                        notes = notes.trim().nonEmpty(),
                        isCustom = true,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = Radius.Panel,
            height = 48.dp,
            enabled = canSubmit,
        ) { ink -> VText("Add session", style = vtext(TypeScale.Body, Weight.Bold), color = ink) }
    }
}

/** One radio pill of the "Mark it as" grid. */
@Composable
private fun StatusRadio(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val c = Vectra.colors
    val interaction = remember { MutableInteractionSource() }
    val (bg, border) = when {
        accent && selected -> c.accentPrimary.copy(alpha = 0.15f) to c.accentPrimary.copy(alpha = 0.5f)
        selected -> c.surfaceOverlay to c.borderPrimary
        else -> c.surfaceSecondary to c.borderSubtle
    }
    Row(
        modifier
            .pressScale(interaction)
            .height(40.dp)
            .background(bg, CircleShape)
            .border(1.dp, border, CircleShape)
            .clip(CircleShape)
            .tappable(role = Role.RadioButton, highlight = false, interactionSource = interaction, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** A counter that advances on every opening and holds through the exit animation (a fresh form per opening). */
@Composable
internal fun rememberOpenGeneration(open: Boolean): Int {
    val counter = remember { intArrayOf(0) }
    return remember(open) { if (open) ++counter[0] else counter[0] }
}
