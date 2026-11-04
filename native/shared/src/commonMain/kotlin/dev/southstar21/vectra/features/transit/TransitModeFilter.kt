package dev.southstar21.vectra.features.transit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Order-insensitive equality for mode-filter lists. */
fun sameModeFilter(a: List<String>, b: List<String>): Boolean {
    if (a.size != b.size) return false
    val set = a.toSet()
    return b.all { it in set }
}

/**
 * Serializes persisted mode-filter writes. Only one write is in flight; calls arriving
 * meanwhile only update the desired value, so the latest selection is what gets
 * persisted. A write repeating the last successfully written value is skipped. Each
 * call throws only for its own write; the queue keeps accepting later writes.
 */
class ModeFilterWriteQueue {
    private class Desired(val filter: List<String>, val write: suspend (List<String>) -> Unit)

    private val mutex = Mutex()
    private var desired: Desired? = null
    private var written: List<String>? = null

    suspend operator fun invoke(filter: List<String>, write: suspend (List<String>) -> Unit) {
        desired = Desired(filter, write)
        // Yield before writing so calls made in the same tick collapse to the last one.
        yield()
        mutex.withLock {
            val next = desired ?: return
            val last = written
            if (last != null && sameModeFilter(next.filter, last)) return
            next.write(next.filter)
            written = next.filter
        }
    }
}

fun createModeFilterWriteQueue(): ModeFilterWriteQueue = ModeFilterWriteQueue()

/**
 * Selected transit modes from the user's `preferences` JSON; empty = no filter (ALL).
 * Reads the list form and the legacy single-string value.
 */
fun parseModeFilter(preferences: JsonObject?): List<String> {
    val raw = preferences?.get("transit_mode_filter")
    if (raw is JsonArray) {
        return raw.filterIsInstance<JsonPrimitive>()
            .filter { it.isString }
            .map { it.content.uppercase() }
            .filter { it != "ALL" }
    }
    if (raw is JsonPrimitive && raw.isString && raw.content.isNotEmpty() && raw.content.uppercase() != "ALL") {
        return listOf(raw.content.uppercase())
    }
    return emptyList()
}

/**
 * Next filter list when `id` is picked from the explorer's single-select chips: tapping
 * the only active mode clears the filter, any other replaces the selection ("ALL" clears).
 */
fun selectSingleModeFilter(current: List<String>, id: String): List<String> {
    val upper = id.uppercase()
    if (upper == "ALL") return emptyList()
    if (current.size == 1 && current[0].uppercase() == upper) return emptyList()
    return listOf(upper)
}
