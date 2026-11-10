package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.data.LiveQuery
import dev.southstar21.vectra.core.data.OfflineCache
import dev.southstar21.vectra.core.data.QueryState
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.bool
import dev.southstar21.vectra.core.network.int
import dev.southstar21.vectra.core.network.isCiphertext
import dev.southstar21.vectra.core.network.pbString
import dev.southstar21.vectra.core.network.string
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.platform.LocationProvider
import dev.southstar21.vectra.core.auth.SessionListener
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class NotSignedInException : Exception("You need to be signed in.")

/** Per-stop arrivals state, keyed by the stop id that was requested (StopArrivalsEntry). */
data class StopArrivalsEntry(
    val data: FutarArrivalsResponse? = null,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    /** When this stop's snapshot arrived — the card's staleness signal. */
    val dataUpdatedAt: Long = 0,
)

fun QueryState<FutarArrivalsResponse>.toEntry() =
    StopArrivalsEntry(data, isLoading || isFetching, isError, dataUpdatedAt)

/**
 * The `transit_trackers` collection (encrypted at rest,
 * server-digest `dedupe_key`), favourites + manual order, and the live Futár
 * queries (arrivals coalesced into batches, vehicles, trip and route details).
 *
 * Obtain through [transitRepository].
 */
class TransitRepository(
    private val pb: PocketBaseClient,
    private val crypto: CryptoClient,
    private val kv: KeyValueStore,
    val connectivity: Connectivity,
    val location: LocationProvider,
    http: HttpClient,
    catalogOrigin: String,
    val scope: CoroutineScope,
) : SessionListener {
    val api = FutarApi(pb)
    val catalog = TransitCatalog(http, catalogOrigin, scope)
    private val cache = OfflineCache(kv, "vectra_transit_trackers_cache", ListSerializer(TransitTracker.serializer()))
    private var cachedTrackers: Pair<String, List<TransitTracker>>? = null

    val userId: String? get() = pb.auth.current?.userId?.takeIf { it.isNotEmpty() }
    private fun requireUserId(): String = userId ?: throw NotSignedInException()

    // ---- trackers -----------------------------------------------------------

    /**
     * The user's trackers. The slow poll is only for cross-device sync: every write here
     * invalidates, and showing a page or resuming refetches anyway.
     */
    val trackersQuery: LiveQuery<Unit, List<TransitTracker>> =
        LiveQuery(scope, refetchIntervalMs = 120_000, staleTimeMs = 0, online = connectivity.online) { fetchTrackers() }

    init {
        // Returning users never see an empty flash.
        scope.launch {
            val uid = userId ?: return@launch
            cache.read(uid)?.let { trackersQuery.seedPlaceholder(Unit, it) }
        }
    }

    private suspend fun fetchTrackers(): List<TransitTracker> {
        val uid = userId
        if (!connectivity.online.value || uid == null) return uid?.let { cache.read(it) }.orEmpty()
        val rows = pb.collection("transit_trackers").getFullList(filter = "user = ${pbString(uid)}", sort = "sort_order,created_at")
        val trackers = try {
            decryptTrackers(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Show the last good copy rather than ciphertext.
            return cache.read(uid) ?: throw t
        }
        // Most polls find nothing new: don't rewrite the snapshot for them.
        if (cachedTrackers != (uid to trackers)) {
            cache.write(uid, trackers)
            cachedTrackers = uid to trackers
        }
        return trackers
    }

    /** Every row's fields in one decrypt round trip. */
    private suspend fun decryptTrackers(rows: List<JsonObject>): List<TransitTracker> {
        val perRow = TrackerTextFields.size + 1
        val clear = crypto.decryptAll(rows.flatMap { row ->
            TrackerTextFields.map { row.string(it) } + (row["stops"] as? JsonPrimitive)?.contentOrNull
        })
        return rows.mapIndexed { i, row -> adaptTracker(row, clear.subList(i * perRow, (i + 1) * perRow)) }
    }

    /** [decrypted]: the [TrackerTextFields] in order, then the `stops` field. */
    private fun adaptTracker(row: JsonObject, decrypted: List<String?>): TransitTracker {
        val values = TrackerTextFields.indices.associate { TrackerTextFields[it] to decrypted[it].orEmpty() }
        val stopsRaw = (row["stops"] as? JsonArray)?.toString() ?: decrypted.last()
        if (values.values.any(::isCiphertext) || isCiphertext(stopsRaw)) error("Could not decrypt transit trackers.")
        val stops = stopsRaw?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { VectraJson.decodeFromString(ListSerializer(TrackerStop.serializer()), raw) }.getOrNull()
        }
        return TransitTracker(
            id = row.string("id").orEmpty(),
            userId = row.string("user").orEmpty(),
            stopId = values.getValue("stop_id"),
            stopName = values.getValue("stop_name"),
            routeId = values.getValue("route_id"),
            routeShortName = values.getValue("route_short_name"),
            routeLongName = values.getValue("route_long_name"),
            routeType = row.string("route_type").orEmpty(),
            routeColor = row.string("route_color").orEmpty(),
            routeTextColor = row.string("route_text_color").orEmpty(),
            headsign = values.getValue("headsign"),
            isFavorite = row.bool("is_favorite") ?: false,
            dedupeKey = row.string("dedupe_key").orEmpty(),
            createdAt = row.string("created_at").orEmpty(),
            stops = stops,
            sortOrder = row.int("sort_order"),
        )
    }

    private fun stopsJson(stops: List<TrackerStop>): String =
        VectraJson.encodeToString(ListSerializer(TrackerStop.serializer()), stops)

    /** Create a tracker; identifying fields are encrypted, `dedupe_key` is the server's keyed digest. */
    suspend fun addTracker(input: NewTrackerInput): TransitTracker {
        val uid = requireUserId()
        val primary = input.stops?.firstOrNull() ?: TrackerStop(input.stopId.orEmpty(), input.stopName.orEmpty(), input.headsign.orEmpty())
        val stopsList = if (!input.stops.isNullOrEmpty()) input.stops else listOf(primary)
        val body = coroutineScope {
            val encStops = async { crypto.encrypt(stopsJson(stopsList)) }
            val stopId = async { crypto.encrypt(primary.stopId) }
            val stopName = async { crypto.encrypt(primary.stopName) }
            val routeId = async { crypto.encrypt(input.routeId) }
            val shortName = async { crypto.encrypt(input.routeShortName) }
            val longName = async { crypto.encrypt(input.routeLongName) }
            val headsign = async { primary.headsign?.takeIf { it.isNotEmpty() }?.let { crypto.encrypt(it) } }
            val dedupe = async { crypto.digest(input.routeId, primary.stopId, primary.headsign.orEmpty()) }
            buildJsonObject {
                put("stop_id", stopId.await().orEmpty())
                put("stop_name", stopName.await().orEmpty())
                put("route_id", routeId.await().orEmpty())
                put("route_short_name", shortName.await().orEmpty())
                put("route_long_name", longName.await().orEmpty())
                headsign.await()?.let { put("headsign", it) }
                put("route_type", input.routeType)
                put("route_color", input.routeColor)
                put("route_text_color", input.routeTextColor)
                put("is_favorite", false)
                put("dedupe_key", dedupe.await())
                put("stops", encStops.await().orEmpty())
                put("user", uid)
            }
        }
        val record = pb.collection("transit_trackers").create(body)
        trackersQuery.invalidate(Unit)
        return TransitTracker(
            id = record.string("id").orEmpty(),
            userId = uid,
            stopId = primary.stopId,
            stopName = primary.stopName,
            routeId = input.routeId,
            routeShortName = input.routeShortName,
            routeLongName = input.routeLongName,
            routeType = input.routeType,
            routeColor = input.routeColor,
            routeTextColor = input.routeTextColor,
            headsign = primary.headsign.orEmpty(),
            stops = stopsList,
        )
    }

    suspend fun updateTrackerStops(id: String, stops: List<TrackerStop>) {
        requireUserId()
        val primary = stops.firstOrNull() ?: TrackerStop("", "", "")
        val body = coroutineScope {
            val encStops = async { crypto.encrypt(stopsJson(stops)) }
            val stopId = async { crypto.encrypt(primary.stopId) }
            val stopName = async { crypto.encrypt(primary.stopName) }
            val headsign = async { primary.headsign?.takeIf { it.isNotEmpty() }?.let { crypto.encrypt(it) } ?: "" }
            buildJsonObject {
                put("stops", encStops.await().orEmpty())
                put("stop_id", stopId.await().orEmpty())
                put("stop_name", stopName.await().orEmpty())
                put("headsign", headsign.await().orEmpty())
            }
        }
        pb.collection("transit_trackers").update(id, body)
        trackersQuery.invalidate(Unit)
    }

    /** Read-then-write order mutations share one serial queue. */
    private val orderLock = Mutex()

    /** Toggle the dashboard favourite flag; returns the new value. */
    suspend fun toggleFavorite(trackerId: String): Boolean = orderLock.withLock {
        val uid = requireUserId()
        val col = pb.collection("transit_trackers")
        val row = col.getOne(trackerId)
        val next = !(row.bool("is_favorite") ?: false)
        var nextSort = row.int("sort_order") ?: 0
        if (next) {
            val favorites = col.getFullList(filter = "user = ${pbString(uid)} && is_favorite = true")
            if (favorites.size >= MaxFavoriteTrackers) error("Maximum of $MaxFavoriteTrackers favorite trackers allowed.")
            nextSort = (favorites.maxOfOrNull { it.int("sort_order") ?: 0 } ?: -1) + 1
        }
        val updated = col.update(trackerId, buildJsonObject {
            put("is_favorite", next)
            put("sort_order", nextSort)
        })
        trackersQuery.invalidate(Unit)
        if ((updated.bool("is_favorite") ?: false) != next) error("Could not update favorite")
        next
    }

    /** Reorder trackers by writing their sort_order. */
    suspend fun reorderTrackers(orderedIds: List<String>) = orderLock.withLock {
        requireUserId()
        try {
            coroutineScope {
                orderedIds.mapIndexed { i, id ->
                    async { pb.collection("transit_trackers").update(id, buildJsonObject { put("sort_order", i) }) }
                }.awaitAll()
            }
        } finally {
            // Refetch on failure too: some rows may already have their new order.
            trackersQuery.invalidate(Unit)
        }
    }

    suspend fun deleteTracker(trackerId: String) {
        requireUserId()
        pb.collection("transit_trackers").delete(trackerId)
        trackersQuery.invalidate(Unit)
    }

    // ---- live Futár queries ------------------------------------------------

    val arrivalsLoader = ArrivalsLoader(
        scope,
        fetchSingle = { id, minutes, limit -> api.fetchStopArrivals(id, minutes, limit) },
        fetchBatch = { ids, minutes -> api.fetchStopArrivalsBatch(ids, minutes) },
    )

    /** One stop's arrivals: 20s poll, 10s stale, coalesced into batches. */
    val arrivals: LiveQuery<String, FutarArrivalsResponse> =
        LiveQuery(scope, refetchIntervalMs = 20_000, staleTimeMs = 10_000, online = connectivity.online, maxIdleEntries = MaxIdleStops) { arrivalsLoader.load(it) }

    /** Deep arrivals (trip planning): key = stopId to stop-times limit. */
    val deepArrivals: LiveQuery<Pair<String, Int>, FutarArrivalsResponse> =
        LiveQuery(scope, refetchIntervalMs = 20_000, staleTimeMs = 10_000, online = connectivity.online, maxIdleEntries = MaxIdleStops) { (id, limit) -> arrivalsLoader.loadWithLimit(id, limit) }

    /**
     * Batched arrivals keyed by the sorted, comma-joined
     * stop ids. The batch also seeds each stop's own entry.
     */
    val batchArrivals: LiveQuery<String, StopArrivalsBatch> =
        LiveQuery(scope, refetchIntervalMs = 20_000, staleTimeMs = 10_000, online = connectivity.online, maxIdleEntries = MaxIdleLargeResponses) { key ->
            val ids = key.split(',').filter { it.isNotEmpty() }
            api.fetchStopArrivalsBatch(ids, ArrivalsMinutesAfter).also { batch ->
                batch.responses.forEach { (id, r) -> arrivals.setData(id, r) }
            }
        }

    /** Live vehicles on a route (combined groups merged): 10s poll. */
    val vehicles: LiveQuery<String, FutarVehiclesForRouteResponse> =
        LiveQuery(scope, refetchIntervalMs = 10_000, staleTimeMs = 5_000, online = connectivity.online, maxIdleEntries = MaxIdleLargeResponses) { api.fetchVehiclesForRouteCombined(it) }

    /** Live vehicles for one member route. */
    val memberVehicles: LiveQuery<String, FutarVehiclesForRouteResponse> =
        LiveQuery(scope, refetchIntervalMs = 10_000, staleTimeMs = 5_000, online = connectivity.online, maxIdleEntries = MaxIdleLargeResponses) { api.fetchVehiclesForRoute(it) }

    val tripDetails: LiveQuery<TripDetailsKey, FutarTripDetailsResponse> =
        LiveQuery(scope, refetchIntervalMs = 60_000, staleTimeMs = 60_000, online = connectivity.online, maxIdleEntries = MaxIdleLargeResponses) {
            api.fetchTripDetails(it.tripId, it.serviceDate)
        }

    /** Route-level stops + geometry, combined lines merged. */
    val routeDetails: LiveQuery<String, FutarRouteDetailsResponse> =
        LiveQuery(scope, refetchIntervalMs = 60_000, staleTimeMs = 60_000, online = connectivity.online, maxIdleEntries = MaxIdleLargeResponses) { api.fetchCombinedRouteDetails(it) }

    /** Member route ids for a set of routes (combined groups expand). */
    fun memberRouteIds(routeIds: List<String?>): List<String> =
        routeIds.filterNotNull().filter { it.isNotEmpty() }.flatMap { id ->
            getCombinedRouteIds(id)?.takeIf { it.size > 1 } ?: listOf(id)
        }.distinct()

    /** Pull to refresh: refetches everything on screen and returns when it has landed. */
    suspend fun refresh() = coroutineScope {
        listOf(trackersQuery, arrivals, deepArrivals, batchArrivals, vehicles, memberVehicles)
            .forEach { q -> launch { q.refetchObserved() } }
    }

    /** An app resume refreshes everything that is observed and stale. */
    fun onAppResumed() {
        trackersQuery.refetchStaleObserved()
        arrivals.refetchStaleObserved()
        deepArrivals.refetchStaleObserved()
        batchArrivals.refetchStaleObserved()
        vehicles.refetchStaleObserved()
        memberVehicles.refetchStaleObserved()
    }

    override suspend fun onSessionCleared() {
        cachedTrackers = null
        trackersQuery.clear()
    }

    companion object {
        /** Fields that identify the user's stops & routes — encrypted at rest. */
        val EncryptedTrackerFields = listOf("stop_id", "stop_name", "route_id", "route_short_name", "route_long_name", "headsign", "stops")
        private val TrackerTextFields = EncryptedTrackerFields - "stops"

        /** Enough to keep every page's data for an instant return, without growing per neighbourhood. */
        private const val MaxIdleStops = 64
        private const val MaxIdleLargeResponses = 16
    }
}

