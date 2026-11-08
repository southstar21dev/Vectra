package dev.southstar21.vectra.android.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import dev.southstar21.vectra.android.VectraApplication
import dev.southstar21.vectra.core.platform.WidgetHost
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.features.widgets.WidgetSnapshot
import dev.southstar21.vectra.features.widgets.decodeWidgetSnapshot
import dev.southstar21.vectra.features.widgets.nextBudapestMidnightMs
import dev.southstar21.vectra.features.widgets.nextRefreshMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Instant

/** What every widget draws from: the snapshot and the moment it's drawn at. */
internal class WidgetFrame(val snapshot: WidgetSnapshot?, val nowMs: Long) {
    val today: String = nowInBudapest(Instant.fromEpochMilliseconds(nowMs)).ymd
}

internal object WidgetStore {
    private const val FileName = "widget_snapshot.json"

    /** The app's snapshot older than this gets rebuilt when a widget redraws without the app open. */
    private const val StaleMs = 3 * 60 * 60_000L

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun file(context: Context) = File(context.filesDir, FileName)

    fun read(context: Context): WidgetSnapshot? =
        runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull().let(::decodeWidgetSnapshot)

    fun frame(context: Context) = WidgetFrame(read(context), System.currentTimeMillis())

    fun write(context: Context, json: String) {
        val target = file(context)
        val tmp = File(target.parentFile, "$FileName.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(target)) target.writeText(json)
    }

    fun isStale(snapshot: WidgetSnapshot?): Boolean =
        snapshot == null || System.currentTimeMillis() - snapshot.generatedAtMs > StaleMs

    private val widgets: List<GlanceAppWidget>
        get() = listOf(NextClassWidget(), TodayWidget(), WeekWidget(), AgendaWidget())

    /** Redraws every placed widget and books the next redraw. */
    suspend fun redraw(context: Context) {
        if (!anyPlaced(context)) return
        widgets.forEach { it.updateAll(context) }
        scheduleNext(context, read(context))
    }

    private fun anyPlaced(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return Receivers.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    /**
     * A non-waking alarm: it fires as soon as the screen is on again, which is the only time a widget
     * is seen, so a countdown never costs a wake-up.
     */
    private fun scheduleNext(context: Context, snapshot: WidgetSnapshot?) {
        val now = System.currentTimeMillis()
        val at = snapshot.nextRefreshMs(now, nextBudapestMidnightMs()).coerceAtLeast(now + 30_000)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.setWindow(AlarmManager.RTC, at, 60_000, refreshIntent(context))
    }

    private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WidgetRefreshReceiver::class.java).setAction(WidgetRefreshReceiver.ActionRefresh),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Rebuilds the snapshot from the app's cached timetable when it's old (a new day, the app not
     * opened in a while), then redraws; otherwise just redraws.
     */
    suspend fun refresh(context: Context) {
        if (isStale(read(context))) {
            val app = context.applicationContext as? VectraApplication
            if (app != null) {
                // publish() writes the file and redraws through AndroidWidgetHost.
                withTimeoutOrNull(8_000) { app.graph.widgets.publishWhenReady(timeoutMs = 7_000) }
                return
            }
        }
        redraw(context)
    }

    private val Receivers = listOf(
        NextClassReceiver::class.java,
        TodayReceiver::class.java,
        WeekReceiver::class.java,
        AgendaReceiver::class.java,
    )
}

/** Hands the app's snapshot to the widgets. */
class AndroidWidgetHost(private val context: Context) : WidgetHost {
    override fun publish(snapshot: String) {
        WidgetStore.write(context, snapshot)
        WidgetStore.scope.launch { runCatching { WidgetStore.redraw(context) } }
    }
}

/** The booked redraw, plus the clock changes and reboots that invalidate it. */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in Actions) return
        val pending = goAsync()
        WidgetStore.scope.launch {
            try {
                withTimeoutOrNull(9_000) { WidgetStore.refresh(context) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ActionRefresh = "dev.southstar21.vectra.WIDGET_REFRESH"
        private val Actions = setOf(
            ActionRefresh,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
        )
    }
}

/** Shared by the four widget receivers: a newly placed widget also books the next redraw. */
abstract class VectraWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetStore.scope.launch { runCatching { WidgetStore.refresh(context) } }
    }
}

class NextClassReceiver : VectraWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextClassWidget()
}

class TodayReceiver : VectraWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

class WeekReceiver : VectraWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekWidget()
}

class AgendaReceiver : VectraWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}
