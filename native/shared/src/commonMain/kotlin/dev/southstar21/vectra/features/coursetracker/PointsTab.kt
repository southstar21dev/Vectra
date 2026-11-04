package dev.southstar21.vectra.features.coursetracker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.StatusLabel
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraColors
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.util.roundHalfUpToInt
import kotlin.math.round
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** One hue per grade everywhere a grade is drawn. */
internal data class GradeTone(val fill: Color, val dim: Color, val zone: Color, val text: Color)

internal fun gradeTone(grade: Int, c: VectraColors): GradeTone = when (grade) {
    5 -> GradeTone(Fills.Emerald500, Fills.Emerald500.copy(alpha = 0.45f), Fills.Emerald500.copy(alpha = 0.75f), c.tints.emerald.text)
    4 -> GradeTone(Fills.Sky500, Fills.Sky500.copy(alpha = 0.45f), Fills.Sky500.copy(alpha = 0.75f), c.tints.sky.text)
    3 -> GradeTone(Fills.Amber500, Fills.Amber500.copy(alpha = 0.45f), Fills.Amber500.copy(alpha = 0.75f), c.tints.amber.text)
    2 -> GradeTone(Fills.Orange500, Fills.Orange500.copy(alpha = 0.45f), Fills.Orange500.copy(alpha = 0.75f), c.tints.amber.text)
    else -> GradeTone(Fills.Red500, Fills.Red500.copy(alpha = 0.45f), Fills.Red500.copy(alpha = 0.35f), c.tints.red.text)
}

/** Rounded to two decimals, printed without a trailing `.0`. */
internal fun formatScore(value: Double): String = formatNumber(round(value * 100) / 100)

// ---------------------------------------------------------------------------
// Grade scale bar
// ---------------------------------------------------------------------------

internal data class Zone(val grade: Int, val start: Double, val end: Double)

/** The scale as contiguous bands from 0 to 100, lowest grade first. */
internal fun zonesFor(scale: List<GradingScaleTier>): List<Zone> {
    val cutoffs = scale.filter { it.grade >= 2 && it.minPercentage > 0 && it.minPercentage < 100 }.sortedBy { it.minPercentage }
    val zones = mutableListOf<Zone>()
    var start = 0.0
    var grade = 1
    for (tier in cutoffs) {
        if (tier.minPercentage > start) zones += Zone(grade, start, tier.minPercentage)
        start = tier.minPercentage
        grade = tier.grade
    }
    zones += Zone(grade, start, 100.0)
    return zones
}

