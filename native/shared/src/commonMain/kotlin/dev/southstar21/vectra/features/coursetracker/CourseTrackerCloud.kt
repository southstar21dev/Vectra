package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.pbString
import dev.southstar21.vectra.core.network.string
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `course_tracker_data` is one row per user — `user`,
 * `data` (the encrypted blob) and `updated_at` — with a unique index on `user`.
 * PocketBase has no upsert, so the writer finds the row first.
 */
interface TrackerCloud {
    /** The encrypted blob, or null when the user has no row yet; other failures throw. */
    suspend fun fetch(userId: String): String?

    /** Update the singleton row or create it (adopting a row a racing writer created). */
    suspend fun upsert(userId: String, encrypted: String)
}

class PocketBaseTrackerCloud(private val pb: PocketBaseClient) : TrackerCloud {
    private val collection get() = pb.collection("course_tracker_data")

    private suspend fun findRow(userId: String): JsonObject? = collection.getFirstOrNull("user = ${pbString(userId)}")

    override suspend fun fetch(userId: String): String? = findRow(userId)?.string("data")

    override suspend fun upsert(userId: String, encrypted: String) {
        // Only "no row" falls through to create; a failed lookup rethrows so a
        // transient error can never produce a duplicate row.
        val existing = findRow(userId)
        val payload = JsonObject(
            mapOf(
                "data" to JsonPrimitive(encrypted),
                "updated_at" to JsonPrimitive(nowIso()),
                "user" to JsonPrimitive(userId),
            ),
        )
        if (existing != null) {
            collection.update(rowId(existing), payload)
            return
        }
        try {
            collection.create(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (createError: Throwable) {
            // Race: another writer created the row between lookup and create.
            val raced = runSuspendCatching { findRow(userId) }.getOrNull() ?: throw createError
            collection.update(rowId(raced), payload)
        }
    }

    private fun rowId(row: JsonObject): String = row.string("id") ?: error("Course tracker row has no id")
}

/** Field encryption for the blob. */
interface TrackerCipher {
    /** Throws on failure — plaintext is never stored. */
    suspend fun encrypt(plain: String): String

    /** Passes the raw value through on failure (it then still reads as ciphertext). */
    suspend fun decrypt(value: String): String
}

class CryptoTrackerCipher(private val crypto: CryptoClient) : TrackerCipher {
    override suspend fun encrypt(plain: String): String = crypto.encrypt(plain) ?: error("Encryption failed")
    override suspend fun decrypt(value: String): String = crypto.decrypt(value) ?: value
}

/** Thrown when the cloud row exists but cannot be decrypted or parsed. */
class UnreadableCloudTrackerException : IllegalStateException("Course tracker cloud data is unreadable")

/**
 * The cloud-write gate. The row is one whole-document
 * blob, so uploads stay blocked until the cloud copy has been read on this
 * device (else an empty cache could overwrite other devices' data); a blocked
 * write is remembered, and uploads are serialised with queued ones collapsing
 * to the newest (each carries the full document).
 */
class CloudWriteGate {
    private val state = Mutex()
    private val queue = Mutex()
    private val readable = mutableSetOf<String>()
    private val heldBack = mutableSetOf<String>()
    private var latestPending: (suspend () -> Unit)? = null

    suspend fun markReadable(userId: String) = state.withLock { readable += userId }
    suspend fun isReadable(userId: String): Boolean = state.withLock { userId in readable }

    /** Records that a local write could not be uploaded because the gate was closed. */
    suspend fun noteHeldBack(userId: String) = state.withLock { heldBack += userId }

    /** Returns (and clears) whether a write was held back. */
    suspend fun takeHeldBack(userId: String): Boolean = state.withLock { heldBack.remove(userId) }

    /** Runs [run] after every earlier upload; uploads queued meanwhile collapse to the newest. */
    suspend fun enqueue(run: suspend () -> Unit) {
        state.withLock { latestPending = run }
        queue.withLock {
            val pending = state.withLock { latestPending.also { latestPending = null } } ?: return
            pending()
        }
    }
}
