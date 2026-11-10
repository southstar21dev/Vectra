package dev.southstar21.vectra.features.schedule

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleItemsCodecTest {
    private val item = StoredScheduleItem(
        "11111111-1111-4111-8111-111111111111", "shift", "Work", "2026-09-07", "09:00", "12:00",
        updatedAt = null, pendingSync = true,
    )
    private val event = ScheduleEvent(
        id = "22222222-2222-4222-8222-222222222222", userId = "user-1", courseName = "Seminar", courseCode = "CS101-G01",
        dayOfWeek = 1, startTime = "10:00", endTime = "11:00", color = "#000000", semester = "2026/27/1",
        semesterStart = "2026-09-07", semesterEnd = "2026-12-14", eventType = "class", attendanceType = null,
        csvMetadata = JsonObject(emptyMap()), source = "neptun",
    )

    @Test fun newOverlapStaysUnacknowledged() {
        val queued = enqueueScheduleItemUpsert(createEmptyScheduleItemsState(), item, null)
        val result = reconcileQueuedItemConflicts(queued, listOf(event))
        assertEquals(1, result.queue.size)
        assertEquals("upsert", result.queue[0].kind)
        assertEquals(emptyList(), result.queue[0].item!!.acknowledgedConflictKeys)
        assertEquals(emptyList(), result.items[0].acknowledgedConflictKeys)
    }

    @Test fun noConflictLeavesStateUnchanged() {
        val queued = enqueueScheduleItemUpsert(createEmptyScheduleItemsState(), item.copy(acknowledgedConflictKeys = listOf("prior-conflict")), null)
        assertEquals(queued, reconcileQueuedItemConflicts(queued, emptyList()))
    }

    private val cleartext = """{"type":"shift","title":"Work","date":"2026-09-07","startTime":"09:00","endTime":"12:00","acknowledgedConflictKeys":[]}"""
    private fun row(id: String = "pb15charrecord", clientId: String = "11111111-1111-4111-8111-111111111111", payload: String = "vc2:$cleartext") =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(id), "client_id" to JsonPrimitive(clientId), "payload" to JsonPrimitive(payload),
                "user" to JsonPrimitive("user-1"), "created_at" to JsonPrimitive("2026-09-01 00:00:00.000Z"),
                "updated_at" to JsonPrimitive("2026-09-02 00:00:00.000Z"),
            ),
        )
    private val decrypt: suspend (String?) -> String? = { v -> if (v != null && v.startsWith("vc2:")) v.substring(4) else v }

    @Test fun keysOnClientId() = runTest {
        val d = decodeScheduleItemRow(row(), decrypt)
        assertEquals("11111111-1111-4111-8111-111111111111", d?.id)
        assertEquals("2026-09-02 00:00:00.000Z", d?.updatedAt)
        assertEquals(false, d?.pendingSync)
    }

    @Test fun legacyRowFallsBackToRecordId() = runTest {
        val legacy = "33333333-3333-4333-8333-333333333333"
        assertEquals(legacy, decodeScheduleItemRow(row(id = legacy, clientId = ""), decrypt)?.id)
    }

    @Test fun ignoresPlaintextPayload() = runTest {
        assertNull(decodeScheduleItemRow(row(payload = "plaintext"), decrypt))
    }
}
