package dev.southstar21.vectra.android.widget

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidgetManager
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.features.widgets.WidgetKind
import dev.southstar21.vectra.features.widgets.WidgetSession
import dev.southstar21.vectra.features.widgets.WidgetSnapshot
import kotlin.time.Instant

// The picker previews: on Android 15+ each widget renders itself from this sample day (light or dark
// with the system); older launchers show the static widget_preview_* images.

/** A busy sample day around [nowMs]: a class under way, a seminar, a task and a shift. */
internal fun sampleFrame(nowMs: Long = System.currentTimeMillis()): WidgetFrame {
    val today = nowInBudapest(Instant.fromEpochMilliseconds(nowMs)).ymd
    val minute = 60_000L
    fun session(title: String, location: String, kind: WidgetKind, from: Int, to: Int, color: String = "") =
        WidgetSession(title, location, kind, nowMs + from * minute, nowMs + to * minute, today, color)
    // A class or two on each of the next days, so the week view (next week's at the weekend) has bars.
    val week = (1..9).flatMap { day ->
        val date = nowInBudapest(Instant.fromEpochMilliseconds(nowMs + day * 24 * 60 * minute)).ymd
        val kinds = listOf(WidgetKind.Lecture, WidgetKind.Seminar, WidgetKind.Lab, WidgetKind.Exam).take(1 + day % 3)
        kinds.mapIndexed { i, kind ->
            val start = nowMs + day * 24 * 60 * minute + i * 120 * minute
            WidgetSession("Sample class", "", kind, start, start + 90 * minute, date)
        }
    }
    val snapshot = WidgetSnapshot(
        generatedAtMs = nowMs,
        signedIn = true,
        sessions = listOf(
            session("Business Statistics", "E.338", WidgetKind.Lecture, -140, -50),
            session("Microeconomics", "C.203", WidgetKind.Lecture, -20, 70, "#38bdf8"),
            session("Corporate Finance", "S.304", WidgetKind.Seminar, 100, 190, "#10b981"),
            session("Problem set 3", "", WidgetKind.Task, 230, 231),
        ) + week,
    )
    return WidgetFrame(snapshot, nowMs)
}

internal object WidgetPreviews {
    private const val Prefs = "vectra.widgets"
    private const val PublishedFor = "previewsPublishedFor"

    /**
     * Hands the launcher the generated previews, once per app version: the call is rate-limited, and
     * the previews only change with the app.
     */
    suspend fun publishIfNeeded(context: Context, appVersion: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        if (prefs.getString(PublishedFor, null) == appVersion) return
        val manager = GlanceAppWidgetManager(context)
        val results = listOf(NextClassReceiver::class, TodayReceiver::class, WeekReceiver::class, AgendaReceiver::class)
            .map { manager.setWidgetPreviews(it) }
        if (results.all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }) {
            prefs.edit { putString(PublishedFor, appVersion) }
        }
    }
}
