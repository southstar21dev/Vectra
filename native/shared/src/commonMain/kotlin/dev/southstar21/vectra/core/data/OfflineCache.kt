package dev.southstar21.vectra.core.data

import dev.southstar21.vectra.core.network.VectraJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer

/**
 * A per-user JSON snapshot under `<base>_<userId>`,
 * read when offline or when a fetch fails. Wiped on sign-out. The bases are
 * [LargeValueKeyPrefixes], so [KeyValueStore] keeps each snapshot in its own file.
 */
class OfflineCache<T>(
    private val kv: KeyValueStore,
    private val baseKey: String,
    private val serializer: KSerializer<T>,
) {
    private fun key(userId: String) = "${baseKey}_$userId"

    suspend fun read(userId: String): T? =
        kv.get(key(userId))?.let { runCatching { VectraJson.decodeFromString(serializer, it) }.getOrNull() }

    suspend fun write(userId: String, value: T) = kv.set(key(userId), VectraJson.encodeToString(serializer, value))

    suspend fun clear(userId: String) = kv.set(key(userId), null)
}

/** Offline cache bases and other per-user keys: removed on sign-out. */
val UserScopedKeyPrefixes = arrayOf(
    "vectra_schedule_cache_events",
    "vectra_schedule_items",
    "vectra_transit_trackers_cache",
    "vectra_course_tracker_cache",
    "vectra_trip_plans_cache",
    "vectra_neptun",
    "vectra.weather.snapshot",
    "vectra.deviceLocation",
)

/**
 * Offline-first read: cache when offline; otherwise fetch → write cache, and
 * fall back to the cache when the fetch fails (no cache → rethrow).
 */
suspend fun <T> offlineFirst(
    online: Boolean,
    cache: OfflineCache<T>,
    userId: String,
    fetch: suspend () -> T,
): T {
    if (!online) return cache.read(userId) ?: fetch()
    return try {
        fetch().also { cache.write(userId, it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        cache.read(userId) ?: throw e
    }
}
