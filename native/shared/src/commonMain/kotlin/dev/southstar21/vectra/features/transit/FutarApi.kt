package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.PocketBaseException
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.core.util.runSuspendCatching
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class FutarException(message: String) : Exception(message)

/** What every proxy response carries: `status` is "OK" unless the call failed. */
interface FutarEnvelope {
    val status: String?
}

/**
 * Stop-times cap every arrivals request asks for. 200 is the ceiling Futár accepts
 * and covers 60–90+ min even at Deák tér; without it Futár stops at 60, which at a
 * station-level stop id is only minutes of metro and tram traffic.
 */
const val DeepArrivalsLimit = 200

/** Stops per batch request — mirrors the server-side cap (backend/internal/futar/). */
const val MaxBatchArrivalStops = 20

/** Wire format of the `stop-arrivals` batch endpoint (shared dictionaries deduplicated). */
@Serializable
data class FutarStopArrivalsBatchResponse(
    val currentTime: Long? = null,
    override val status: String? = null,
    val data: Data? = null,
) : FutarEnvelope {
    @Serializable
    data class Shared(
        val routes: Map<String, FutarRoute>? = null,
        val trips: Map<String, FutarTripRef>? = null,
        val alerts: Map<String, FutarAlert>? = null,
    )

    @Serializable
    data class Arrive(
        val stopId: String? = null,
        val stopTimes: List<FutarStopTime>? = null,
        val alertIds: List<String>? = null,
        val stops: Map<String, FutarStop>? = null,
        /** Per-stop upstream failure, keeping the other stops usable. */
        val error: String? = null,
    )

    @Serializable
    data class Data(val shared: Shared? = null, val arrives: Map<String, Arrive?>? = null)
}

data class StopArrivalsBatch(
    /** Per-stop responses, each shaped exactly like a single-stop arrivals call. */
    val responses: Map<String, FutarArrivalsResponse>,
    /** Per-stop failures (a whole-request failure never reaches this shape). */
    val errors: Map<String, String>,
)

/** Rebuild per-stop responses from the compact batch payload. */
fun expandStopArrivalsBatch(payload: FutarStopArrivalsBatchResponse?, now: Long = nowMs()): StopArrivalsBatch {
    val responses = LinkedHashMap<String, FutarArrivalsResponse>()
    val errors = LinkedHashMap<String, String>()
    val arrives = payload?.data?.arrives ?: return StopArrivalsBatch(responses, errors)
    val shared = payload.data.shared
    val currentTime = payload.currentTime ?: now
    for ((stopId, entry) in arrives) {
        if (entry == null) continue
        if (!entry.error.isNullOrEmpty()) {
            errors[stopId] = entry.error
            continue
        }
        responses[stopId] = FutarArrivalsResponse(
            currentTime = currentTime,
            status = "OK",
            data = FutarArrivalsData(
                entry = FutarArrivalsEntry(
                    stopId = entry.stopId?.takeIf { it.isNotEmpty() } ?: stopId,
                    stopTimes = entry.stopTimes.orEmpty(),
                    alertIds = entry.alertIds,
                ),
                references = FutarReferences(
                    routes = shared?.routes.orEmpty(),
                    trips = shared?.trips.orEmpty(),
                    alerts = shared?.alerts.orEmpty(),
                    stops = entry.stops.orEmpty(),
                ),
            ),
        )
    }
    return StopArrivalsBatch(responses, errors)
}

/** Throws unless the proxy answered `status: "OK"`. */
internal fun assertFutarOk(json: JsonElement): JsonObject {
    val obj = json as? JsonObject
    val status = (obj?.get("status") as? JsonPrimitive)?.contentOrNull
    if (obj == null || status != "OK") {
        val msg = (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("text") as? JsonPrimitive)?.contentOrNull
            ?: "Futár API returned an error"
        throw FutarException(msg)
    }
    return obj
}

/**
 * Whitelisted Futár endpoints through the server-side proxy (`POST
 * /api/vectra/futar`, which injects the API key). Aborted after 40s so a hung
 * proxy never leaves a poll pending forever.
 */
class FutarApi(private val pb: PocketBaseClient) {

