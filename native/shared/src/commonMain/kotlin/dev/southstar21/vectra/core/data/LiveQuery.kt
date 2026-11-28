package dev.southstar21.vectra.core.data

import dev.southstar21.vectra.core.util.exponentialBackoffMs
import dev.southstar21.vectra.core.util.BackoffPolicy
import dev.southstar21.vectra.core.util.rethrowIfOwnCancellation
import dev.southstar21.vectra.core.util.retrying
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.core.time.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/** The longest a failing query waits between polls. */
internal const val MaxPollBackoffMs = 5 * 60_000L

/** The wait between polls: [intervalMs] when healthy, doubling per consecutive failed poll up to [MaxPollBackoffMs]. */
internal fun pollIntervalMs(intervalMs: Long, consecutiveFailures: Int): Long {
    if (consecutiveFailures <= 0) return intervalMs
    return max(intervalMs, exponentialBackoffMs(min(consecutiveFailures, 16), intervalMs, MaxPollBackoffMs))
}

/**
 * A keyed, polled query cache with what the transit feature relies on: entries shared across observers, a stale time,
 * a poll interval while observed, request de-duplication, retries with
 * exponential back-off, and direct seeding of an entry. A failing query polls less and
 * less often, and with [online] given polling pauses offline and resumes on reconnect.
 * With [maxIdleEntries] set, the least recently used unobserved entries are dropped,
 * so keys that are never asked for again do not pin their last response forever.
 *
 * All bookkeeping runs on one confined dispatcher so entries need no locks. [peek] is the
 * one read from outside it, so the entry map is an immutable snapshot, replaced on change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LiveQuery<K, T>(
    private val scope: CoroutineScope,
    private val refetchIntervalMs: Long? = null,
    private val staleTimeMs: Long = 0,
    private val retry: Int = 2,
    private val clock: () -> Long = ::nowMs,
    private val online: StateFlow<Boolean>? = null,
    private val maxIdleEntries: Int? = null,
    /** False for a failure another try cannot fix (a 4xx, a rate limit): it settles at once and backs off. */
    private val shouldRetry: (Throwable) -> Boolean = { true },
    private val fetcher: suspend (K) -> T,
) {
    private val confined = Dispatchers.Default.limitedParallelism(1)

    private inner class Entry {
        val state = MutableStateFlow(QueryState<T>())
        var observers = 0
        var poll: Job? = null
        /** The request in flight, shared by everyone who asks meanwhile. */
        val flight = SingleFlight<Unit, T>()
        var invalidated = false
        /** Bumped by every invalidate, so a fetch that began before one cannot clear its flag. */
        var invalidations = 0
        var failures = 0
        var lastSettledAt = 0L
        var lastUsed = 0L
    }

    @Volatile private var entries: Map<K, Entry> = emptyMap()
    private var useCounter = 0L

    private fun entry(key: K): Entry {
        val e = entries[key] ?: Entry().also {
            entries = entries + (key to it)
            it.lastUsed = ++useCounter
            evictIdle()
        }
        e.lastUsed = ++useCounter
        return e
    }

    private fun evictIdle() {
        val cap = maxIdleEntries ?: return
        val idle = entries.entries.filter { it.value.observers == 0 && it.value.flight.running == null }
        if (idle.size <= cap) return
        entries = entries - idle.sortedBy { it.value.lastUsed }.take(idle.size - cap).map { it.key }.toSet()
    }

    /** The current snapshot without subscribing. */
    fun peek(key: K): QueryState<T> = entries[key]?.state?.value ?: QueryState()

    /** Observe [key]; the query polls while at least one observer is collecting. */
    fun observe(key: K): Flow<QueryState<T>> = flow {
        // NonCancellable: a collector cancelled during this hop would otherwise get the exception on
        // return, after acquire() counted it and started polling but before the finally can release it.
        val e = withContext(NonCancellable + confined) { acquire(key) }
        try {
            emitAll(e.state)
        } finally {
            withContext(NonCancellable + confined) { release(key) }
        }
    }

    /** Several keys at once, as a map keyed by the requested key. */
    fun observeMany(keys: List<K>): Flow<Map<K, QueryState<T>>> {
        val distinct = keys.distinct()
        if (distinct.isEmpty()) return flowOf(emptyMap())
        return combine(distinct.map { observe(it) }) { states -> distinct.zip(states.toList()).toMap() }
    }

    private val staleGate = StaleGate(staleTimeMs, clock)

    private fun isStale(e: Entry): Boolean = staleGate.isStale(e.state.value.dataUpdatedAt, e.invalidated)

    private fun acquire(key: K): Entry {
        val e = entry(key)
        e.observers++
        if (e.observers == 1) {
            e.poll = scope.launch(confined) {
                if (isStale(e)) runSuspendCatching { fetchEntry(key, e) }
                val interval = refetchIntervalMs ?: return@launch
                while (isActive) {
                    if (online?.value == false) {
                        online.first { it }
                        refetchAfterReconnect(key, e)
                        continue
                    }
                    val wait = pollIntervalMs(interval, e.failures)
                    val since = clock() - lastActivity(e)
                    if (waitForReconnect((wait - since).coerceIn(1_000, wait))) {
                        refetchAfterReconnect(key, e)
                    } else if (clock() - lastActivity(e) >= wait - 50 || e.invalidated) {
                        runSuspendCatching { fetchEntry(key, e) }
                    }
                }
            }
        }
        return e
    }

    /** When the entry last settled; a failed attempt counts, so it cannot re-fire straight away. */
    private fun lastActivity(e: Entry): Long = max(e.lastSettledAt, e.state.value.dataUpdatedAt)

    /** Sleeps [ms]; true when the connection dropped and came back meanwhile, which ends the wait early. */
    private suspend fun waitForReconnect(ms: Long): Boolean {
        val connectivity = online
        if (connectivity == null) {
            delay(ms)
            return false
        }
        return withTimeoutOrNull(ms) {
            connectivity.first { !it }
            connectivity.first { it }
            true
        } ?: false
    }

    private suspend fun refetchAfterReconnect(key: K, e: Entry) {
        e.failures = 0
        if (isStale(e)) runSuspendCatching { fetchEntry(key, e) }
    }

    private fun release(key: K) {
        val e = entries[key] ?: return
        e.observers = (e.observers - 1).coerceAtLeast(0)
        if (e.observers == 0) {
            e.poll?.cancel()
            e.poll = null
            e.lastUsed = ++useCounter
            evictIdle()
        }
    }

    /**
     * Only ever called on [confined]. The job resets the fetching flag itself rather than its awaiter:
     * one awaiter leaving must not hide a fetch that is still running from [clear].
     */
    private suspend fun fetchEntry(key: K, e: Entry): T =
        e.flight.join(Unit, scope, confined) {
            try {
                fetchWithRetry(key, e)
            } finally {
                // A cancelled fetch must not leave the flag set, nor touch a newer one's.
                if (e.flight.isCurrent(coroutineContext[Job])) e.state.update { it.notFetching() }
            }
        }.await()

    private suspend fun fetchWithRetry(key: K, e: Entry): T {
        e.state.update { it.fetching() }
        val invalidationsAtStart = e.invalidations
        try {
            val value = retrying(BackoffPolicy.exponential(retry + 1, 1000L, 30_000L), retryIf = shouldRetry) { fetcher(key) }
            // An invalidate that arrived mid-flight is newer than this response.
            e.invalidated = e.invalidations != invalidationsAtStart
            e.failures = 0
            e.lastSettledAt = clock()
            e.state.value = succeededState(value, e.lastSettledAt)
            return value
        } catch (t: Throwable) {
            rethrowIfOwnCancellation(t)
            e.failures++
            e.lastSettledAt = clock()
            e.state.update { it.failed(t) }
            throw t
        }
    }

    /** Fetch now (or join the in-flight request) and return the data. */
    suspend fun fetch(key: K): T = withContext(confined) { fetchEntry(key, entry(key)) }

    /** ensureQueryData: fresh cached data, else fetch. */
    suspend fun ensure(key: K): T = withContext(confined) {
        val e = entry(key)
        val d = e.state.value.data
        if (d != null && !isStale(e)) d else fetchEntry(key, e)
    }

    /** Seed or replace an entry's data. */
    fun setData(key: K, value: T, updatedAt: Long = clock()) {
        scope.launch(confined) {
            val e = entry(key)
            e.failures = 0
            e.state.update { it.seeded(value, updatedAt) }
        }
    }

    /** Seed placeholder data without marking the query fetched. */
    fun seedPlaceholder(key: K, value: T) {
        scope.launch(confined) {
            val e = entry(key)
            if (e.state.value.data == null) e.state.update { it.copy(data = value) }
        }
    }

    /** Mark stale and refetch when observed. */
    fun invalidate(key: K) {
        scope.launch(confined) {
            val e = entries[key] ?: return@launch
            e.invalidated = true
            e.invalidations++
            if (e.observers > 0) {
                // A fetch already running may have read the server before the change that
                // caused this invalidate: let it finish, then fetch again.
                e.flight.running?.let { runSuspendCatching { it.await() } }
                if (e.invalidated && e.observers > 0) runSuspendCatching { fetchEntry(key, e) }
            }
        }
    }

    /** Refetches every observed entry and returns once they have all settled (pull to refresh). */
    suspend fun refetchObserved() = withContext(confined) {
        coroutineScope {
            for ((k, e) in entries.toList()) {
                if (e.observers > 0) launch { runSuspendCatching { fetchEntry(k, e) } }
            }
        }
    }

    /** On resume: refetch every observed entry whose data is stale. */
    fun refetchStaleObserved() {
        scope.launch(confined) {
            for ((k, e) in entries.toList()) {
                if (e.observers > 0 && isStale(e)) launch { runSuspendCatching { fetchEntry(k, e) } }
            }
        }
    }

    /** Forget every entry's data (sign-out); observers stay attached. */
    fun clear() {
        scope.launch(confined) {
            entries.values.forEach {
                it.flight.cancel()
                it.state.value = QueryState()
            }
        }
    }
}
