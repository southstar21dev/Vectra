package dev.southstar21.vectra.core.network

import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.platform.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    suspend fun restore() {
        // A storage or Keychain/Keystore failure reads as signed out rather than crashing every launch.
        val stored = runSuspendCatching { storage.get(KEY) }.getOrNull() ?: return
        // Keystore calls are binder round trips; this runs during startup on Main otherwise.
        _state.value = withContext(Dispatchers.Default) {
            val raw = runCatching { cipher.decrypt(stored) }.getOrNull() ?: return@withContext null
            runCatching { parseSession(VectraJson.parseToJsonElement(raw).jsonObject) }.getOrNull()
        }
    }

    internal suspend fun saveFrom(response: JsonObject): AuthState {
        val state = parseSession(response) ?: error("The server sent an unreadable session.")
        save(state)
        return state
    }

    private fun parseSession(json: JsonObject): AuthState? {
        val token = (json["token"] as? JsonPrimitive)?.contentOrNull ?: return null
        val record = json["record"] as? JsonObject ?: return null
        return AuthState(token, record)
    }

    suspend fun save(state: AuthState) {
        _state.value = state
        val json = buildJsonObject {
            put("token", JsonPrimitive(state.token))
            put("record", state.record)
        }
        // Publishing the session removes the login screen and cancels its scope; the write must outlive that.
        withContext(NonCancellable) {
            storage.set(KEY, withContext(Dispatchers.Default) { cipher.encrypt(json.toString()) })
        }
    }

    /** Replace the cached record (after a profile update) keeping the token. */
    suspend fun updateRecord(record: JsonObject) {
        val s = _state.value ?: return
        save(s.copy(record = record))
    }

    suspend fun clear() {
        _state.value = null
        storage.set(KEY, null)
    }

    private companion object {
        const val KEY = "pocketbase_auth"
    }
}
