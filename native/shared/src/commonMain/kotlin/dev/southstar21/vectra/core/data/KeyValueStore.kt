package dev.southstar21.vectra.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * String key/value persistence. Small app state lives in DataStore, whose whole file is
 * read and rewritten on each access; keys under [LargeValueKeyPrefixes] go to [blobs]
 * instead so a schedule cache never slows the session or appearance down. Values are
 * JSON strings written by the callers.
 */
class KeyValueStore(private val store: DataStore<Preferences>, private val blobs: BlobStore? = null) {
    private fun blobsFor(key: String): BlobStore? = blobs?.takeIf { LargeValueKeyPrefixes.any(key::startsWith) }

    suspend fun get(key: String): String? {
        val blob = blobsFor(key)
        return if (blob != null) blob.get(key) else store.data.first()[stringPreferencesKey(key)]
    }

    /** Changes to a small value. Large values live outside DataStore and cannot be observed. */
    fun observe(key: String): Flow<String?> {
        require(blobsFor(key) == null) { "$key is stored as a blob" }
        return store.data.map { it[stringPreferencesKey(key)] }
    }

    suspend fun set(key: String, value: String?) {
        val blob = blobsFor(key)
        if (blob != null) {
            blob.set(key, value)
            return
        }
        store.edit { prefs ->
            val k = stringPreferencesKey(key)
            if (value == null) prefs.remove(k) else prefs[k] = value
        }
    }

    /** Remove every key starting with one of [prefixes], except the exact keys in [keep] (logout cleanup). */
    suspend fun removeByPrefix(vararg prefixes: String, keep: Set<String> = emptySet()) {
        store.edit { prefs ->
            prefs.asMap().keys.filter { k -> k.name !in keep && prefixes.any { k.name.startsWith(it) } }.forEach { prefs.remove(it) }
        }
        blobs?.removeByPrefix(*prefixes, keep = keep)
    }

    /**
     * Moves large values written by older versions out of DataStore, once. Not just
     * dropped: the schedule items value holds the offline queue, which has no other copy.
     * Run before anything reads those keys.
     */
    suspend fun moveLargeValuesToBlobs() {
        val target = blobs ?: return
        val prefs = store.data.first()
        if (prefs[BlobsMovedFlag] != null) return
        val large = prefs.asMap().filterKeys { k -> LargeValueKeyPrefixes.any(k.name::startsWith) }
        for ((key, value) in large) {
            if (value is String && target.get(key.name) == null) target.set(key.name, value)
        }
        store.edit { edited ->
            large.keys.forEach { edited.remove(it) }
            edited[BlobsMovedFlag] = "1"
        }
    }

    private companion object {
        val BlobsMovedFlag = stringPreferencesKey("vectra.blobs.moved")
    }
}

/** Keys that can grow large, stored one file per key. */
internal val LargeValueKeyPrefixes = arrayOf(
    "vectra_schedule_cache_events",
    "vectra_schedule_items",
    "vectra_transit_trackers_cache",
    "vectra_course_tracker_cache",
    "vectra_trip_plans_cache",
    "vectra.weather.snapshot",
    "vectra.deviceLocation",
)
