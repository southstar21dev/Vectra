package dev.southstar21.vectra.core.platform

/**
 * The shared HTTP client's disk cache (OkHttp's on Android, NSURLCache on iOS). It holds responses
 * from the proxy, weather, geocoding and map services, so a sign-out empties it with the rest of
 * the account's data.
 */
fun interface HttpCache {
    fun clear()
}

object NoHttpCache : HttpCache {
    override fun clear() = Unit
}
