package dev.southstar21.vectra.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.southstar21.vectra.platform.IosApp
import dev.southstar21.vectra.platform.toGeoPermission
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberPermissionRequester(kind: PermissionKind): PermissionRequester = remember(kind) {
    when (kind) {
        PermissionKind.Location -> PermissionRequester(locationPrompt::request)
        PermissionKind.Notifications -> PermissionRequester(::requestNotifications)
    }
}

// One for the app: CoreLocation holds its delegate weakly. (Kotlin objects can't subclass NSObject.)
private val locationPrompt by lazy { LocationPrompt() }

/** The when-in-use prompt. iOS shows it once; later asks answer from the stored decision. */
private class LocationPrompt : NSObject(), CLLocationManagerDelegateProtocol {
    private val manager = CLLocationManager().also { it.delegate = this }
    private val waiting = mutableListOf<(Boolean) -> Unit>()

    fun request(onResult: (Boolean) -> Unit) {
        if (manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) {
            onResult(manager.authorizationStatus.toGeoPermission() == GeoPermission.Granted)
            return
        }
        waiting += onResult
        manager.requestWhenInUseAuthorization()
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) return
        val granted = manager.authorizationStatus.toGeoPermission() == GeoPermission.Granted
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(granted) }
    }
}

private fun requestNotifications(onResult: (Boolean) -> Unit) {
    val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
    UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(options) { granted, _ ->
        // Settings reads permissionGranted() right after the answer, so the cache must be current first.
        IosApp.notifier.refresh { dispatch_async(dispatch_get_main_queue()) { onResult(granted) } }
    }
}
