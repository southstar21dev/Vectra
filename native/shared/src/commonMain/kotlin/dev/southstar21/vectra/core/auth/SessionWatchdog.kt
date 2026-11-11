package dev.southstar21.vectra.core.auth

import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.PocketBaseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Keeps the session alive: refresh on start, on return to foreground, on reconnect,
 * plus a 5-minute backstop while the app is visible. Each refresh rewrites the stored
 * session, so triggers within [MIN_REFRESH_GAP_MS] of the last one are skipped unless
 * the token is close to expiry. Only a definitive auth rejection (401/403) or an
 * already-expired token signs the user out; network errors wait for the next trigger.
 */
class SessionWatchdog(
    private val auth: AuthRepository,
    private val store: AuthStore,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private var backstop: Job? = null
    private var lastRefreshAt: Long? = null

    /** The app became visible. */
    fun startBackstop() {
        backstop?.cancel()
        backstop = scope.launch {
            while (isActive) {
                delay(BACKSTOP_MS)
                refreshIfNeeded()
            }
        }
    }

    fun stopBackstop() {
        backstop?.cancel()
        backstop = null
    }

    /** Foreground / online. */
    fun trigger() {
        scope.launch { refreshIfNeeded() }
    }

    suspend fun refreshIfNeeded() = lock.withLock {
        val current = store.current ?: return@withLock
        if (!current.isValid()) {
            auth.expire(onlyToken = current.token)
            return@withLock
        }
        val now = Clock.System.now().epochSeconds
        if (!shouldRefreshSession(lastRefreshAt, current.expiresAt, now)) return@withLock
        try {
            auth.refreshToken()
            lastRefreshAt = now
        } catch (e: PocketBaseException) {
            if (e.isAuthRejection) auth.expire(onlyToken = current.token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // retryable
        }
    }

    companion object {
        const val BACKSTOP_MS = 5 * 60_000L
        const val MIN_REFRESH_GAP_MS = 60 * 60_000L
        const val EXPIRY_MARGIN_SECONDS = 24 * 60 * 60L
    }
}

/**
 * Whether a refresh is worth its stored-session rewrite. The first of a process always
 * is: a token's lifetime restarts with each refresh, so skipping on remaining lifetime
 * alone would sign out someone who merely opens the app less often than that margin.
 */
internal fun shouldRefreshSession(lastRefreshAt: Long?, expiresAt: Long?, nowSeconds: Long): Boolean {
    if (lastRefreshAt == null || expiresAt == null) return true
    if (expiresAt - nowSeconds < SessionWatchdog.EXPIRY_MARGIN_SECONDS) return true
    return (nowSeconds - lastRefreshAt) * 1000 >= SessionWatchdog.MIN_REFRESH_GAP_MS
}
