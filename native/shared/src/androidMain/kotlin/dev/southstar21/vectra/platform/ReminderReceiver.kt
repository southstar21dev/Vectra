package dev.southstar21.vectra.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.platform.ScheduledNotification
import dev.southstar21.vectra.features.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the receiver needs from the app, which builds the graph: set by the Application before
 * any receiver runs, including in a process started just for an alarm.
 */
object ReminderRuntime {
    @Volatile var notifier: AndroidNotifier? = null
    @Volatile var scheduler: (() -> ReminderScheduler)? = null
}

/**
 * Fires reminders (adding live departures first), keeps commute departures fresh for a few
 * minutes, and puts every alarm back after a reboot, a clock change or an app update.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                // A broadcast gets about 10 s; leave room to post whatever is ready.
                withTimeoutOrNull(8_500) { handle(intent) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(intent: Intent) {
        // The scheduler first: asking for it builds the graph, which creates the notifier.
        val scheduler = ReminderRuntime.scheduler?.invoke()
        val notifier = ReminderRuntime.notifier
        if (scheduler == null || notifier == null) return
        when (intent.action) {
            ACTION_FIRE -> fire(intent, scheduler, notifier)
            ACTION_DISMISS -> intent.data?.lastPathSegment?.let(Uri::decode)?.let(notifier::cancelAlarm)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            -> scheduler.rescheduleWhenReady(timeoutMs = 7_000)
        }
    }

    private suspend fun fire(intent: Intent, scheduler: ReminderScheduler, notifier: AndroidNotifier) {
        val n = intent.getStringExtra(AndroidNotifier.EXTRA_PAYLOAD)
            ?.let { runCatching { VectraJson.decodeFromString(ScheduledNotification.serializer(), it) }.getOrNull() } ?: return
        val refresh = intent.getIntExtra(EXTRA_REFRESH, 0)
        // An alarm left behind by an account that has since signed out (or expired) shows nothing, and
        // a refresh only updates a reminder that is still showing: one tapped open or swiped away stays gone.
        if (scheduler.hasSession() && (refresh == 0 || notifier.isShowing(n))) show(n, refresh, scheduler, notifier)
    }

    private suspend fun show(n: ScheduledNotification, refresh: Int, scheduler: ReminderScheduler, notifier: AndroidNotifier) {
        if (n.departures.isEmpty()) {
            notifier.post(n, n.body, silent = false)
        } else {
            val live = scheduler.liveDepartures(n.departures)
            notifier.post(n, n.body + "\n" + live.line, silent = refresh > 0)
            // Refresh the times every minute until the first one has left (at most ten times).
            val first = live.firstMs
            if (first != null && refresh < MaxRefreshes && first > System.currentTimeMillis()) {
                notifier.setAlarm(n, refresh = refresh + 1, atMs = System.currentTimeMillis() + 60_000)
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.southstar21.vectra.REMINDER"
        const val ACTION_DISMISS = "dev.southstar21.vectra.REMINDER_DISMISSED"
        internal const val EXTRA_REFRESH = "vectra.reminder.refresh"
        private const val MaxRefreshes = 10
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
