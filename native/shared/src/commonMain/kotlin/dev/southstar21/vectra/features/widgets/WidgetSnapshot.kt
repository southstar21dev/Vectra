package dev.southstar21.vectra.features.widgets

import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.time.ymd
import dev.southstar21.vectra.features.reminders.ReminderSession
import dev.southstar21.vectra.features.reminders.SessionKind
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the widgets draw from: the sessions from Monday of this week through the next two weeks.
 * iOS decodes the same JSON in Swift (iosApp/VectraWidgets/Model.swift), so keep the two in step.
 */
@Serializable
data class WidgetSnapshot(
    val version: Int = SnapshotVersion,
    val generatedAtMs: Long,
    val signedIn: Boolean,
    val sessions: List<WidgetSession>,
    /** The app is locked until it is updated (the server refused this build); no sessions then. */
    val updateRequired: Boolean = false,
    /** Days after [generatedAtMs]'s day the sessions cover; iOS asks for the app once past it. */
    val aheadDays: Int = SnapshotAheadDays,
    /** The app's 12-hour clock setting; snapshots written before the key existed stay on 24-hour. */
    val hour12: Boolean = false,
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
    /** The teacher, empty when unknown. Added after version 1 shipped, so it is optional. */
    val teacher: String = "",
    /** The class group ("G04"), empty when unknown. Optional like [teacher]. */
    val group: String = "",
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

internal const val SnapshotVersion = 1

internal fun ReminderSession.widgetKind(): WidgetKind = when (kind) {
    SessionKind.Lecture -> WidgetKind.Lecture
    SessionKind.Seminar -> WidgetKind.Seminar
    SessionKind.Lab -> WidgetKind.Lab
    SessionKind.Exam -> WidgetKind.Exam
    SessionKind.Personal -> if (typeLabel == "Shift") WidgetKind.Shift else WidgetKind.Task
    SessionKind.Other -> WidgetKind.Other
}

internal fun buildWidgetSnapshot(sessions: List<ReminderSession>, nowMs: Long, hour12: Boolean = false): WidgetSnapshot =
    WidgetSnapshot(
        generatedAtMs = nowMs,
        signedIn = true,
        hour12 = hour12,
        sessions = sessions.map {
            WidgetSession(it.title, it.location, it.widgetKind(), it.startMs, it.endMs, it.day, it.color, it.teacher, it.group)
        },
    )

internal fun signedOutSnapshot(nowMs: Long, hour12: Boolean = false) =
    WidgetSnapshot(generatedAtMs = nowMs, signedIn = false, sessions = emptyList(), hour12 = hour12)

/** What the widgets hold while the app is locked: nothing from the timetable, only the update prompt. */
internal fun updateRequiredSnapshot(nowMs: Long, hour12: Boolean = false) =
    WidgetSnapshot(generatedAtMs = nowMs, signedIn = false, sessions = emptyList(), updateRequired = true, hour12 = hour12)

internal const val UpdateRequiredWidgetMessage = "Update Vectra to keep using it."

/** What a widget says when its snapshot has run out (same wording as StaleMessage in Model.swift). */
const val StaleWidgetMessage = "Open Vectra to refresh your schedule."

/**
 * False once [today] is past the last day the snapshot covers ([aheadDays] after the day it was
 * generated, in Budapest). It can no longer tell an empty day from one it never saw, so the widgets
 * ask for the app instead of saying "No classes". Same rule as `Snapshot.covers` in Model.swift.
 */
internal fun WidgetSnapshot.covers(today: String): Boolean {
    val generated = nowInBudapest(Instant.fromEpochMilliseconds(generatedAtMs)).date
    val last = generated.plus(DatePeriod(days = aheadDays)).ymd()
    return today <= last
}

/** Signed in, but the snapshot no longer reaches [today]. */
fun WidgetSnapshot?.isStale(today: String): Boolean = this != null && signedIn && !covers(today)

/** A widget's empty-state line: the update prompt while locked, else [signedOut]. */
fun WidgetSnapshot?.emptyMessage(signedOut: String): String =
    if (this?.updateRequired == true) UpdateRequiredWidgetMessage else signedOut

internal fun encodeWidgetSnapshot(snapshot: WidgetSnapshot): String =
    VectraJson.encodeToString(WidgetSnapshot.serializer(), snapshot)

/** Null for a missing, unreadable or newer-format snapshot: the widget then shows its empty state. */
fun decodeWidgetSnapshot(json: String?): WidgetSnapshot? {
    if (json.isNullOrBlank()) return null
    return runCatching { VectraJson.decodeFromString(WidgetSnapshot.serializer(), json) }
        .getOrNull()
        ?.takeIf { it.version == SnapshotVersion }
}
