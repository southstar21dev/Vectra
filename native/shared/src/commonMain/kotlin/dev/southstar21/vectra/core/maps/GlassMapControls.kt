package dev.southstar21.vectra.core.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.specularBorder
import dev.southstar21.vectra.core.designsystem.theme.tappable

/** One glass column of map buttons (44dp each, hairlines between), like the map apps' control stacks. */
@Composable
fun GlassMapControls(buttons: List<MapControlButton>, modifier: Modifier = Modifier) {
    if (buttons.isEmpty()) return
    val c = Vectra.colors
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .width(44.dp)
            .boxShadow(shape, c.glassShadow.filter { !it.inset })
            .background(c.glassBgElevated.copy(alpha = 0.86f), shape)
            .specularBorder(shape, c.glassBorder, c.glassBorderSpecular),
    ) {
        buttons.forEachIndexed { i, b ->
            if (i > 0) Box(Modifier.padding(horizontal = 9.dp).fillMaxWidth().height(1.dp).background(c.borderSubtle))
            Box(
                Modifier
                    .size(44.dp)
                    .tappable(CircleShape, role = Role.Button, onClick = b.onClick)
                    .semantics { contentDescription = b.title },
                contentAlignment = Alignment.Center,
            ) { VIcon(b.icon, size = 18.dp, tint = if (b.active) c.accentPrimary else c.textPrimary) }
        }
    }
}
