package dev.southstar21.vectra.features.transit.demo

import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.features.transit.data.FutarArrivalsResponse
import dev.southstar21.vectra.features.transit.data.FutarRouteDetailsResponse
import dev.southstar21.vectra.features.transit.data.FutarSource
import dev.southstar21.vectra.features.transit.data.FutarTripDetailsResponse
import dev.southstar21.vectra.features.transit.data.FutarVehiclesForRouteResponse
import dev.southstar21.vectra.features.transit.data.StopArrivalsBatch

/**
 * Debug builds: ids that [DemoFeed] handles get its fake answers, everything else goes to [real].
 * `AppGraph` wraps the real source in this once, so nothing downstream branches on demo ids.
 */
internal class DemoFutarSource(private val real: FutarSource, private val now: () -> Long = ::nowMs) : FutarSource {
    override suspend fun arrivals(stopId: String): FutarArrivalsResponse =
        if (DemoFeed.handles(stopId)) DemoFeed.arrivals(stopId, now()) else real.arrivals(stopId)

    override suspend fun arrivalsWithLimit(stopId: String, limit: Int): FutarArrivalsResponse =
        if (DemoFeed.handles(stopId)) DemoFeed.arrivals(stopId, now()) else real.arrivalsWithLimit(stopId, limit)

    override suspend fun stopArrivals(stopId: String, minutesAfter: Int): FutarArrivalsResponse =
        if (DemoFeed.handles(stopId)) DemoFeed.arrivals(stopId, now()) else real.stopArrivals(stopId, minutesAfter)

    /** Demo stops are left out: their own polled query answers for them. */
    override suspend fun arrivalsBatch(stopIds: List<String>, minutesAfter: Int): StopArrivalsBatch =
        real.arrivalsBatch(stopIds.filterNot(DemoFeed::handles), minutesAfter)

    override suspend fun vehicles(routeId: String): FutarVehiclesForRouteResponse =
        if (DemoFeed.handles(routeId)) DemoFeed.vehicles(routeId, now()) else real.vehicles(routeId)

    override suspend fun memberVehicles(routeId: String): FutarVehiclesForRouteResponse =
        if (DemoFeed.handles(routeId)) DemoFeed.vehicles(routeId, now()) else real.memberVehicles(routeId)

    override suspend fun tripDetails(tripId: String, serviceDate: String?): FutarTripDetailsResponse =
        if (DemoFeed.handles(tripId)) DemoFeed.tripDetails(tripId, now()) else real.tripDetails(tripId, serviceDate)

    override suspend fun routeDetails(routeId: String): FutarRouteDetailsResponse =
        if (DemoFeed.handles(routeId)) DemoFeed.routeDetails(routeId, now()) else real.routeDetails(routeId)
}
