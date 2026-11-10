package dev.southstar21.vectra.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.platform.Notifier
import dev.southstar21.vectra.core.platform.ReminderChannel
import dev.southstar21.vectra.core.platform.ScheduledNotification

/**
 * Posts system notifications; taps open the app at `url`. Reminders are exact alarms that wake
 * [ReminderReceiver], which fills in live departures before posting them.
 */
class AndroidNotifier(private val context: Context, private val smallIcon: Int, private val launchActivity: Class<*>) : Notifier {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val store = context.getSharedPreferences("vectra.reminders", Context.MODE_PRIVATE)

    init {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL, "Vectra", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    vibrationPattern = longArrayOf(0, 100, 50, 100)
                },
                NotificationChannel(CHANNEL_CLASSES, "Class reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Your next class and its room"
                    vibrationPattern = longArrayOf(0, 100, 50, 100)
                },
                NotificationChannel(CHANNEL_COMMUTE, "Commute", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Departures of your line to uni and back home"
                    vibrationPattern = longArrayOf(0, 100, 50, 100)
                },
            ),
        )
        ReminderRuntime.notifier = this
    }

    override fun permissionGranted(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun show(title: String, body: String, url: String?, tag: String?): Boolean {
        if (!permissionGranted()) return false
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openApp(url, (tag ?: title).hashCode()))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(tag ?: title, 0, n)
        return true
    }

    // ---- reminders --------------------------------------------------------------

    override val resolvesAtFireTime: Boolean get() = true

    override fun replaceScheduled(notifications: List<ScheduledNotification>) {
        store.getStringSet(KEY_IDS, emptySet()).orEmpty().forEach(::cancelAlarm)
        notifications.forEach(::setAlarm)
        store.edit().putStringSet(KEY_IDS, notifications.map { it.id }.toSet()).apply()
    }

    /** [refresh] > 0 re-posts an already shown commute reminder with fresh departures, quietly. */
    internal fun setAlarm(n: ScheduledNotification, refresh: Int = 0, atMs: Long = n.atMs) {
        val intent = alarmIntent(n.id)
            .putExtra(EXTRA_PAYLOAD, VectraJson.encodeToString(ScheduledNotification.serializer(), n))
            .putExtra(ReminderReceiver.EXTRA_REFRESH, refresh)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Exact where allowed (USE_EXACT_ALARM from Android 13); otherwise the system may run it a few minutes late.
        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        }
    }

    internal fun cancelAlarm(id: String) {
        PendingIntent.getBroadcast(context, 0, alarmIntent(id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    /** One intent per id: the data URI makes them distinct PendingIntents. */
    private fun alarmIntent(id: String) = Intent(context, ReminderReceiver::class.java)
        .setAction(ReminderReceiver.ACTION_FIRE)
        .setData(Uri.parse("vectra-reminder://fire/${Uri.encode(id)}"))

    /**
     * Posts a fired reminder. Each channel keeps one notification that the next replaces, so the
     * shade never fills up with old rooms; it disappears once its class has started.
     */
    internal fun post(n: ScheduledNotification, body: String, silent: Boolean) {
        if (!permissionGranted()) return
        val channel = if (n.channel == ReminderChannel.Commute) CHANNEL_COMMUTE else CHANNEL_CLASSES
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(smallIcon)
            .setContentTitle(n.title)
            .setContentText(body.lineSequence().firstOrNull { it.isNotBlank() } ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setContentIntent(openApp(n.url, n.id.hashCode()))
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_DISMISS)
                        .setData(Uri.parse("vectra-reminder://dismiss/${Uri.encode(n.id)}")),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (silent) builder.setSilent(true)
        n.expiresAtMs?.let { builder.setTimeoutAfter((it - System.currentTimeMillis()).coerceAtLeast(60_000)) }
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(channel, 0, builder.build())
    }

    /** Whether [n]'s reminder is still in the shade (tapping it auto-cancels it, swiping dismisses it). */
    internal fun isShowing(n: ScheduledNotification): Boolean {
        val channel = if (n.channel == ReminderChannel.Commute) CHANNEL_COMMUTE else CHANNEL_CLASSES
        return manager.activeNotifications.any { it.tag == channel && it.id == 0 }
    }

    private fun openApp(url: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, launchActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (url != null) putExtra(EXTRA_URL, url)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL = "vectra"
        const val CHANNEL_CLASSES = "class_reminders"
        const val CHANNEL_COMMUTE = "commute"
        const val EXTRA_URL = "vectra.url"
        internal const val EXTRA_PAYLOAD = "vectra.reminder"
        private const val KEY_IDS = "scheduled_ids"
    }
}
