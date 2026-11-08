package dev.southstar21.vectra.features.widgets

import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.features.reminders.ReminderSession
import dev.southstar21.vectra.features.reminders.SessionKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the widgets draw from: the sessions from Monday of this week through the next two weeks.
 * iOS decodes the same JSON in Swift (iosApp/VectraWidgets/Snapshot.swift), so keep the two in step.
 */
@Serializable
data class WidgetSnapshot(
    val version: Int = SnapshotVersion,
    val generatedAtMs: Long,
    val signedIn: Boolean,
    val sessions: List<WidgetSession>,
)

@Serializable
data class WidgetSession(
    val title: String,
    val location: String,
    val kind: WidgetKind,
    val startMs: Long,
    val endMs: Long,
    /** yyyy-mm-dd in Budapest. */
    val day: String,
    /** The course colour (#rrggbb), empty for personal items and classes without one. */
    val color: String = "",
)

@Serializable
enum class WidgetKind {
    @SerialName("lecture") Lecture,
    @SerialName("seminar") Seminar,
    @SerialName("lab") Lab,
    @SerialName("exam") Exam,
    @SerialName("task") Task,
    @SerialName("shift") Shift,
    @SerialName("other") Other,
    ;

    /** Tasks are deadlines, not time you spend somewhere: they never count as "now" or "next". */
    val isTimed: Boolean get() = this != Task

    val label: String
        get() = when (this) {
            Lecture -> "Lecture"
            Seminar -> "Seminar"
            Lab -> "Lab"
            Exam -> "Exam"
            Task -> "Task"
            Shift -> "Work shift"
            Other -> "Class"
        }
}

const val SnapshotVersion = 1

internal fun ReminderSession.widgetKind(): WidgetKind = when (kind) {
    SessionKind.Lecture -> WidgetKind.Lecture
    SessionKind.Seminar -> WidgetKind.Seminar
    SessionKind.Lab -> WidgetKind.Lab
    SessionKind.Exam -> WidgetKind.Exam
    SessionKind.Personal -> if (typeLabel == "Shift") WidgetKind.Shift else WidgetKind.Task
    SessionKind.Other -> WidgetKind.Other
}

fun buildWidgetSnapshot(sessions: List<ReminderSession>, nowMs: Long): WidgetSnapshot =
    WidgetSnapshot(
        generatedAtMs = nowMs,
        signedIn = true,
        sessions = sessions.map {
            WidgetSession(it.title, it.location, it.widgetKind(), it.startMs, it.endMs, it.day, it.color)
        },
    )

fun signedOutSnapshot(nowMs: Long) = WidgetSnapshot(generatedAtMs = nowMs, signedIn = false, sessions = emptyList())

fun encodeWidgetSnapshot(snapshot: WidgetSnapshot): String =
    VectraJson.encodeToString(WidgetSnapshot.serializer(), snapshot)

/** Null for a missing, unreadable or newer-format snapshot: the widget then shows its empty state. */
fun decodeWidgetSnapshot(json: String?): WidgetSnapshot? {
    if (json.isNullOrBlank()) return null
    return runCatching { VectraJson.decodeFromString(WidgetSnapshot.serializer(), json) }
        .getOrNull()
        ?.takeIf { it.version == SnapshotVersion }
}
