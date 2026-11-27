package dev.southstar21.vectra.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/** Why work started for one account stopped: that account is no longer the signed-in one. */
internal class SessionEndedException(uid: String) : CancellationException("The session of user $uid ended")

/**
 * Coroutine scopes tied to one signed-in user. Async work that belongs to account A runs in
 * [scopeFor] (A), a child of [parent] that is cancelled the moment the signed-in user is anyone
 * else, so A's in-flight fetches and syncs die on sign-out instead of each re-checking the user
 * after every await.
 *
 * [currentUser] answers who is signed in right now (synchronously); [userChanges] reports changes.
 * A scope asked for a user who is not the current one is already cancelled.
 */
internal class SessionScope(
    private val parent: CoroutineScope,
    private val currentUser: () -> String?,
    userChanges: Flow<String?>,
) {
    private class Entry(val uid: String, val job: Job)

    private val parentJob: Job? = parent.coroutineContext[Job]
    private val current = MutableStateFlow<Entry?>(null)

    init {
        // Unconfined and undispatched: the cancel runs inside the store update that signed the user out.
        parent.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            userChanges.distinctUntilChanged().collect { uid ->
                val entry = current.value
                if (entry != null && entry.uid != uid) {
                    current.compareAndSet(entry, null)
                    entry.job.cancel(SessionEndedException(entry.uid))
                }
            }
        }
    }

    /** The scope of [uid]'s session: cancelled when [uid] stops being the signed-in user. */
    fun scopeFor(uid: String): CoroutineScope {
        // A supervisor: one failed child (a fetch that errors) must not end the whole session.
        val fresh = SupervisorJob(parentJob)
        var replaced: Entry? = null
        val entry = current.updateAndGet { old ->
            replaced = null
            if (old != null && old.uid == uid && old.job.isActive) {
                old
            } else {
                replaced = old
                Entry(uid, fresh)
            }
        }!!
        if (entry.job !== fresh) fresh.cancel()
        replaced?.let { it.job.cancel(SessionEndedException(it.uid)) }
        // A user who is already gone gets a dead scope rather than a live one nobody would cancel.
        if (currentUser() != uid) entry.job.cancel(SessionEndedException(uid))
        return CoroutineScope(parent.coroutineContext + entry.job)
    }

    fun launchFor(uid: String, block: suspend CoroutineScope.() -> Unit): Job = scopeFor(uid).launch(block = block)

    fun <T> asyncFor(uid: String, block: suspend CoroutineScope.() -> T): Deferred<T> = scopeFor(uid).async(block = block)

    /** As [withSession], but null instead of a [SessionEndedException] for callers that just stop quietly. */
    suspend fun <T> withSessionOrNull(uid: String, block: suspend CoroutineScope.() -> T): T? =
        try {
            withSession(uid, block)
        } catch (_: SessionEndedException) {
            null
        }

    /**
     * Runs [block] in the caller's coroutine, but ends it with [SessionEndedException] (a
     * cancellation the caller was not given) as soon as [uid] stops being the signed-in user.
     */
    suspend fun <T> withSession(uid: String, block: suspend CoroutineScope.() -> T): T {
        val sessionJob = checkNotNull(scopeFor(uid).coroutineContext[Job])
        return coroutineScope {
            val watch = sessionJob.invokeOnCompletion { cause ->
                if (cause != null) cancel(cause as? CancellationException ?: SessionEndedException(uid))
            }
            try {
                // A session that is already over must not start any work.
                ensureActive()
                block()
            } finally {
                watch.dispose()
            }
        }
    }
}
