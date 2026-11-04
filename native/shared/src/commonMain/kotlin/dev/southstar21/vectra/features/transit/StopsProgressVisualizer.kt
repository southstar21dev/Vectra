package dev.southstar21.vectra.features.transit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** Which tracked direction a card/list belongs to (second direction = violet). */
enum class DirectionAccent { Emerald, Violet }

private val ShadowSm = listOf(BoxShadow(y = 1f, blur = 2f, color = Color.Black.copy(alpha = 0.05f)))

/** Top-ruled wrapper (subtle hairline above, small top padding) shared by every state. */
@Composable
private fun RuleTop(content: @Composable ColumnScope.() -> Unit) {
    val rule = Vectra.colors.borderSubtle.copy(alpha = Vectra.colors.borderSubtle.alpha * 0.4f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .drawBehind { drawRect(rule, size = Size(size.width, 1.dp.toPx())) }
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * Visualized real-time position for how many stops left to the user: the line
 * of stops to YOU, the vehicle puck between its departed and approaching stop
 * (springing as live telemetry advances) and the traversed track lit behind it.
 * Static under reduced motion.
 */
@Composable
fun StopsProgressVisualizer(
    stopsLeft: Int?,
    liveProgress: RouteProgress?,
    hasVehicle: Boolean,
    accent: DirectionAccent,
    approx: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Vectra.colors
    val dark = c.isDark
    val reduce = Vectra.reduceMotion
    val violet = accent == DirectionAccent.Violet
    val themeBg = when {
        approx -> if (dark) Palette.Pink400 else Palette.Pink500
        violet -> if (dark) Palette.Violet400 else Palette.Violet500
        else -> if (dark) Palette.Emerald400 else Palette.Emerald500
    }
    val themeText = when {
        approx -> if (dark) Palette.Pink400 else Palette.Pink500
        violet -> if (dark) Palette.Violet400 else Palette.Violet600
        else -> if (dark) Palette.Emerald400 else Palette.Emerald600
    }
    val emeraldInk = if (dark) c.tints.emerald.text else Palette.Emerald600

    // Live remaining stops from real-time telemetry.
    val liveStops = liveProgress?.stopsLeft ?: stopsLeft

    Box(modifier.fillMaxWidth()) {
        when {
            liveStops == 0 -> RuleTop {
                val shape = Radius.Control
                Row(
                    Modifier
                        .fillMaxWidth()
                        .boxShadow(shape, ShadowSm)
                        .background(Palette.Emerald500.copy(alpha = 0.1f), shape)
                        .border(1.dp, Palette.Emerald500.copy(alpha = 0.3f), shape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Palette.Emerald500, CircleShape))
                        VText("Arriving now", style = vtext(TypeScale.Body, Weight.Bold), color = emeraldInk)
                    }
                    VText("0 stops left", style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textSecondary)
                }
            }

            stopsLeft != null && stopsLeft > 0 -> RuleTop {
                // Total segments to YOU for this session's track layout (up to 14 stops).
                val totalStops = minOf(maxOf(stopsLeft, 1), 14)
                val intermediateDots = totalStops - 1
                val dotSize: Dp = when {
                    intermediateDots > 10 -> 6.dp
                    intermediateDots > 6 -> 8.dp
                    else -> 10.dp
                }
                val activeLiveStops = if (liveStops != null && liveStops > 0) liveStops else stopsLeft
                val segIdx = maxOf(0, minOf(totalStops - 1, totalStops - activeLiveStops))
                val segStart = segIdx.toFloat() / totalStops
                val segEnd = (segIdx + 1).toFloat() / totalStops
                val frac = liveProgress?.segmentFraction?.let { it.coerceIn(0.12, 0.88) } ?: 0.45
                val target = segStart + frac.toFloat() * (segEnd - segStart)

                val pos = remember { Animatable(target) }
                LaunchedEffect(target, reduce) {
                    if (reduce) pos.snapTo(target) else pos.animateTo(target, VectraSprings.glide())
                }
                val vehiclePos = pos.value

                VText(
                    if (activeLiveStops == 1) "Next stop" else "$activeLiveStops stops to you",
                    style = vtext(TypeScale.Body, Weight.Bold, Tracking.Tight),
                    color = if (activeLiveStops == 1) emeraldInk else c.textPrimary,
                )

                Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)) {
                        val w = maxWidth
                        // Base rail.
                        Box(
                            Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp)
                                .background(if (dark) Color.White.copy(alpha = 0.2f) else Palette.Zinc200, CircleShape),
                        )
                        // Trailing luminous fill up to the vehicle.
                        Box(
                            Modifier.align(Alignment.CenterStart).width(w * vehiclePos).height(4.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        0f to Color.Transparent,
                                        0.5f to themeText.copy(alpha = 0.9f),
                                        1f to themeText.copy(alpha = 0.9f),
                                    ),
                                    CircleShape,
                                ),
                        )
                        val idle = if (dark) Palette.Zinc800 else Color.White
                        val idleBorder = if (dark) Palette.Zinc600 else Palette.Zinc300
                        // Origin stop.
                        RailStopDot(
                            Modifier.align(Alignment.CenterStart).offset(x = -dotSize / 2),
                            dotSize,
                            fill = if (vehiclePos > 0f) themeBg else idle,
                            border = if (vehiclePos > 0f) Color.Transparent else idleBorder,
                        )
                        // Intermediate stops.
                        for (idx in 0 until intermediateDots) {
                            val p = (idx + 1).toFloat() / totalStops
                            val passed = p <= vehiclePos
                            val approaching = idx == segIdx
                            val ring = when {
                                approx -> Palette.Pink500
                                violet -> Palette.Violet500
                                else -> Palette.Emerald500
                            }
                            RailStopDot(
                                Modifier.align(Alignment.CenterStart).offset(x = w * p - dotSize / 2),
                                dotSize,
                                fill = when {
                                    passed -> themeBg
                                    approaching -> if (dark) Palette.Zinc900 else Color.White
                                    else -> idle
                                },
                                border = when {
                                    passed -> Color.Transparent
                                    approaching -> ring
                                    else -> idleBorder
                                },
                                ring = if (!passed && approaching) ring.copy(alpha = 0.25f) else null,
                            )
                        }
                        // Destination "YOU".
                        RailStopDot(
                            Modifier.align(Alignment.CenterStart).offset(x = w - dotSize / 2).semantics { contentDescription = "YOU" },
                            dotSize,
                            fill = Palette.Blue500,
                            border = if (dark) Palette.Blue400 else Palette.Blue500,
                            ring = Palette.Blue500.copy(alpha = 0.25f),
                        )
                        // Vehicle puck with its forward arrow.
                        val puck = when {
                            approx -> Palette.Pink500
                            violet -> if (dark) Palette.Violet500 else Palette.Violet600
                            else -> if (dark) Palette.Emerald500 else Palette.Emerald600
                        }
                        val glow = when {
                            approx -> Palette.Pink500
                            violet -> Palette.Violet500
                            else -> Palette.Emerald500
                        }.copy(alpha = 0.3f)
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = w * vehiclePos - 10.dp)
                                .size(20.dp)
                                .boxShadow(
                                    CircleShape,
                                    listOf(
                                        BoxShadow(y = 4f, blur = 6f, spread = -1f, color = glow),
                                        BoxShadow(y = 2f, blur = 4f, spread = -2f, color = glow),
                                    ),
                                )
                                .background(puck, CircleShape)
                                .border(1.dp, Color.White.copy(alpha = if (dark) 0.4f else 0.8f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { VIcon(RemixIcons.ArrowRightFill, size = 12.dp, tint = Color.White) }
                    }
                }
            }

            // Has a vehicle but the approach calculation is not active yet.
            hasVehicle -> RuleTop {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(themeBg, CircleShape))
                        VText("Vehicle on route", style = vtext(TypeScale.Body, Weight.Semibold), color = c.textSecondary)
                    }
                    VText("Awaiting approach", style = vtext(TypeScale.Caption), color = c.textMuted)
                }
            }

            // Scheduled departure only.
            else -> RuleTop {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        VIcon(RemixIcons.TimeLine, size = 14.dp, tint = c.textMuted)
                        VText("Scheduled departure", style = vtext(TypeScale.Body), color = c.textMuted)
                    }
                    VText("No live vehicle", style = vtext(TypeScale.Caption), color = c.textMuted)
                }
            }
        }
    }
}

/** A stop on the progress rail: 2dp border, optional 2dp ring, 300ms colour transitions. */
@Composable
private fun RailStopDot(modifier: Modifier, size: Dp, fill: Color, border: Color, ring: Color? = null) {
    val f by animateColorAsState(fill, tween(300), label = "dotFill")
    val b by animateColorAsState(border, tween(300), label = "dotBorder")
    val r by animateColorAsState(ring ?: Color.Transparent, tween(300), label = "dotRing")
    Box(
        modifier
            .size(size)
            .boxShadow(CircleShape, ShadowSm + BoxShadow(spread = 2f, color = r))
            .background(f, CircleShape)
            .border(2.dp, b, CircleShape),
    )
}
