package dev.southstar21.vectra.core.config

/** Build-time configuration supplied by the platform app. */
data class VectraConfig(
    /** PocketBase origin. */
    val pocketbaseUrl: String,
    /** Origin serving static data such as /catalog. */
    val catalogOrigin: String,
    val appVersion: String,
    val isDebug: Boolean,
)
