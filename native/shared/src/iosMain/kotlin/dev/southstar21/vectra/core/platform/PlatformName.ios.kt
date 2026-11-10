package dev.southstar21.vectra.core.platform

import platform.UIKit.UIDevice

actual val platformName: String = "ios"

actual val deviceDisplayName: String
    get() = UIDevice.currentDevice.model.ifEmpty { "iPhone" }
