package dev.southstar21.vectra.core.data

import dev.southstar21.vectra.core.time.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** One cached query's state. */
internal data class QueryState<T>(
    val data: T? = null,
    /** First load with no data yet. */
    val isLoading: Boolean = false,
    /** Any request in flight (initial or background). */
    val isFetching: Boolean = false,
    val isError: Boolean = false,
    val error: Throwable? = null,
    /** When the current data landed (epoch ms), 0 when never. */
    val dataUpdatedAt: Long = 0,
    /** At least one fetch settled (success or failure). */
    val isFetched: Boolean = false,
)

/** A request went out: [QueryState.isLoading] only while there is nothing to show yet. */
internal fun <T> QueryState<T>.fetching(): QueryState<T> = copy(isFetching = true, isLoading = data == null)

/** A request ended without an answer (cancelled): no longer loading, nothing else changes. */
internal fun <T> QueryState<T>.notFetching(): QueryState<T> = copy(isFetching = false, isLoading = false)

/** A request succeeded: the answer replaces everything, errors included. */
internal fun <T> succeededState(value: T, at: Long): QueryState<T> = QueryState(data = value, dataUpdatedAt = at, isFetched = true)

/** A request failed for good: the old data stays, the error is recorded. */
internal fun <T> QueryState<T>.failed(error: Throwable): QueryState<T> =
    copy(isFetching = false, isLoading = false, isError = true, error = error, isFetched = true)

/** Data put in directly (seeding), not fetched: clears an error, does not mark the query fetched. */
internal fun <T> QueryState<T>.seeded(value: T, at: Long): QueryState<T> =
    copy(data = value, dataUpdatedAt = at, isError = false, error = null)

/**
 * When data counts as stale: [staleTimeMs] after it landed, or at once when it never did or was
 * invalidated. The one rule behind every refetch-on-resume and refresh-when-old check.
 */
internal class StaleGate(private val staleTimeMs: Long, private val clock: () -> Long = ::nowMs) {
    fun isStale(updatedAt: Long, invalidated: Boolean = false): Boolean =
        invalidated || updatedAt == 0L || clock() - updatedAt >= staleTimeMs
}

/**
 * At most one request running per [key]: whoever asks while one is in flight joins it. A request
 * for another key replaces it (and cancels it when [cancelReplaced]); the replaced one still answers
 * whoever already joined it. Lock-free, so it works from any thread.
 */
internal class SingleFlight<K, T> {
    private class Flight<K, T>(val key: K, val job: Deferred<T>)

    private val current = MutableStateFlow<Flight<K, T>?>(null)

    /** The request running now, if any. */
    val running: Deferred<T>? get() = current.value?.job?.takeIf { it.isActive }

    /** The request running now for [key], if any. */
    fun running(key: K): Deferred<T>? = current.value?.takeIf { it.key == key }?.job?.takeIf { it.isActive }

    /** Whether [job] is the request this last started and nothing has replaced or cancelled it since. */
    fun isCurrent(job: kotlinx.coroutines.Job?): Boolean = job != null && current.value?.job === job

    /** Joins the running request for [key], or starts [block] in [scope] (under [context]) as the one to join. */
    fun join(
        key: K,
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext,
        cancelReplaced: Boolean = false,
        block: suspend CoroutineScope.() -> T,
    ): Deferred<T> {
        // Started only once it is registered, so a request can look itself up as the current one.
        val fresh = scope.async(context, start = CoroutineStart.LAZY, block = block)
        var replaced: Flight<K, T>? = null
        val winner = checkNotNull(
            current.updateAndGet { old ->
                replaced = null
                if (old != null && old.key == key && old.job.isActive) {
                    old
                } else {
                    replaced = old
                    Flight(key, fresh)
                }
            },
        )
        if (winner.job === fresh) fresh.start() else fresh.cancel()
        if (cancelReplaced) replaced?.job?.cancel()
        return winner.job
    }

    /** Cancels the running request and forgets it. */
    fun cancel() {
        val flight = current.value ?: return
        flight.job.cancel()
        current.compareAndSet(flight, null)
    }
}
