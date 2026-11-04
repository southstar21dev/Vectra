package dev.southstar21.vectra.features.schedule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One weekly class slot (manual or Neptun-synced), already decrypted. Times are
 * Budapest wall clock "HH:MM[:SS]"; day_of_week counts from Sunday (0 = Sunday),
 * as stored in the backend.
 *
 * Shared contract: the schedule, dashboard and course-tracker features all read it.
 */
@Serializable
data class ScheduleEvent(
    val id: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("course_name") val courseName: String = "",
    @SerialName("course_code") val courseCode: String = "",
    val instructor: String = "",
    val location: String = "",
    @SerialName("day_of_week") val dayOfWeek: Int = 0,
    @SerialName("start_time") val startTime: String = "",
    @SerialName("end_time") val endTime: String = "",
    val color: String = "",
    val semester: String = "",
    @SerialName("semester_start") val semesterStart: String? = null,
    @SerialName("semester_end") val semesterEnd: String? = null,
    @SerialName("event_type") val eventType: String = "",
    @SerialName("attendance_type") val attendanceType: String? = null,
    @SerialName("csv_metadata") val csvMetadata: JsonElement? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    /** 'manual' | 'neptun' */
    val source: String? = null,
)
