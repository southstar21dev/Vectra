package dev.southstar21.vectra.features.coursetracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import dev.southstar21.vectra.core.designsystem.components.BarLeading
import dev.southstar21.vectra.core.designsystem.components.BottomBar
import dev.southstar21.vectra.core.designsystem.components.DateField
import dev.southstar21.vectra.core.designsystem.components.ScrollBody
import dev.southstar21.vectra.core.designsystem.components.SectionTitle
import dev.southstar21.vectra.core.designsystem.components.SurfaceGutter
import dev.southstar21.vectra.core.designsystem.components.TopBar
import dev.southstar21.vectra.core.designsystem.components.TopBarSubtitle
import dev.southstar21.vectra.core.designsystem.components.DeleteRow
import dev.southstar21.vectra.core.designsystem.components.FormField
import dev.southstar21.vectra.core.designsystem.components.FormGroup
import dev.southstar21.vectra.core.designsystem.components.PillRadio
import dev.southstar21.vectra.core.designsystem.components.SelectOption
import dev.southstar21.vectra.core.designsystem.components.SheetInputHeight
import dev.southstar21.vectra.core.designsystem.components.SwitchRow
import dev.southstar21.vectra.core.designsystem.components.VInput
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.VToggle
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.util.roundHalfUpToInt
import kotlin.math.floor
import kotlin.math.round
import dev.southstar21.vectra.core.designsystem.theme.Radius

private enum class ScoreMode { Points, Percent }

private val ScoreOptions = listOf(SelectOption(ScoreMode.Points, "Points"), SelectOption(ScoreMode.Percent, "Percent"))

private fun formatWeight(value: Double): String =
    if (value == floor(value)) formatNumber(value) else (round(value * 10) / 10).toString()

private fun draftNumber(value: Double?, fallback: String) = if (value != null && value.isFinite()) formatNumber(value) else fallback

private fun round2(x: Double) = round(x * 100) / 100

/**
 * [value] in the other score mode: points to a percentage of [max], or back. Null when either
 * is not a usable number, so the field keeps what it had.
 */
internal fun convertScore(value: String, max: String, toPercent: Boolean): String? {
    val v = parseFiniteNumber(value)
    val m = parseFiniteNumber(max)?.takeIf { it > 0 }
    if (v == null || m == null) return null
    return formatNumber(round2(if (toPercent) v / m * 100 else v / 100 * m))
}

/**
 * Add or edit an assessment: name and date, the
 * score as points or percent with a live grade hint, weight, must-pass rule,
 * notes. The form is re-created for each opening (or each edited item).
 */
@Composable
internal fun AddAssessmentDialog(
    open: Boolean,
    onDismiss: () -> Unit,
    onExited: () -> Unit,
    item: AssessmentItem?,
    onSave: (AssessmentItem) -> Unit,
    onDelete: (() -> Unit)?,
    useWeightedCategories: Boolean,
    courseName: String?,
    projectedPercentage: Double?,
    estimatedGrade: Int?,
    gradingScale: List<GradingScaleTier>?,
    existingWeightTotal: Double,
    online: Boolean,
    toast: UndoToastState,
) {
    val generation = rememberOpenGeneration(open)
    TrackerSheetDialog(open, onDismiss, onExited) {
        key(generation, item?.id) {
            AssessmentForm(onDismiss, item, onSave, onDelete, useWeightedCategories, courseName, projectedPercentage, estimatedGrade, gradingScale, existingWeightTotal, online, toast)
        }
    }
}

/** sheet-form FormField with a rich hint (the core one takes plain text). */
@Composable
private fun RichField(label: String, error: String? = null, hint: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Vectra.colors
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VText(label, style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textSecondary)
        content()
        when {
            error != null -> VText(error, style = vtext(TypeScale.Caption, Weight.Semibold), color = c.tints.red.text)
            hint != null -> hint()
        }
    }
}

