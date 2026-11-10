package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.isCiphertext
import dev.southstar21.vectra.core.network.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The pure parts of schedule items: the encrypted row codec and the conflict
 * gate every save passes through.
 */

/** Thrown when a save still has clashes the user has not acknowledged. */
class ScheduleItemConflictException(val conflicts: List<ScheduleConflict>) :
    Exception("Acknowledge the current schedule conflicts before saving.")

/**
 * The encrypted payload: every item field except `id` (the row carries it as
 * client_id). Key names, order and the omitted-when-null fields are the stored
 * format; keep them stable so existing rows keep comparing equal.
 */
fun scheduleItemPayload(item: ScheduleItem): JsonObject = buildJsonObject {
    put("type", item.type)
    put("title", item.title)
    put("date", item.date)
    put("startTime", item.startTime)
    put("endTime", item.endTime)
    item.location?.let { put("location", it) }
    item.completed?.let { put("completed", it) }
    put("acknowledgedConflictKeys", JsonArray(item.acknowledgedConflictKeys.map(::JsonPrimitive)))
    item.continuation?.let { put("continuation", it) }
}

fun sameItemPayload(a: ScheduleItem, b: ScheduleItem): Boolean = scheduleItemPayload(a) == scheduleItemPayload(b)

/**
 * A schedule_items row → item. The app id is the client UUID (client_id); the
 * PocketBase id is only a fallback for legacy rows. Non-ciphertext or invalid
 * payloads are skipped (null); an undecryptable one throws.
 */
suspend fun decodeScheduleItemRow(row: JsonObject, decrypt: suspend (String?) -> String?): StoredScheduleItem? {
    val payload = row.string("payload")
    if (!isCiphertext(payload)) return null
    val appId = row.string("client_id") ?: row.string("id").orEmpty()
    val clear = decrypt(payload)
    if (clear == null || isCiphertext(clear)) error("Unable to decrypt schedule item $appId.")
    return try {
        val obj = VectraJson.parseToJsonElement(clear) as? JsonObject ?: return null
        val acks = (obj["acknowledgedConflictKeys"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: emptyList()
        val merged = JsonObject(obj + mapOf("id" to JsonPrimitive(appId), "acknowledgedConflictKeys" to JsonArray(acks.map(::JsonPrimitive))))
        val parsed = parsePayload(merged) ?: return null
        val item = validateScheduleItem(parsed) ?: return null
        item.stored(updatedAt = row.string("updated_at"), pendingSync = false)
    } catch (_: Throwable) {
        null
    }
}

/** Clashes of [item] against the other items and the classes, on the item's own dates. */
fun getConflictsForItem(
    item: ScheduleItem,
    otherItems: List<ScheduleItem>,
    events: List<ScheduleEvent>,
): List<ScheduleConflict> {
    val dates = getItemIntervals(item).map { it.date }.distinct()
    val acknowledged = item.acknowledgedConflictKeys.toSet()
    return getScheduleConflicts(otherItems.filter { it.id != item.id } + item, events, dates)
        .filter { it.itemId == item.id || it.otherItemId == item.id }
        .map { it.copy(needsAcknowledgment = it.needsAcknowledgment && it.key !in acknowledged) }
}

fun throwForUnacknowledgedConflicts(conflicts: List<ScheduleConflict>, acknowledgedKeys: List<String>) {
    val ack = acknowledgedKeys.toSet()
    val open = conflicts.filter { it.needsAcknowledgment && it.key !in ack }
    if (open.isNotEmpty()) throw ScheduleItemConflictException(open)
}

/**
 * Never manufacture an acknowledgment during background reconciliation: queued
 * intervals are re-validated, but unacknowledged keys stay absent from the item.
 */
fun reconcileQueuedItemConflicts(
    state: ScheduleItemsState,
    events: List<ScheduleEvent>,
): ScheduleItemsState {
    for (m in state.queue) {
        if (m.isUpsert) getConflictsForItem(m.item!!.toItem(), state.items.map { it.toItem() }, events)
    }
    return state
}
