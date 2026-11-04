package dev.southstar21.vectra.features.coursetracker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.ConfirmDialog
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.VToggle
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.components.SectionTitle
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlinx.coroutines.delay
import kotlin.math.round
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

private const val MaxAbsences = 50

private enum class SaveWhere { Attendance, Grades, Points }

private fun tierInputsFromScale(scale: List<GradingScaleTier>): Map<Int, String> =
    (2..5).associateWith { g -> scale.firstOrNull { it.grade == g }?.minPercentage?.let(::formatNumber) ?: "0" }

/** The percentages a grade covers, e.g. "75–86%", from the committed scale. */
private fun gradeRange(scale: List<GradingScaleTier>, grade: Int): String {
    val min = scale.firstOrNull { it.grade == grade }?.minPercentage ?: 0.0
    if (grade == 5) return "${formatNumber(min)}–100%"
    val next = scale.firstOrNull { it.grade == grade + 1 }?.minPercentage ?: 100.0
    return "${formatNumber(min)}–${formatNumber(if (next == kotlin.math.floor(next)) next - 1 else next)}%"
}

/**
 * The "Rules" tab: the syllabus check, the absence limit with a
 * stepper, the course total, and the grade cutoffs over a banded scale.
 */
@Composable
internal fun SettingsTab(
    tracker: CourseTracker,
    online: Boolean,
    onUpdateLimits: (TrackerLimits) -> Unit,
    onUpdateGradingScale: (List<GradingScaleTier>) -> Unit,
    onConfirmSettings: (() -> Unit)?,
) {
    val c = Vectra.colors
    var maxInput by remember { mutableStateOf("") }
    var totalInput by remember { mutableStateOf("") }
    var scale by remember { mutableStateOf(DefaultGradingScale) }
    val tierInputs = remember { mutableStateMapOf<Int, String>() }
    var scaleError by remember { mutableStateOf<String?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var saveWhere by remember { mutableStateOf(SaveWhere.Attendance) }
    var saveTick by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }

    // Sync the drafts whenever the stored tracker changes.
    remember(tracker) {
        maxInput = tracker.attendance.maxAbsencesAllowed?.toString().orEmpty()
        totalInput = formatNumber(tracker.points.totalPoints.takeIf { it > 0 } ?: DefaultCourseTotalPoints)
        scale = tracker.points.gradingScale
        tierInputs.clear(); tierInputs.putAll(tierInputsFromScale(tracker.points.gradingScale))
        scaleError = null
        Unit
    }

    LaunchedEffect(saveMessage, saveTick) {
        if (saveMessage != null) {
            delay(2600)
            saveMessage = null
        }
    }

    fun flashSaved(where: SaveWhere = SaveWhere.Attendance) {
        saveMessage = if (online) "Saved" else "Saved on this device — syncs when online"
        saveWhere = where
        saveTick++
    }

    fun commitLimits(override: String? = null) {
        val raw = override ?: maxInput
        val v = if (raw.trim().isEmpty()) null else clampNumber(round(parseFiniteNumber(raw) ?: 0.0), 0.0, MaxAbsences.toDouble()).toInt()
        maxInput = v?.toString().orEmpty()
        if (v == tracker.attendance.maxAbsencesAllowed && tracker.attendance.minAttendancePercent == null) return
        onUpdateLimits(TrackerLimits(v, null))
        flashSaved()
    }

    val limited = maxInput.trim().isNotEmpty()
    val committedMax = parseFiniteNumber(maxInput) ?: 0.0

    fun commitTier(grade: Int) {
        val parsed = parseFiniteNumber(tierInputs[grade].orEmpty())
        if (parsed == null) {
            tierInputs[grade] = formatNumber(scale.firstOrNull { it.grade == grade }?.minPercentage ?: 0.0)
            scaleError = null
            return
        }
        val value = clampNumber(parsed, 0.0, 100.0)
        val next = scale.map { if (it.grade == grade) it.copy(minPercentage = value) else it }
        val error = validateScaleOrder(next)
        tierInputs[grade] = formatNumber(value)
        if (error != null) {
            scaleError = error
            return
        }
        scale = next
        scaleError = null
        onUpdateGradingScale(next)
        flashSaved(SaveWhere.Grades)
    }

    // Course total: blank or invalid falls back to the default, never a broken denominator.
    fun commitTotal() {
        val parsed = parseFiniteNumber(totalInput)
        val value = if (parsed == null) DefaultCourseTotalPoints else clampNumber(round(parsed), 1.0, 10000.0)
        totalInput = formatNumber(value)
        if (value == (tracker.points.totalPoints.takeIf { it > 0 } ?: DefaultCourseTotalPoints)) return
        onUpdateLimits(TrackerLimits(tracker.attendance.maxAbsencesAllowed, tracker.attendance.minAttendancePercent, totalPoints = value))
        flashSaved(SaveWhere.Points)
    }

    fun reset() {
        scale = DefaultGradingScale
        tierInputs.clear(); tierInputs.putAll(tierInputsFromScale(DefaultGradingScale))
        maxInput = DefaultAttendanceLimits.MaxAbsencesAllowed.toString()
        totalInput = formatNumber(DefaultCourseTotalPoints)
        scaleError = null
        onUpdateGradingScale(DefaultGradingScale)
        onUpdateLimits(TrackerLimits(DefaultAttendanceLimits.MaxAbsencesAllowed, null, totalPoints = DefaultCourseTotalPoints))
        flashSaved()
    }

    val savedFlash: (SaveWhere) -> (@Composable () -> Unit) = { where ->
        {
            AnimatedVisibility(saveWhere == where && saveMessage != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(0))) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    VIcon(RemixIcons.CheckLine, size = 14.dp, tint = c.tints.emerald.text)
                    VText(saveMessage.orEmpty(), style = vtext(TypeScale.Caption, Weight.Bold), color = c.tints.emerald.text)
                }
            }
        }
    }
    val card = Radius.Card
    val field = vtext(15.sp to 20.sp, Weight.Black).tnum()

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // Seeded defaults must never pass for the course's actual rules.
        if (!tracker.settingsConfirmed && onConfirmSettings != null) {
            Column(Modifier.fillMaxWidth().glassDepth(card).padding(18.dp)) {
                VText("Check your syllabus", style = vtext(15.sp to 22.sp, Weight.Extrabold), color = c.textPrimary)
                VText(
                    "These are Vectra's defaults. Match them to this course so your standing and grade are right.",
                    Modifier.padding(top = 4.dp),
                    vtext(13.sp to 17.875.sp),
                    c.textSecondary,
                )
                val interaction = remember { MutableInteractionSource() }
                val shape = Radius.Tile
                Row(
                    Modifier.padding(top = 14.dp).fillMaxWidth().height(44.dp)
                        .pressScale(interaction, 0.98f)
                        .markDepth(shape, MarkFill.Present)
                        .clip(shape)
                        .tappable(role = Role.Button, highlight = false, interactionSource = interaction, onClick = onConfirmSettings),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VIcon(RemixIcons.CheckLine, tint = Color.White)
                    VText("They match", style = vtext(TypeScale.Body, Weight.Extrabold), color = Color.White)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Attendance", action = { savedFlash(SaveWhere.Attendance)() })
            Column(Modifier.fillMaxWidth().glassDepth(card)) {
                Divided(buildList {
                    add {
                        SettingRow("Limit absences", "Off if the course doesn't count them") {
                            VToggle(limited, { on -> commitLimits(if (on) DefaultAttendanceLimits.MaxAbsencesAllowed.toString() else "") }, label = "Limit absences")
                        }
                    }
                    if (limited) add {
                        SettingRow("Absences allowed", "Lectures and seminars together") {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Stepper(RemixIcons.SubtractLine, "One fewer absence", committedMax > 0) {
                                    commitLimits(clampNumber(round(committedMax) - 1, 0.0, MaxAbsences.toDouble()).toInt().toString())
                                }
                                DraftField(
                                    value = maxInput,
                                    onValueChange = { maxInput = it.filter(Char::isDigit).take(2) },
                                    onCommit = { commitLimits() },
                                    modifier = Modifier.size(40.dp),
                                    style = vtext(TypeScale.Title, Weight.Black).tnum(),
                                    background = Color.Transparent,
                                    border = null,
                                    keyboardType = KeyboardType.Number,
                                    contentDescription = "Absences allowed",
                                )
                                Stepper(RemixIcons.AddLine, "One more absence", committedMax < MaxAbsences) {
                                    commitLimits(clampNumber(round(committedMax) + 1, 0.0, MaxAbsences.toDouble()).toInt().toString())
                                }
                            }
                        }
                    }
                })
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Points", action = { savedFlash(SaveWhere.Points)() })
            Box(Modifier.fillMaxWidth().glassDepth(card)) {
                SettingRow("Course total", "Everything you can earn this term") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DraftField(
                            value = totalInput,
                            onValueChange = { totalInput = it.filter(Char::isDigit).take(5) },
                            onCommit = ::commitTotal,
                            modifier = Modifier.width(72.dp).height(40.dp),
                            style = field,
                            keyboardType = KeyboardType.Number,
                            contentDescription = "Course total",
                        )
                        VText("pts", style = vtext(13.sp to 18.sp, Weight.Semibold), color = c.textSecondary)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Grade cutoffs", action = { savedFlash(SaveWhere.Grades)() })
            Column(Modifier.fillMaxWidth().clip(card).glassDepth(card)) {
                Divided(buildList {
                    add {
                        GradeScaleBar(
                            scale,
                            "Grade cutoffs: " + (2..5).joinToString(", ") { g -> "Grade $g from ${formatNumber(scale.firstOrNull { it.grade == g }?.minPercentage ?: 0.0)}%" },
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                        )
                    }
                    for (grade in listOf(5, 4, 3, 2)) add {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(36.dp, 40.dp), contentAlignment = Alignment.Center) {
                                VText(grade.toString(), style = vtext(28.sp to 36.sp, Weight.Black, Tracking.Tight), color = gradeTone(grade, c).text)
                            }
                            Column(Modifier.weight(1f)) {
                                VText(GradeNames.getValue(grade), style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary)
                                VText(gradeRange(scale, grade), style = vtext(TypeScale.Caption, mono = true).tnum(), color = c.textMuted)
                            }
                            VText("from", style = vtext(TypeScale.Caption), color = c.textMuted)
                            DraftField(
                                value = tierInputs[grade].orEmpty(),
                                onValueChange = { tierInputs[grade] = normalizeDecimalInput(it).take(5) },
                                onCommit = { commitTier(grade) },
                                modifier = Modifier.width(52.dp).height(40.dp),
                                style = field,
                                contentDescription = "Grade $grade minimum percentage",
                            )
                            VText("%", style = vtext(13.sp to 18.sp), color = c.textSecondary)
                        }
                    }
                    scaleError?.let { err ->
                        add {
                            VText(err, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), vtext(TypeScale.Caption, Weight.Semibold, lineHeight = 16.5.sp), c.tints.red.text)
                        }
                    }
                })
            }
        }

        TrackerButton({ confirmReset = true }, Modifier.fillMaxWidth(), kind = PillKind.Outline, shape = Radius.Tile, height = 44.dp) {
            VText("Reset to defaults", style = vtext(13.sp to 18.sp, Weight.Bold), color = c.textSecondary, align = TextAlign.Center)
        }
    }

    ConfirmDialog(
        open = confirmReset,
        onClose = { confirmReset = false },
        onConfirm = { reset() },
        title = "Reset to defaults?",
        description = "Restores the default absence limit (3), grading scale (87 / 75 / 62 / 50) and course total (100 pts). Your attendance records and assessments are not affected.",
        confirmText = "Reset settings",
        cancelText = "Keep my settings",
    )
}

@Composable
private fun SettingRow(title: String, caption: String, control: @Composable () -> Unit) {
    val c = Vectra.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            VText(title, style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary)
            VText(caption, Modifier.padding(top = 2.dp), vtext(TypeScale.Caption), c.textMuted)
        }
        control()
    }
}

@Composable
private fun Stepper(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Vectra.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .then(if (enabled) Modifier.pressScale(interaction) else Modifier)
            .alpha(if (enabled) 1f else 0.4f)
            .size(40.dp)
            .markGlass(CircleShape)
            .clip(CircleShape)
            .tappable(enabled = enabled, role = Role.Button, highlight = false, interactionSource = interaction, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { VIcon(icon, tint = c.textPrimary) }
}
