package dev.southstar21.vectra.features.transit.data

import dev.southstar21.vectra.core.network.ClearColumns
import dev.southstar21.vectra.core.network.EncryptedColumns
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.bool
import dev.southstar21.vectra.core.network.int
import dev.southstar21.vectra.core.network.string
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The encrypted columns of `transit_trackers`, declared once: creating, editing and reading a
 * tracker all go through these names, so they cannot disagree about which value is which column.
 */
internal val TrackerColumns = EncryptedColumns(
    listOf("stop_id", "stop_name", "route_id", "route_short_name", "route_long_name", "headsign", "stops"),
)

private fun stopsJson(stops: List<TrackerStop>): String =
    VectraJson.encodeToString(ListSerializer(TrackerStop.serializer()), stops)

/**
 * The columns that follow a tracker's stops: the primary (first) stop, its headsign and the full
 * list. An empty headsign is stored as `""` whichever way the row is written, which reads back
 * the same as a missing one, and clears an earlier headsign on an edit.
 */
internal fun stopColumnValues(stops: List<TrackerStop>): Map<String, String?> {
    val primary = stops.firstOrNull() ?: TrackerStop("", "", "")
    return mapOf(
        "stop_id" to primary.stopId,
        "stop_name" to primary.stopName,
        "headsign" to primary.headsign.orEmpty(),
        "stops" to stopsJson(stops),
    )
}

/** Every encrypted column of a new tracker: its stops' columns plus the route's names. */
internal fun newTrackerColumnValues(input: NewTrackerInput, stops: List<TrackerStop>): Map<String, String?> =
    stopColumnValues(stops) + mapOf(
        "route_id" to input.routeId,
        "route_short_name" to input.routeShortName,
        "route_long_name" to input.routeLongName,
    )

/** A stored `transit_trackers` [row] with its [TrackerColumns] already decrypted into [c]. */
internal fun adaptTracker(row: JsonObject, c: ClearColumns): TransitTracker {
    // Older rows keep the stops as a plain JSON array rather than a ciphertext string.
    val stopsRaw = (row["stops"] as? JsonArray)?.toString() ?: c["stops"]
    if (c.hasCiphertext) error("Could not decrypt transit trackers.")
    val stops = stopsRaw?.takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { VectraJson.decodeFromString(ListSerializer(TrackerStop.serializer()), raw) }.getOrNull()
    }
    return TransitTracker(
        id = row.string("id").orEmpty(),
        userId = row.string("user").orEmpty(),
        stopId = c["stop_id"].orEmpty(),
        stopName = c["stop_name"].orEmpty(),
        routeId = c["route_id"].orEmpty(),
        routeShortName = c["route_short_name"].orEmpty(),
        routeLongName = c["route_long_name"].orEmpty(),
        routeType = row.string("route_type").orEmpty(),
        routeColor = row.string("route_color").orEmpty(),
        routeTextColor = row.string("route_text_color").orEmpty(),
        headsign = c["headsign"].orEmpty(),
        isFavorite = row.bool("is_favorite") ?: false,
        dedupeKey = row.string("dedupe_key").orEmpty(),
        createdAt = row.string("created_at").orEmpty(),
        stops = stops,
        sortOrder = row.int("sort_order"),
    )
}
