package dev.southstar21.vectra.android.widget

import dev.southstar21.vectra.features.widgets.AgendaCut
import dev.southstar21.vectra.features.widgets.SpineRow
import dev.southstar21.vectra.features.widgets.FootNote
import dev.southstar21.vectra.features.widgets.footNotes

// Which of the shared cuts the large widget shows: the first whose rows and footer fit its height.

internal class Pick(val cut: AgendaCut, val compact: Boolean, val heights: List<Float>)

/** The first cut (with rooms, then without) whose rows and footer fit in [space]. */
internal fun pickCut(cuts: List<AgendaCut>, task: FootNote?, next: FootNote?, b: Budget, width: Float, space: Float): Pick {
    val nameWidth = width - 40f - 10f - 12f - 10f
    for (cut in cuts) {
        for (compact in listOf(false, true)) {
            val heights = cut.shown.map { rowHeight(it, b, nameWidth, compact) }
            val foot = footNotes(cut, task, next).sumOf { b.height(it.text, 12f, width - 70f, bold = false, max = 2).toDouble() + 5.0 }.toFloat()
            val footer = if (foot > 0f) foot + 11f else 0f
            if (cut.shown.size <= 10 && heights.sum() + footer <= space) return Pick(cut, compact, heights)
        }
    }
    val last = cuts.last()
    return Pick(last, true, last.shown.map { rowHeight(it, b, nameWidth, true) })
}

/** A row's height including the 6 dp gap under it. */
private fun rowHeight(row: SpineRow, b: Budget, nameWidth: Float, compact: Boolean): Float = when (row) {
    is SpineRow.Break -> 16f + 6f
    is SpineRow.Day -> b.line(10f) + 10f
    is SpineRow.Past -> b.line(10f) + b.height(row.session.title, 14f, nameWidth, max = 2) + 2f + 6f
    is SpineRow.Now -> b.line(10f) + b.height(row.session.title, 14f, nameWidth, max = 2) + b.line(11f) + 10f + 6f
    is SpineRow.Next -> {
        val room = !compact && (row.session.location.isNotBlank() || row.session.group.isNotBlank())
        maxOf(b.line(10f) + b.height(row.session.title, 14f, nameWidth, max = 2) + if (room) b.line(11f) + 1f else 0f, b.line(12f) + b.line(11f)) + 6f
    }
}
