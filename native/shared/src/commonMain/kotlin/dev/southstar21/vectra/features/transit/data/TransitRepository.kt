package dev.southstar21.vectra.features.transit.data

import dev.southstar21.vectra.core.data.UserKeys

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.data.LiveQuery
import dev.southstar21.vectra.core.data.OfflineCache
import dev.southstar21.vectra.core.data.QueryState
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.bool
import dev.southstar21.vectra.core.network.int
import dev.southstar21.vectra.core.network.pbString
import dev.southstar21.vectra.core.network.string
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.auth.SessionListener
import dev.southstar21.vectra.features.transit.MaxFavoriteTrackers
import dev.southstar21.vectra.features.transit.getCombinedRouteIds
import dev.southstar21.vectra.features.transit.trackers.RouteDetailsParsed
import dev.southstar21.vectra.features.transit.trackers.parseRouteDetails
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NotSignedInException : Exception("You need to be signed in.")

/** Per-stop arrivals state, keyed by the stop id that was requested (StopArrivalsEntry). */
internal data class StopArrivalsEntry(
    val data: FutarArrivalsResponse? = null,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    /** When this stop's snapshot arrived — the card's staleness signal. */
    val dataUpdatedAt: Long = 0,
)

internal fun QueryState<FutarArrivalsResponse>.toEntry() =
    StopArrivalsEntry(data, isLoading || isFetching, isError, dataUpdatedAt)

/**
 * The `transit_trackers` collection (encrypted at rest,
 * server-digest `dedupe_key`), favourites + manual order, and the live Futár
 * queries (arrivals coalesced into batches, vehicles, trip and route details).
 *
 * Obtain through [transitRepository].
 */
internal class TransitRepository internal constructor(
    private val pb: PocketBaseClient,
    private val crypto: CryptoClient,
    private val kv: KeyValueStore,
    private val auth: AuthRepository,
    private val connectivity: Connectivity,
    private val source: FutarSource,
    http: HttpClient,
    catalogOrigin: String,
    catalogCache: CatalogDiskCache,
    private val scope: CoroutineScope,
) : SessionListener {
    private val catalog = TransitCatalog(http, catalogOrigin, scope, catalogCache)
    private val cache = OfflineCache(kv, UserKeys.TRANSIT_TRACKERS, ListSerializer(TransitTracker.serializer()))
    private var cachedTrackers: Pair<String, List<TransitTracker>>? = null

    /** The live Futár queries, for the screens that only read feeds. */
    internal val feeds = TransitFeeds(scope, source, connectivity.online)

    /** Whether the device is online, for the offline banners. */
    val online: StateFlow<Boolean> get() = connectivity.online

    private val userId: String? get() = auth.userId
    private fun requireUserId(): String = userId ?: throw NotSignedInException()

    /** Runs [block] against the stop and route catalog (static data, cached to disk). */
    internal suspend fun <T> withCatalog(block: suspend TransitCatalog.() -> T): T = catalog.block()

    // ---- trackers -----------------------------------------------------------

    /**
     * The user's trackers. The slow poll is only for cross-device sync: every write here
     * invalidates, and showing a page or resuming refetches anyway.
     */
    internal val trackersQuery: LiveQuery<Unit, List<TransitTracker>> =
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
        val clear = crypto.decryptAll(rows.flatMap(TrackerColumns::ciphertexts))
        return rows.mapIndexed { i, row -> adaptTracker(row, TrackerColumns.clear(clear, i)) }
    }

    /** Create a tracker; identifying fields are encrypted, `dedupe_key` is the server's keyed digest. */
    internal suspend fun addTracker(input: NewTrackerInput): TransitTracker {
        val uid = requireUserId()
        val primary = input.stops?.firstOrNull() ?: TrackerStop(input.stopId.orEmpty(), input.stopName.orEmpty(), input.headsign.orEmpty())
        val stopsList = if (!input.stops.isNullOrEmpty()) input.stops else listOf(primary)
        val body = coroutineScope {
            val encrypted = async { TrackerColumns.encrypt(newTrackerColumnValues(input, stopsList), crypto::encrypt) }
            val dedupe = async { crypto.digest(input.routeId, primary.stopId, primary.headsign.orEmpty()) }
            buildJsonObject {
                encrypted.await().forEach { (name, value) -> put(name, value.orEmpty()) }
                put("route_type", input.routeType)
                put("route_color", input.routeColor)
                put("route_text_color", input.routeTextColor)
                put("is_favorite", false)
                put("dedupe_key", dedupe.await())
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

    internal suspend fun updateTrackerStops(id: String, stops: List<TrackerStop>) {
        requireUserId()
        val primary = stops.firstOrNull() ?: TrackerStop("", "", "")
        val collection = pb.collection("transit_trackers")
        // The dedupe key covers the primary stop and headsign, so it moves with them.
        val routeId = crypto.decrypt(collection.getOne(id).string("route_id")).orEmpty()
        val dedupe = crypto.digest(routeId, primary.stopId, primary.headsign.orEmpty())
        val body = buildJsonObject {
            TrackerColumns.encryptSome(stopColumnValues(stops), crypto::encrypt).forEach { (name, value) -> put(name, value.orEmpty()) }
            put("dedupe_key", dedupe)
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

    /** Member route ids for a set of routes (combined groups expand). */
    fun memberRouteIds(routeIds: List<String?>): List<String> =
        routeIds.filterNotNull().filter { it.isNotEmpty() }.flatMap { id ->
            getCombinedRouteIds(id)?.takeIf { it.size > 1 } ?: listOf(id)
        }.distinct()

    /**
     * One stop's arrivals once, outside the polled [arrivals] query (the add-tracker flow and
     * reminders), through the same demo routing.
     */
    internal suspend fun stopArrivals(stopId: String, minutesAfter: Int): FutarArrivalsResponse =
        source.stopArrivals(stopId, minutesAfter)

    /** A route's stops, headsigns and polylines, parsed off the main thread. */
    internal suspend fun parsedRouteDetails(routeId: String): RouteDetailsParsed =
        withContext(Dispatchers.Default) { parseRouteDetails(source.routeDetails(routeId)) }

    /** Runs [block] on the app-lifetime scope, for work that must outlive the screen that started it. */
    fun launchInAppScope(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    /** Pull to refresh: refetches everything on screen and returns when it has landed. */
    suspend fun refresh() = coroutineScope {
        launch { trackersQuery.refetchObserved() }
        launch { feeds.refetchObserved() }
    }

    /** An app resume refreshes everything that is observed and stale. */
    fun onAppResumed() {
        trackersQuery.refetchStaleObserved()
        feeds.refetchStaleObserved()
    }

    override suspend fun onSessionCleared() {
        cachedTrackers = null
        trackersQuery.clear()
    }
}
