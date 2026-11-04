package dev.southstar21.vectra.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.platform.GeoFix
import dev.southstar21.vectra.core.platform.GeoPermission
import dev.southstar21.vectra.core.platform.Launcher
import dev.southstar21.vectra.core.platform.LocationProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AndroidConnectivity(context: Context) : Connectivity {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(isConnected())
    override val online: StateFlow<Boolean> = state

    init {
        // The default network's callback: onLost means no network is left. activeNetwork can't
        // tell inside onLost, it still names the network being lost.
        cm.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { state.value = true }
                override fun onLost(network: Network) { state.value = false }
            },
        )
    }

    private fun isConnected(): Boolean =
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

class AndroidLocationProvider(private val context: Context) : LocationProvider {
    private val lm = context.getSystemService(LocationManager::class.java)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    override fun permission(): GeoPermission = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> GeoPermission.Granted
        else -> GeoPermission.Prompt
    }

    private fun watchProvider(): String = when {
        lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> LocationManager.GPS_PROVIDER
    }

    private fun Location.toFix() = GeoFix(
        lat = latitude,
        lon = longitude,
        accuracy = accuracy.toDouble(),
        heading = if (hasBearing()) bearing.toDouble() else null,
        speed = if (hasSpeed()) speed.toDouble() else null,
        timeMs = time,
    )

    private fun enabledProviders(): List<String> =
        listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    @SuppressLint("MissingPermission")
    private fun freshestKnown(maxAgeMs: Long): Location? = enabledProviders()
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time <= maxAgeMs }
        .maxByOrNull { it.time }

    /**
     * getCurrentPosition({ maximumAge: 30s, timeout: 10s }): a recent cached fix wins;
     * otherwise every enabled provider is asked at once and the first answer is used.
     * On timeout a fix up to 30 minutes old (the stored-location window) still beats
     * no location at all.
     */
    @SuppressLint("MissingPermission")
    override suspend fun current(): GeoFix {
        if (permission() != GeoPermission.Granted) throw SecurityException("User denied Geolocation")
        freshestKnown(30_000)?.let { return it.toFix() }
        val providers = enabledProviders().ifEmpty { throw IllegalStateException("Position unavailable") }
        val fix = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                providers.forEach { p ->
                    if (Build.VERSION.SDK_INT >= 30) {
                        lm.getCurrentLocation(p, signal, context.mainExecutor) { loc ->
                            if (loc != null && cont.isActive) { signal.cancel(); cont.resume(loc.toFix()) }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(p, { loc -> if (cont.isActive) cont.resume(loc.toFix()) }, Looper.getMainLooper())
                    }
                }
            }
        }
        return fix ?: freshestKnown(30 * 60_000)?.toFix() ?: throw IllegalStateException("Location timed out")
    }

    @SuppressLint("MissingPermission")
    override fun watch(): Flow<GeoFix> = callbackFlow {
        if (permission() != GeoPermission.Granted) {
            close(SecurityException("User denied Geolocation"))
            return@callbackFlow
        }
        val listener = LocationListener { trySend(it.toFix()) }
        // No distance filter: a stationary phone must keep reporting, or the watchdog sees a lost fix.
        lm.requestLocationUpdates(watchProvider(), 5_000L, 0f, listener, Looper.getMainLooper())
        awaitClose { lm.removeUpdates(listener) }
    }
}

class AndroidLauncher(private val context: Context) : Launcher {
    override fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openAppSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
