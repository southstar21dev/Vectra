package dev.southstar21.vectra.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * [runCatching] for suspending work. It never swallows the caller's own cancellation.
 *
 * A [CancellationException] with the caller still active came from something else (a
 * cancelled `Deferred` being awaited, a timeout that ran out inside [block]), so it is an
 * ordinary failure: rethrowing it would quietly end a coroutine nobody cancelled.
 */
internal suspend inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
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
internal fun exponentialBackoffMs(attempt: Int, baseMs: Long, capMs: Long = Long.MAX_VALUE): Long =
    minOf(capMs, baseMs shl attempt.coerceIn(0, MaxBackoffShift))

/**
 * Rethrows [e] only when the coroutine running this is itself cancelled. For a hand-written catch
 * that must not turn a cancellation nobody asked for (a cancelled shared `Deferred`, a timeout)
 * into the end of the caller; with `runSuspendCatching` it is the same rule.
 */
internal suspend fun rethrowIfOwnCancellation(e: Throwable) {
    if (e is CancellationException) currentCoroutineContext().ensureActive()
}

/**
 * The one retry policy: [attempts] tries in all, [wait] ms between them (given how many have failed
 * so far, from 1), each try ended after [attemptTimeoutMs] when set. A timeout is a failure like any other.
 */
internal class BackoffPolicy private constructor(
    val attempts: Int,
    val attemptTimeoutMs: Long?,
    private val wait: (failures: Int) -> Long,
) {
    /** The wait after [failures] failed tries (1 for the first). */
    fun delayAfter(failures: Int): Long = wait(failures)

    companion object {
        /** [baseMs] after the first failure, doubling per failure, never above [capMs]. */
        fun exponential(attempts: Int, baseMs: Long, capMs: Long = Long.MAX_VALUE, attemptTimeoutMs: Long? = null) =
            BackoffPolicy(attempts, attemptTimeoutMs) { exponentialBackoffMs(it - 1, baseMs, capMs) }

        /** [stepMs] after the first failure, [stepMs] more after each further one. */
        fun linear(attempts: Int, stepMs: Long, attemptTimeoutMs: Long? = null) =
            BackoffPolicy(attempts, attemptTimeoutMs) { stepMs * it }
    }
}

/**
 * Runs [block] up to `policy.attempts` times, waiting between tries, and returns the first result.
 * After the last try, or when [retryIf] says a failure cannot be cured by trying again, it throws that failure.
 * The caller's own cancellation is never retried.
 */
internal suspend fun <T> retrying(
    policy: BackoffPolicy,
    retryIf: (Throwable) -> Boolean = { true },
    block: suspend (attempt: Int) -> T,
): T {
    var failure: Throwable? = null
    for (attempt in 0 until policy.attempts) {
        val result = runSuspendCatching {
            val timeout = policy.attemptTimeoutMs
            if (timeout == null) block(attempt) else withTimeout(timeout) { block(attempt) }
        }
        result.onSuccess { return it }
        val e = result.exceptionOrNull()!!
        failure = e
        if (attempt == policy.attempts - 1 || !retryIf(e)) break
        delay(policy.delayAfter(attempt + 1))
    }
    throw failure ?: IllegalArgumentException("a retry policy needs at least one attempt")
}

private const val MaxBackoffShift = 30
