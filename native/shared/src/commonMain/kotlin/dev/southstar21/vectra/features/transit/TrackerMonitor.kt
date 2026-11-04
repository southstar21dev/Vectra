package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.time.formatBudapestHm
import kotlin.math.ceil
import kotlin.math.max

/** Directions a tracker monitor renders as columns; extra ones are counted. */
const val MonitorMaxColumns = 3

data class MonitorStopInput(
    val stopId: String,
    /** The tracked direction at this stop; it leads the column order. */
    val headsign: String?,
    val arrivals: List<Arrival>,
)

data class MonitorRow(val stopId: String, val cells: List<Arrival?>)

data class MonitorGrid(
    /** Direction (terminus) per column, in display order. */
    val columns: List<String>,
    /** Per stop, in tracker order: the next arrival in each column, or null. */
    val rows: List<MonitorRow>,
    /** Directions beyond MonitorMaxColumns, counted rather than dropped. */
    val extraColumns: Int,
)

/**
 * A tracker as a stop display: directions are the columns, stops are the rows,
 * each cell the next departure. Columns are the union of every stop's
 * directions, tracked direction first, then first-seen order.
 */
fun buildMonitorGrid(stops: List<MonitorStopInput>, maxColumns: Int = MonitorMaxColumns): MonitorGrid {
    val perStop = stops.map { stop ->
        var groups = groupArrivalsByHeadsign(stop.arrivals)
        if (!stop.headsign.isNullOrEmpty()) {
            // Stable sort: the tracked direction first, the rest keep their order.
            groups = groups.sortedBy { if (it.name == stop.headsign) 0 else 1 }
        }
        stop.stopId to groups
    }
    val all = mutableListOf<String>()
    for ((_, groups) in perStop) for (g in groups) if (g.name !in all) all += g.name
    val columns = all.take(maxColumns)
    return MonitorGrid(
        columns = columns,
        rows = perStop.map { (stopId, groups) -> MonitorRow(stopId, columns.map { name -> groups.firstOrNull { it.name == name }?.list?.firstOrNull() }) },
        extraColumns = all.size - columns.size,
    )
}

/** Minutes until [arrivalMs], never negative. */
fun minutesUntil(arrivalMs: Long, now: Long): Int = max(0, ceil((arrivalMs - now) / 60_000.0).toInt())

/** "NOW" when due, `7'` within the hour, the Budapest clock time beyond it. */
fun formatMonitorTime(arrivalMs: Long, now: Long): String {
    val minutes = minutesUntil(arrivalMs, now)
    if (minutes <= 0) return "NOW"
    if (minutes < 60) return "$minutes'"
    return formatBudapestHm(arrivalMs)
}
