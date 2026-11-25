package dev.southstar21.vectra.features.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class BiometricCopyTest {
    @Test fun iPhonesSayFaceIdNotFingerprint() {
        assertEquals("Sign in with Face ID or Touch ID instead of a password", BiometricCopy.signInInstead("ios"))
        assertEquals("Face ID or Touch ID instead of a password", BiometricCopy.setupSubtitle("ios"))
        assertEquals("Use your fingerprint or face instead of a password", BiometricCopy.useInstead("android"))
    }
}
