package dev.southstar21.vectra.features.auth

import dev.southstar21.vectra.core.platform.platformName

/** What the passkey rows call the unlock method: iPhones use Face ID or Touch ID, so "fingerprint" would be wrong there. */
object BiometricCopy {
    fun unlockMethods(platform: String = platformName): String =
        if (platform == "ios") "Face ID or Touch ID" else "your fingerprint or face"

    fun signInInstead(platform: String = platformName): String =
        "Sign in with ${unlockMethods(platform)} instead of a password"

    fun useInstead(platform: String = platformName): String =
        "Use ${unlockMethods(platform)} instead of a password"

    /** The setup-card subtitle: a sentence start, so the first letter is upper case. */
    fun setupSubtitle(platform: String = platformName): String =
        unlockMethods(platform).replaceFirstChar { it.uppercase() } + " instead of a password"
}
