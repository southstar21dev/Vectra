package dev.southstar21.vectra.features.schedule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.tappable
import kotlinx.coroutines.launch

/** The attendance statuses' look: fills, glyphs and the discs that carry them. */

internal val StatusGreen = Color(0xFF34D399)
internal val GreenInk = Color(0xFF052E1F)
internal val RimLight = Color.White.copy(alpha = 0.45f)

/** Fill of a marked disc; the same in light and dark. */
internal fun statusFill(status: AttendanceStatus): Color = when (status) {
    AttendanceStatus.Present -> StatusGreen
    AttendanceStatus.Absent -> Color(0xFFF87171)
    AttendanceStatus.Excused -> Color(0xFFFBBF24)
    AttendanceStatus.Cancelled -> Color(0xFF94A3B8)
    AttendanceStatus.Unmarked -> Color.Transparent
}

private fun glyphInk(status: AttendanceStatus): Color = when (status) {
    AttendanceStatus.Present -> GreenInk
    AttendanceStatus.Absent -> Color(0xFF450A0A)
    AttendanceStatus.Excused -> Color(0xFF451A03)
    else -> Color(0xFF0F172A)
}

/** Tap order on a marked orb; an unmarked one goes to Present. */
internal fun nextStatus(status: AttendanceStatus): AttendanceStatus = when (status) {
    AttendanceStatus.Unmarked -> AttendanceStatus.Present
    AttendanceStatus.Present -> AttendanceStatus.Absent
    AttendanceStatus.Absent -> AttendanceStatus.Excused
    AttendanceStatus.Excused -> AttendanceStatus.Cancelled
    AttendanceStatus.Cancelled -> AttendanceStatus.Unmarked
}

internal fun statusWord(status: AttendanceStatus): String = when (status) {
    AttendanceStatus.Present -> "present"
    AttendanceStatus.Absent -> "absent"
    AttendanceStatus.Excused -> "excused"
    AttendanceStatus.Cancelled -> "cancelled"
    AttendanceStatus.Unmarked -> "not checked in"
}

/** The glyph strokes, in a 24-unit box (the mockup's SVG paths). */
private fun glyphPath(status: AttendanceStatus): Path = Path().apply {
    when (status) {
        AttendanceStatus.Present -> { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f) }
        AttendanceStatus.Absent -> { moveTo(7f, 7f); lineTo(17f, 17f); moveTo(17f, 7f); lineTo(7f, 17f) }
        AttendanceStatus.Excused -> { moveTo(12f, 6f); lineTo(12f, 14f); moveTo(12f, 18.5f); lineTo(12f, 18.51f) }
        AttendanceStatus.Cancelled -> {
            moveTo(5f, 7f); lineTo(19f, 7f); lineTo(19f, 19f); lineTo(5f, 19f); close()
            moveTo(5f, 10.5f); lineTo(19f, 10.5f)
            moveTo(9f, 4f); lineTo(9f, 8f); moveTo(15f, 4f); lineTo(15f, 8f)
            moveTo(10f, 13.5f); lineTo(14f, 17.5f); moveTo(14f, 13.5f); lineTo(10f, 17.5f)
        }
        AttendanceStatus.Unmarked -> Unit
    }
}

/** Draws [status]'s glyph centred, [glyph] wide; [progress] < 1 draws the stroke part way. */
internal fun DrawScope.drawGlyph(status: AttendanceStatus, glyph: Dp, ink: Color = glyphInk(status), weight: Float = 3f, progress: Float = 1f) {
    if (status == AttendanceStatus.Unmarked || progress <= 0f) return
    val full = glyphPath(status)
    val path = if (progress >= 1f) full else Path().also { seg ->
        val pm = PathMeasure()
        pm.setPath(full, false)
        pm.getSegment(0f, pm.length * progress, seg, true)
    }
    val s = glyph.toPx() / 24f
    val stroke = if (status == AttendanceStatus.Cancelled) 2.2f else weight
    translate((size.width - glyph.toPx()) / 2f, (size.height - glyph.toPx()) / 2f) {
        scale(s, s, pivot = Offset.Zero) {
            drawPath(path, ink, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A settled marked disc: status fill and a light rim, no glow. */
@Composable
internal fun StatusDisc(
    status: AttendanceStatus,
    size: Dp,
    glyph: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    weight: Float = 3f,
) {
    Canvas(
        modifier
            .size(size)
            .background(statusFill(status), shape)
            .border(1.dp, RimLight, shape),
    ) { drawGlyph(status, glyph, weight = weight) }
}

/** The 24dp disc on a finished-class card; a dashed ring when it was never checked in. */
@Composable
internal fun SmallStatusDisc(status: AttendanceStatus?, dashed: Color, modifier: Modifier = Modifier, square: Boolean = false) {
    val shape = if (square) RoundedCornerShape(7.dp) else CircleShape
    when (status) {
        null -> Canvas(modifier.size(24.dp).background(dashed.copy(alpha = 0.25f), shape)) {
            drawGlyph(AttendanceStatus.Present, 13.dp, ink = dashed, weight = 3.2f)
        }
        AttendanceStatus.Unmarked -> Canvas(modifier.size(24.dp)) {
            val sw = 1.5.dp.toPx()
            drawCircle(
                dashed,
                radius = size.minDimension / 2f - sw / 2f,
                style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()))),
            )
        }
        else -> Canvas(modifier.size(24.dp).background(statusFill(status), shape)) { drawGlyph(status, 13.dp, weight = 3.2f) }
    }
}

/** The 46dp green disc of the "All done for today" card. */
@Composable
internal fun DoneDisc() {
    Canvas(
        Modifier
            .size(46.dp)
            .background(StatusGreen, CircleShape)
            .border(1.dp, RimLight, CircleShape),
    ) { drawGlyph(AttendanceStatus.Present, 22.dp) }
}

/** A task's square check: open ring or green tick; tapping toggles with a nod. */
@Composable
internal fun TaskCheck(done: Boolean, title: String, neutralRing: Color, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = Vectra.haptics
    val reduceMotion = Vectra.reduceMotion
    val scope = rememberCoroutineScope()
    val nod = remember { Animatable(1f) }
    val square = RoundedCornerShape(10.dp)
    Box(
        modifier
            .size(44.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title: ${if (done) "done" else "not done"}. Tap to change" }
            .tappable(square, role = Role.Checkbox, highlight = false) {
                haptics.toggle(!done)
                onToggle()
                if (!reduceMotion) scope.launch {
                    nod.snapTo(0.82f)
                    nod.animateTo(1f, keyframes { durationMillis = 400; 1.08f at 200 })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).graphicsLayer { scaleX = nod.value; scaleY = nod.value }, contentAlignment = Alignment.Center) {
            if (done) StatusDisc(AttendanceStatus.Present, 36.dp, 17.dp, shape = square)
            else Box(Modifier.fillMaxSize().border(1.5.dp, neutralRing, square))
        }
    }
}