/** NUMBER_FIELD_CLASS: the sheet input, centred, black weight, tabular figures. */
@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    ink: Color = Vectra.colors.textPrimary,
    placeholder: String? = null,
    label: String? = null,
    invalid: Boolean = false,
) {
    val shape = Radius.Control
    Box(modifier.height(SheetInputHeight).glass(shape, hairlineInLight = true)) {
        DraftField(
            value = value,
            onValueChange = onChange,
            onCommit = onCommit,
            modifier = Modifier.fillMaxWidth().height(SheetInputHeight),
            style = vtext(TypeScale.BodyLarge, Weight.Black).tnum(),
            color = ink,
            shape = shape,
            background = Color.Transparent,
            border = if (invalid) Palette.Rose500.copy(alpha = 0.5f) else null,
            placeholder = placeholder,
            contentDescription = label,
        )
    }
}

@Composable
private fun ColumnScope.AssessmentForm(
    onClose: () -> Unit,
    item: AssessmentItem?,
    onSave: (AssessmentItem) -> Unit,
    onDelete: (() -> Unit)?,
    useWeightedCategories: Boolean,
    courseName: String?,
    projectedPercentage: Double?,
    estimatedGrade: Int?,
    gradingScale: List<GradingScaleTier>?,
    existingWeightTotal: Double,
    online: Boolean,
    toaster: UndoToastState,
) {
    val c = Vectra.colors
    val editing = item != null
    var scrolled by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(item?.name.orEmpty()) }
    var date by remember { mutableStateOf(item?.date.orEmpty()) }
    var mode by remember { mutableStateOf(ScoreMode.Points) }
    var earned by remember { mutableStateOf(draftNumber(item?.earnedPoints, "0")) }
    var maxPoints by remember { mutableStateOf(draftNumber(item?.maxPoints, "20")) }
    var percentage by remember { mutableStateOf("85") }
    var weight by remember { mutableStateOf(draftNumber(item?.weightPercent, "")) }
    var mustPass by remember { mutableStateOf(item?.isMustPass ?: false) }
    var minPassPoints by remember { mutableStateOf(draftNumber(item?.minPassPoints, "10")) }
    var minPassPercent by remember { mutableStateOf("50") }
    var completed by remember { mutableStateOf(item?.isScored ?: true) }
    var notes by remember { mutableStateOf(item?.notes.orEmpty()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var maxError by remember { mutableStateOf<String?>(null) }

    val maxParsed = parseFiniteNumber(maxPoints)

    // Commit-on-blur: drafts stay unclamped while typing; commits clamp and normalise.
    fun commitMax() {
        val p = parseFiniteNumber(maxPoints)
        if (p == null || p <= 0) {
            maxError = "Enter the maximum points for this assessment (e.g. 20)."
            return
        }
        maxError = null
        maxPoints = formatNumber(clampNumber(p, 0.1, 10000.0))
    }
    fun commitEarned() { earned = parseFiniteNumber(earned)?.let { formatNumber(clampNumber(it, 0.0, 10000.0)) } ?: "0" }
    fun commitPercentage() { percentage = parseFiniteNumber(percentage)?.let { formatNumber(clampNumber(it, 0.0, 200.0)) } ?: "0" }
    fun commitWeight() { weight = parseFiniteNumber(weight)?.let { formatNumber(clampNumber(it, 0.0, 100.0)) } ?: "" }
    fun commitMinPass() {
        if (mode == ScoreMode.Percent) {
            minPassPercent = parseFiniteNumber(minPassPercent)?.let { formatNumber(clampNumber(it, 0.0, 100.0)) } ?: "50"
        } else {
            minPassPoints = parseFiniteNumber(minPassPoints)?.let { formatNumber(clampNumber(it, 0.0, maxParsed ?: 10000.0)) } ?: ""
        }
    }

    // Switching how the score is entered carries the values across, so a save from Percent
    // never stores the placeholder's 85% (audit 04 #19).
    fun switchMode(next: ScoreMode) {
        if (next == mode) return
        val toPercent = next == ScoreMode.Percent
        if (toPercent) {
            convertScore(earned, maxPoints, true)?.let { percentage = it }
            convertScore(minPassPoints, maxPoints, true)?.let { minPassPercent = it }
        } else {
            convertScore(percentage, maxPoints, false)?.let { earned = it }
            convertScore(minPassPercent, maxPoints, false)?.let { minPassPoints = it }
        }
        mode = next
    }

    fun submit() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            nameError = "Name this assessment, e.g. “Midterm Exam 1” or “ZH 2”."
            return
        }
        nameError = null
        val max = parseFiniteNumber(maxPoints)
        if (max == null || max <= 0) {
            maxError = "Enter the maximum points for this assessment (e.g. 20)."
            return
        }
        maxError = null
        val maxClamped = clampNumber(max, 0.1, 10000.0)
        val earnedValue: Double
        var minPass: Double? = null
        if (mode == ScoreMode.Percent) {
            val pct = clampNumber(parseFiniteNumber(percentage) ?: 0.0, 0.0, 200.0)
            earnedValue = round2(pct / 100 * maxClamped)
            if (mustPass) minPass = round2(clampNumber(parseFiniteNumber(minPassPercent) ?: 50.0, 0.0, 100.0) / 100 * maxClamped)
        } else {
            earnedValue = clampNumber(parseFiniteNumber(earned) ?: 0.0, 0.0, 10000.0)
            // Blank means "half the points" of this assessment's own max.
            if (mustPass) minPass = clampNumber(parseFiniteNumber(minPassPoints) ?: (maxClamped * 0.5), 0.0, maxClamped)
        }
        val w = if (weight.trim().isEmpty()) null else clampNumber(parseFiniteNumber(weight) ?: 0.0, 0.0, 100.0)
        onSave(
            (item ?: AssessmentItem(genId(), trimmed, 0.0, 0.0)).copy(
                name = trimmed,
                earnedPoints = earnedValue,
                maxPoints = maxClamped,
                weightPercent = w,
                isMustPass = mustPass,
                minPassPoints = minPass,
                isCompleted = completed,
                date = date.nonEmpty(),
                notes = notes.trim().nonEmpty(),
            ),
        )
        toaster.success(
            if (editing) "Assessment updated" else "Assessment saved",
            "$trimmed · ${formatNumber(earnedValue)} / ${formatNumber(maxClamped)} pts${if (online) "" else " · saved on this device, syncs when online"}",
        )
        onClose()
    }

    // Live readouts -------------------------------------------------------------
    val calculatedPoints = round2((parseFiniteNumber(percentage) ?: 0.0) / 100 * (maxParsed?.takeIf { it != 0.0 } ?: 20.0))
    val scorePercent: Double? = if (mode == ScoreMode.Percent) parseFiniteNumber(percentage)?.let { clampNumber(it, 0.0, 200.0) }
    else {
        val e = parseFiniteNumber(earned)
        if (e == null || maxParsed == null || maxParsed <= 0) null else clampNumber(e / maxParsed * 100, 0.0, 200.0)
    }
    val scaleHint = if (scorePercent == null || gradingScale.isNullOrEmpty()) null else {
        val g = estimateGrade(scorePercent, gradingScale)
        val cutoff = gradingScale.sortedByDescending { it.minPercentage }.firstOrNull { it.grade == g }
        "Grade $g${if (cutoff != null) " (≥${formatNumber(cutoff.minPercentage)}%)" else ""}"
    }
    // Over 100% is allowed (bonus points) but usually a typo: say so gently.
    val overMax = scorePercent != null && if (mode == ScoreMode.Percent) (parseFiniteNumber(percentage) ?: 0.0) > 100
    else (parseFiniteNumber(earned) ?: 0.0) > (maxParsed ?: Double.POSITIVE_INFINITY)
    val minThreshold = if (mode == ScoreMode.Percent) parseFiniteNumber(minPassPercent) else parseFiniteNumber(minPassPoints)
    val scoreForCompare = if (mode == ScoreMode.Percent) parseFiniteNumber(percentage) else parseFiniteNumber(earned)
    val belowMinPass = mustPass && minThreshold != null && minThreshold > 0 && scoreForCompare != null && scoreForCompare < minThreshold
    val weightProjected = existingWeightTotal + (parseFiniteNumber(weight) ?: 0.0)

    val subtitle = buildList {
        if (!courseName.isNullOrEmpty()) add(courseName)
        if (estimatedGrade != null) add("Projected Grade $estimatedGrade${projectedPercentage?.let { " (${roundHalfUpToInt(it)}%)" } ?: ""}")
    }.joinToString(" · ").ifEmpty { null }

    val overMaxHint: @Composable () -> Unit = {
        VText("More than the maximum. Fine for bonus points, otherwise check for a typo.", style = vtext(TypeScale.Caption), color = c.tints.amber.text)
    }
    val scaleHintText: @Composable (prefix: String?) -> Unit = { prefix ->
        VText(
            buildAnnotatedString {
                if (prefix != null) append(prefix)
                if (scaleHint != null) {
                    if (prefix != null) append(" · ")
                    withStyle(SpanStyle(fontWeight = Weight.Semibold, color = c.textPrimary)) { append(scaleHint) }
                    append(" on your scale")
                }
            },
            style = vtext(TypeScale.Caption),
            color = c.textMuted,
        )
    }

    TopBar(
        if (editing) "Edit assessment" else "Add assessment",
        BarLeading.Close,
        onClose,
        scrolled = scrolled,
        subtitle = subtitle?.let { text -> { TopBarSubtitle(text) } },
    )
    ScrollBody(
        scrollState = rememberScrollState(),
        onScrolledChange = { scrolled = it },
        fadeBottom = false,
        contentPadding = PaddingValues(start = SurfaceGutter, end = SurfaceGutter, top = 12.dp, bottom = 24.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Assessment")
            FormGroup(rows = listOf(
                {
                    FormField("Name", error = nameError) {
                        VInput(name, { name = it; if (nameError != null) nameError = null }, placeholder = "Midterm 1, ZH 2, homework…", height = SheetInputHeight, textSize = TypeScale.BodyLarge)
                    }
                },
                { FormField("Date", hint = "Optional. Shown next to the name in the list.") { DateField(date, { date = it }, onClear = { date = "" }) } },
            ))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Score")
            FormGroup(rows = listOf(
                { FormField("Enter it as") { PillRadio(mode, ScoreOptions, ::switchMode) } },
                {
                    if (mode == ScoreMode.Percent) {
                        RichField("Result", error = maxError, hint = { if (overMax) overMaxHint() else scaleHintText("${formatNumber(calculatedPoints)} / ${formatNumber(maxParsed?.takeIf { it != 0.0 } ?: 20.0)} pts") }) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                NumberField(percentage, { percentage = normalizeDecimalInput(it).take(6) }, ::commitPercentage, Modifier.weight(1f), c.tints.emerald.text, "85", "Score percentage")
                                VText("% of", style = vtext(TypeScale.Body, Weight.Semibold), color = c.textSecondary)
                                NumberField(maxPoints, { maxPoints = normalizeDecimalInput(it).take(8); maxError = null }, ::commitMax, Modifier.weight(1f), placeholder = "20", label = "Maximum points", invalid = maxError != null)
                                VText("pts", style = vtext(TypeScale.Body, Weight.Semibold), color = c.textSecondary)
                            }
                        }
                    } else {
                        RichField("Result", error = maxError, hint = {
                            when {
                                overMax -> overMaxHint()
                                scaleHint != null -> scaleHintText(null)
                                else -> VText("Points earned out of the maximum.", style = vtext(TypeScale.Caption), color = c.textMuted)
                            }
                        }) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                NumberField(earned, { earned = normalizeDecimalInput(it).take(8) }, ::commitEarned, Modifier.weight(1f), c.tints.emerald.text, label = "Points earned")
                                VText("/", style = vtext(TypeScale.Body, Weight.Semibold), color = c.textSecondary)
                                NumberField(maxPoints, { maxPoints = normalizeDecimalInput(it).take(8); maxError = null }, ::commitMax, Modifier.weight(1f), label = "Maximum points", invalid = maxError != null)
                                VText("pts", style = vtext(TypeScale.Body, Weight.Semibold), color = c.textSecondary)
                            }
                        }
                    }
                },
                {
                    SwitchRow("Already scored", description = "Off for an upcoming assessment you are planning for.") {
                        VToggle(completed, { completed = it }, label = "Already scored")
                    }
                },
            ))
        }
        if (useWeightedCategories) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Weight")
                FormGroup(rows = listOf({
                    RichField("Share of the final grade", hint = {
                        if (weightProjected > 100) {
                            VText("Over 100% in total. Double-check the weights on your syllabus.", style = vtext(TypeScale.Caption, Weight.Semibold), color = c.tints.amber.text)
                        } else {
                            VText(
                                if (existingWeightTotal > 0) "Other assessments add up to ${formatWeight(existingWeightTotal)}%. With this one: ${formatWeight(weightProjected)}%."
                                else "With this one the weights add up to ${formatWeight(weightProjected)}%.",
                                style = vtext(TypeScale.Caption),
                                color = c.textMuted,
                            )
                        }
                    }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            NumberField(weight, { weight = normalizeDecimalInput(it).take(5) }, ::commitWeight, Modifier.widthIn(max = 112.dp).weight(1f, fill = false), placeholder = "25")
                            VText("%", style = vtext(TypeScale.Body, Weight.Semibold), color = c.textSecondary)
                        }
                    }
                }))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Rules")
            FormGroup(rows = buildList {
                add {
                    SwitchRow("Must pass", description = "A minimum score here is needed to pass the course.") {
                        VToggle(mustPass, { mustPass = it }, label = "Must pass")
                    }
                }
                if (mustPass) add {
                    RichField(
                        if (mode == ScoreMode.Percent) "Minimum to pass" else "Minimum points to pass",
                        error = if (belowMinPass) "This score is below the minimum, so the course counts as Grade 1 until it improves." else null,
                        hint = {
                            VText(
                                if (mode == ScoreMode.Percent) "Of this assessment." else "Out of ${maxParsed?.let(::formatNumber) ?: maxPoints} pts.",
                                style = vtext(TypeScale.Caption),
                                color = c.textMuted,
                            )
                        },
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            NumberField(
                                if (mode == ScoreMode.Percent) minPassPercent else minPassPoints,
                                { raw -> val n = normalizeDecimalInput(raw).take(8); if (mode == ScoreMode.Percent) minPassPercent = n else minPassPoints = n },
                                ::commitMinPass,
                                Modifier.widthIn(max = 112.dp).weight(1f, fill = false),
                                ink = if (belowMinPass) c.tints.red.text else c.textPrimary,
                                placeholder = if (mode == ScoreMode.Percent) "50" else "10",
                                invalid = belowMinPass,
                            )
                            VText(if (mode == ScoreMode.Percent) "%" else "pts", style = vtext(TypeScale.Body, Weight.Semibold), color = c.textSecondary)
                        }
                    }
                }
            })
        }
        FormGroup(rows = listOf({
            FormField("Notes") {
                VInput(notes, { notes = it }, placeholder = "Open book, in-class paper quiz…", height = SheetInputHeight, textSize = TypeScale.BodyLarge)
            }
        }))
        if (editing && onDelete != null) DeleteRow("Delete assessment", onDelete)
      }
    }
    BottomBar {
        TrackerButton(::submit, Modifier.fillMaxWidth(), shape = Radius.Panel, height = 48.dp) { ink ->
            VText(if (editing) "Save changes" else "Add assessment", style = vtext(TypeScale.Body, Weight.Bold), color = ink)
        }
    }
}
