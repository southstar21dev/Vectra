package dev.southstar21.vectra.core.platform

import android.os.Build

actual val platformName: String = "android"

actual val deviceDisplayName: String
    get() {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        return name.trim().ifEmpty { "Android" }
    }
