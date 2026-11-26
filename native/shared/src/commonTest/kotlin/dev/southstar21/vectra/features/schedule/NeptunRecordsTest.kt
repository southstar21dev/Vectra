package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.network.string
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeptunRecordsTest {
    private val event = NeptunSyncEvent(
        courseName = "Calculus", courseCode = "MATH1", instructor = "Dr. Kovacs", location = "A105", dayOfWeek = 3,
        startTime = "08:00", endTime = "09:30", color = "#38BDF8", semester = "2026/27/1",
        semesterStart = "2026-09-01", semesterEnd = null, eventType = "lecture",
    )

    private val encrypt: suspend (String?) -> String? = { v -> v?.takeIf { it.isNotEmpty() }?.let { "enc:v1:$it" } ?: v }

    /** What the reader sees after `decryptAll`: the stored ciphertexts, opened. */
    private fun decrypted(rows: List<JsonObject>) =
        rows.flatMap { NeptunColumns.ciphertexts(it) }.map { it?.removePrefix("enc:v1:") }

    @Test fun writerOutputReadsBackIntoTheSameClass() = runTest {
        val row = neptunRecord(event, encrypt)
        val stored = JsonObject(row + ("id" to JsonPrimitive("r1")) + ("user" to JsonPrimitive("u1")))
        val back = adaptNeptunRow(stored, NeptunColumns.clear(decrypted(listOf(stored)), 0))
        assertEquals("Calculus", back.courseName)
        assertEquals("MATH1", back.courseCode)
        assertEquals("Dr. Kovacs", back.instructor)
        assertEquals("A105", back.location)
        assertEquals(3, back.dayOfWeek)
        assertEquals("08:00", back.startTime)
        assertEquals("09:30", back.endTime)
        assertEquals("#38BDF8", back.color)
        assertEquals("2026/27/1", back.semester)
        assertEquals("2026-09-01", back.semesterStart)
        assertEquals(null, back.semesterEnd)
        assertEquals("lecture", back.eventType)
    }

    @Test fun everyEncryptedColumnIsStoredEncryptedAndColorIsNot() = runTest {
        val row = neptunRecord(event, encrypt)
        for (name in NeptunColumns.names) {
            val v = row.string(name)
            assertTrue(v == null || v.startsWith("enc:v1:"), "$name is stored in the clear")
        }
        assertEquals("#38BDF8", row.string("color"))
        assertFalse("color" in NeptunColumns.names)
    }

    @Test fun secondRowOfABatchIsReadFromItsOwnSlice() = runTest {
        val a = neptunRecord(event, encrypt)
        val b = neptunRecord(event.copy(courseName = "Physics", instructor = "Nagy"), encrypt)
        val clear = decrypted(listOf(a, b))
        assertEquals("Physics", adaptNeptunRow(buildJsonObject { put("id", "x") }, NeptunColumns.clear(clear, 1)).courseName)
        assertEquals("Nagy", adaptNeptunRow(b, NeptunColumns.clear(clear, 1)).instructor)
    }
}