/** The grading scale as a banded track with each band's starting percentage underneath. */
@Composable
internal fun GradeScaleBar(scale: List<GradingScaleTier>, label: String, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val zones = zonesFor(scale)
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).semantics { contentDescription = label },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (z in zones) {
                val w = (z.end - z.start).toFloat()
                if (w > 0f) Box(Modifier.weight(w).fillMaxHeight().background(gradeTone(z.grade, c).zone))
            }
        }
        // Each label sits at its band's start, centred on it (the first hugs the left edge).
        Layout(
            content = {
                for (z in zones) VText(formatNumber(z.start), style = vtext(10.sp to 14.sp, mono = true).tnum(), color = c.textMuted)
            },
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(14.dp),
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
            layout(constraints.maxWidth, 14.dp.roundToPx()) {
                placeables.forEachIndexed { i, p ->
                    val start = zones[i].start
                    val x = (constraints.maxWidth * start / 100.0).toInt() - if (start > 0) p.width / 2 else 0
                    p.place(x, 0)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Assessment card
// ---------------------------------------------------------------------------

/**
 * A score input holding its own draft while focused ("18." or a cleared field
 * can be typed through); every parseable draft commits as typed.
 */
@Composable
private fun ScoreField(value: Double, min: Double, max: Double, label: String, onCommit: (Double) -> Unit) {
    var draft by remember { mutableStateOf<String?>(null) }
    DraftField(
        value = draft ?: formatScore(value),
        onValueChange = { raw ->
            val next = normalizeDecimalInput(raw).take(7)
            draft = next
            parseFiniteNumber(next)?.let { onCommit(clampNumber(it, min, max)) }
        },
        onFocus = { draft = formatScore(value) },
        onCommit = { draft = null },
        modifier = Modifier.width(64.dp).height(40.dp),
        style = vtext(15.sp to 20.sp, Weight.Black).tnum(),
        contentDescription = label,
    )
}

/**
 * One assessment: date tile, worth, and the score in its grade hue over a
 * meter. Tapping the score (or "Add score") opens a quick editor; the pencil
 * opens the full sheet.
 */
@Composable
internal fun AssessmentCard(
    item: AssessmentItem,
    gradingScale: List<GradingScaleTier>,
    weighted: Boolean,
    onUpdate: ((AssessmentItem) -> AssessmentItem) -> Unit,
    onEdit: () -> Unit,
) {
    val c = Vectra.colors
    var editing by remember { mutableStateOf(false) }
    val completed = item.isScored
    val percent = item.earnedPoints / (if (item.maxPoints == 0.0) 1.0 else item.maxPoints) * 100
    val tones = gradeTone(gradeForPercent(percent, gradingScale), c)
    val details = listOfNotNull(
        "${formatScore(item.maxPoints)} pts",
        if (weighted && item.weightPercent != null) "${formatNumber(item.weightPercent)}% of grade" else null,
        if (completed) null else "not graded yet",
    ).joinToString(" · ")
    val shape = Radius.Card
    Column(Modifier.fillMaxWidth().glassDepth(shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DateTile(item.date)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    VText(item.name, Modifier.weight(1f, fill = false), vtext(TypeScale.Body, Weight.Bold), c.textPrimary, maxLines = 1)
                    if (item.isMustPass == true) StatusLabel("Must-pass", tone = StatusTone.Red)
                }
                VText(details, Modifier.padding(top = 2.dp), vtext(TypeScale.Caption), c.textSecondary, maxLines = 1)
                if (!item.notes.isNullOrEmpty()) VText(item.notes, Modifier.padding(top = 2.dp), vtext(TypeScale.Caption), c.textMuted, maxLines = 1)
            }
            if (completed) {
                VText(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = tones.text, fontSize = TypeScale.Title.first, fontWeight = Weight.Black)) { append(formatScore(item.earnedPoints)) }
                        withStyle(SpanStyle(color = c.textSecondary, fontSize = 13.sp, fontWeight = Weight.Bold)) { append(" / ${formatScore(item.maxPoints)}") }
                    },
                    Modifier
                        .negativeVerticalMargin(6.dp)
                        .clip(Radius.Control)
                        .tappable(role = Role.Button) { editing = !editing }
                        .padding(6.dp)
                        .semantics { contentDescription = "${item.name}: ${formatScore(item.earnedPoints)} of ${formatScore(item.maxPoints)} points. Edit score" },
                    vtext(TypeScale.Title).tnum(),
                    maxLines = 1,
                    align = TextAlign.End,
                )
            } else if (!editing) {
                TrackerButton({ editing = true }, shape = Radius.Tile, horizontalPadding = 14.dp) { ink ->
                    VText("Add score", style = vtext(TypeScale.Caption, Weight.Extrabold), color = ink)
                }
            }
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .offset(x = 6.dp)
                    .pressScale(interaction)
                    .size(36.dp)
                    .clip(CircleShape)
                    .tappable(role = Role.Button, highlight = false, interactionSource = interaction, onClick = onEdit)
                    .semantics { contentDescription = "Edit ${item.name}" },
                contentAlignment = Alignment.Center,
            ) { VIcon(RemixIcons.PencilLine, tint = c.textSecondary) }
        }

        if (completed && !editing) {
            val width by animateFloatAsState((percent / 100).toFloat().coerceIn(0f, 1f), tween(500), label = "meter")
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(c.borderPrimary)) {
                    Box(Modifier.fillMaxWidth(width).fillMaxHeight().clip(CircleShape).background(tones.fill))
                }
                VText("${roundHalfUpToInt(percent)}%", style = vtext(TypeScale.Caption, Weight.Bold).tnum(), color = c.textSecondary)
            }
        }

        AnimatedVisibility(editing, enter = fadeIn(tween(200)), exit = fadeOut(tween(0))) {
            Column(
                Modifier.padding(top = 12.dp).fillMaxWidth().topRule(c.borderSubtle).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    VText("Score", Modifier.weight(1f), vtext(TypeScale.Caption, Weight.Semibold), c.textSecondary)
                    ScoreField(item.earnedPoints, 0.0, 10000.0, "${item.name} points earned") { v -> onUpdate { it.copy(earnedPoints = v, isCompleted = true) } }
                    VText("/", style = vtext(13.sp to 18.sp, Weight.Semibold), color = c.textSecondary)
                    ScoreField(item.maxPoints, 0.1, 10000.0, "${item.name} maximum points") { v -> onUpdate { it.copy(maxPoints = v) } }
                    VText("pts", style = vtext(13.sp to 18.sp, Weight.Semibold), color = c.textSecondary)
                }
                if (item.earnedPoints > item.maxPoints) {
                    // Allowed — bonus points exist — but usually a typo.
                    VText(
                        "More than the ${formatScore(item.maxPoints)} pt maximum. Fine for bonus points, otherwise check for a typo.",
                        style = vtext(TypeScale.Caption),
                        color = c.tints.amber.text,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TrackerButton(
                        { editing = false; onEdit() },
                        Modifier.offset(x = (-8).dp),
                        kind = PillKind.Ghost,
                        horizontalPadding = 12.dp,
                    ) { ink ->
                        VIcon(RemixIcons.PencilLine, size = 14.dp, tint = ink)
                        VText("Edit details", style = vtext(TypeScale.Caption, Weight.Bold), color = ink)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (completed) {
                            TrackerButton({ onUpdate { it.copy(isCompleted = false) }; editing = false }, kind = PillKind.Ghost, horizontalPadding = 12.dp) { ink ->
                                VText("Not graded yet", style = vtext(TypeScale.Caption), color = ink)
                            }
                        }
                        TrackerButton({ editing = false }, shape = Radius.Tile) { ink ->
                            VText("Done", style = vtext(TypeScale.Caption, Weight.Bold), color = ink)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Points tab
// ---------------------------------------------------------------------------

/**
 * The points tab: the grade on the five-point scale, the points it is read
 * from, must-pass failures, and the assessment list with an add/edit sheet.
 */
@Composable
internal fun PointsTab(
    tracker: CourseTracker,
    online: Boolean,
    onAddAssessment: (AssessmentItem) -> Unit,
    onUpdateAssessment: (String, (AssessmentItem) -> AssessmentItem) -> Unit,
    onRemoveAssessment: (String) -> Unit,
    toast: UndoToastState,
) {
    val c = Vectra.colors
    var showSheet by remember { mutableStateOf(false) }
    /** Kept through the close animation so the sheet title does not flip. */
    var editing by remember { mutableStateOf<AssessmentItem?>(null) }
    val assessments = tracker.points.assessments
    val scale = tracker.points.gradingScale
    val totalPoints = tracker.points.totalPoints.takeIf { it > 0 } ?: DefaultCourseTotalPoints
    val weighted = tracker.points.useWeightedCategories
    val summary = calculatePointsSummary(assessments, weighted, scale, totalPoints)
    // Weighted courses grade from the weighted sum, so the bar reads from it too.
    val coursePct = if (weighted) summary.overallPercentage else summary.totalEarned / totalPoints * 100
    val fill = coursePct.coerceIn(0.0, 100.0)
    val unweightedCount = if (weighted) assessments.count { it.weightPercent == null } else 0
    val remaining = maxOf(0.0, totalPoints - summary.totalEarned)
    val weightTotal = assessments.filter { it.id != editing?.id }.sumOf { it.weightPercent ?: 0.0 }
    val hasGrades = assessments.isNotEmpty()
    val grade = summary.estimatedGrade
    val tones = gradeTone(grade, c)

    fun removeAssessment(item: AssessmentItem) {
        onRemoveAssessment(item.id)
        toast.show("Assessment deleted", item.name) { onAddAssessment(item) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        val shape = Radius.Card
        Column(Modifier.fillMaxWidth().glassDepth(shape).padding(20.dp).semantics { contentDescription = "Grade" }) {
            if (hasGrades) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VText(grade.toString(), Modifier.alignByBaseline(), vtext(44.sp to 44.sp, Weight.Black, Tracking.Tight), tones.text)
                    VText(GradeNames.getValue(grade), Modifier.alignByBaseline(), vtext(15.sp to 22.sp, Weight.Bold), c.textPrimary)
                }
            } else {
                VText("No grade yet", style = vtext(TypeScale.Title, Weight.Black, Tracking.Tight), color = c.textPrimary)
            }
            Row(
                Modifier.padding(top = 16.dp).fillMaxWidth().semantics { contentDescription = if (hasGrades) "Grade $grade of 5" else "No grade yet" },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (g in 1..5) {
                    val color by animateColorAsState(
                        when {
                            !hasGrades || g > grade -> c.borderSubtle
                            g == grade -> tones.fill
                            else -> tones.dim
                        },
                        tween(500),
                        label = "gradeBar",
                    )
                    Box(
                        Modifier.weight(1f).height(10.dp).clip(CircleShape).background(color)
                            .then(if (hasGrades && g == grade) Modifier.drawBehind {
                                drawLine(rgba(255, 255, 255, 0.35f), Offset(5.dp.toPx(), 0.5.dp.toPx()), Offset(size.width - 5.dp.toPx(), 0.5.dp.toPx()), 1.dp.toPx())
                            } else Modifier),
                    )
                }
            }
            Row(Modifier.padding(top = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (g in 1..5) {
                    val lit = hasGrades && g == grade
                    VText(
                        g.toString(),
                        Modifier.weight(1f),
                        vtext(TypeScale.Caption, if (lit) Weight.Extrabold else Weight.Semibold).tnum(),
                        if (lit) tones.text else c.textMuted,
                        align = TextAlign.Center,
                    )
                }
            }
            Column(Modifier.padding(top = 18.dp).fillMaxWidth().topRule(c.borderSubtle).padding(top = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VText(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontSize = 15.sp, fontWeight = Weight.Extrabold, color = c.textPrimary)) {
                                append(if (weighted) "${roundHalfUpToInt(summary.overallPercentage)}%" else formatNumber(summary.totalEarned))
                            }
                            withStyle(SpanStyle(fontSize = 13.sp, color = c.textSecondary)) {
                                append(if (weighted) " of the grade" else " / ${formatNumber(totalPoints)} pts")
                            }
                        },
                        Modifier.weight(1f).alignByBaseline(),
                        vtext(15.sp to 22.sp).tnum(),
                    )
                    VText(
                        when {
                            !hasGrades -> "Nothing logged"
                            weighted -> "${maxOf(0, 100 - roundHalfUpToInt(summary.overallPercentage))}% still open"
                            remaining > 0 -> "${formatNumber(remaining)} still open"
                            else -> "All scored"
                        },
                        Modifier.alignByBaseline(),
                        vtext(13.sp to 18.sp).tnum(),
                        c.textSecondary,
                    )
                }
                val width by animateFloatAsState((fill / 100).toFloat(), tween(700), label = "course")
                Box(
                    Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(c.borderSubtle)
                        .semantics {
                            contentDescription = if (weighted) "${roundHalfUpToInt(fill)} percent of the weighted grade"
                            else "${formatNumber(summary.totalEarned)} of ${formatNumber(totalPoints)} points, ${roundHalfUpToInt(fill)} percent"
                        },
                ) {
                    Box(Modifier.fillMaxWidth(width).fillMaxHeight().clip(CircleShape).background(if (hasGrades) tones.fill else c.accentPrimary))
                }
            }
            if (unweightedCount > 0) {
                VText(
                    if (unweightedCount == 1) "1 assessment has no weight, so it doesn't count toward the grade. Add its weight from the syllabus."
                    else "$unweightedCount assessments have no weight, so they don't count toward the grade. Add their weights from the syllabus.",
                    Modifier.padding(top = 12.dp),
                    vtext(TypeScale.Caption, lineHeight = 19.5.sp),
                    c.tints.amber.text,
                )
            }
        }

        if (summary.hasFailedMustPass) {
            Row(
                Modifier.fillMaxWidth().background(c.tints.red.bg, shape).border(1.dp, c.tints.red.border, shape).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VIcon(RemixIcons.ShieldCrossLine, Modifier.padding(top = 2.dp), size = 20.dp, tint = c.tints.red.text)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VText("Must-pass requirement missed", style = vtext(TypeScale.Caption, Weight.Bold), color = c.tints.red.text)
                    VText(
                        buildAnnotatedString {
                            append("Below the minimum on ")
                            summary.failedMustPassItems.forEachIndexed { i, item ->
                                withStyle(SpanStyle(fontWeight = Weight.Semibold, color = c.tints.red.text)) {
                                    append(if (i > 0) ", " else "")
                                    append("${item.name} (${formatNumber(item.earnedPoints)} / ${formatNumber(item.maxPoints)} pts, needs ${item.minPassPoints?.let(::formatNumber).orEmpty()})")
                                }
                            }
                            append(". The course counts as Grade 1 until it is improved or retaken.")
                        },
                        style = vtext(TypeScale.Caption, lineHeight = 19.5.sp),
                        color = c.textSecondary,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A section title with the count set right after the word, on its baseline.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    VText("Assessments", style = vtext(TypeScale.BodyLarge, Weight.Extrabold, Tracking.Tight), color = c.textPrimary)
                    if (hasGrades) {
                        VText(assessments.size.toString(), Modifier.padding(start = 8.dp, bottom = 2.dp), vtext(TypeScale.Body, Weight.Semibold).tnum(), c.textMuted)
                    }
                }
                AddPill { editing = null; showSheet = true }
            }
            if (!hasGrades) {
                Column(Modifier.fillMaxWidth().glassDepth(shape).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    VIcon(RemixIcons.AwardLine, size = 32.dp, tint = c.textMuted)
                    VText("Nothing logged yet", Modifier.padding(top = 12.dp), vtext(TypeScale.BodyLarge, Weight.Extrabold, Tracking.Tight), c.textPrimary, align = TextAlign.Center)
                    VText(
                        "Add the midterm, homework or project once you know its points.",
                        Modifier.padding(top = 4.dp).widthIn(max = 320.dp),
                        vtext(TypeScale.Body),
                        c.textSecondary,
                        align = TextAlign.Center,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (item in assessments) {
                        key(item.id) {
                            AssessmentCard(
                                item = item,
                                gradingScale = scale,
                                weighted = weighted,
                                onUpdate = { transform -> onUpdateAssessment(item.id, transform) },
                                onEdit = { editing = item; showSheet = true },
                            )
                        }
                    }
                }
            }
        }
    }

    val current = editing
    AddAssessmentDialog(
        open = showSheet,
        onDismiss = { showSheet = false },
        onExited = { editing = null },
        item = current,
        onSave = { next -> if (current != null) onUpdateAssessment(current.id) { next } else onAddAssessment(next) },
        onDelete = if (current != null) { { removeAssessment(current); showSheet = false } } else null,
        useWeightedCategories = weighted,
        courseName = tracker.courseName,
        projectedPercentage = summary.overallPercentage,
        estimatedGrade = if (assessments.isNotEmpty()) summary.estimatedGrade else null,
        gradingScale = scale,
        existingWeightTotal = weightTotal,
        online = online,
        toast = toast,
    )
}
