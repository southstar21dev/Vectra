package dev.southstar21.vectra.core.auth

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.data.UserScopedKeyPrefixes
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Hooks run on sign-out (push cleanup) and after the session ends (cache wipe). */
interface SessionListener {
    suspend fun beforeSignOut() {}
    suspend fun onSessionCleared() {}
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

    suspend fun restore() = store.restore()

    suspend fun login(email: String, password: String) {
        pb.authWithPassword(email, password)
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
    }

    /** Save a token + record obtained elsewhere (passkey login). */
    suspend fun adoptSession(token: String, record: JsonObject) = store.save(AuthState(token, record))

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
            clear()
            error("Email updated, but your session ended — please sign in again with your new address")
        }
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val session = requireSession()
        val email = session.email ?: error("Not authenticated")
        try {
            pb.authWithPassword(email, currentPassword)
        } catch (_: PocketBaseException) {
            error("Current password is incorrect")
        }
        pb.collection("users").update(
            session.userId,
            JsonObject(mapOf("password" to JsonPrimitive(newPassword), "passwordConfirm" to JsonPrimitive(newPassword))),
        )
        try {
            pb.authWithPassword(email, newPassword)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            clear()
            error("Password updated, but your session ended — please sign in again")
        }
    }

    suspend fun deleteAccount() {
        requireSession()
        pb.send("/api/vectra/account/delete", HttpMethod.Post)
        clear()
    }

    /** Explicit sign-out: device push cleanup first (bounded), then the session is wiped regardless. */
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

    /** The watchdog found the token dead: flag the login hint and drop the session. */
    suspend fun expire() {
        _sessionExpired.value = true
        clear()
    }

    /** Runs to the end even if the caller is cancelled: half a wipe leaves one account's data behind. */
    private suspend fun clear() = withContext(NonCancellable) {
        store.clear()
        // A shared device must not keep the previous account's data.
        kv.removeByPrefix(*UserScopedKeyPrefixes)
        listeners.value.forEach { runSuspendCatching { it.onSessionCleared() } }
    }

    private fun requireSession(): AuthState = store.current ?: error("Not authenticated")

    internal suspend fun refreshToken() = pb.authRefresh()

    companion object {
        const val SIGN_OUT_TIMEOUT_MS = 3000L
    }
}
