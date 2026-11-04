package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Minutes of arrivals every per-stop query asks for. */
const val ArrivalsMinutesAfter = 90

/** How long requests are collected before the batch goes out. */
const val BatchWindowMs = 25L

/** A deep response this young also answers a shallow request. */
const val DeepReuseMs = 15_000L

/**
 * Request coalescing for per-stop arrivals. Stops
 * requested within [BatchWindowMs] go out as one `stop-arrivals` batch call; a
 * fresh deep (trip-planning) response for a station also answers a shallow
 * request for it.
 */
class ArrivalsLoader(
    private val scope: CoroutineScope,
    private val fetchSingle: suspend (stationId: String, minutesAfter: Int, limit: Int?) -> FutarArrivalsResponse,
    private val fetchBatch: suspend (stationIds: List<String>, minutesAfter: Int) -> StopArrivalsBatch,
    private val clock: () -> Long = ::nowMs,
) {
    private val lock = Mutex()
    private var queue = LinkedHashMap<String, MutableList<CompletableDeferred<FutarArrivalsResponse>>>()
    private var timer: Job? = null

    private class DeepHit(var at: Long, val promise: Deferred<FutarArrivalsResponse>)
    private val recentDeep = HashMap<String, DeepHit>()

    private suspend fun flush() {
        val batch = lock.withLock {
            val b = queue
            queue = LinkedHashMap()
            timer = null
            b
        }
        val ids = batch.keys.toList()
        if (ids.isEmpty()) return
        if (ids.size == 1) {
            val id = ids[0]
            try {
                val r = fetchSingle(id, ArrivalsMinutesAfter, null)
                batch[id]?.forEach { it.complete(r) }
            } catch (t: Throwable) {
                batch[id]?.forEach { it.completeExceptionally(t) }
            }
            return
        }
        try {
            val (responses, errors) = fetchBatch(ids, ArrivalsMinutesAfter)
            for (id in ids) {
                val r = responses[id]
                batch[id]?.forEach { w ->
                    if (r != null) w.complete(r) else w.completeExceptionally(FutarException(errors[id] ?: "Arrivals unavailable"))
                }
            }
        } catch (t: Throwable) {
            for (id in ids) batch[id]?.forEach { it.completeExceptionally(t) }
        }
    }

    private suspend fun freshDeep(stationId: String): Deferred<FutarArrivalsResponse>? = lock.withLock {
        val hit = recentDeep[stationId] ?: return@withLock null
        if (clock() - hit.at > DeepReuseMs) {
            recentDeep.remove(stationId)
            null
        } else hit.promise
    }

    /** Shallow arrivals for one stop, coalesced with every other stop requested in the same window. */
    suspend fun load(stopId: String): FutarArrivalsResponse {
        val stationId = normalizeStopIdToStation(stopId)
        freshDeep(stationId)?.let { return it.await() }
        val waiter = CompletableDeferred<FutarArrivalsResponse>()
        lock.withLock {
            queue.getOrPut(stationId) { mutableListOf() }.add(waiter)
            if (timer == null) {
                timer = scope.launch {
                    delay(BatchWindowMs)
                    flush()
                }
            }
        }
        return waiter.await()
    }

    /**
     * Arrivals with a custom stop-times cap (stays per-stop). A deep response is
     * remembered briefly so the shallow query for the same station reuses it.
     */
    suspend fun loadWithLimit(stopId: String, limit: Int): FutarArrivalsResponse {
        val stationId = normalizeStopIdToStation(stopId)
        val promise = scope.async { fetchSingle(stationId, ArrivalsMinutesAfter, limit) }
        if (limit >= DeepArrivalsLimit) {
            val hit = DeepHit(clock(), promise)
            lock.withLock { recentDeep[stationId] = hit }
            scope.launch {
                val ok = runSuspendCatching { promise.await() }.isSuccess
                lock.withLock {
                    // Freshness counts from when the data landed, not when it was asked for.
                    if (recentDeep[stationId] === hit) {
                        if (ok) hit.at = clock() else recentDeep.remove(stationId)
                    }
                }
            }
        }
        return promise.await()
    }

    suspend fun reset() = lock.withLock {
        timer?.cancel()
        timer = null
        queue = LinkedHashMap()
        recentDeep.clear()
    }
}
