package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.network.ClearColumns
import dev.southstar21.vectra.core.network.EncryptedColumns
import dev.southstar21.vectra.core.network.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The encrypted columns of `neptun_schedule_events`: the writer and the reader below both go by these names. */
internal val NeptunColumns = EncryptedColumns(
    listOf(
        "course_name", "course_code", "instructor", "location", "day_of_week", "start_time", "end_time",
        "semester", "semester_start", "semester_end", "event_type",
    ),
)

/** The row the replace endpoint takes for [e]: [NeptunColumns] encrypted, `color` in the clear. */
internal suspend fun neptunRecord(e: NeptunSyncEvent, encrypt: suspend (String?) -> String?): JsonObject {
    val enc = NeptunColumns.encrypt(
        mapOf(
            "course_name" to e.courseName,
            "course_code" to e.courseCode,
            "instructor" to e.instructor,
            "location" to e.location,
            "day_of_week" to e.dayOfWeek.toString(),
            "start_time" to e.startTime,
            "end_time" to e.endTime,
            "semester" to e.semester,
            "semester_start" to e.semesterStart,
            "semester_end" to e.semesterEnd,
            "event_type" to e.eventType,
        ),
        encrypt,
    )
    return buildJsonObject {
        for (name in NeptunColumns.names) put(name, enc.getValue(name))
        put("color", e.color)
    }
}

/** A stored Neptun [row] with its [NeptunColumns] already decrypted into [c]. */
internal fun adaptNeptunRow(row: JsonObject, c: ClearColumns) = ScheduleEvent(
    id = row.string("id").orEmpty(),
    userId = row.string("user").orEmpty(),
    courseName = c["course_name"].orEmpty(),
    courseCode = c["course_code"].orEmpty(),
    instructor = c["instructor"].orEmpty(),
    location = c["location"].orEmpty(),
    // A missing weekday must not become 0 (Sunday); -1 lists it on no day.
    dayOfWeek = c["day_of_week"]?.trim()?.toIntOrNull() ?: -1,
    startTime = c["start_time"].orEmpty(),
    endTime = c["end_time"].orEmpty(),
    color = row.string("color").orEmpty(),
    semester = c["semester"].orEmpty(),
    semesterStart = c["semester_start"]?.ifEmpty { null },
    semesterEnd = c["semester_end"]?.ifEmpty { null },
    eventType = c["event_type"]?.ifEmpty { null } ?: "class",
    attendanceType = null,
    csvMetadata = null,
    createdAt = row.string("created_at").orEmpty(),
    updatedAt = row.string("updated_at").orEmpty(),
    source = "neptun",
)
