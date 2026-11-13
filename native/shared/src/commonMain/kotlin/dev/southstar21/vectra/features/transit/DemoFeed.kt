package dev.southstar21.vectra.features.transit

import kotlinx.coroutines.awaitCancellation

/**
 * Debug builds only: fake Futár answers for the demo trackers (stop, route and trip ids starting
 * with [Prefix]), built from [DemoLines] relative to now. Every other id goes to the real feed.
 */
internal object DemoFeed {
    const val Prefix = "DEMO_"
    const val DemoEmail = "transit-demo@test.com"
    const val NoPinEmail = "transit-nopin@test.com"

    private val stopsByKey by lazy { DemoLines.flatMap { line -> line.stops.map { it.key to (line to it) } }.toMap() }
    private val stopsById by lazy { stopsByKey.values.associateBy { it.second.stopId } }
    private val linesByRoute by lazy { DemoLines.associateBy { it.routeId } + DemoLines.filter { it.hasReplacement }.associateBy { it.replacementRouteId } }

    /** Stops whose feed answered once already ([DemoBehaviour.StaleAfterFirst]). */
    private val served = HashSet<String>()

    fun handles(id: String?): Boolean = id?.startsWith(Prefix) == true

    suspend fun arrivals(stopId: String, now: Long): FutarArrivalsResponse {
        val (line, stop) = stopsById[stopId] ?: throw FutarException("Unknown demo stop")
        behave(stop)
        val trips = LinkedHashMap<String, FutarTripRef>()
        val stopTimes = stop.directions.flatMapIndexed { i, d ->
            if (stop.behaviour == DemoBehaviour.Empty) return@flatMapIndexed emptyList()
            tripsOf(stop, i, now).map { (tripId, t) ->
                trips[tripId] = FutarTripRef(if (d.replacement) line.replacementRouteId else line.routeId, d.headsign)
                val at = (now + (t.minutes * 60_000).toLong()) / 1000
                FutarStopTime(
                    stopId = stopId,
                    stopHeadsign = d.headsign,
                    arrivalTime = at,
                    departureTime = at,
                    predictedArrivalTime = at.takeIf { t.predicted },
                    predictedDepartureTime = at.takeIf { t.predicted },
                    tripId = tripId,
                )
            }
        }
        val alertId = "${Prefix}A_${stop.key}"
        val alerts = stop.alert?.let { text ->
            mapOf(alertId to FutarAlert(alertId, header = FutarTranslations(someTranslation = text), description = FutarTranslations(someTranslation = text), routeIds = listOf(line.routeId)))
        }.orEmpty()
        val geometry = demoGeometry(stop, 0)
        return FutarArrivalsResponse(
            currentTime = now,
            status = "OK",
            data = FutarArrivalsData(
                entry = FutarArrivalsEntry(stopId, stopTimes, alerts.keys.toList().takeIf { it.isNotEmpty() }),
                references = FutarReferences(
                    routes = mapOf(line.routeId to line.route) + (if (line.hasReplacement) mapOf(line.replacementRouteId to line.replacementRoute) else emptyMap()),
                    trips = trips,
                    alerts = alerts,
                    stops = mapOf(stopId to geometry.stops().getValue(stopId)),
                ),
            ),
        )
    }

    fun vehicles(routeId: String, now: Long): FutarVehiclesForRouteResponse {
        val line = linesByRoute[routeId]
        // The line's own route has its own vehicles, its replacement route the buses.
        val buses = line?.replacementRouteId == routeId
        val list = line?.stops.orEmpty().flatMap { stop ->
            stop.directions.indices.filter { stop.directions[it].replacement == buses }.flatMap { i ->
                val geometry = demoGeometry(stop, i)
                tripsOf(stop, i, now).mapNotNull { (tripId, t) ->
                    t.dist?.let { FutarVehicle(vehicleId = "v-$tripId", tripId = tripId, routeId = routeId, location = geometry.locationAt(it)) }
                }
            }
        }
        return FutarVehiclesForRouteResponse(now, "OK", FutarVehiclesData(list))
    }

    fun tripDetails(tripId: String, now: Long): FutarTripDetailsResponse {
        // DEMO_T_<stop key>_<direction>_<slot>
        val parts = tripId.removePrefix("${Prefix}T_").split('_')
        val (_, stop) = stopsByKey[parts.getOrNull(0)] ?: throw FutarException("Unknown demo trip")
        val dir = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val geometry = demoGeometry(stop, dir)
        // A timetable that reaches your stop when the arrival says, 90 s a stop, so the metro's
        // timetable estimate (it reports no GPS) lands where the trip should be.
        val minutes = tripsOf(stop, dir, now).firstOrNull { it.first == tripId }?.second?.minutes ?: 0.0
        val atStop = now / 1000 + (minutes * 60).toLong()
        val stopTimes = geometry.stopIds.mapIndexed { i, id ->
            val at = atStop + (i - geometry.tracked) * 90L
            FutarTripStopTime(stopId = id, stopSequence = i, arrivalTime = at, departureTime = at, shapeDistTraveled = i * DemoStopMeters)
        }
        return FutarTripDetailsResponse(
            now,
            "OK",
            FutarTripDetailsData(
                FutarTripDetailsEntry(tripId, polyline = FutarPolyline(encodePolyline(geometry.points)), stopTimes = stopTimes),
                FutarStopsReferences(geometry.stops()),
            ),
        )
    }

    fun routeDetails(routeId: String, now: Long): FutarRouteDetailsResponse {
        val line = linesByRoute[routeId] ?: throw FutarException("Unknown demo route")
        val stop = line.stops.first()
        val geometries = stop.directions.indices.map { demoGeometry(stop, it) }
        val variants = stop.directions.mapIndexed { i, d ->
            FutarRouteVariant(routeId, d.headsign, d.headsign, geometries[i].stopIds, FutarPolyline(encodePolyline(geometries[i].points)))
        }
        val stops = geometries.fold(emptyMap<String, FutarStop>()) { acc, g -> acc + g.stops() }
        return FutarRouteDetailsResponse(now, "OK", FutarRouteDetailsData(FutarRouteDetailsEntry(routeId, variants), FutarStopsReferences(stops, mapOf(routeId to line.route))))
    }

    /** A failing feed throws, a loading one never answers, a stale one answers only the first time. */
    private suspend fun behave(stop: DemoStop) {
        when (stop.behaviour) {
            DemoBehaviour.Loading -> awaitCancellation()
            DemoBehaviour.Error -> throw FutarException("Demo: live feed unavailable")
            DemoBehaviour.StaleAfterFirst -> if (!served.add(stop.stopId)) throw FutarException("Demo: feed went quiet")
            else -> Unit
        }
    }

    /** A direction's upcoming trips with their ids; a cycling direction turns its trips over every cycle. */
    private fun tripsOf(stop: DemoStop, dirIndex: Int, now: Long): List<Pair<String, DemoTrip>> {
        val d = stop.directions[dirIndex]
        fun id(slot: Long) = "${Prefix}T_${stop.key}_${dirIndex}_$slot"
        if (d.cycleMs <= 0) return d.trips.mapIndexed { i, t -> id(i.toLong()) to t }
        val base = now / d.cycleMs
        return (1..3).map { k ->
            val minutes = ((base + k) * d.cycleMs - now) / 60_000.0
            id(base + k) to DemoTrip(minutes, minutes * CycleStopsPerMinute)
        }
    }

    private const val CycleStopsPerMinute = 0.9

    /** Shape distance between two demo stops (only the ratios matter). */
    private const val DemoStopMeters = 500.0
}
