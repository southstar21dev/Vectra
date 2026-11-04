package dev.southstar21.vectra.features.schedule

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ID1 = "11111111-1111-4111-8111-111111111111"

private fun item(
    id: String = ID1,
    title: String = "Work",
    updatedAt: String? = null,
    pendingSync: Boolean? = null,
) = StoredScheduleItem(id, "shift", title, "2026-09-07", "09:00", "12:00", updatedAt = updatedAt, pendingSync = pendingSync)

class ScheduleItemsStoreTest {
    // writeScheduleItemsState/readScheduleItemsState → a map "storage" over encode/parse.
    private val storage = mutableMapOf<String, String>()
    private fun write(state: ScheduleItemsState, user: String): Boolean {
        if (!isValidScheduleItemsState(state)) return false
        storage[getScheduleItemsStorageKey(user)] = encodeScheduleItemsState(state)
        return true
    }
    private fun read(user: String) = parseScheduleItemsState(storage[getScheduleItemsStorageKey(user)]) ?: createEmptyScheduleItemsState()

    @Test fun scopedToAccount() {
        val alice = createEmptyScheduleItemsState().copy(items = listOf(item()))
        val bob = createEmptyScheduleItemsState().copy(items = listOf(item(id = "22222222-2222-4222-8222-222222222222", title = "Other")))
        assertTrue(write(alice, "alice"))
        assertTrue(write(bob, "bob"))
        assertEquals("Work", read("alice").items[0].title)
        assertEquals("Other", read("bob").items[0].title)
        assertNotEquals(getScheduleItemsStorageKey("alice"), getScheduleItemsStorageKey("bob"))
    }

    // Storage-quota failures are the repository's concern, so they aren't tested here.
    @Test fun rejectsMalformedJson() {
        storage[getScheduleItemsStorageKey("alice")] = "{bad-json"
        assertEquals(createEmptyScheduleItemsState(), read("alice"))
        assertNull(parseScheduleItemsState("{bad-json"))
    }

    @Test fun dropsOnlyInvalidEntries() {
        val goodJson = JsonObject(
            mapOf(
                "id" to JsonPrimitive(ID1), "type" to JsonPrimitive("shift"), "title" to JsonPrimitive("Work"),
                "date" to JsonPrimitive("2026-09-07"), "startTime" to JsonPrimitive("09:00"), "endTime" to JsonPrimitive("12:00"),
                "acknowledgedConflictKeys" to JsonArray(emptyList()), "updatedAt" to JsonNull,
            ),
        )
        val raw = JsonObject(
            mapOf(
                "version" to JsonPrimitive(1),
                "items" to JsonArray(listOf(goodJson, JsonObject(mapOf("id" to JsonPrimitive("not-a-uuid"), "title" to JsonPrimitive(42))))),
                "queue" to JsonArray(
                    listOf(
                        JsonObject(mapOf("kind" to JsonPrimitive("upsert"), "id" to JsonPrimitive(ID1), "item" to goodJson, "baseUpdatedAt" to JsonNull)),
                        JsonObject(mapOf("kind" to JsonPrimitive("mystery"), "id" to JsonPrimitive(ID1))),
                    ),
                ),
            ),
        ).toString()
        val parsed = parseScheduleItemsState(raw)
        assertEquals(listOf(ID1), parsed?.items?.map { it.id })
        assertEquals(1, parsed?.queue?.size)
    }

    @Test fun coalescesRetainingOriginalVersion() {
        val first = item(updatedAt = "2026-09-01T10:00:00.000Z")
        val created = ScheduleItemsState(1, listOf(first), listOf(ScheduleItemMutation("upsert", first.id, first, first.updatedAt)))
        val edited = first.copy(title = "Updated work", updatedAt = "2026-09-03T10:00:00.000Z")
        val next = enqueueScheduleItemUpsert(created, edited, "2026-09-03T10:00:00.000Z")
        assertEquals(1, next.queue.size)
        assertEquals("upsert", next.queue[0].kind)
        assertEquals("2026-09-01T10:00:00.000Z", next.queue[0].baseUpdatedAt)
        assertEquals("Updated work", next.items[0].title)
        assertEquals(true, next.items[0].pendingSync)
    }

    @Test fun cancelsUnsyncedCreate() {
        val created = item()
        val state = enqueueScheduleItemUpsert(createEmptyScheduleItemsState(), created, null)
        val after = enqueueScheduleItemDelete(state, created.id)
        assertEquals(0, after.items.size)
        assertEquals(0, after.queue.size)
    }

    @Test fun retainsQueuedServerDelete() {
        val server = item(updatedAt = "2026-09-01T10:00:00.000Z")
        val state = ScheduleItemsState(1, listOf(server), emptyList())
        val queued = enqueueScheduleItemDelete(state, server.id)
        assertEquals(listOf(ScheduleItemMutation("delete", server.id, null, server.updatedAt)), queued.queue)
        assertEquals(0, removeQueuedScheduleItemMutation(queued, server.id).queue.size)
    }

    @Test fun overlaysPendingChanges() {
        val base = item(updatedAt = "2026-09-01T10:00:00.000Z")
        val local = enqueueScheduleItemUpsert(ScheduleItemsState(1, listOf(base), emptyList()), base.copy(title = "Offline edit"))
        val merged = mergeScheduleItemsState(local, listOf(base.copy(title = "Cloud snapshot")))
        assertEquals("Offline edit", merged.items[0].title)
        assertEquals(true, merged.items[0].pendingSync)
        assertEquals(1, merged.queue.size)
    }

    @Test fun marksSyncConflict() {
        val local = item(pendingSync = true)
        val state = enqueueScheduleItemUpsert(createEmptyScheduleItemsState(), local, null)
        val cloud = item(title = "Remote edit", updatedAt = "2026-09-04T10:00:00.000Z")
        val conflicted = markScheduleItemSyncConflict(state, local.id, cloud, cloud.updatedAt)
        val sc = conflicted.items[0].syncConflict!!
        assertEquals("Remote edit", sc.serverItem?.title)
        assertEquals(cloud.updatedAt, sc.serverUpdatedAt)
        assertEquals(1, conflicted.queue.size)
    }

    @Test fun aQueuedCreateSurvivesARoundTrip() {
        val state = enqueueScheduleItemUpsert(createEmptyScheduleItemsState(), item(), null)
        assertTrue(write(state, "alice"))
        assertEquals(state.queue, read("alice").queue)
    }

    @Test fun aConflictWithADeletedRowSurvivesARoundTrip() {
        val state = enqueueScheduleItemUpsert(createEmptyScheduleItemsState(), item(updatedAt = "v1"), "v1")
        val conflicted = markScheduleItemSyncConflict(state, ID1, null, null)
        assertTrue(write(conflicted, "alice"))
        assertEquals(conflicted.queue, read("alice").queue)
    }

    @Test fun aQueuedDeleteStillNeedsItsVersion() {
        val raw = """{"version":1,"items":[],"queue":[{"kind":"delete","id":"$ID1"}]}"""
        assertEquals(emptyList(), parseScheduleItemsState(raw)?.queue)
    }
}
