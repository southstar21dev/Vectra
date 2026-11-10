package dev.southstar21.vectra.core.network

import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.platform.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/** The signed-in session: PocketBase token + the users auth record. */
data class AuthState(val token: String, val record: JsonObject) {
    val userId: String get() = record.string("id").orEmpty()
    val email: String? get() = record.string("email")

    /** JWT `exp` claim, seconds since epoch (null when unreadable). */
    val expiresAt: Long?
        get() = runCatching {
            val payload = token.split('.')[1]
            val json = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString()
            VectraJson.parseToJsonElement(json).jsonObject["exp"].let { (it as JsonPrimitive).long }
        }.getOrNull()

    /** A token that is not yet expired. */
    fun isValid(nowSeconds: Long = Clock.System.now().epochSeconds): Boolean = expiresAt?.let { it > nowSeconds } ?: false
}

/**
 * The session lives in memory as a StateFlow and is persisted (encrypted) so a
 * cold start resumes signed in.
 */
class AuthStore(private val storage: KeyValueStore, private val cipher: SecretCipher) {
    private val _state = MutableStateFlow<AuthState?>(null)
    val state: StateFlow<AuthState?> = _state.asStateFlow()

    val token: String? get() = _state.value?.token
    val current: AuthState? get() = _state.value

    /**
     * Every change to the session, in memory and on disk, runs under this lock: a save that is
     * still encrypting when a sign-out clears the session would otherwise write it back to disk.
     */
    private val lock = Mutex()

    suspend fun restore() {
        lock.withLock {
            // A storage or Keychain/Keystore failure reads as signed out rather than crashing every launch.
            val stored = runSuspendCatching { storage.get(KEY) }.getOrNull() ?: return
            // Keystore calls are binder round trips; this runs during startup on Main otherwise.
            _state.value = withContext(Dispatchers.Default) {
                val raw = runCatching { cipher.decrypt(stored) }.getOrNull() ?: return@withContext null
                runCatching { parseSession(VectraJson.parseToJsonElement(raw).jsonObject) }.getOrNull()
            }
        }
    }

    internal suspend fun saveFrom(response: JsonObject): AuthState {
        val state = parseSession(response) ?: error("The server sent an unreadable session.")
        save(state)
        return state
    }

    /**
     * [saveFrom] for an answer to a request sent with [sentToken], saved only while that token is
     * still the session. A refresh that lands after a sign-out, or after another account signed
     * in, must not bring the old session back. Null when the session has moved on.
     */
    internal suspend fun saveFromIfCurrent(response: JsonObject, sentToken: String): AuthState? {
        val state = parseSession(response) ?: error("The server sent an unreadable session.")
        return locked { if (_state.value?.token == sentToken) state.also { write(it) } else null }
    }

    private fun parseSession(json: JsonObject): AuthState? {
        val token = (json["token"] as? JsonPrimitive)?.contentOrNull ?: return null
        val record = json["record"] as? JsonObject ?: return null
        return AuthState(token, record)
    }

    // Publishing the session removes the login screen and cancels its scope; the write must outlive that.
    suspend fun save(state: AuthState) = locked { write(state) }

    /** Replace the cached record (after a profile update) keeping the token, unless the account changed meanwhile. */
    suspend fun updateRecord(record: JsonObject) = locked {
        val s = _state.value ?: return@locked
        if (record.string("id").let { it != null && it != s.userId }) return@locked
        write(s.copy(record = record))
    }

    /** Drops the session and returns the one it dropped. */
    suspend fun clear(): AuthState? = locked { drop() }

    /** [clear], but only while [token] is still the session; null when it isn't. */
    suspend fun clearIfCurrent(token: String): AuthState? = locked { if (_state.value?.token == token) drop() else null }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(NonCancellable) { lock.withLock { block() } }

    private suspend fun write(state: AuthState) {
        _state.value = state
        val json = buildJsonObject {
            put("token", JsonPrimitive(state.token))
            put("record", state.record)
        }
        storage.set(KEY, withContext(Dispatchers.Default) { cipher.encrypt(json.toString()) })
    }

    private suspend fun drop(): AuthState? {
        val previous = _state.value
        _state.value = null
        storage.set(KEY, null)
        return previous
    }

    private companion object {
        const val KEY = "pocketbase_auth"
    }
}
