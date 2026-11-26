package dev.southstar21.vectra.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [runCatching] for suspending work. It never swallows the caller's own cancellation.
 *
 * A [CancellationException] with the caller still active came from something else (a
 * cancelled `Deferred` being awaited, a timeout that ran out inside [block]), so it is an
 * ordinary failure: rethrowing it would quietly end a coroutine nobody cancelled.
 */
suspend inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    Result.failure(e)
} catch (e: Throwable) {
    Result.failure(e)
}

/**
 * Exponential backoff: [baseMs] doubled [attempt] times (attempt 0 waits [baseMs]), never above [capMs].
 * The one curve behind the retry loops; callers pick the base and the cap.
 */
fun exponentialBackoffMs(attempt: Int, baseMs: Long, capMs: Long = Long.MAX_VALUE): Long =
    minOf(capMs, baseMs shl attempt.coerceIn(0, MaxBackoffShift))

private const val MaxBackoffShift = 30
