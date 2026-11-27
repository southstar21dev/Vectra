package dev.southstar21.vectra.features.auth

import androidx.compose.ui.graphics.vector.ImageVector
import dev.southstar21.vectra.core.designsystem.icons.FaceIdLine
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.platform.biometricName
import dev.southstar21.vectra.core.platform.platformName

/**
 * What the passkey rows call the unlock method: Face ID or Touch ID on an iPhone (whichever the
 * device has), the fingerprint on Android. The feature itself is always called "Passkey".
 */
object BiometricCopy {
    /** The unlock method inside a sentence: "Face ID", "your fingerprint". */
    fun method(platform: String = platformName, device: String = biometricName): String =
        if (platform == "ios") device else "your fingerprint"

    /** The button and toggle title: "Sign in with Face ID", "Sign in with fingerprint". */
    fun signInTitle(platform: String = platformName, device: String = biometricName): String =
        "Sign in with " + if (platform == "ios") device else "fingerprint"

    fun signInInstead(platform: String = platformName, device: String = biometricName): String =
        "Sign in with ${method(platform, device)} instead of a password"

    fun useInstead(platform: String = platformName, device: String = biometricName): String =
        "Use ${method(platform, device)} instead of a password"

    /** The setup-card subtitle: a sentence start, so the first letter is upper case. */
    fun setupSubtitle(platform: String = platformName, device: String = biometricName): String =
        method(platform, device).replaceFirstChar { it.uppercase() } + " instead of a password"

    /** Confirming a password before the device's unlock prompt, as the settings form words it. */
    fun confirmPrompt(platform: String = platformName, device: String = biometricName): String =
        "Confirm with your password, then follow the ${if (platform == "ios") device else "fingerprint"} prompt."

    /** The Face ID glyph on iOS (Touch ID has a fingerprint), the fingerprint glyph on Android. */
    fun icon(platform: String = platformName, device: String = biometricName): ImageVector =
        if (platform == "ios" && device == "Face ID") FaceIdLine else RemixIcons.FingerprintLine
}
