package dev.southstar21.vectra.core.data

import dev.southstar21.vectra.core.network.VectraJson
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
val UserScopedKeyPrefixes: Array<String> = UserKeys.all.map { it.base }.toTypedArray()

/**
 * Bases of the keys that hold writes only this device has (the schedule items queue and the
 * course tracker's not yet uploaded data), as `<base>_<userId>`. An involuntary sign-out keeps
 * them so the same account can replay them after signing back in; see AuthRepository.
 */
val OfflineQueueKeyPrefixes: Array<String> =
    UserKeys.all.filter { it.keepOnInvoluntarySignOut }.map { it.base }.toTypedArray()

/** This user's [OfflineQueueKeyPrefixes] keys. */
fun offlineQueueKeys(userId: String): Set<String> = OfflineQueueKeyPrefixes.mapTo(LinkedHashSet()) { "${it}_$userId" }
