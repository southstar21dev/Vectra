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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.VectraHaptics
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val OrbSize = 36
private const val SlotSize = 44

/** Pill padding around its text: 12dp on the left; tick (17), gap (6) and 9dp on the right. */
private const val PillChrome = 12 + 6 + 17 + 9
private val PillText = 12.sp to 16.sp

private val OrbitEase = CubicBezierEasing(0.5f, 0f, 0.3f, 1f)
private val StretchEase = CubicBezierEasing(0.3f, 1.2f, 0.5f, 1f)
private val TickEase = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

/** [t] mapped from [from]..[to] (ms) to 0..1 through [easing]. */
private fun phase(t: Float, from: Float, to: Float, easing: Easing = LinearEasing): Float =
    easing.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

/**
 * The reward's state: a clock in ms through its timeline, the streak it shows, and the small
 * nod a status step gets. One per orb.
 */
@Stable
private class OrbReward(private val scope: CoroutineScope) {
    val clock = Animatable(0f)
    val nod = Animatable(1f)
    var celebrating by mutableStateOf(false)
    var streak by mutableIntStateOf(0)

    /** When the pill starts shrinking back: with a streak it stays open about 1.5 s longer. */
    fun closeAt(streak: Int = this.streak) = if (streak >= 2) 3500f else 2700f

    /** The whole reward: the pill takes 300 ms to shrink back after [closeAt]. */
    fun length() = closeAt() + 300f

    fun play(streak: Int, haptics: VectraHaptics) {
        this.streak = streak
        celebrating = true
        haptics.tick()
        scope.launch {
            clock.snapTo(0f)
            clock.animateTo(length(), tween(length().toInt(), easing = LinearEasing))
            celebrating = false
        }
        scope.launch {
            delay(520)
            haptics.confirm()
        }
    }

    fun nod() {
        scope.launch {
            nod.snapTo(0.82f)
            nod.animateTo(1f, keyframes { durationMillis = 400; 1.08f at 200 })
        }
    }
}

/** The pill's open width: its longer line of text plus [PillChrome], never a fixed size. */
@Composable
private fun rememberPillWide(streak: Int): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val sans = Vectra.fonts.sans
    return remember(streak, sans, density) {
        fun w(text: String, weight: FontWeight) = with(density) {
            measurer.measure(text, vtext(PillText, weight).copy(fontFamily = sans)).size.width.toDp().value
        }
        val a = w("Checked in", Weight.Extrabold)
        val b = if (streak >= 2) w("+1, $streak in a row", Weight.Bold) else 0f
        maxOf(a, b) + PillChrome
    }
}

/** The pill's width at this point of the reward: the orb, stretching open, holding, closing. */
private fun pillWidthAt(reward: OrbReward, wide: Float): Float {
    if (!reward.celebrating) return OrbSize.toFloat()
    val t = reward.clock.value
    return when {
        t < 900f -> OrbSize.toFloat()
        t < reward.closeAt() -> lerp(OrbSize.toFloat(), wide, phase(t, 900f, 1150f, StretchEase))
        else -> lerp(wide, OrbSize.toFloat(), phase(t, reward.closeAt(), reward.length(), VectraEasing.Move))
    }
}

/** What the orb shows when it is not playing the reward. */
@Stable
internal class OrbLook(
    val courseColor: Color,
    /** The class to check in to next: its ring takes the course colour. */
    val focus: Boolean,
    /** The class is on now: the ring breathes while it waits. */
    val live: Boolean,
    /** Marked offline; a small clock sits on the orb until it syncs. */
    val pendingSync: Boolean,
    val neutralRing: Color,
    val badgeBg: Color,
)

/**
 * The check-in button at the right of a class card's room row. Unmarked → Present plays the
 * reward (orbit, merge, tick, "Checked in" pill, streak); other taps step the status with a nod.
 * The slot widens with the pill, so the room text beside it shortens instead of being covered.
 */
