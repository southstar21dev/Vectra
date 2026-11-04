package dev.southstar21.vectra.platform

import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.platform.GeoFix
import dev.southstar21.vectra.core.platform.GeoPermission
import dev.southstar21.vectra.core.platform.Launcher
import dev.southstar21.vectra.core.platform.LocationProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/** NWPathMonitor on the main queue; it reports the current path as soon as it starts. */
class IosConnectivity : Connectivity {
    private val state = MutableStateFlow(true)
    override val online: StateFlow<Boolean> = state
    private val monitor = nw_path_monitor_create()

    init {
        nw_path_monitor_set_update_handler(monitor) { path -> state.value = nw_path_get_status(path) == nw_path_status_satisfied }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }
}

internal fun CLAuthorizationStatus.toGeoPermission(): GeoPermission = when (this) {
    kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> GeoPermission.Granted
    kCLAuthorizationStatusNotDetermined -> GeoPermission.Prompt
    else -> GeoPermission.Denied
}

@OptIn(ExperimentalForeignApi::class)
private fun CLLocation.toFix() = GeoFix(
    lat = coordinate.useContents { latitude },
    lon = coordinate.useContents { longitude },
    accuracy = horizontalAccuracy,
    heading = course.takeIf { it >= 0 },
    speed = speed.takeIf { it >= 0 },
    timeMs = (timestamp.timeIntervalSince1970 * 1000).toLong(),
)

private fun CLLocation.ageMs(): Long = ((NSDate().timeIntervalSince1970 - timestamp.timeIntervalSince1970) * 1000).toLong()

/** Forwards CLLocationManager callbacks; CoreLocation holds its delegate weakly, so owners keep it. */
private class LocationDelegate(
    private val onFix: (CLLocation) -> Unit,
    private val onError: (NSError) -> Unit = {},
) : NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        (didUpdateLocations.lastOrNull() as? CLLocation)?.let(onFix)
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) = onError(didFailWithError)
}

/** CoreLocation, used on the main thread, which is where CLLocationManager delivers to. */
class IosLocationProvider : LocationProvider {
    private val probe by lazy { CLLocationManager() }

    override fun permission(): GeoPermission = probe.authorizationStatus.toGeoPermission()

    /**
     * getCurrentPosition({ maximumAge: 30s, timeout: 10s }): a recent cached fix wins; otherwise one
     * requestLocation. On timeout a fix up to 30 minutes old (the stored-location window) still beats none.
     */
    override suspend fun current(): GeoFix = withContext(Dispatchers.Main) {
        if (permission() != GeoPermission.Granted) throw IllegalStateException("User denied Geolocation")
        probe.location?.takeIf { it.ageMs() <= 30_000 }?.let { return@withContext it.toFix() }
        val manager = CLLocationManager().apply { desiredAccuracy = kCLLocationAccuracyHundredMeters }
        var delegate: LocationDelegate? = null
        val fix = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont: CancellableContinuation<CLLocation?> ->
                delegate = LocationDelegate(
                    onFix = { if (cont.isActive) cont.resume(it) },
                    onError = { if (cont.isActive) cont.resume(null) },
                )
                manager.delegate = delegate
                manager.requestLocation()
                cont.invokeOnCancellation { manager.stopUpdatingLocation() }
            }
        }
        manager.delegate = null
        delegate = null
        fix?.toFix()
            ?: probe.location?.takeIf { it.ageMs() <= 30 * 60_000 }?.toFix()
            ?: throw IllegalStateException("Location timed out")
    }

    override fun watch(): Flow<GeoFix> = callbackFlow {
        if (permission() != GeoPermission.Granted) {
            close(IllegalStateException("User denied Geolocation"))
            return@callbackFlow
        }
        val manager = CLLocationManager()
        val delegate = LocationDelegate(onFix = { trySend(it.toFix()) })
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        // No distance filter: a stationary phone must keep reporting, or the watchdog sees a lost fix.
        manager.distanceFilter = kCLDistanceFilterNone
        manager.startUpdatingLocation()
        awaitClose {
            manager.stopUpdatingLocation()
            manager.delegate = null
            delegate.hashCode()
        }
    }.flowOn(Dispatchers.Main)
}

class IosLauncher : Launcher {
    override fun openUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl, emptyMap<Any?, Any>(), null)
    }

    override fun openAppSettings() = openUrl(UIApplicationOpenSettingsURLString)
}
