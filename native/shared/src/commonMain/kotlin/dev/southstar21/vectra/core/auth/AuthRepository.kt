package dev.southstar21.vectra.core.auth

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.data.UserScopedKeyPrefixes
import dev.southstar21.vectra.core.data.offlineQueueKeys
import dev.southstar21.vectra.core.network.AuthState
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.PocketBaseException
import dev.southstar21.vectra.core.util.runSuspendCatching
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Hooks run on sign-out (push cleanup) and after the session ends (cache wipe). */
interface SessionListener {
    suspend fun beforeSignOut() {}
    suspend fun onSessionCleared() {}

    /**
     * Uploads writes that only this device holds, before an explicit sign-out deletes them.
     * True when none are left behind.
     */
    suspend fun flushPendingWrites(): Boolean = true
}

/**
 * The session, the profile, and the account
 * mutations. The profile is the users auth record itself.
 */
class AuthRepository(
    private val pb: PocketBaseClient,
    private val store: AuthStore,
    private val kv: KeyValueStore,
    scope: CoroutineScope,
    private val listeners: Lazy<List<SessionListener>>,
) {
    val session: StateFlow<AuthState?> = store.state
    val profile: StateFlow<Profile?> = store.state.map { it?.record?.let(Profile::from) }
        .stateIn(scope, SharingStarted.Eagerly, store.current?.record?.let(Profile::from))

    private val _sessionExpired = MutableStateFlow(false)
    /** One-shot hint for the login page after the watchdog signed us out. */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()
    fun consumeSessionExpired() {
        _sessionExpired.value = false
    }

    private val restoreLock = Mutex()
    private var restored = false

    /**
     * Reads the stored session, once per process. The UI does it at startup; work that runs with
     * no screen (boot, widget redraws, background refresh) calls it too, or it would see no session.
     */
    suspend fun restore() = restoreLock.withLock {
        if (!restored) {
            store.restore()
            restored = true
        }
    }

    suspend fun login(email: String, password: String) {
        pb.authWithPassword(email, password)
        settleKeptQueue()
    }

    suspend fun signup(email: String, password: String, fullName: String) {
        pb.collection("users").create(
            buildJsonObject {
                put("email", JsonPrimitive(email))
                put("password", JsonPrimitive(password))
                put("passwordConfirm", JsonPrimitive(password))
                put("full_name", JsonPrimitive(fullName))
            },
        )
        pb.authWithPassword(email, password)
        settleKeptQueue()
    }

    /**
     * Emails a reset link. PocketBase answers the same whether or not the address has an account, and
     * the link opens its own confirm-password-reset page, so the new password is set in the browser.
     */
    suspend fun requestPasswordReset(email: String) {
        pb.send(
            "/api/collections/users/request-password-reset",
            HttpMethod.Post,
            JsonObject(mapOf("email" to JsonPrimitive(email))),
        )
    }

    /** Save a token + record obtained elsewhere (passkey login). */
    suspend fun adoptSession(token: String, record: JsonObject) {
        store.save(AuthState(token, record))
        settleKeptQueue()
    }

    suspend fun updateProfile(fields: Map<String, JsonElement>) {
        val id = requireSession().userId
        val record = pb.collection("users").update(id, JsonObject(fields))
        store.updateRecord(record)
    }

    suspend fun changeEmail(currentPassword: String, newEmail: String) {
        requireSession()
        pb.send(
            "/api/vectra/account/email",
            HttpMethod.Post,
            JsonObject(mapOf("currentPassword" to JsonPrimitive(currentPassword), "newEmail" to JsonPrimitive(newEmail))),
        )
        // The email change rotates the token key; re-auth so the user stays signed in.
        try {
            pb.authWithPassword(newEmail, currentPassword)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            clear(keepQueue = true)
            error("Email updated, but your session ended — please sign in again with your new address")
        }
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val session = requireSession()
        val email = session.email ?: error("Not authenticated")
        try {
            pb.authWithPassword(email, currentPassword)
        } catch (e: PocketBaseException) {
            // Only a rejected login means a wrong password; offline or rate-limited is said as such.
            when {
                e.status == 400 || e.status == 401 -> error("Current password is incorrect")
                e.status == 429 -> error("Too many attempts — please wait a moment and try again")
                else -> throw e
            }
        }
        // PocketBase requires oldPassword whenever a user changes their own password.
        pb.collection("users").update(
            session.userId,
            JsonObject(
                mapOf(
                    "oldPassword" to JsonPrimitive(currentPassword),
                    "password" to JsonPrimitive(newPassword),
                    "passwordConfirm" to JsonPrimitive(newPassword),
                ),
            ),
        )
        try {
            pb.authWithPassword(email, newPassword)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            clear(keepQueue = true)
            error("Password updated, but your session ended — please sign in again")
        }
    }

    /** The server asks for the current password, so a stolen session token alone cannot delete the account. */
    suspend fun deleteAccount(currentPassword: String) {
        requireSession()
        pb.send(
            "/api/vectra/account/delete",
            HttpMethod.Post,
            JsonObject(mapOf("currentPassword" to JsonPrimitive(currentPassword))),
        )
        clear()
    }

    /**
     * Tries, for at most [FLUSH_TIMEOUT_MS], to upload the edits only this device holds (offline
     * schedule items, debounced course tracker writes). True when none are left; a sign-out
     * after false loses them, so the caller warns first.
     */
    suspend fun flushPendingWrites(): Boolean = withTimeoutOrNull(FLUSH_TIMEOUT_MS) {
        listeners.value.map { runSuspendCatching { it.flushPendingWrites() }.getOrDefault(false) }.all { it }
    } ?: false

    /**
     * Explicit sign-out: device push cleanup first (bounded), then the session and every
     * per-user key are wiped regardless, the offline queue included. Call [flushPendingWrites]
     * first and warn when it returns false.
     */
    suspend fun signOut() {
        try {
            withTimeout(SIGN_OUT_TIMEOUT_MS) { listeners.value.forEach { it.beforeSignOut() } }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
        } catch (_: Throwable) {
            // cleanup is best-effort
        }
        clear()
    }

    /**
     * The watchdog found the token dead: flag the login hint and drop the session. The user
     * did not choose to sign out, so their unsynced edits are kept for when they sign back in.
     */
    suspend fun expire() {
        _sessionExpired.value = true
        clear(keepQueue = true)
    }

    /**
     * Runs to the end even if the caller is cancelled: half a wipe leaves one account's data behind.
     * [keepQueue] keeps the signed-out account's offline queue (and notes whose it is), for an
     * involuntary sign-out: those writes have no other copy.
     */
    private suspend fun clear(keepQueue: Boolean = false) = withContext(NonCancellable) {
        val keptFor = store.current?.userId?.ifEmpty { null }?.takeIf { keepQueue }
        store.clear()
        // A shared device must not keep the previous account's data.
        val kept = keptFor?.let(::offlineQueueKeys).orEmpty()
        runSuspendCatching {
            kv.removeByPrefix(*UserScopedKeyPrefixes, keep = kept)
            kv.set(KEPT_QUEUE_OWNER_KEY, keptFor)
        }
        listeners.value.forEach { runSuspendCatching { it.onSessionCleared() } }
    }

    /**
     * After a sign-in: a queue kept by [expire] is replayed by the features when its own account
     * signs back in; any other account signing in deletes it instead. Best effort: the sign-in
     * itself has already succeeded.
     */
    private suspend fun settleKeptQueue() = withContext(NonCancellable) {
        val uid = store.current?.userId?.ifEmpty { null } ?: return@withContext
        runSuspendCatching {
            val owner = kv.get(KEPT_QUEUE_OWNER_KEY)
            if (owner != null) {
                if (owner != uid) offlineQueueKeys(owner).forEach { kv.set(it, null) }
                kv.set(KEPT_QUEUE_OWNER_KEY, null)
            }
        }
    }

    private fun requireSession(): AuthState = store.current ?: error("Not authenticated")

    internal suspend fun refreshToken() = pb.authRefresh()

    companion object {
        const val SIGN_OUT_TIMEOUT_MS = 3000L
        const val FLUSH_TIMEOUT_MS = 8000L

        /** Which account the offline queue kept by an involuntary sign-out belongs to. */
        const val KEPT_QUEUE_OWNER_KEY = "vectra.keptQueueOwner"
    }
}
