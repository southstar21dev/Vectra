package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.data.UserKeys
import dev.southstar21.vectra.core.time.nowMs

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
    catalogCache: CatalogDiskCache,
    private val scope: CoroutineScope,
    /** Debug builds: DEMO_ trackers (the demo accounts') get [DemoFeed]'s fake data. */
    val demoFeed: Boolean = false,
) : SessionListener {
    private fun demo(id: String?) = demoFeed && DemoFeed.handles(id)
    private val api = FutarApi(pb)
    val catalog = TransitCatalog(http, catalogOrigin, scope, catalogCache)
    private val cache = OfflineCache(kv, UserKeys.TRANSIT_TRACKERS, ListSerializer(TransitTracker.serializer()))
    private var cachedTrackers: Pair<String, List<TransitTracker>>? = null

    val userId: String? get() = pb.auth.current?.userId?.takeIf { it.isNotEmpty() }
    val userEmail: String? get() = pb.auth.current?.email
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
        // New trackers (and restored ones) go to the end of the manual order.
        val record = orderLock.withLock {
            val last = pb.collection("transit_trackers").getList(1, 1, filter = "user = ${pbString(uid)}", sort = "-sort_order").items
                .firstOrNull()?.int("sort_order")
            pb.collection("transit_trackers").create(buildJsonObject {
                body.forEach { (k, v) -> put(k, v) }
                put("sort_order", (last ?: -1) + 1)
            })
        }
        val created = TransitTracker(
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
            dedupeKey = record.string("dedupe_key").orEmpty(),
            createdAt = record.string("created_at").orEmpty(),
            sortOrder = record.int("sort_order"),
        )
        // Show it at once; the refetch below replaces this with the server's copy.
        patchTrackers { list -> if (list.any { it.id == created.id }) list else list + created }
        trackersQuery.invalidate(Unit)
        return created
    }

    /** Update the cached list in place so every screen observing it reflects a write before the refetch lands. */
    private fun patchTrackers(transform: (List<TransitTracker>) -> List<TransitTracker>) {
        val current = trackersQuery.peek(Unit).data ?: return
        trackersQuery.setData(Unit, transform(current))
    }

    suspend fun updateTrackerStops(id: String, stops: List<TrackerStop>) {
        requireUserId()
        val primary = stops.firstOrNull() ?: TrackerStop("", "", "")
        val collection = pb.collection("transit_trackers")
        // The dedupe key covers the primary stop and headsign, so it moves with them.
        val routeId = crypto.decrypt(collection.getOne(id).string("route_id")).orEmpty()
        val dedupe = crypto.digest(routeId, primary.stopId, primary.headsign.orEmpty())
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
                put("dedupe_key", dedupe)
            }
        }
        collection.update(id, body)
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
        if (next) {
            val favorites = col.getFullList(filter = "user = ${pbString(uid)} && is_favorite = true")
            if (favorites.size >= MaxFavoriteTrackers) error("Maximum of $MaxFavoriteTrackers favourite trackers allowed.")
        }
        // The favourites order is the All order filtered, so sort_order is left alone.
        val updated = col.update(trackerId, buildJsonObject { put("is_favorite", next) })
        trackersQuery.invalidate(Unit)
        if ((updated.bool("is_favorite") ?: false) != next) error("Could not update favourites")
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
            patchTrackers { list ->
                val rank = orderedIds.withIndex().associate { (i, id) -> id to i }
                list.map { t -> rank[t.id]?.let { t.copy(sortOrder = it) } ?: t }
                    .sortedBy { rank[it.id] ?: Int.MAX_VALUE }
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

    private val arrivalsLoader = ArrivalsLoader(
        scope,
        fetchSingle = { id, minutes, limit -> api.fetchStopArrivals(id, minutes, limit) },
        fetchBatch = { ids, minutes -> api.fetchStopArrivalsBatch(ids, minutes) },
    )

    /** One stop's arrivals: 20s poll, 10s stale, coalesced into batches. */
    val arrivals: LiveQuery<String, FutarArrivalsResponse> =
        LiveQuery(scope, refetchIntervalMs = 20_000, staleTimeMs = 10_000, online = connectivity.online, maxIdleEntries = MaxIdleStops, shouldRetry = ::isRetryableFeedError) {
            if (demo(it)) DemoFeed.arrivals(it, nowMs()) else arrivalsLoader.load(it)
        }

    /** Deep arrivals (trip planning): key = stopId to stop-times limit. */
    val deepArrivals: LiveQuery<Pair<String, Int>, FutarArrivalsResponse> =
        LiveQuery(scope, refetchIntervalMs = 20_000, staleTimeMs = 10_000, online = connectivity.online, maxIdleEntries = MaxIdleStops, shouldRetry = ::isRetryableFeedError) { (id, limit) -> arrivalsLoader.loadWithLimit(id, limit) }

    /**
     * Batched arrivals keyed by the sorted, comma-joined
     * stop ids. The batch also seeds each stop's own entry.
     */
    val batchArrivals: LiveQuery<String, StopArrivalsBatch> =
        LiveQuery(scope, refetchIntervalMs = 20_000, staleTimeMs = 10_000, online = connectivity.online, maxIdleEntries = MaxIdleLargeResponses, shouldRetry = ::isRetryableFeedError) { key ->
            val ids = key.split(',').filter { it.isNotEmpty() && !demo(it) }
            api.fetchStopArrivalsBatch(ids, ArrivalsMinutesAfter).also { batch ->
                batch.responses.forEach { (id, r) -> arrivals.setData(id, r) }
            }
        }

    /** Live vehicles on a route (combined groups merged): 10s poll. */
    val vehicles: LiveQuery<String, FutarVehiclesForRouteResponse> =
        LiveQuery(scope, refetchIntervalMs = 10_000, staleTimeMs = 5_000, online = connectivity.online, maxIdleEntries = MaxIdleLargeResponses) {
            if (demo(it)) DemoFeed.vehicles(it, nowMs()) else api.fetchVehiclesForRouteCombined(it)
        }

    /** Live vehicles for one member route. */
    val memberVehicles: LiveQuery<String, FutarVehiclesForRouteResponse> =
        LiveQuery(scope, refetchIntervalMs = 10_000, staleTimeMs = 5_000, online = connectivity.online, maxIdleEntries = MaxIdleLargeResponses) {
            if (demo(it)) DemoFeed.vehicles(it, nowMs()) else api.fetchVehiclesForRoute(it)
        }

    val tripDetails: LiveQuery<TripDetailsKey, FutarTripDetailsResponse> =
        LiveQuery(scope, refetchIntervalMs = 60_000, staleTimeMs = 60_000, online = connectivity.online, maxIdleEntries = MaxIdleLargeResponses) {
            if (demo(it.tripId)) DemoFeed.tripDetails(it.tripId, nowMs()) else api.fetchTripDetails(it.tripId, it.serviceDate)
        }

    /** Route-level stops + geometry, combined lines merged. */
    val routeDetails: LiveQuery<String, FutarRouteDetailsResponse> =
        LiveQuery(scope, refetchIntervalMs = 60_000, staleTimeMs = 60_000, online = connectivity.online, maxIdleEntries = MaxIdleLargeResponses) {
            if (demo(it)) DemoFeed.routeDetails(it, nowMs()) else api.fetchCombinedRouteDetails(it)
        }

    /** Member route ids for a set of routes (combined groups expand). */
    fun memberRouteIds(routeIds: List<String?>): List<String> =
        routeIds.filterNotNull().filter { it.isNotEmpty() }.flatMap { id ->
            getCombinedRouteIds(id)?.takeIf { it.size > 1 } ?: listOf(id)
        }.distinct()

    /**
     * One stop's arrivals once, outside the polled [arrivals] query (the add-tracker flow and
     * reminders), through the same demo routing.
     */
    suspend fun stopArrivals(stopId: String, minutesAfter: Int): FutarArrivalsResponse =
        if (demo(stopId)) DemoFeed.arrivals(stopId, nowMs()) else api.fetchStopArrivals(stopId, minutesAfter)

    /** A route's stops, headsigns and polylines, parsed off the main thread. */
    internal suspend fun parsedRouteDetails(routeId: String): RouteDetailsParsed = api.fetchParsedRouteDetails(routeId)

    /** Runs [block] on the app-lifetime scope, for work that must outlive the screen that started it. */
    fun launchInAppScope(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    /** The queries that pull to refresh and an app resume cover. The trip and route detail queries are left out on purpose. */
    private val refreshedQueries: List<LiveQuery<*, *>> =
        listOf(trackersQuery, arrivals, deepArrivals, batchArrivals, vehicles, memberVehicles)

    /** Pull to refresh: refetches everything on screen and returns when it has landed. */
    suspend fun refresh() = coroutineScope {
        refreshedQueries.forEach { q -> launch { q.refetchObserved() } }
    }

    /** An app resume refreshes everything that is observed and stale. */
    fun onAppResumed() {
        refreshedQueries.forEach { it.refetchStaleObserved() }
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

