package dev.southstar21.vectra.features.widgets

import kotlin.math.ceil

// Glance has no "does it fit" check, so the widgets work out row heights before choosing rows. The
// drawing code and the budget below read the same measurements (WidgetGeometry), so a padding that
// changes in one changes in the other.

/**
 * Rough text metrics for the budget (Roboto): a line is 1.18 em tall, an average glyph about 0.5 em
 * wide (0.55 bold), both scaled by the user's font size ([scale]).
 */
class Budget(private val scale: Float) {
    fun line(sp: Float): Float = sp * 1.18f * scale

    /** Lines [text] takes at [sp] in [widthDp], at most [max]. */
    fun lines(text: String, sp: Float, widthDp: Float, bold: Boolean = true, max: Int = 3): Int {
        val perLine = (widthDp / (sp * scale * if (bold) 0.55f else 0.5f)).coerceAtLeast(1f)
        return ceil(text.length / perLine).toInt().coerceIn(1, max)
    }

    fun height(text: String, sp: Float, widthDp: Float, bold: Boolean = true, max: Int = 3): Float = line(sp) * lines(text, sp, widthDp, bold, max)
}

/** How many of [heights], from the top, fit in [space]. */
fun fitRows(heights: List<Float>, space: Float): Int {
    var left = space
    heights.forEachIndexed { i, h ->
        left -= h
        if (left < 0) return i
    }
    return heights.size
}

/** Sizes in dp that the widgets lay out with and the budget counts with. */
object WidgetGeometry {
    // Today (the wide list): padding, then a time column, the node, and the name.
    const val TodayPadStart = 12f
    const val TodayPadEnd = 15f
    const val TodayPadTop = 11f
    const val TodayPadBottom = 10f
    const val TodayTimeColumn = 40f
    const val TodayTimeGap = 9f
    const val TodayNodeColumn = 11f
    const val TodayNodeGap = 9f

    /** The gap under each row, which belongs to the row so the time line runs through it. */
    const val TodayRowGap = 5f

    /** Where the name column starts, measured from the padding. */
    const val TodayNameOffset = TodayTimeColumn + TodayTimeGap + TodayNodeColumn + TodayNodeGap

    /** Where a later day's label starts: at the node gap, left of the names (60 dp, as on iOS). */
    const val TodayDayLabelOffset = TodayTimeColumn + TodayTimeGap + TodayNodeColumn

    /** The room a name has in a Today widget [width] dp wide. */
    fun todayNameWidth(width: Float): Float = width - TodayPadStart - TodayPadEnd - TodayNameOffset

    /** The height rows can fill in a Today widget [height] dp tall. */
    fun todayRowSpace(height: Float): Float = height - TodayPadTop - TodayPadBottom

    // Agenda (the large day view).
    const val AgendaPadStart = 16f
    const val AgendaPadEnd = 18f
    const val AgendaPadTop = 14f
    const val AgendaPadBottom = 13f
    const val AgendaTimeColumn = 40f
    const val AgendaTimeGap = 10f
    const val AgendaNodeColumn = 12f
    const val AgendaNodeGap = 10f

    /** Where the name column starts, measured from the row's left edge. */
    const val AgendaNameOffset = AgendaTimeColumn + AgendaTimeGap + AgendaNodeColumn + AgendaNodeGap

    /** The gap under each Agenda row. */
    const val AgendaRowGap = 6f

    /** The width of an Agenda row in a widget [width] dp wide. */
    fun agendaRowWidth(width: Float): Float = width - AgendaPadStart - AgendaPadEnd

    /** The room a name has in an Agenda row [rowWidth] dp wide. */
    fun agendaNameWidth(rowWidth: Float): Float = rowWidth - AgendaNameOffset
}

/** A Today row's height: the type and name beside the times, whichever is taller. */
fun todayRowHeight(b: Budget, s: WidgetSession, nowMs: Long, nameWidth: Float): Float {
    val live = s.startMs <= nowMs && nowMs < s.endMs && s.kind.isTimed
    val body = b.line(10f) + 1f + b.height(s.title, 14f, nameWidth, max = 2) + if (live) 7f else 0f
    val times = b.line(12f) + if (s.kind.isTimed) b.line(10f) + 1f else 0f
    return maxOf(body, times)
}
