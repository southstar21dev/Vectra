package dev.southstar21.vectra.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Widest a page's content column grows, so it stays readable on tablets and wide windows. */
val ContentMaxWidth = 768.dp

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
