package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * The Futár (BKK OneBusAway fork) wire shapes the proxy returns. Every field is optional on the wire; defaults keep
 * the parsers total.
 */

/** A route as reported by the Futár API (references.routes). */
@Serializable
data class FutarRoute(
    val id: String = "",
    val shortName: String = "",
    val description: String = "",
    /** Some API forks return the human name under longName instead of description. */
    val longName: String? = null,
    /** SUBWAY | TRAM | BUS | TROLLEYBUS | RAIL | FERRY | ... */
    val type: String = "",
    val color: String = "",
    val textColor: String = "",
)

/** A physical stop as reported by the Futár API. */
@Serializable
data class FutarStop(
    val id: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val name: String = "",
    val code: String? = null,
    /** Station-level id (BKK_CS…) when this stop is a platform. */
    val parentStationId: String? = null,
)

/** Localized text block — the API returns hu/en translations plus a default. */
@Serializable
data class FutarTranslations(
    val translations: Map<String, String>? = null,
    val someTranslation: String? = null,
)

@Serializable
data class FutarAlertRoute(val routeId: String? = null, val effectType: String? = null)

/** A disruption/delay alert (OTP `references.alerts` entry, this fork's shape). */
@Serializable
data class FutarAlert(
    val id: String = "",
    /** Validity window, epoch SECONDS (open-ended when `end` is absent). */
    val start: Double? = null,
    val end: Double? = null,
    val header: FutarTranslations? = null,
    val description: FutarTranslations? = null,
    val url: FutarTranslations? = null,
    val effectType: String? = null,
    val priority: JsonElement? = null,
    val routeIds: List<String>? = null,
    val stopIds: List<String>? = null,
    val vehicleRouteTypes: List<String>? = null,
    /** Rich per-route attachment list this fork returns (routeId + per-route effectType). */
    val routes: List<FutarAlertRoute>? = null,
)

/** A normalized alert after parsing (translated text, active-window filtered). */
@Serializable
data class TransitAlert(
    val id: String,
    val header: String,
    val description: String,
    val url: String? = null,
    val effectType: String? = null,
    /** Which routes/stops this alert is attached to (empty = agency-wide). */
    val routeIds: List<String>? = null,
    val stopIds: List<String>? = null,
)

/** One stopTime entry (a single vehicle visit). Times are epoch seconds. */
@Serializable
data class FutarStopTime(
    val stopId: String = "",
    val stopHeadsign: String? = null,
    val arrivalTime: Long? = null,
    val departureTime: Long? = null,
    val predictedArrivalTime: Long? = null,
    val predictedDepartureTime: Long? = null,
    val stopSequence: Int? = null,
    val tripId: String = "",
    val canceled: Boolean = false,
    /** The trip's service day (YYYYMMDD); after midnight it can be the previous day. */
    val serviceDate: String? = null,
)

/** Which trip-details to load: the trip, on its own service day when known. */
data class TripDetailsKey(val tripId: String, val serviceDate: String? = null)

private val ServiceDateShape = Regex("^[0-9]{8}$")

/** The key for [tripId], or null without one. A service date not in Futár's YYYYMMDD form is left out. */
fun tripDetailsKey(tripId: String?, serviceDate: String?): TripDetailsKey? =
    tripId?.takeIf { it.isNotEmpty() }?.let { TripDetailsKey(it, serviceDate?.takeIf(ServiceDateShape::matches)) }

@Serializable
data class FutarTripRef(
    val routeId: String? = null,
    val tripHeadsign: String? = null,
    val directionId: String? = null,
)

@Serializable
data class FutarArrivalsEntry(
    val stopId: String = "",
    val stopTimes: List<FutarStopTime> = emptyList(),
    /** Alert ids that affect this stop's arrivals (keys into references.alerts). */
    val alertIds: List<String>? = null,
)

@Serializable
data class FutarReferences(
    val routes: Map<String, FutarRoute> = emptyMap(),
    val trips: Map<String, FutarTripRef> = emptyMap(),
    val alerts: Map<String, FutarAlert> = emptyMap(),
    val stops: Map<String, FutarStop> = emptyMap(),
)

@Serializable
data class FutarArrivalsData(
    val entry: FutarArrivalsEntry? = null,
    val references: FutarReferences? = null,
)

