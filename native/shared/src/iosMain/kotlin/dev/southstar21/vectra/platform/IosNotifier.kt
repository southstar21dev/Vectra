package dev.southstar21.vectra.platform

import dev.southstar21.vectra.core.platform.Notifier
import dev.southstar21.vectra.core.platform.ScheduledNotification
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.navigation.DeepLinks
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import kotlin.concurrent.Volatile

/**
 * Local notifications through UNUserNotificationCenter; taps open the app at `url`. The
 * authorization check is asynchronous on iOS, so the answer is cached and refreshed on every
 * foregrounding and after each prompt.
 */
class IosNotifier : Notifier {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    @Volatile private var granted = false

    init {
        refresh()
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            refresh()
        }
    }

    /** Re-reads the authorization; [then] runs once the cached answer is current. */
    fun refresh(then: () -> Unit = {}) {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            granted = when (settings?.authorizationStatus) {
                UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral -> true
                else -> false
            }
            then()
        }
    }

    override fun permissionGranted(): Boolean = granted

    override fun show(title: String, body: String, url: String?, tag: String?): Boolean {
        if (!granted) return false
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound)
            if (url != null) setUserInfo(mapOf(URL_KEY to url))
        }
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(tag ?: title, content, null), null)
        return true
    }

    /**
     * Pending requests, in the system's queue until they fire. iOS runs no app code then, so
     * the body is final here (departures were filled in by the scheduler).
     */
    override fun replaceScheduled(notifications: List<ScheduledNotification>) {
        val defaults = NSUserDefaults.standardUserDefaults
        @Suppress("UNCHECKED_CAST")
        val previous = (defaults.stringArrayForKey(IDS_KEY) as? List<String>).orEmpty()
        if (previous.isNotEmpty()) center.removePendingNotificationRequestsWithIdentifiers(previous)
        val now = nowMs()
        val added = notifications.mapNotNull { n ->
            val seconds = (n.atMs - now) / 1000.0
            if (seconds < 1) return@mapNotNull null
            val content = UNMutableNotificationContent().apply {
                setTitle(n.title)
                setBody(n.body)
                setSound(UNNotificationSound.defaultSound)
                setThreadIdentifier(n.channel.name)
                if (n.url != null) setUserInfo(mapOf(URL_KEY to n.url))
            }
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
            center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(n.id, content, trigger), null)
            n.id
        }
        defaults.setObject(added, IDS_KEY)
    }

    companion object {
        const val URL_KEY = "vectra.url"
        private const val IDS_KEY = "vectra.reminders.ids"
    }
}

/**
 * Shows notifications while the app is open and routes taps to [DeepLinks]. Installed before
 * launch finishes so a tap that cold-starts the app still reaches it.
 */
internal fun installNotificationTaps() {
    UNUserNotificationCenter.currentNotificationCenter().delegate = notificationTaps
}

// The center holds its delegate weakly; this keeps it. (Kotlin objects can't subclass NSObject.)
private val notificationTaps by lazy { NotificationTaps() }

private class NotificationTaps : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or UNNotificationPresentationOptionSound)
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        (didReceiveNotificationResponse.notification.request.content.userInfo[IosNotifier.URL_KEY] as? String)
            ?.let { DeepLinks.pending.value = it }
        withCompletionHandler()
    }
}
