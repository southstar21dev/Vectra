package dev.southstar21.vectra.features.transit.data

import kotlinx.coroutines.CoroutineScope

/**
 * Where live Futár answers come from. The repository only knows this interface: [RealFutarSource]
 * talks to the proxy, and debug builds wrap it in a demo source chosen once in `AppGraph`, so no
 * query has to ask whether an id is a demo one.
 */
internal interface FutarSource {
    /** One stop's arrivals for the polled query, coalesced with every other stop asked for at the same time. */
    suspend fun arrivals(stopId: String): FutarArrivalsResponse

    /** Arrivals with a stop-times cap (multi-stop boards). */
    suspend fun arrivalsWithLimit(stopId: String, limit: Int): FutarArrivalsResponse

    /** One stop's arrivals once, outside the polled query. */
    suspend fun stopArrivals(stopId: String, minutesAfter: Int): FutarArrivalsResponse

    suspend fun arrivalsBatch(stopIds: List<String>, minutesAfter: Int): StopArrivalsBatch

    /** Live vehicles on a route, combined groups merged. */
    suspend fun vehicles(routeId: String): FutarVehiclesForRouteResponse

    /** Live vehicles for one member route. */
    suspend fun memberVehicles(routeId: String): FutarVehiclesForRouteResponse

    suspend fun tripDetails(tripId: String, serviceDate: String?): FutarTripDetailsResponse

    /** Route-level stops and geometry, combined lines merged. */
    suspend fun routeDetails(routeId: String): FutarRouteDetailsResponse
}

/** The live feed: [api] behind the arrivals batching. */
internal class RealFutarSource(private val api: FutarApi, scope: CoroutineScope) : FutarSource {
    private val loader = ArrivalsLoader(
        scope,
        fetchSingle = { id, minutes, limit -> api.fetchStopArrivals(id, minutes, limit) },
        fetchBatch = { ids, minutes -> api.fetchStopArrivalsBatch(ids, minutes) },
    )

    override suspend fun arrivals(stopId: String) = loader.load(stopId)
    override suspend fun arrivalsWithLimit(stopId: String, limit: Int) = loader.loadWithLimit(stopId, limit)
    override suspend fun stopArrivals(stopId: String, minutesAfter: Int) = api.fetchStopArrivals(stopId, minutesAfter)
    override suspend fun arrivalsBatch(stopIds: List<String>, minutesAfter: Int) = api.fetchStopArrivalsBatch(stopIds, minutesAfter)
    override suspend fun vehicles(routeId: String) = api.fetchVehiclesForRouteCombined(routeId)
    override suspend fun memberVehicles(routeId: String) = api.fetchVehiclesForRoute(routeId)
    override suspend fun tripDetails(tripId: String, serviceDate: String?) = api.fetchTripDetails(tripId, serviceDate)
    override suspend fun routeDetails(routeId: String) = api.fetchCombinedRouteDetails(routeId)
}
