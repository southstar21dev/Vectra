package dev.southstar21.vectra.core.platform

/** Encrypts the auth token at rest (Android Keystore; iOS Keychain later). */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(cipher: String): String?
}

/** Pass-through used by tests and platforms without a secure store yet. */
object PlainSecretCipher : SecretCipher {
    override fun encrypt(plain: String) = plain
    override fun decrypt(cipher: String): String? = cipher
}
