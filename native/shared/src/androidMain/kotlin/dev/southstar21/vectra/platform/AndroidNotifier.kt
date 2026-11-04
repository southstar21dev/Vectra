package dev.southstar21.vectra.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.southstar21.vectra.core.platform.Notifier

/** Posts system notifications on the "Vectra" channel; taps open the app at `url`. */
class AndroidNotifier(private val context: Context, private val smallIcon: Int, private val launchActivity: Class<*>) : Notifier {
    init {
        val channel = NotificationChannel(CHANNEL, "Vectra", NotificationManager.IMPORTANCE_DEFAULT).apply {
            vibrationPattern = longArrayOf(0, 100, 50, 100)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun permissionGranted(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun show(title: String, body: String, url: String?, tag: String?): Boolean {
        if (!permissionGranted()) return false
        val intent = Intent(context, launchActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (url != null) putExtra(EXTRA_URL, url)
        }
        val pending = PendingIntent.getActivity(context, (tag ?: title).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(tag ?: title, 0, n)
        return true
    }

    companion object {
        const val CHANNEL = "vectra"
        const val EXTRA_URL = "vectra.url"
    }
}