/** Raw shape of arrivals-and-departures-for-stop.json. `currentTime` is epoch ms. */
@Serializable
data class FutarArrivalsResponse(
    val currentTime: Long = 0,
    override val status: String? = null,
    val data: FutarArrivalsData? = null,
) : FutarEnvelope

/** A live vehicle on a route (vehicles-for-route.json `data.list` entry). */
@Serializable
data class FutarVehicle(
    val vehicleId: String = "",
    val tripId: String? = null,
    val routeId: String = "",
    val bearing: Double? = null,
    val location: LatLon? = null,
    val lastUpdateTime: Long? = null,
    val status: String? = null,
    val stopId: String? = null,
    val stopSequence: Int? = null,
    val stopDistancePercent: Double? = null,
    val vehicleRouteType: String? = null,
    val label: String? = null,
    val licensePlate: String? = null,
    val model: String? = null,
)

@Serializable
data class FutarVehiclesReferences(val trips: Map<String, FutarTripRef> = emptyMap())

@Serializable
data class FutarVehiclesData(val list: List<FutarVehicle> = emptyList(), val references: FutarVehiclesReferences? = null)

/** Raw shape of vehicles-for-route.json. */
@Serializable
data class FutarVehiclesForRouteResponse(
    val currentTime: Long? = null,
    override val status: String? = null,
    val data: FutarVehiclesData? = null,
) : FutarEnvelope

@Serializable
data class FutarPolyline(val points: String? = null, val length: Int? = null)

/** One scheduled stop on a trip (trip-details.json `entry.stopTimes`). */
@Serializable
data class FutarTripStopTime(
    val stopId: String = "",
    val stopHeadsign: String? = null,
    val stopSequence: Int? = null,
    /** Scheduled times, epoch seconds. A terminus carries only `arrivalTime`. */
    val arrivalTime: Long? = null,
    val departureTime: Long? = null,
    /** Live (includeStatus) estimates, epoch seconds — absent when not tracked. */
    val predictedArrivalTime: Long? = null,
    val predictedDepartureTime: Long? = null,
    val shapeDistTraveled: Double? = null,
)

@Serializable
data class FutarTripDetailsEntry(
    val tripId: String? = null,
    val vehicle: FutarVehicle? = null,
    val polyline: FutarPolyline? = null,
    val stopTimes: List<FutarTripStopTime>? = null,
)

@Serializable
data class FutarStopsReferences(
    val stops: Map<String, FutarStop> = emptyMap(),
    val routes: Map<String, FutarRoute> = emptyMap(),
)

@Serializable
data class FutarTripDetailsData(val entry: FutarTripDetailsEntry? = null, val references: FutarStopsReferences? = null)

/** Raw shape of trip-details.json (subset we use). */
@Serializable
data class FutarTripDetailsResponse(
    val currentTime: Long? = null,
    override val status: String? = null,
    val data: FutarTripDetailsData? = null,
) : FutarEnvelope

/** One direction variant of a route (route-details.json `entry.variants`). */
@Serializable
data class FutarRouteVariant(
    /** Source member route id when variants from combined lines are merged. */
    val routeId: String? = null,
    val name: String? = null,
    val headsign: String? = null,
    /** Ordered stop ids along this direction. */
    val stopIds: List<String>? = null,
    val polyline: FutarPolyline? = null,
)

@Serializable
data class FutarRouteDetailsEntry(val id: String? = null, val variants: List<FutarRouteVariant>? = null)

@Serializable
data class FutarRouteDetailsData(val entry: FutarRouteDetailsEntry? = null, val references: FutarStopsReferences? = null)

/** Raw shape of route-details.json (subset we use). */
@Serializable
data class FutarRouteDetailsResponse(
    val currentTime: Long? = null,
    override val status: String? = null,
    val data: FutarRouteDetailsData? = null,
) : FutarEnvelope

/** A normalized, route-filtered arrival. */
@Serializable
data class Arrival(
    /** epoch ms */
    val arrivalMs: Long,
    val headSign: String,
    val tripHeadsign: String? = null,
    val isPredicted: Boolean,
    /** The trip whose vehicle is arriving — links to vehicles-for-route. */
    val tripId: String,
    val routeId: String? = null,
    val routeShortName: String? = null,
    /** Physical stop (platform) this departure leaves from. */
    val platformStopId: String? = null,
    val platformLat: Double? = null,
    val platformLon: Double? = null,
    /** The trip's service day (YYYYMMDD), for its trip-details. */
    val serviceDate: String? = null,
)
