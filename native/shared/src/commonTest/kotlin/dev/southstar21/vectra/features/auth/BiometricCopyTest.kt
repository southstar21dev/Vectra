package dev.southstar21.vectra.features.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class BiometricCopyTest {
    @Test fun iPhonesNameTheirOwnUnlockMethod() {
        assertEquals("Sign in with Face ID instead of a password", BiometricCopy.signInInstead("ios", "Face ID"))
        assertEquals("Touch ID instead of a password", BiometricCopy.setupSubtitle("ios", "Touch ID"))
        assertEquals("Sign in with Touch ID", BiometricCopy.signInTitle("ios", "Touch ID"))
        assertEquals("Confirm with your password, then follow the Face ID prompt.", BiometricCopy.confirmPrompt("ios", "Face ID"))
    }

    @Test fun androidKeepsTheFingerprintWording() {
        assertEquals("Use your fingerprint instead of a password", BiometricCopy.useInstead("android", "fingerprint"))
        assertEquals("Sign in with fingerprint", BiometricCopy.signInTitle("android", "fingerprint"))
        assertEquals("Confirm with your password, then follow the fingerprint prompt.", BiometricCopy.confirmPrompt("android", "fingerprint"))
    }
}
