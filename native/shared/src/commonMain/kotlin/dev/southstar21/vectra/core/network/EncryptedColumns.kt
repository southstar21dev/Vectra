package dev.southstar21.vectra.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

/**
 * The encrypted text columns of one PocketBase collection, declared once and used by both the
 * writer and the reader so the two can never disagree about which value belongs to which column.
 * Values are addressed by column name; the position in [names] only matters for the batched
 * `decryptAll` round trip, where [ciphertexts] and [clear] share it.
 */
class EncryptedColumns(val names: List<String>) {
    init {
        require(names.toSet().size == names.size) { "Duplicate encrypted column" }
    }

    /** This row's ciphertexts in [names] order, ready to be concatenated into one `decryptAll`. */
    fun ciphertexts(row: JsonObject): List<String?> = names.map { row.string(it) }

    /** The [index]th row's slice of a `decryptAll` over rows' [ciphertexts] laid end to end. */
    fun clear(decrypted: List<String?>, index: Int): ClearColumns {
        val slice = decrypted.subList(index * names.size, (index + 1) * names.size)
        return ClearColumns(names.zip(slice).toMap())
    }

    /** Encrypts [values], which must hold exactly the declared columns, all at once. */
    suspend fun encrypt(values: Map<String, String?>, encrypt: suspend (String?) -> String?): Map<String, String?> {
        require(values.keys == names.toSet()) { "Columns written differ from the declared ones" }
        return encryptSome(values, encrypt)
    }

    /** Like [encrypt] for an update that rewrites only some of the declared columns. */
    suspend fun encryptSome(values: Map<String, String?>, encrypt: suspend (String?) -> String?): Map<String, String?> {
        require(names.containsAll(values.keys)) { "Columns written are not all declared" }
        return coroutineScope { values.map { (name, value) -> async { name to encrypt(value) } }.awaitAll().toMap() }
    }
}

/** One row's decrypted columns, by name. */
class ClearColumns(private val values: Map<String, String?>) {
    operator fun get(name: String): String? = values.getValue(name)

    /** Whether any column came back still encrypted (the key could not be used). */
    val hasCiphertext: Boolean get() = values.values.any(::isCiphertext)
}
