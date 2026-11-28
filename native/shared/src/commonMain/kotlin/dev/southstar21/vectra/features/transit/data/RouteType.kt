package dev.southstar21.vectra.features.transit.data

/**
 * The route types the Futár feed and the stored trackers use, as the strings they are sent and
 * saved as (those values must not change, so they stay plain strings; this only names them).
 */
internal object RouteType {
    const val Subway = "SUBWAY"
    const val Tram = "TRAM"
    const val Bus = "BUS"
    const val Trolleybus = "TROLLEYBUS"
    const val Rail = "RAIL"
    const val SuburbanRailway = "SUBURBAN_RAILWAY"
    const val Ferry = "FERRY"
    const val Coach = "COACH"

    /** The one vehicle-type order: line lists and the route boards at a stop sort by it. */
    val Priority = listOf(Subway, Tram, Trolleybus, Bus, Rail, Ferry)
}
