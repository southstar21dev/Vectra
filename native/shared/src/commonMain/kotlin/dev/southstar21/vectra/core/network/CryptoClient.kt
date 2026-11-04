package dev.southstar21.vectra.core.network

import dev.southstar21.vectra.core.data.LruCache
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val CiphertextPrefixes = listOf("vc2:", "vc3:")

/** True when [value] is still server ciphertext (encrypted, or a failed decrypt). */
fun isCiphertext(value: String?): Boolean = value != null && CiphertextPrefixes.any(value::startsWith)

/** How long calls are collected before the batch goes out. */
internal const val CryptoBatchWindowMs = 5L

private const val DecryptMemoEntries = 4096

/** A whole-document blob is too big to pin in memory for the sake of one poll. */
private const val MaxMemoizedChars = 32 * 1024

/**
 * Field encryption through the server's `/api/vectra/crypto` route. Calls made
 * within [CryptoBatchWindowMs] are coalesced into one round-trip per op; prefer
 * [decryptAll] for a whole collection. Decrypted values are remembered in memory
 * only, until the session ends, so an unchanged row needs no round trip.
 *
 * Failure semantics: encrypt/digest throw (never store plaintext);
 * decrypt passes the raw value through.
 */
class CryptoClient(private val pb: PocketBaseClient, private val scope: CoroutineScope) {
    private class Pending<T>(val input: T, val result: CompletableDeferred<String>)

    private val lock = Mutex()
    private var encrypts = mutableListOf<Pending<String>>()
    private var decrypts = LinkedHashMap<String, CompletableDeferred<String>>()
    private var digests = mutableListOf<Pending<List<String>>>()
    private var flushScheduled = false
    private val memo = LruCache<String, String>(DecryptMemoEntries)
    /** Bumped on [clearMemo], so a decrypt still in flight cannot refill the memo afterwards. */
    private var memoGeneration = 0

    /** Call with [lock] held. */
    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            delay(CryptoBatchWindowMs)
            flush()
        }
    }

    private suspend fun <T> enqueue(list: () -> MutableList<Pending<T>>, input: T): String {
        val deferred = CompletableDeferred<String>()
        lock.withLock {
            list().add(Pending(input, deferred))
            scheduleFlush()
        }
        return deferred.await()
    }

    private suspend fun flush() {
        val (e, d, g) = lock.withLock {
            flushScheduled = false
            val batch = Triple(encrypts, decrypts, digests)
            encrypts = mutableListOf(); decrypts = LinkedHashMap(); digests = mutableListOf()
            batch
        }
        if (e.isNotEmpty()) run("encrypt", "values", "values", e.map { JsonPrimitive(it.input) }, e)
        if (d.isNotEmpty()) {
            val generation = lock.withLock { memoGeneration }
            val pending = d.map { (cipher, result) -> Pending(cipher, result) }
            run("decrypt", "values", "values", pending.map { JsonPrimitive(it.input) }, pending) { clear ->
                lock.withLock {
                    if (generation != memoGeneration) return@withLock
                    clear.forEachIndexed { i, plain ->
                        val cipher = pending[i].input
                        // A value echoed back still encrypted is a failed decrypt: never remember it.
                        if (cipher.length <= MaxMemoizedChars && !isCiphertext(plain)) memo[cipher] = plain
                    }
                }
            }
        }
        if (g.isNotEmpty()) run("digest", "parts", "digests", g.map { JsonArray(it.input.map(::JsonPrimitive)) }, g)
    }

    private suspend fun <T> run(
        op: String,
        inKey: String,
        outKey: String,
        payload: List<JsonElement>,
        pending: List<Pending<T>>,
        onSuccess: suspend (List<String>) -> Unit = {},
    ) {
        try {
            val res = pb.send(
                "/api/vectra/crypto",
                HttpMethod.Post,
                JsonObject(mapOf("op" to JsonPrimitive(op), inKey to JsonArray(payload))),
            ).jsonObject
            val values = res[outKey] as? JsonArray
            if (values == null || values.size != pending.size) error("Crypto service returned an invalid $op response")
            val clear = values.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
            onSuccess(clear)
            pending.forEachIndexed { i, p -> p.result.complete(clear[i]) }
        } catch (t: Throwable) {
            pending.forEach { it.result.completeExceptionally(t) }
        }
    }

    suspend fun encrypt(value: String?): String? = if (value.isNullOrEmpty()) value else enqueue({ encrypts }, value)

    suspend fun decrypt(value: String?): String? = decryptAll(listOf(value)).single()

    /** Decrypts many values in one round trip, keeping their order; anything already known needs none. */
    suspend fun decryptAll(values: List<String?>): List<String?> {
        val out = values.toMutableList()
        val waiting = LinkedHashMap<Int, CompletableDeferred<String>>()
        lock.withLock {
            values.forEachIndexed { i, value ->
                if (value.isNullOrEmpty() || !isCiphertext(value)) return@forEachIndexed
                val known = memo[value]
                if (known != null) out[i] = known
                else waiting[i] = decrypts.getOrPut(value) { CompletableDeferred() }
            }
            if (waiting.isNotEmpty()) scheduleFlush()
        }
        for ((i, result) in waiting) {
            try {
                out[i] = result.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The raw value stays in place; callers guard with isCiphertext.
            }
        }
        return out
    }

    /** Forgets every decrypted value (the session ended). */
    suspend fun clearMemo() {
        lock.withLock {
            memo.clear()
            memoGeneration++
        }
    }

    suspend fun digest(vararg parts: String): String = enqueue({ digests }, parts.toList())

    /** decryptNeptunUrl: plain http(s) URLs pass through. */
    suspend fun decryptUrl(value: String?): String? =
        if (value != null && (value.startsWith("http://") || value.startsWith("https://"))) value else decrypt(value)
}
