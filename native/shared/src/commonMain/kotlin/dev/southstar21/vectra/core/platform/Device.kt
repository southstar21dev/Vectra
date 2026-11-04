package dev.southstar21.vectra.core.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Whether the device has a network, and when that changes. */
interface Connectivity {
    val online: StateFlow<Boolean>
}

enum class GeoPermission { Granted, Prompt, Denied, Unsupported }

data class GeoFix(
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
    val heading: Double? = null,
    val speed: Double? = null,
    val timeMs: Long,
)

/** The single owner of device geolocation. */
interface LocationProvider {
    fun permission(): GeoPermission
    /** One-shot fix: low accuracy, 10s timeout, accepts a cached fix up to 30s old. */
    suspend fun current(): GeoFix
    /** Continuous updates while collected. */
    fun watch(): Flow<GeoFix>
}

/** Opens external URLs / settings. */
interface Launcher {
    fun openUrl(url: String)
    fun openAppSettings()
}
