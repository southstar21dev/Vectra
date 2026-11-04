package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.time.isValidCalendarDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/*
 * The offline-first item cache + mutation queue. Every
 * function is a pure reducer over [ScheduleItemsState]; the repository persists
 * the result under `vectra_schedule_items_<userId>`.
 */

@Serializable
data class ScheduleItemSyncConflict(val serverItem: ScheduleItem?, val serverUpdatedAt: String?)

@Serializable
data class StoredScheduleItem(
    val id: String,
    val type: String,
    val title: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val location: String? = null,
    val completed: Boolean? = null,
    val acknowledgedConflictKeys: List<String> = emptyList(),
    val continuation: Boolean? = null,
    val updatedAt: String? = null,
    val pendingSync: Boolean? = null,
    val syncConflict: ScheduleItemSyncConflict? = null,
) {
    fun toItem() = ScheduleItem(id, type, title, date, startTime, endTime, location, completed, acknowledgedConflictKeys, continuation)
}

fun ScheduleItem.stored(updatedAt: String? = null, pendingSync: Boolean? = null, syncConflict: ScheduleItemSyncConflict? = null) =
    StoredScheduleItem(id, type, title, date, startTime, endTime, location, completed, acknowledgedConflictKeys, continuation, updatedAt, pendingSync, syncConflict)

/** kind: 'upsert' (item set, base nullable) | 'delete' (base required). */
@Serializable
data class ScheduleItemMutation(
    val kind: String,
    val id: String,
    val item: StoredScheduleItem? = null,
    val baseUpdatedAt: String? = null,
    val syncConflict: ScheduleItemSyncConflict? = null,
) {
    val isUpsert get() = kind == UPSERT
    val isDelete get() = kind == DELETE

    companion object {
        const val UPSERT = "upsert"
        const val DELETE = "delete"
    }
}

@Serializable
data class ScheduleItemsState(
    val version: Int = 1,
    val items: List<StoredScheduleItem> = emptyList(),
    val queue: List<ScheduleItemMutation> = emptyList(),
)

const val SCHEDULE_ITEMS_CACHE_BASE_KEY = "vectra_schedule_items"

fun getScheduleItemsStorageKey(userId: String?): String {
    val scope = userId?.trim()?.ifEmpty { null } ?: "anonymous"
    return "${SCHEDULE_ITEMS_CACHE_BASE_KEY}_$scope"
}

fun createEmptyScheduleItemsState() = ScheduleItemsState()

// ---- Validation ---------------------------------------------------------------

private val Uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$|^00000000-0000-0000-0000-000000000000$")
private val DateRe = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val TimeRe = Regex("""^\d{2}:\d{2}$""")

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** Null when the shape is invalid; absent optional keys take their defaults. */
internal fun parsePayload(o: JsonObject?): ScheduleItem? {
    o ?: return null
    val id = o["id"].str()?.takeIf { Uuid.matches(it) } ?: return null
    val type = o["type"].str()?.takeIf { it == "shift" || it == "task" } ?: return null
    val title = o["title"].str()?.takeIf { it.length in 1..200 } ?: return null
    val date = o["date"].str()?.takeIf { DateRe.matches(it) } ?: return null
    val start = o["startTime"].str()?.takeIf { TimeRe.matches(it) } ?: return null
    val end = o["endTime"].str()?.takeIf { TimeRe.matches(it) } ?: return null
    val location = o["location"]?.let { if (it is JsonNull) return null else it.str()?.takeIf { s -> s.length <= 200 } ?: return null }
    val completed = o["completed"]?.let { it.bool() ?: return null }
    val acks = o["acknowledgedConflictKeys"]?.let { el ->
        (el as? JsonArray)?.map { it.str() ?: return null } ?: return null
    } ?: emptyList()
    val continuation = o["continuation"]?.let { it.bool() ?: return null }
    return ScheduleItem(id, type, title, date, start, end, location, completed, acks, continuation)
}

/*
 * VectraJson leaves nulls out, so a missing key here means null: a queued create has no
 * base version and a conflict with a row deleted elsewhere has no server copy.
 */
private fun parseSyncConflict(el: JsonElement?): Result<ScheduleItemSyncConflict?> {
    if (el == null) return Result.success(null)
    val o = el as? JsonObject ?: return Result.failure(IllegalArgumentException())
    val server = o["serverItem"]
    val serverItem = if (server == null || server is JsonNull) null
    else parsePayload(server as? JsonObject) ?: return Result.failure(IllegalArgumentException())
    val at = o["serverUpdatedAt"]
    val serverUpdatedAt = when (at) {
        null, is JsonNull -> null
        else -> at.str() ?: return Result.failure(IllegalArgumentException())
    }
    return Result.success(ScheduleItemSyncConflict(serverItem, serverUpdatedAt))
}

