package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.PillTone
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.VectraMotion
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.insetShadow
import dev.southstar21.vectra.core.designsystem.theme.pillDepth
import dev.southstar21.vectra.core.designsystem.theme.rgba

/** On/off switch. The thumb springs so a fast flick keeps its momentum. */
@Composable
fun VToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    label: String? = null,
) {
    val c = Vectra.colors
    val haptics = Vectra.haptics
    val thumbX by animateDpAsState(
        if (checked) 24.dp else 0.dp,
        if (Vectra.reduceMotion) VectraMotion.reducedFade() else VectraSprings.snappy(),
        label = "thumb",
    )
    // The off and on track recipes cross-fade rather than interpolating colour and shadow.
    val on by animateFloatAsState(if (checked) 1f else 0f, tween(200, easing = VectraEasing.Fade), label = "track")
    val off = Modifier
        .background(if (c.isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.12f), CircleShape)
        .insetShadow(CircleShape, listOf(BoxShadow(y = 2f, blur = 4f, color = rgba(0, 0, 0, 0.05f), inset = true)))
        .border(1.dp, if (c.isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f), CircleShape)
    Box(
        modifier
            .size(48.dp, 24.dp)
            .toggleable(checked, enabled = enabled && !loading, role = Role.Switch) {
                haptics.toggle(it)
                onCheckedChange(it)
            }
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (on < 1f) Box(Modifier.matchParentSize().graphicsLayer { alpha = 1f - on }.then(off))
        if (on > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = on }.pillDepth(CircleShape, PillTone.Accent))
        if (loading) {
            InlineLoader(Modifier.align(Alignment.Center), size = 16.dp, tint = Color.White)
        } else {
            Box(
                Modifier
                    // 1dp border + 2dp inset put the thumb 3dp in from the edge.
                    .padding(start = 3.dp)
                    .offset(x = thumbX)
                    .size(20.dp)
                    .boxShadow(CircleShape, listOf(BoxShadow(y = 2f, blur = 5f, color = rgba(0, 0, 0, 0.28f))))
                    .background(Color.White, CircleShape),
            )
        }
    }
}