    /**
     * Decodes straight into [T] rather than through a JSON tree: these polls run every
     * 10-20 s and their payloads are the largest the app handles. Only a response that
     * is not "OK" is parsed again, to find its error message.
     */
    private suspend fun <T : FutarEnvelope> proxy(endpoint: String, params: Map<String, Any>, deserializer: DeserializationStrategy<T>): T {
        val body = buildJsonObject {
            put("endpoint", endpoint)
            put("params", buildJsonObject {
                params.forEach { (k, v) ->
                    when (v) {
                        is Number -> put(k, v)
                        is Boolean -> put(k, v)
                        else -> put(k, v.toString())
                    }
                }
            })
        }
        // withTimeoutOrNull, not withTimeout: its TimeoutCancellationException would read as the caller's
        // own cancellation, so a hung proxy would never count as a failure, back off or show an error.
        val text = withTimeoutOrNull(40_000) { pb.sendText("/api/vectra/futar", HttpMethod.Post, body) }
            ?: throw FutarException("Transit feed timed out")
        val decoded = runCatching { VectraJson.decodeFromString(deserializer, text) }.getOrNull()
        if (decoded?.status == "OK") return decoded
        assertFutarOk(runCatching { VectraJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) })
        return VectraJson.decodeFromString(deserializer, text)
    }

    private suspend inline fun <reified T : FutarEnvelope> proxy(endpoint: String, params: Map<String, Any>): T =
        proxy(endpoint, params, serializer<T>())

    suspend fun fetchStopArrivals(stopId: String, minutesAfter: Int = 60, limit: Int? = null): FutarArrivalsResponse {
        val params = mutableMapOf<String, Any>(
            "stopId" to normalizeStopIdToStation(stopId),
            "minutesBefore" to 0,
            "minutesAfter" to minutesAfter,
        )
        // An explicit cap even for shallow polls: Futár's default (60) truncates big interchanges.
        params["limit"] = limit?.takeIf { it > 0 } ?: DeepArrivalsLimit
        return proxy("arrivals-and-departures-for-stop", params)
    }

    private suspend fun fetchStopArrivalsIndividually(stationIds: List<String>, minutesAfter: Int): StopArrivalsBatch = coroutineScope {
        val settled = stationIds.map { id -> async { runSuspendCatching { fetchStopArrivals(id, minutesAfter) } } }.awaitAll()
        val responses = LinkedHashMap<String, FutarArrivalsResponse>()
        val errors = LinkedHashMap<String, String>()
        settled.forEachIndexed { i, r ->
            r.onSuccess { responses[stationIds[i]] = it }
                .onFailure { errors[stationIds[i]] = it.message?.takeIf(String::isNotBlank) ?: "Arrivals unavailable" }
        }
        // Nothing came back: a failure, so the query backs off instead of caching an empty board.
        if (responses.isEmpty() && errors.isNotEmpty()) throw FutarException(errors.values.first())
        StopArrivalsBatch(responses, errors)
    }

    /**
     * Arrivals for many stops in one request (chunked at [MaxBatchArrivalStops]). A batch the
     * server failed on (5xx, or an unreadable answer) falls back to one request per stop so the
     * feed still loads. Not when the request never got an answer, nor on a 4xx such as 429:
     * N more requests could not fare better, and a rate limit asks for fewer, not more.
     */
    suspend fun fetchStopArrivalsBatch(stopIds: List<String>, minutesAfter: Int = 90): StopArrivalsBatch {
        val stationIds = stopIds.map(::normalizeStopIdToStation).filter { it.isNotEmpty() }.distinct()
        if (stationIds.isEmpty()) return StopArrivalsBatch(emptyMap(), emptyMap())
        val chunks = stationIds.chunked(MaxBatchArrivalStops)
        return try {
            val payloads = coroutineScope {
                chunks.map { chunk ->
                    async {
                        proxy<FutarStopArrivalsBatchResponse>(
                            "stop-arrivals",
                            mapOf("stopIds" to chunk.joinToString(","), "minutesBefore" to 0, "minutesAfter" to minutesAfter),
                        )
                    }
                }.awaitAll()
            }
            val responses = LinkedHashMap<String, FutarArrivalsResponse>()
            val errors = LinkedHashMap<String, String>()
            for (p in payloads) {
                val e = expandStopArrivalsBatch(p)
                responses.putAll(e.responses)
                errors.putAll(e.errors)
            }
            StopArrivalsBatch(responses, errors)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PocketBaseException) {
            if (e.isNetwork || e.status in 400..499) throw e
            fetchStopArrivalsIndividually(stationIds, minutesAfter)
        } catch (_: Throwable) {
            fetchStopArrivalsIndividually(stationIds, minutesAfter)
        }
    }

    suspend fun fetchVehiclesForRoute(routeId: String): FutarVehiclesForRouteResponse =
        proxy("vehicles-for-route", mapOf("routeId" to routeId))

    /**
     * Without a service date Futár resolves the trip against today, so a trip from the previous
     * service day (past midnight, GTFS times over 24:00) would 404 or come back a day ahead.
     */
    suspend fun fetchTripDetails(tripId: String, serviceDate: String? = null): FutarTripDetailsResponse {
        val params = mutableMapOf<String, Any>("tripId" to tripId, "includeSchedule" to true, "includeStatus" to true)
        if (!serviceDate.isNullOrEmpty()) params["date"] = serviceDate
        return proxy("trip-details", params)
    }

    /** Route-level stop list + per-direction geometry. */
    suspend fun fetchRouteDetails(routeId: String): FutarRouteDetailsResponse =
        proxy("route-details", mapOf("routeId" to routeId))

    private suspend fun fetchRouteDetailsTagged(routeId: String): FutarRouteDetailsResponse {
        val r = fetchRouteDetails(routeId)
        val variants = r.data?.entry?.variants
        if (variants.isNullOrEmpty()) return r
        return r.copy(data = r.data.copy(entry = r.data.entry.copy(variants = variants.map { it.copy(routeId = routeId) })))
    }

    /**
     * Route details with merging for combined lines (4/6, 28/28A, 37/37A): every
     * branch's variants and stops, each variant tagged with its member route id.
     */
    suspend fun fetchCombinedRouteDetails(routeId: String): FutarRouteDetailsResponse {
        val combined = getCombinedRouteIds(routeId)
        if (combined == null || combined.size <= 1) return fetchRouteDetailsTagged(routeId)
        val responses = coroutineScope {
            combined.map { id -> async { id to runSuspendCatching { fetchRouteDetails(id) }.getOrNull() } }.awaitAll()
        }
        val valid = responses.filter { it.second?.data?.entry != null }.map { it.first to it.second!! }
        if (valid.isEmpty()) return fetchRouteDetailsTagged(routeId)
        val primary = valid.first().second
        val all = mutableListOf<FutarRouteVariant>()
        for ((id, response) in valid) {
            // Dedupe within each member only — the same headsign from another
            // member is a distinct direction variant (4 vs 6).
            val seen = mutableSetOf<String>()
            for (v in response.data?.entry?.variants.orEmpty()) {
                val key = "${v.headsign.orEmpty()}-${v.name.orEmpty()}"
                if (!seen.add(key)) continue
                all += v.copy(routeId = id)
            }
        }
        val stops = LinkedHashMap<String, FutarStop>()
        val routes = LinkedHashMap<String, FutarRoute>()
        for ((_, r) in valid) {
            stops.putAll(r.data?.references?.stops.orEmpty())
            routes.putAll(r.data?.references?.routes.orEmpty())
        }
        return FutarRouteDetailsResponse(
            currentTime = primary.currentTime?.takeIf { it > 0 } ?: nowMs(),
            status = "OK",
            data = FutarRouteDetailsData(
                entry = (primary.data?.entry ?: FutarRouteDetailsEntry()).copy(variants = all),
                references = FutarStopsReferences(stops = stops, routes = routes),
            ),
        )
    }

    /** Live vehicles; combined groups (28/28A) merge every member's list. */
    suspend fun fetchVehiclesForRouteCombined(routeId: String): FutarVehiclesForRouteResponse {
        val combined = getCombinedRouteIds(routeId)
        if (combined == null || combined.size <= 1) return fetchVehiclesForRoute(routeId)
        val responses = coroutineScope {
            combined.map { id -> async { runSuspendCatching { fetchVehiclesForRoute(id) }.getOrNull() } }.awaitAll()
        }
        val valid = responses.filterNotNull().filter { it.data != null }
        if (valid.isEmpty()) throw FutarException("Vehicle positions unavailable for every member route")
        val trips = LinkedHashMap<String, FutarTripRef>()
        valid.forEach { trips.putAll(it.data?.references?.trips.orEmpty()) }
        return FutarVehiclesForRouteResponse(
            currentTime = valid.first().currentTime?.takeIf { it > 0 } ?: nowMs(),
            status = "OK",
            data = FutarVehiclesData(list = valid.flatMap { it.data?.list.orEmpty() }, references = FutarVehiclesReferences(trips)),
        )
    }
}