private fun parseStoredItem(el: JsonElement?): StoredScheduleItem? {
    val o = el as? JsonObject ?: return null
    val payload = parsePayload(o) ?: return null
    val updatedAt = o["updatedAt"]?.let { if (it is JsonNull) null else it.str() ?: return null }
    val pending = o["pendingSync"]?.let { it.bool() ?: return null }
    val conflict = parseSyncConflict(o["syncConflict"]).getOrElse { return null }
    return payload.stored(updatedAt, pending, conflict)
}

private fun parseMutation(el: JsonElement?): ScheduleItemMutation? {
    val o = el as? JsonObject ?: return null
    val id = o["id"].str()?.takeIf { Uuid.matches(it) } ?: return null
    val conflict = parseSyncConflict(o["syncConflict"]).getOrElse { return null }
    return when (o["kind"].str()) {
        "upsert" -> {
            val item = parseStoredItem(o["item"]) ?: return null
            val base = o["baseUpdatedAt"]
            val baseValue = if (base == null || base is JsonNull) null else base.str() ?: return null
            ScheduleItemMutation(ScheduleItemMutation.UPSERT, id, item, baseValue, conflict)
        }
        "delete" -> {
            val base = o["baseUpdatedAt"].str() ?: return null
            ScheduleItemMutation(ScheduleItemMutation.DELETE, id, null, base, conflict)
        }
        else -> null
    }
}

/**
 * Validate the envelope, then each item and queued change on its own: one entry
 * written by another build must not discard every offline item along with it.
 */
fun parseScheduleItemsState(raw: String?): ScheduleItemsState? {
    if (raw.isNullOrEmpty()) return null
    return try {
        val env = VectraJson.parseToJsonElement(raw) as? JsonObject ?: return null
        if ((env["version"] as? JsonPrimitive)?.intOrNull != 1) return null
        val rawItems = env["items"] as? JsonArray ?: return null
        val rawQueue = env["queue"] as? JsonArray ?: return null
        val byId = LinkedHashMap<String, StoredScheduleItem>()
        rawItems.mapNotNull(::parseStoredItem).forEach { byId[it.id] = it }
        val queueById = LinkedHashMap<String, ScheduleItemMutation>()
        for (m in rawQueue.mapNotNull(::parseMutation)) {
            if (m.isUpsert && m.id !in byId) continue
            queueById[m.id] = m
        }
        ScheduleItemsState(1, byId.values.toList(), queueById.values.toList())
    } catch (_: Throwable) {
        null
    }
}

/** stateSchema check before a write: refuse to persist an invalid state. */
fun isValidScheduleItemsState(state: ScheduleItemsState): Boolean =
    state.version == 1 &&
        state.items.all { parseStoredItem(VectraJson.encodeToJsonElement(StoredScheduleItem.serializer(), it)) != null } &&
        state.queue.all { parseMutation(VectraJson.encodeToJsonElement(ScheduleItemMutation.serializer(), it)) != null }

fun encodeScheduleItemsState(state: ScheduleItemsState): String =
    VectraJson.encodeToString(ScheduleItemsState.serializer(), state)

// ---- reducers -------------------------------------------------------------------

private fun upsertItem(items: List<StoredScheduleItem>, item: StoredScheduleItem): List<StoredScheduleItem> =
    if (items.none { it.id == item.id }) items + item else items.map { if (it.id == item.id) item else it }

/** Coalesce local writes while preserving the server version first read. */
fun enqueueScheduleItemUpsert(state: ScheduleItemsState, item: StoredScheduleItem, baseUpdatedAt: String? = null): ScheduleItemsState {
    val previous = state.queue.firstOrNull { it.id == item.id }
    val existing = state.items.firstOrNull { it.id == item.id }
    val base = previous?.baseUpdatedAt ?: baseUpdatedAt ?: existing?.updatedAt ?: item.updatedAt
    val next = item.copy(pendingSync = true, syncConflict = null)
    val mutation = ScheduleItemMutation(ScheduleItemMutation.UPSERT, item.id, next, base)
    return ScheduleItemsState(1, upsertItem(state.items, next), state.queue.filter { it.id != item.id } + mutation)
}

