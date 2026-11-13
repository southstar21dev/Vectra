package dev.southstar21.vectra.features.navbar

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The navbar tucks away like Revolut's: scrolling a page down makes it smaller with its labels
 * hidden, scrolling up brings it back. [connection] watches every scroll on the tab pages.
 */
@Stable
internal class NavbarCompact {
    var compact by mutableStateOf(false)

    /** How far the page has scrolled in the current direction (px; negative is down the page). */
    private var travel = 0f

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            if (dy == 0f) return Offset.Zero
            if (sign(dy) != sign(travel)) travel = 0f
            travel += dy
            if (travel < -CompactTravelPx) compact = true else if (travel > CompactTravelPx) compact = false
            return Offset.Zero
        }
    }

    /** Back to full size (a tab switch). */
    fun reset() {
        compact = false
        travel = 0f
    }
}

/** How far a page scrolls one way before the bar follows (px). */
private const val CompactTravelPx = 24f

/** A tab's label folding away as the bar tucks ([collapse] 0..1, read in layout and draw only). */
internal fun Modifier.foldAway(collapse: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val p = collapse().coerceIn(0f, 1f)
    layout(placeable.width, (placeable.height * (1f - p)).roundToInt()) {
        placeable.placeWithLayer(0, 0) {
            alpha = (1f - p * 1.6f).coerceIn(0f, 1f)
            val s = 1f - 0.2f * p
            scaleX = s
            scaleY = s
        }
    }
}
