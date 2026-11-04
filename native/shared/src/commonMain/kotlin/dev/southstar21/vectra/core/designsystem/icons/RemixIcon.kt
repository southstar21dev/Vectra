package dev.southstar21.vectra.core.designsystem.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Remix icons are 24×24 single-colour paths; tint them via the Icon composable. */
internal fun remixIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = "Remix.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        for (d in paths) addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
    }.build()