@Composable
internal fun CheckInOrb(
    status: AttendanceStatus,
    title: String,
    look: OrbLook,
    streak: () -> Int,
    onSetStatus: (AttendanceStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = Vectra.haptics
    val reduceMotion = Vectra.reduceMotion
    val scope = rememberCoroutineScope()
    val reward = remember { OrbReward(scope) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Finger down flattens the orb; it never sinks.
    val press by animateFloatAsState(if (pressed && !reward.celebrating) 0.88f else 1f, tween(90), label = "orbPress")
    val pillWidth = pillWidthAt(reward, rememberPillWide(reward.streak))

    Box(
        modifier
            .width((SlotSize + pillWidth - OrbSize).dp)
            .height(SlotSize.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Attendance for $title: ${statusWord(status)}. Tap to change" }
            .tappable(CircleShape, role = Role.Button, highlight = false, interactionSource = interaction) {
                // The reward plays out before the next tap counts.
                if (reward.celebrating) return@tappable
                val next = nextStatus(status)
                onSetStatus(next)
                when {
                    next == AttendanceStatus.Present && !reduceMotion -> reward.play(streak(), haptics)
                    next == AttendanceStatus.Present -> haptics.confirm()
                    else -> {
                        haptics.tick()
                        if (!reduceMotion) reward.nod()
                    }
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (reward.celebrating) {
            RewardFrame(reward.clock.value, pillWidth, look.courseColor, reward.streak, reward.closeAt())
        } else {
            SettledOrb(status, look, scale = { press * reward.nod.value }, reduceMotion)
        }
    }
}

/** The orb at rest: the ring when unmarked, else the status disc; plus the offline clock. */
@Composable
private fun SettledOrb(status: AttendanceStatus, look: OrbLook, scale: () -> Float, reduceMotion: Boolean) {
    Box(contentAlignment = Alignment.CenterEnd) {
        val orb: @Composable (AttendanceStatus) -> Unit = { s ->
            Box(
                // The 36dp orb sits centred in the right-most 44dp of the slot.
                Modifier.padding(end = 4.dp).size(OrbSize.dp).graphicsLayer {
                    scaleX = scale()
                    scaleY = scale()
                },
                contentAlignment = Alignment.Center,
            ) {
                if (s == AttendanceStatus.Unmarked) {
                    Ring(
                        if (look.focus) look.courseColor.copy(alpha = 0.9f) else look.neutralRing,
                        if (look.focus) look.courseColor else null,
                        look.live && !reduceMotion,
                    )
                } else {
                    StatusDisc(s, OrbSize.dp, 17.dp)
                }
            }
        }
        // Reduced motion: no orbit, no pill; the new state cross-fades in.
        if (reduceMotion) Crossfade(status, animationSpec = tween(150), label = "orbFade") { orb(it) } else orb(status)
        if (look.pendingSync && status != AttendanceStatus.Unmarked) {
            // The badge overhangs the orb's bottom-right by 4dp.
            Box(Modifier.align(Alignment.BottomEnd).offset(y = 4.dp).size(16.dp).background(look.badgeBg, CircleShape), contentAlignment = Alignment.Center) {
                VIcon(RemixIcons.TimeLine, size = 12.dp, tint = Color(0xFFFBBF24))
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
            val w = (if (f != null) 7f * f else 6f).dp.toPx()
            val alpha = if (f != null) 0.16f * f else 0.12f
            if (w > 0f) drawCircle(halo.copy(alpha = alpha), radius = r + w / 2f, style = Stroke(w))
        }
        val sw = 1.5.dp.toPx()
        drawCircle(color, radius = r - sw / 2f, style = Stroke(sw))
    }
}

/** One frame of the reward, at [t] ms: the ring until the fill covers it, the orbit, the pill. */
@Composable
private fun RewardFrame(t: Float, pillWidth: Float, courseColor: Color, streak: Int, close: Float) {
    Box(contentAlignment = Alignment.CenterEnd) {
        if (t < 620f) Box(Modifier.padding(end = 4.dp).size(OrbSize.dp)) { Ring(courseColor.copy(alpha = 0.9f), null, breathe = false) }
        val orbit = phase(t, 0f, 600f, OrbitEase)
        if (orbit < 1f) OrbitDots(orbit, courseColor)
        // Merge: the orb fills green and springs 0 → 1.14 → 1 (520–720 ms).
        val fill = when {
            t < 520f -> 0f
            t < 620f -> lerp(0f, 1.14f, phase(t, 520f, 620f, VectraEasing.Decelerate))
            else -> lerp(1.14f, 1f, phase(t, 620f, 720f, VectraEasing.Soft))
        }
        if (fill > 0f) RewardPill(t, fill, pillWidth, streak, close)
    }
}

/** Three lights circle the ring once and spiral into the centre ([orbit] 0 → 1 over 600 ms). */
@Composable
private fun OrbitDots(orbit: Float, courseColor: Color) {
    Canvas(Modifier.padding(end = 4.dp).size(OrbSize.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val entering = orbit < 0.15f
        val radius = (if (entering) lerp(30f, 28f, orbit / 0.15f) else lerp(28f, 0f, (orbit - 0.15f) / 0.85f)).dp.toPx()
        val alpha = when {
            entering -> orbit / 0.15f
            orbit > 0.85f -> 1f - (orbit - 0.85f) / 0.15f
            else -> 1f
        }
        val scale = if (entering) lerp(0.4f, 1f, orbit / 0.15f) else lerp(1f, 0.3f, (orbit - 0.15f) / 0.85f)
        listOf(StatusGreen, Color(0xFFF0FDF4), courseColor).forEachIndexed { i, dot ->
            val angle = (i * 120f + lerp(0f, 330f, orbit)) * PI.toFloat() / 180f
            val p = c + Offset(cos(angle) * radius, sin(angle) * radius)
            drawCircle(dot.copy(alpha = alpha * 0.35f), radius = 6.dp.toPx() * scale, center = p)
            drawCircle(dot.copy(alpha = alpha), radius = 3.dp.toPx() * scale, center = p)
        }
    }
}

/** The green pill: it springs in, draws its tick, says "Checked in", then the streak, then closes. */
@Composable
private fun RewardPill(t: Float, fill: Float, pillWidth: Float, streak: Int, close: Float) {
    val showStreak = streak >= 2
    val aIn = phase(t, 1000f, 1150f)
    val aOut = if (showStreak) phase(t, 1750f, 1900f) else phase(t, close - 150f, close)
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
        if (aIn > 0f && aOut < 1f) {
            VText(
                "Checked in",
                textEnd.graphicsLayer {
                    alpha = aIn * (1f - aOut)
                    translationY = (1f - aIn) * 5.dp.toPx() - aOut * 5.dp.toPx()
                },
                vtext(PillText, Weight.Extrabold),
                GreenInk,
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
                GreenInk,
                maxLines = 1,
            )
        }
        Canvas(Modifier.align(Alignment.CenterEnd).size(OrbSize.dp)) {
            drawGlyph(AttendanceStatus.Present, 17.dp, progress = phase(t, 620f, 940f, TickEase))
        }
    }
}
