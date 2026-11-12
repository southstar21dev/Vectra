package dev.southstar21.vectra.core.designsystem.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/** Widest a page's content column grows, so it stays readable on tablets and wide windows. */
val ContentMaxWidth = 768.dp

/** Room for content (inside the safe area) from which tab pages switch to two columns. */
val WideLayoutMinWidth = 720.dp

/** Widest a two-column page grows. */
val WideContentMaxWidth = 1200.dp

/** Gap between the two columns of a wide page. */
val WideColumnGap = 18.dp

/**
 * True when the tab pages have room for two columns: an unfolded iPhone Duo, a tablet, a
 * landscape foldable. Provided by the tab shell from the window's width. A layout fact every
 * tab page reads, like the window insets, so a local beats threading it through every page.
 */
@Suppress("CompositionLocalAllowlist")
val LocalWideLayout = compositionLocalOf { false }

/**
 * The window's left and right safe area: a side cutout in landscape, or the sensor housing and
 * side tab bar of an unfolded iPhone Duo. Content that runs edge to edge pads it.
 */
@Composable
fun horizontalSafeArea(): PaddingValues = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()

/** Corner shapes, by what wears them. */
object Radius {
    val Hairline = RoundedCornerShape(4.dp)
    val Tag = RoundedCornerShape(6.dp)
    val Chip = RoundedCornerShape(8.dp)
    val Control = RoundedCornerShape(12.dp)
    val Row = RoundedCornerShape(14.dp)
    val Panel = RoundedCornerShape(16.dp)
    val Tile = RoundedCornerShape(20.dp)
    val Card = RoundedCornerShape(24.dp)
}
