package dev.southstar21.vectra.core.designsystem.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The Face ID glyph (corner brackets around eyes, nose and smile) as a 2 px line icon on the Remix grid. */
val FaceIdLine: ImageVector by lazy {
    ImageVector.Builder(name = "FaceIdLine", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        listOf(
            "M8 3H6a3 3 0 0 0-3 3v2M16 3h2a3 3 0 0 1 3 3v2M8 21H6a3 3 0 0 1-3-3v-2M16 21h2a3 3 0 0 0 3-3v-2",
            "M9 9.5v1.5M15 9.5v1.5M12 9.5v3.5h-1M9 16c1.5 1.4 4.5 1.4 6 0",
        ).forEach { d ->
            addPath(pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
        }
    }.build()
}
