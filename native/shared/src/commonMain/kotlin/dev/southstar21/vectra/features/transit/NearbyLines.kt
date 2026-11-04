package dev.southstar21.vectra.features.transit

data class LineGroup(
    val routeId: String,
    val shortName: String,
    val color: String,
    val textColor: String,
    val directions: List<RadarDepartureItem>,
)

private const val MaxLines = 2

/** The next departures at the nearest stop grouped by line (max two directions each). */
fun groupByLine(items: List<RadarDepartureItem>, stopId: String, maxLines: Int = MaxLines): List<LineGroup> {
    class Acc(val first: RadarDepartureItem, val directions: MutableList<RadarDepartureItem> = mutableListOf())
    val groups = mutableListOf<Acc>()
    for (r in items) {
        if (r.stopId != stopId) continue
        var g = groups.firstOrNull { it.first.routeId == r.routeId }
        if (g == null) {
            if (groups.size >= maxLines) continue
            g = Acc(r)
            groups += g
        }
        if (g.directions.size >= 2 || g.directions.any { it.headsign == r.headsign }) continue
        g.directions += r
    }
    return groups.map { LineGroup(it.first.routeId, it.first.shortName, it.first.color, it.first.textColor, it.directions.toList()) }
}