fun enqueueScheduleItemDelete(state: ScheduleItemsState, id: String, baseUpdatedAt: String? = null): ScheduleItemsState {
    val previous = state.queue.firstOrNull { it.id == id }
    val base = previous?.baseUpdatedAt ?: baseUpdatedAt ?: state.items.firstOrNull { it.id == id }?.updatedAt
    val items = state.items.filter { it.id != id }
    val queue = state.queue.filter { it.id != id }
    if (base == null) return ScheduleItemsState(1, items, queue)
    return ScheduleItemsState(1, items, queue + ScheduleItemMutation(ScheduleItemMutation.DELETE, id, null, base))
}

fun removeQueuedScheduleItemMutation(state: ScheduleItemsState, id: String, serverItem: StoredScheduleItem? = null): ScheduleItemsState {
    val queue = state.queue.filter { it.id != id }
    val items = if (serverItem != null) upsertItem(state.items, serverItem.copy(pendingSync = false, syncConflict = null)) else state.items
    return ScheduleItemsState(1, items, queue)
}

fun markScheduleItemSyncConflict(
    state: ScheduleItemsState,
    id: String,
    serverItem: StoredScheduleItem?,
    serverUpdatedAt: String?,
): ScheduleItemsState {
    val conflict = ScheduleItemSyncConflict(serverItem?.toItem(), serverUpdatedAt)
    var items = state.items.map { if (it.id == id) it.copy(pendingSync = true, syncConflict = conflict) else it }
    if (serverItem != null && items.none { it.id == id }) {
        items = upsertItem(items, serverItem.copy(pendingSync = true, syncConflict = conflict))
    }
    val queue = state.queue.map { if (it.id == id) it.copy(syncConflict = conflict) else it }
    return state.copy(items = items, queue = queue)
}

/** 'server' adopts the server copy; 'local' rebases the local intent on the server version. */
fun resolveScheduleItemSyncConflict(state: ScheduleItemsState, id: String, resolution: String): ScheduleItemsState {
    val op = state.queue.firstOrNull { it.id == id } ?: return state
    val conflict = op.syncConflict ?: return state
    if (resolution == "server" || (op.isDelete && conflict.serverItem == null)) {
        val items = if (conflict.serverItem != null) {
            upsertItem(state.items, conflict.serverItem.stored(conflict.serverUpdatedAt, false, null))
        } else state.items.filter { it.id != id }
        return state.copy(items = items, queue = state.queue.filter { it.id != id })
    }
    if (op.isDelete) {
        val updated = op.copy(baseUpdatedAt = conflict.serverUpdatedAt, syncConflict = null)
        return state.copy(queue = state.queue.map { if (it.id == id) updated else it })
    }
    val local = op.item!!.copy(updatedAt = conflict.serverUpdatedAt, pendingSync = true, syncConflict = null)
    val updated = op.copy(item = local, baseUpdatedAt = conflict.serverUpdatedAt, syncConflict = null)
    return state.copy(items = upsertItem(state.items, local), queue = state.queue.map { if (it.id == id) updated else it })
}

/** Overlay pending local intent over the latest server snapshot. */
fun mergeScheduleItemsState(local: ScheduleItemsState, cloudItems: List<StoredScheduleItem>): ScheduleItemsState {
    val items = LinkedHashMap<String, StoredScheduleItem>()
    cloudItems.forEach { items[it.id] = it.copy(pendingSync = false) }
    for (op in local.queue) {
        if (op.isDelete) {
            val server = op.syncConflict?.serverItem
            if (server != null) {
                items[op.id] = server.stored(op.syncConflict.serverUpdatedAt, true, op.syncConflict)
            } else items.remove(op.id)
        } else {
            val item = op.item!!
            items[op.id] = item.copy(pendingSync = true, syncConflict = op.syncConflict ?: item.syncConflict)
        }
    }
    return ScheduleItemsState(
        1,
        items.values.sortedWith(compareBy<StoredScheduleItem> { it.date }.thenBy { it.startTime }),
        local.queue,
    )
}

/** validateScheduleItem: the payload schema plus the calendar/time rules. */
fun validateScheduleItem(item: ScheduleItem): ScheduleItem? {
    val parsed = parsePayload(VectraJson.encodeToJsonElement(ScheduleItem.serializer(), item) as JsonObject) ?: return null
    if (parsed.title.isBlank()) return null
    if (!isValidCalendarDate(parsed.date)) return null
    fun validTime(t: String): Boolean {
        val (h, m) = t.split(':').map { it.toInt() }
        return h <= 23 && m <= 59
    }
    if (!validTime(parsed.startTime) || !validTime(parsed.endTime)) return null
    if (parsed.startTime == parsed.endTime) return null
    if (parsed.isTask && parsed.endTime < parsed.startTime) return null
    if (parsed.isTask && !parsed.location.isNullOrEmpty()) return null
    if (parsed.isShift && parsed.completed == true) return null
    return parsed
}
