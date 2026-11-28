package dev.southstar21.vectra.core.platform

import android.os.Build

internal actual val platformName: String = "android"

internal actual val deviceDisplayName: String
    get() {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        return name.trim().ifEmpty { "Android" }
    }

internal actual val biometricName: String = "fingerprint"
