package dev.southstar21.vectra.features.schedule

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---- Status look ----------------------------------------------------------------

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

internal val StatusGreen = Color(0xFF34D399)
private val GreenInk = Color(0xFF052E1F)
private val RimLight = Color.White.copy(alpha = 0.45f)

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
    val stroke = (if (status == AttendanceStatus.Cancelled) 2.2f else weight)
    translate((size.width - glyph.toPx()) / 2f, (size.height - glyph.toPx()) / 2f) {
        scale(s, s, pivot = Offset.Zero) {
            drawPath(path, ink, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A settled marked disc: status fill and a light rim. */
@Composable
internal fun StatusDisc(
    status: AttendanceStatus,
    size: Dp,
    glyph: Dp,
    shape: Shape = CircleShape,
    weight: Float = 3f,
    modifier: Modifier = Modifier,
) {
    // No glow: a flat status fill with a light rim.
    Canvas(
        modifier
            .size(size)
            .background(statusFill(status), shape)
            .border(1.dp, RimLight, shape),
    ) { drawGlyph(status, glyph, weight = weight) }
}

// ---- The orb ------------------------------------------------------------------------

private const val OrbSize = 36
private const val SlotSize = 44
/** Pill padding around its text: 12dp on the left; tick (17), gap (6) and 9dp on the right. */
private const val PillChrome = 12 + 6 + 17 + 9
private val PillText = 12.sp to 16.sp
private const val Timeline = 3000f

/**
 * When the pill starts shrinking back. With a streak to show it stays open longer, so
 * "+1, N in a row" is on screen for about 1.5 s instead of flashing past.
 */
private fun closeAt(streak: Int) = if (streak >= 2) 3500f else 2700f

/** The whole reward: the pill takes 300 ms to shrink back after [closeAt]. */
private fun rewardLength(streak: Int) = closeAt(streak) + 300f

private val OrbitEase = CubicBezierEasing(0.5f, 0f, 0.3f, 1f)
private val StretchEase = CubicBezierEasing(0.3f, 1.2f, 0.5f, 1f)
private val TickEase = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

/** [t] mapped from [from]..[to] (ms) to 0..1 through [easing]. */
private fun phase(t: Float, from: Float, to: Float, easing: Easing = LinearEasing): Float =
    easing.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

/**
 * The check-in button at the right of a class card's room row. Unmarked → Present plays the
 * reward (orbit, merge, tick, "Checked in" pill, streak); other taps step the status with a nod.
 * The slot widens with the pill, so the room text beside it shortens instead of being covered.
 */
@Composable
internal fun CheckInOrb(
    status: AttendanceStatus,
    title: String,
    courseColor: Color,
    /** The class to check in to next: its ring takes the course colour. */
    focus: Boolean,
    /** The class is on now: the ring breathes while it waits. */
    live: Boolean,
    /** Marked offline; a small clock sits on the orb until it syncs. */
    pendingSync: Boolean,
    neutralRing: Color,
    badgeBg: Color,
    streak: () -> Int,
    onSetStatus: (AttendanceStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = Vectra.haptics
    val reduceMotion = Vectra.reduceMotion
    val scope = rememberCoroutineScope()
    val clock = remember { Animatable(Timeline) }
    var celebrating by remember { mutableStateOf(false) }
    var streakCount by remember { mutableIntStateOf(0) }
    val nod = remember { Animatable(1f) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Finger down flattens the orb; it never sinks.
    val press by animateFloatAsState(if (pressed && !celebrating) 0.88f else 1f, tween(90), label = "orbPress")

    val t = clock.value
    // The pill is as wide as its longer line of text, never a fixed size.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val sans = Vectra.fonts.sans
    val pillWide = remember(streakCount, sans, density) {
        fun w(text: String, weight: androidx.compose.ui.text.font.FontWeight) = with(density) {
            measurer.measure(text, vtext(PillText, weight).copy(fontFamily = sans)).size.width.toDp().value
        }
        val a = w("Checked in", Weight.Extrabold)
        val b = if (streakCount >= 2) w("+1, $streakCount in a row", Weight.Bold) else 0f
        maxOf(a, b) + PillChrome
    }
    val pillWidth = if (!celebrating) OrbSize.toFloat() else when {
        t < 900f -> OrbSize.toFloat()
        t < closeAt(streakCount) -> lerp(OrbSize.toFloat(), pillWide, phase(t, 900f, 1150f, StretchEase))
        else -> lerp(pillWide, OrbSize.toFloat(), phase(t, closeAt(streakCount), rewardLength(streakCount), VectraEasing.Move))
    }
    val slotWidth = SlotSize + (pillWidth - OrbSize)

    Box(
        modifier
            .width(slotWidth.dp)
            .height(SlotSize.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Attendance for $title: ${statusWord(status)}. Tap to change" }
            .tappable(
                CircleShape,
                role = Role.Button,
                highlight = false,
                interactionSource = interaction,
            ) {
                // The reward plays out before the next tap counts.
                if (celebrating) return@tappable
                val next = nextStatus(status)
                onSetStatus(next)
                if (next == AttendanceStatus.Present && !reduceMotion) {
                    haptics.tick()
                    streakCount = streak()
                    celebrating = true
                    scope.launch {
                        clock.snapTo(0f)
                        val length = rewardLength(streakCount)
                        clock.animateTo(length, tween(length.toInt(), easing = LinearEasing))
                        celebrating = false
                    }
                    scope.launch {
                        delay(520)
                        haptics.confirm()
                    }
                } else {
                    if (next == AttendanceStatus.Present) haptics.confirm() else haptics.tick()
                    if (!reduceMotion) scope.launch {
                        nod.snapTo(0.82f)
                        nod.animateTo(1f, keyframes { durationMillis = 400; 1.08f at 200 })
                    }
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        // The 36dp orb sits centred in the right-most 44dp of the slot.
        val orbBox = Modifier.padding(end = 4.dp).size(OrbSize.dp)
        if (celebrating) {
            // The ring stays until the green fill covers it.
            if (t < 620f) Box(orbBox) { Ring(courseColor.copy(alpha = 0.9f), null, breathe = false) }
            Celebration(t, pillWidth, courseColor, streakCount)
        } else {
            val orb: @Composable (AttendanceStatus) -> Unit = { s ->
                Box(
                    orbBox.graphicsLayer {
                        val k = press * nod.value
                        scaleX = k
                        scaleY = k
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (s == AttendanceStatus.Unmarked) {
                        Ring(if (focus) courseColor.copy(alpha = 0.9f) else neutralRing, if (focus) courseColor else null, live && !reduceMotion)
                    } else {
                        StatusDisc(s, OrbSize.dp, 17.dp)
                    }
                }
            }
            // Reduced motion: no orbit, no pill; the new state cross-fades in.
            if (reduceMotion) Crossfade(status, animationSpec = tween(150), label = "orbFade") { orb(it) } else orb(status)
            if (pendingSync && status != AttendanceStatus.Unmarked) {
                // The badge overhangs the orb's bottom-right by 4dp.
                Box(Modifier.align(Alignment.BottomEnd).size(16.dp).background(badgeBg, CircleShape), contentAlignment = Alignment.Center) {
                    VIcon(RemixIcons.TimeLine, size = 12.dp, tint = Color(0xFFFBBF24))
                }
            }
        }
    }
}

/**
 * The unmarked ring; [halo] adds the course-colour halo, breathing when [breathe]. The breath is
 * read only while drawing and the ring has its own layer, so each frame redraws just the ring:
 * invalidating the card's layer would re-rasterize every card shadow on iOS, every frame.
 */
@Composable
private fun Ring(color: Color, halo: Color?, breathe: Boolean) {
    val breath = if (halo != null && breathe) {
        val loop = rememberInfiniteTransition(label = "nowRing")
        loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = VectraEasing.Breathe), RepeatMode.Reverse), label = "breathe")
    } else null
    Canvas(Modifier.size(OrbSize.dp).graphicsLayer()) {
        val r = size.minDimension / 2f
        if (halo != null) {
            val f = breath?.value
            val spread = if (f != null) 7f * f else 6f
            val alpha = if (f != null) 0.16f * f else 0.12f
            if (spread > 0f) {
                val w = spread.dp.toPx()
                drawCircle(halo.copy(alpha = alpha), radius = r + w / 2f, style = Stroke(w))
            }
        }
        val sw = 1.5.dp.toPx()
        drawCircle(color, radius = r - sw / 2f, style = Stroke(sw))
    }
}

/** One frame of the reward, at [t] ms into its 3 s timeline. */
@Composable
private fun Celebration(t: Float, pillWidth: Float, courseColor: Color, streak: Int) {
    val showStreak = streak >= 2
    // Orbit: three lights circle the ring once and spiral into the centre (0–600 ms).
    val orbit = phase(t, 0f, 600f, OrbitEase)
    if (orbit < 1f) {
        Canvas(Modifier.padding(end = 4.dp).size(OrbSize.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            listOf(StatusGreen, Color(0xFFF0FDF4), courseColor).forEachIndexed { i, dot ->
                val radius = when {
                    orbit < 0.15f -> lerp(30f, 28f, orbit / 0.15f)
                    else -> lerp(28f, 0f, (orbit - 0.15f) / 0.85f)
                }.dp.toPx()
                val angle = (i * 120f + lerp(0f, 330f, orbit)) * PI.toFloat() / 180f
                val a = when {
                    orbit < 0.15f -> orbit / 0.15f
                    orbit > 0.85f -> 1f - (orbit - 0.85f) / 0.15f
                    else -> 1f
                }
                val s = when {
                    orbit < 0.15f -> lerp(0.4f, 1f, orbit / 0.15f)
                    else -> lerp(1f, 0.3f, (orbit - 0.15f) / 0.85f)
                }
                val p = c + Offset(cos(angle) * radius, sin(angle) * radius)
                drawCircle(dot.copy(alpha = a * 0.35f), radius = 6.dp.toPx() * s, center = p)
                drawCircle(dot.copy(alpha = a), radius = 3.dp.toPx() * s, center = p)
            }
        }
    }
    // Merge: the orb fills green and springs 0 → 1.14 → 1 (520–700 ms).
    val fill = when {
        t < 520f -> 0f
        t < 620f -> lerp(0f, 1.14f, phase(t, 520f, 620f, VectraEasing.Decelerate))
        else -> lerp(1.14f, 1f, phase(t, 620f, 720f, VectraEasing.Soft))
    }
    if (fill <= 0f) return
    val tick = phase(t, 620f, 940f, TickEase)
    // Text: "Checked in", then "+1, N in a row", each fading in with a small slide.
    val aIn = phase(t, 1000f, 1150f)
    val close = closeAt(streak)
    val aOut = phase(t, if (showStreak) 1750f else close - 150f, if (showStreak) 1900f else close)
    val bIn = phase(t, 1900f, 2050f)
    val bOut = phase(t, close - 150f, close)
    val pill = RoundedCornerShape(50)
    Box(
        Modifier
            .width(pillWidth.dp)
            .height(OrbSize.dp)
            .offset(x = (-4).dp)
            .graphicsLayer {
                scaleX = fill
                scaleY = fill
                transformOrigin = TransformOrigin(1f - (OrbSize / 2f) / pillWidth, 0.5f)
            }
            .clip(pill)
            .background(StatusGreen)
            .border(1.dp, RimLight, pill),
    ) {
        val textEnd = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp).wrapContentWidth(Alignment.End, unbounded = true)
        val ink = GreenInk
        if (aIn > 0f && aOut < 1f) {
            VText(
                "Checked in",
                textEnd.graphicsLayer {
                    alpha = aIn * (1f - aOut)
                    translationY = (1f - aIn) * 5.dp.toPx() - aOut * 5.dp.toPx()
                },
                vtext(PillText, Weight.Extrabold),
                ink,
                maxLines = 1,
            )
        }
        if (showStreak && bIn > 0f && bOut < 1f) {
            VText(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = Weight.Black)) { append("+1") }
                    append(", $streak in a row")
                },
                textEnd.graphicsLayer {
                    alpha = bIn * (1f - bOut)
                    translationY = (1f - bIn) * 5.dp.toPx()
                },
                vtext(PillText, Weight.Bold),
                ink,
                maxLines = 1,
            )
        }
        Canvas(Modifier.align(Alignment.CenterEnd).size(OrbSize.dp)) { drawGlyph(AttendanceStatus.Present, 17.dp, progress = tick) }
    }
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
            .size(SlotSize.dp)
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
        Box(Modifier.size(OrbSize.dp).graphicsLayer { scaleX = nod.value; scaleY = nod.value }, contentAlignment = Alignment.Center) {
            if (done) StatusDisc(AttendanceStatus.Present, OrbSize.dp, 17.dp, square)
            else Box(Modifier.fillMaxSize().border(1.5.dp, neutralRing, square))
        }
    }
}

/** The 24dp disc on a finished-class card; a dashed ring when it was never checked in. */
@Composable
internal fun SmallStatusDisc(status: AttendanceStatus?, dashed: Color, square: Boolean = false, modifier: Modifier = Modifier) {
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
