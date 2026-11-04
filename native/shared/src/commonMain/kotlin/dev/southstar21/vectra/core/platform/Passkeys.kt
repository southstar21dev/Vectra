package dev.southstar21.vectra.core.platform

/**
 * The platform WebAuthn ceremony (Android Credential Manager). Takes the
 * server's PublicKeyCredential{Creation,Request}OptionsJSON and returns the
 * credential's standard JSON (`PublicKeyCredential.toJSON()` shape).
 */
interface PasskeyAuthenticator {
    val isSupported: Boolean
    suspend fun create(optionsJson: String): String
    suspend fun get(optionsJson: String): String
}

/** User dismissed the system sheet. */
class PasskeyCancelledException : Exception("cancelled")
