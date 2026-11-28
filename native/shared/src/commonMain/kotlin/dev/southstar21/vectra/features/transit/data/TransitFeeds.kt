package dev.southstar21.vectra.features.transit.data

import dev.southstar21.vectra.core.data.LiveQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The live Futár queries, built on a [FutarSource] and nothing else, so the screens that only
 * read feeds take this instead of the whole [TransitRepository] and can be driven by a fake source.
 */
internal class TransitFeeds(scope: CoroutineScope, source: FutarSource, val online: StateFlow<Boolean>) {
    /** One stop's arrivals: 20s poll, 10s stale, coalesced into batches. */
    val arrivals: LiveQuery<String, FutarArrivalsResponse> =
        LiveQuery(scope, refetchIntervalMs = 20_000, staleTimeMs = 10_000, online = online, maxIdleEntries = MaxIdleStops, shouldRetry = ::isRetryableFeedError) {
            source.arrivals(it)
        }

    /** Arrivals with a stop-times limit (multi-stop boards, see `rememberMultiStopArrivals`): key = stopId to limit. */
    val deepArrivals: LiveQuery<Pair<String, Int>, FutarArrivalsResponse> =
        LiveQuery(scope, refetchIntervalMs = 20_000, staleTimeMs = 10_000, online = online, maxIdleEntries = MaxIdleStops, shouldRetry = ::isRetryableFeedError) { (id, limit) ->
            source.arrivalsWithLimit(id, limit)
        }

    /**
     * Batched arrivals keyed by the sorted, comma-joined
     * stop ids. The batch also seeds each stop's own entry.
     */
    val batchArrivals: LiveQuery<String, StopArrivalsBatch> =
        LiveQuery(scope, refetchIntervalMs = 20_000, staleTimeMs = 10_000, online = online, maxIdleEntries = MaxIdleLargeResponses, shouldRetry = ::isRetryableFeedError) { key ->
            val ids = key.split(',').filter { it.isNotEmpty() }
            source.arrivalsBatch(ids, ArrivalsMinutesAfter).also { batch ->
                batch.responses.forEach { (id, r) -> arrivals.setData(id, r) }
            }
        }

    /** Live vehicles on a route (combined groups merged): 10s poll. */
    val vehicles: LiveQuery<String, FutarVehiclesForRouteResponse> =
        LiveQuery(scope, refetchIntervalMs = 10_000, staleTimeMs = 5_000, online = online, maxIdleEntries = MaxIdleLargeResponses) { source.vehicles(it) }

    /** Live vehicles for one member route. */
    val memberVehicles: LiveQuery<String, FutarVehiclesForRouteResponse> =
        LiveQuery(scope, refetchIntervalMs = 10_000, staleTimeMs = 5_000, online = online, maxIdleEntries = MaxIdleLargeResponses) { source.memberVehicles(it) }

    val tripDetails: LiveQuery<TripDetailsKey, FutarTripDetailsResponse> =
        LiveQuery(scope, refetchIntervalMs = 60_000, staleTimeMs = 60_000, online = online, maxIdleEntries = MaxIdleLargeResponses) {
            source.tripDetails(it.tripId, it.serviceDate)
        }

    /** Route-level stops + geometry, combined lines merged. */
    val routeDetails: LiveQuery<String, FutarRouteDetailsResponse> =
        LiveQuery(scope, refetchIntervalMs = 60_000, staleTimeMs = 60_000, online = online, maxIdleEntries = MaxIdleLargeResponses) { source.routeDetails(it) }

    /** The queries that pull to refresh and an app resume cover. The trip and route detail queries are left out on purpose. */
    private val refreshed: List<LiveQuery<*, *>> = listOf(arrivals, deepArrivals, batchArrivals, vehicles, memberVehicles)

    /** Refetches everything on screen and returns when it has landed. */
    suspend fun refetchObserved() = coroutineScope {
        refreshed.forEach { q -> launch { q.refetchObserved() } }
    }

    fun refetchStaleObserved() {
        refreshed.forEach { it.refetchStaleObserved() }
    }

    private companion object {
        /** Enough to keep every page's data for an instant return, without growing per neighbourhood. */
        const val MaxIdleStops = 64
        const val MaxIdleLargeResponses = 16
    }
}
