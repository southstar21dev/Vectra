package dev.southstar21.vectra.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.UIKit.UIDevice

internal actual val platformName: String = "ios"

internal actual val deviceDisplayName: String
    get() = UIDevice.currentDevice.model.ifEmpty { "iPhone" }

/** Touch ID on devices that have it, otherwise Face ID (biometryType is only filled in after a policy check). */
@OptIn(ExperimentalForeignApi::class)
internal actual val biometricName: String
    get() {
        val context = LAContext()
        context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)
        return if (context.biometryType == LABiometryTypeTouchID) "Touch ID" else "Face ID"
    }
