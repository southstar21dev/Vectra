package dev.southstar21.vectra.features.auth

import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.PocketBaseException
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.string
import dev.southstar21.vectra.core.platform.PasskeyAuthenticator
import dev.southstar21.vectra.core.platform.PasskeyCancelledException
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class Passkey(val id: String, val friendlyName: String?, val createdAt: String, val lastUsedAt: String?)

/** Passkey listing, registration and removal over the /api/vectra/passkeys routes. */
class PasskeyRepository(
    private val pb: PocketBaseClient,
    private val auth: AuthRepository,
    private val authenticator: PasskeyAuthenticator,
) : PasskeyActions {
    override val isSupported: Boolean get() = authenticator.isSupported

    private val _passkeys = MutableStateFlow<List<Passkey>?>(null)
    val passkeys: StateFlow<List<Passkey>?> = _passkeys.asStateFlow()

    suspend fun refresh() {
        val res = pb.send("/api/vectra/passkeys") as? JsonArray ?: JsonArray(emptyList())
        _passkeys.value = res.map { it.jsonObject }.map {
            Passkey(it.string("id").orEmpty(), it.string("friendly_name"), it.string("created_at").orEmpty(), it.string("last_used_at"))
        }
    }

    override suspend fun register() {
        val start = pb.send("/api/vectra/passkeys/register/start", HttpMethod.Post).jsonObject
        val credential = authenticator.create(start["options"].toString())
        pb.send(
            "/api/vectra/passkeys/register/verify",
            HttpMethod.Post,
            JsonObject(
                mapOf(
                    "challenge_id" to (start["challenge_id"] ?: JsonPrimitive("")),
                    "credential" to VectraJson.parseToJsonElement(credential),
                    "friendly_name" to JsonPrimitive("Android"),
                ),
            ),
        )
        refresh()
    }

    override suspend fun signIn() {
        val start = pb.send("/api/vectra/passkeys/login/start", HttpMethod.Post).jsonObject
        val credential = authenticator.get(start["options"].toString())
        val res = pb.send(
            "/api/vectra/passkeys/login/verify",
            HttpMethod.Post,
            JsonObject(mapOf("challenge_id" to (start["challenge_id"] ?: JsonPrimitive("")), "credential" to VectraJson.parseToJsonElement(credential))),
        ).jsonObject
        val token = res.string("token") ?: error("webauthn_verification_failed")
        auth.adoptSession(token, res["record"] as? JsonObject ?: error("webauthn_verification_failed"))
    }

    suspend fun delete(id: String) {
        pb.send("/api/vectra/passkeys/$id", HttpMethod.Delete)
        refresh()
    }

    /** passkeyErrorMessage(code); null for a user cancel (nothing to show). */
    override fun errorMessage(e: Throwable): String? {
        if (e is PasskeyCancelledException) return null
        val code = (e as? PocketBaseException)?.let { it.data.string("code") ?: it.message } ?: e.message.orEmpty()
        return when {
            code.contains("passkey_disabled") -> "Biometric sign-in is not available yet."
            code.contains("credential_not_found") -> "No passkey found for this account on this device."
            code.contains("too_many_passkeys") -> "Too many passkeys — remove one in Settings first."
            code.contains("credential_exists") -> "This device already has a passkey for your account."
            code.contains("challenge_expired") || code.contains("verification_failed") -> "Something went wrong — please try again."
            code.contains("not_supported") -> "Biometric sign-in is not supported on this device."
            else -> "Biometric sign-in failed — please try again."
        }
    }
}
