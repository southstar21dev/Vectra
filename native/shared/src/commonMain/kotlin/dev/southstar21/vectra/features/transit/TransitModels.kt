package dev.southstar21.vectra.features.transit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One stop configured within a multi-stop route tracker. */
@Serializable
data class TrackerStop(
    @SerialName("stop_id") val stopId: String,
    @SerialName("stop_name") val stopName: String,
    /** Direction/terminus filter for this specific stop ('' or null = all directions). */
    val headsign: String? = null,
)

/**
 * Domain shape the UI reads. Encrypted fields are
 * decrypted at the repository boundary; `stops` is parsed from its JSON text.
 */
@Serializable
data class TransitTracker(
    val id: String,
    val userId: String = "",
    val stopId: String = "",
    val stopName: String = "",
    val routeId: String = "",
    val routeShortName: String = "",
    val routeLongName: String = "",
    val routeType: String = "",
    val routeColor: String = "",
    val routeTextColor: String = "",
    val headsign: String = "",
    val isFavorite: Boolean = false,
    val dedupeKey: String = "",
    val createdAt: String = "",
    val stops: List<TrackerStop>? = null,
    val sortOrder: Int? = null,
)

/** Create payload for a new tracker. */
data class NewTrackerInput(
    val routeId: String,
    val routeShortName: String,
    val routeLongName: String,
    val routeType: String,
    val routeColor: String,
    val routeTextColor: String,
    val stops: List<TrackerStop>? = null,
    /** Legacy optional single-stop fields for compatibility. */
    val stopId: String? = null,
    val stopName: String? = null,
    val headsign: String? = null,
)
