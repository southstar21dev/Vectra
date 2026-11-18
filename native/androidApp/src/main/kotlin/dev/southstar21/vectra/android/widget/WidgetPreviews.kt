package dev.southstar21.vectra.android.widget

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidgetManager
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.features.widgets.widgetSample

// The picker previews: on Android 15+ each widget renders itself from this sample day (light or dark
// with the system); older launchers show the static widget_preview_* images.

/** The picker previews' fixed sample day (Wednesday, 10:05, a class under way). */
internal fun sampleFrame(): WidgetFrame {
    val sample = widgetSample(nowInBudapest().date)
    return WidgetFrame(sample.snapshot, sample.nowMs)
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
        val results = listOf(NextClassReceiver::class, TodayReceiver::class, WeekReceiver::class, AgendaReceiver::class, LockScreenReceiver::class)
            .map { manager.setWidgetPreviews(it) }
        if (results.all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }) {
            prefs.edit { putString(PublishedFor, appVersion) }
        }
    }
}
