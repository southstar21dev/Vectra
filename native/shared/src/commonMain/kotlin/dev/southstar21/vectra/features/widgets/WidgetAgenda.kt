package dev.southstar21.vectra.features.widgets

// How the large widget fits a day (and the days after it) into its height. iOS mirrors this in
// iosApp/VectraWidgets/Spine.swift and DayWidgets.swift (AgendaFit.candidates).

/** [day]'s spine, then the next [laterDays] days that have something, each under its name. */
fun WidgetSnapshot?.agendaRows(day: String, nowMs: Long, laterDays: Int = 3): List<SpineRow> {
    val later = this?.sessions.orEmpty().filter { it.day > day && it.kind.isTimed }.map { it.day }.distinct().take(laterDays)
    return spine(day, nowMs) + later.flatMap { listOf(SpineRow.Day(it)) + spine(it, nowMs) }
}

/** One way the large widget can fit a day: the rows it shows and the sessions it leaves off the end. */
data class AgendaCut(val shown: List<SpineRow>, val hidden: List<WidgetSession>)

/**
 * From every row to fewer: finished classes go first, oldest first (three stay when nothing else is
 * left), then the last ones, which the widget names in its footer instead.
 */
fun agendaCuts(rows: List<SpineRow>): List<AgendaCut> {
    val current = rows.toMutableList().apply { trimFillers() }
    val out = mutableListOf(AgendaCut(current.toList(), emptyList()))
    dropLaterDays(current, out)
    dropFinished(current, out)
    dropFromEnd(current, out)
    return out
}

private fun List<SpineRow>.sessionCount() = count { it !is SpineRow.Break && it !is SpineRow.Day }

/** No break at the start, no break or day name at the end. */
private fun MutableList<SpineRow>.trimFillers() {
    while (firstOrNull() is SpineRow.Break) removeAt(0)
    while (lastOrNull() is SpineRow.Break || lastOrNull() is SpineRow.Day) removeAt(lastIndex)
}

/** Later days' rows go first, one at a time from the end (a day's name goes with its last row). */
private fun dropLaterDays(current: MutableList<SpineRow>, out: MutableList<AgendaCut>) {
    while (current.any { it is SpineRow.Day }) {
        current.removeAt(current.lastIndex)
        current.trimFillers()
        out += AgendaCut(current.toList(), emptyList())
    }
}

/** Then finished classes, oldest first; three stay when nothing else is left. */
private fun dropFinished(current: MutableList<SpineRow>, out: MutableList<AgendaCut>) {
    while (true) {
        val i = current.indexOfFirst { it is SpineRow.Past }
        val othersLeft = current.any { it is SpineRow.Now || it is SpineRow.Next }
        if (i < 0 || (!othersLeft && current.sessionCount() <= 3)) return
        current.removeAt(i)
        current.trimFillers()
        out += AgendaCut(current.toList(), emptyList())
    }
}

/** Then the last ones, which the widget names in its footer instead. */
private fun dropFromEnd(current: MutableList<SpineRow>, out: MutableList<AgendaCut>) {
    val hidden = mutableListOf<WidgetSession>()
    while (current.sessionCount() > 1) {
        when (val last = current.removeAt(current.lastIndex)) {
            is SpineRow.Past -> hidden.add(0, last.session)
            is SpineRow.Now -> hidden.add(0, last.session)
            is SpineRow.Next -> hidden.add(0, last.session)
            is SpineRow.Break, is SpineRow.Day -> Unit
        }
        current.trimFillers()
        out += AgendaCut(current.toList(), hidden.toList())
    }
}
