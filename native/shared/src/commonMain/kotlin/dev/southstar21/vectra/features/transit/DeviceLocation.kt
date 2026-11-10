package dev.southstar21.vectra.features.transit

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.designsystem.components.LocalPageActive
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.haversineMeters
import dev.southstar21.vectra.core.platform.GeoFix
import dev.southstar21.vectra.core.platform.GeoPermission
import dev.southstar21.vectra.core.platform.LocationProvider
import dev.southstar21.vectra.core.platform.PermissionKind
import dev.southstar21.vectra.core.platform.rememberPermissionRequester
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/* The shared continuous device-location watcher. */

enum class WatchLocationStatus { Idle, Locating, Tracking, Error }

@Immutable
data class WatchLocationState(
    val status: WatchLocationStatus,
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
    val heading: Double?,
    val speed: Double?,
    val lastUpdatedMs: Long,
    val isManualRefreshing: Boolean,
    val errorMessage: String?,
    val errorCode: Int?,
)

val InitialWatchLocationState = WatchLocationState(
    status = WatchLocationStatus.Idle,
    lat = 47.4979, // Budapest center fallback while no fix exists
    lon = 19.0402,
    accuracy = 0.0,
    heading = null,
    speed = null,
    lastUpdatedMs = 0,
    isManualRefreshing = false,
    errorMessage = null,
    errorCode = null,
)

val GeoErrorMessages: Map<Int, String> = mapOf(
    1 to "Location permission denied — enable location to find nearby places",
    2 to "Location unavailable right now — try again",
    3 to "Location timed out — try again",
)

const val DeviceLocationStorageKey = "vectra.deviceLocation"

/** A fix older than this is considered lost: the watchdog restarts the watch. */
const val DeviceLocationStaleMs = 30_000L

/** Stored fixes older than this are discarded on load. */
const val StoredLocationMaxAgeMs = 30 * 60_000L

data class PositionFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val heading: Double? = null,
    val speed: Double? = null,
)

/** Pure transition for a successful fix; `lastUpdatedMs` becomes [now], the time the fix counts as taken. */
fun applyPositionSuccess(current: WatchLocationState, coords: PositionFix, now: Long = nowMs()): WatchLocationState =
    WatchLocationState(
        status = WatchLocationStatus.Tracking,
        lat = coords.latitude,
        lon = coords.longitude,
        accuracy = coords.accuracy,
        heading = coords.heading,
        speed = coords.speed,
        lastUpdatedMs = now,
        isManualRefreshing = false,
        errorMessage = null,
        errorCode = null,
    )

/**
 * Pure transition for a position error. A tracking consumer keeps its fix
 * (only the error is recorded); permission denied (code 1) always ends tracking.
 */
fun applyPositionError(current: WatchLocationState, code: Int, message: String? = null): WatchLocationState {
    val text = GeoErrorMessages[code] ?: message?.takeIf { it.isNotEmpty() } ?: "Could not get your location"
    return current.copy(
        status = if (current.status == WatchLocationStatus.Tracking && code != 1) WatchLocationStatus.Tracking else WatchLocationStatus.Error,
        isManualRefreshing = false,
        errorCode = code,
        errorMessage = text,
    )
}

/** A provider's cached fix older than this keeps its own time instead of being stamped as new. */
const val CachedFixAgeMs = 2 * 60_000L

/**
 * The time a fix counts as taken. A last-known fix the provider hands back after a timeout can be
 * many minutes old and must look that old, so it neither resets the watchdog nor is persisted as
 * fresh. Small differences are clock skew between the location provider and the device: those
 * count as now.
 */
fun fixTimeMs(fixTimeMs: Long, now: Long): Long =
    if (fixTimeMs > 0 && now - fixTimeMs > CachedFixAgeMs) fixTimeMs else now

/** True when the last fix is old enough that the watcher must be restarted. */
fun isLocationStale(state: WatchLocationState, now: Long = nowMs(), maxAgeMs: Long = DeviceLocationStaleMs): Boolean {
    if (state.status != WatchLocationStatus.Tracking) return false
    return now - state.lastUpdatedMs > maxAgeMs
}

/** writeStoredLocation's payload: the JSON stored under [DeviceLocationStorageKey], or null (nothing to persist). */
fun storedLocationJson(state: WatchLocationState): String? {
    if (state.status != WatchLocationStatus.Tracking) return null
    return buildJsonObject {
        put("lat", state.lat)
        put("lon", state.lon)
        put("accuracy", state.accuracy)
        put("heading", state.heading)
        put("speed", state.speed)
        put("lastUpdatedMs", state.lastUpdatedMs)
    }.toString()
}

/** readStoredLocation: a recent stored fix as a tracking state; null for stale or garbage payloads. */
fun readStoredLocation(raw: String?, now: Long = nowMs()): WatchLocationState? {
    if (raw.isNullOrEmpty()) return null
    return try {
        val parsed = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        fun num(key: String): Double? {
            val p = parsed[key] as? JsonPrimitive ?: return null
            if (p is JsonNull || p.isString) return null
            return p.doubleOrNull?.takeIf { it.isFinite() }
        }
        val lat = num("lat") ?: return null
        val lon = num("lon") ?: return null
        val last = num("lastUpdatedMs") ?: return null
        // A fix from hours ago (possibly another city) is not "tracking".
        if (now - last.toLong() > StoredLocationMaxAgeMs) return null
        WatchLocationState(
            status = WatchLocationStatus.Tracking,
            lat = lat,
            lon = lon,
            accuracy = num("accuracy") ?: 0.0,
            heading = num("heading"),
            speed = num("speed"),
            lastUpdatedMs = last.toLong(),
            isManualRefreshing = false,
            errorMessage = null,
            errorCode = null,
        )
    } catch (_: Throwable) {
        null
    }
}

private const val WatchdogIntervalMs = 10_000L
private const val RestartCooldownMs = 4_000L
private const val PersistIntervalMs = 60_000L
private const val PersistDistanceMeters = 100.0

/** The stored fix only seeds the next launch: once a minute will do, sooner after a real move. */
internal fun shouldPersistFix(lastAt: Long, lastPosition: LatLon?, now: Long, position: LatLon): Boolean =
    lastPosition == null || now - lastAt >= PersistIntervalMs || haversineMeters(lastPosition, position) > PersistDistanceMeters

private class GeoError(val code: Int, message: String) : Exception(message)

/**
 * One watcher shared by every consumer: seeded from the last persisted fix,
 * kept alive by a watchdog (stale/missed fixes restart the watch), started
 * with the first consumer on screen and stopped with the last one, so nothing
 * tracks behind a hidden tab or a backgrounded app.
 */
class DeviceLocation(
    private val provider: LocationProvider,
    private val kv: KeyValueStore,
    private val scope: CoroutineScope,
    /** Consumers release after a sign-out wiped the stored fix; it must not be written back. */
    private val canPersist: () -> Boolean = { true },
) {
    private val _state = MutableStateFlow(InitialWatchLocationState)
    val state: StateFlow<WatchLocationState> = _state.asStateFlow()

    private var initialized = false
    private var sessionActive = false
    private var watchJob: Job? = null
    private var watchdogJob: Job? = null
    private var refCount = 0
    private var permissionDenied = false
    private var lastWatchStartedAt = 0L
    private var lastPersistedAt = 0L
    private var lastPersisted: LatLon? = null
    private var failedRestartCount = 0

    /** The OS permission prompt (registered by the composable consumers). */
    internal var prompt: ((onResult: (Boolean) -> Unit) -> Unit)? = null
    /** Android would re-prompt on every start, so ask once per process. */
    private var prompted = false
    private var prompting = false

    private fun ensureInitialized() {
        if (initialized) return
        initialized = true
        scope.launch {
            val stored = runSuspendCatching { readStoredLocation(kv.get(DeviceLocationStorageKey)) }.getOrNull()
            // Only seed when nothing fresher arrived meanwhile.
            if (stored != null && _state.value.status != WatchLocationStatus.Tracking) {
                _state.value = stored.copy(isManualRefreshing = _state.value.isManualRefreshing)
            }
        }
    }

    private fun persistThrottled(state: WatchLocationState, now: Long = nowMs()) {
        if (state.status != WatchLocationStatus.Tracking) return
        if (!shouldPersistFix(lastPersistedAt, lastPersisted, now, LatLon(state.lat, state.lon))) return
        persist(state, now)
    }

    private fun persist(state: WatchLocationState, now: Long = nowMs()) {
        lastPersistedAt = now
        lastPersisted = LatLon(state.lat, state.lon)
        if (!canPersist()) return
        val json = storedLocationJson(state) ?: return
        scope.launch { runSuspendCatching { kv.set(DeviceLocationStorageKey, json) } }
    }

    private fun applyFix(fix: GeoFix) {
        val next = applyPositionSuccess(
            _state.value,
            PositionFix(fix.lat, fix.lon, fix.accuracy, fix.heading, fix.speed),
            fixTimeMs(fix.timeMs, nowMs()),
        )
        _state.value = next
        failedRestartCount = 0
        persistThrottled(next)
    }

    private fun applyError(code: Int, message: String?) {
        if (code == 1) {
            // Denied: stop restarting. A resume/manual refresh clears the flag.
            permissionDenied = true
            stopWatch()
        }
        _state.value = applyPositionError(_state.value, code, message)
    }

    private fun normalize(t: Throwable): GeoError = when (t) {
        is GeoError -> t
        is TimeoutCancellationException -> GeoError(3, "Timed out")
        else -> GeoError(if (provider.permission() != GeoPermission.Granted) 1 else 2, t.message ?: "Could not get your location")
    }

    private fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    private fun oneShot() {
        scope.launch {
            try {
                applyFix(provider.current())
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) normalize(e).let { applyError(it.code, it.message) } else throw e
            } catch (t: Throwable) {
                normalize(t).let { applyError(it.code, it.message) }
            }
        }
    }

    private fun startWatch(userInitiated: Boolean = false) {
        if (!sessionActive || watchJob != null || permissionDenied) return
        when (provider.permission()) {
            GeoPermission.Granted -> Unit
            GeoPermission.Unsupported -> {
                applyError(0, "Geolocation is not supported on this device")
                return
            }
            else -> {
                val ask = prompt
                if (ask != null && (userInitiated || !prompted) && !prompting) {
                    prompted = true
                    prompting = true
                    if (_state.value.status != WatchLocationStatus.Tracking) _state.value = _state.value.copy(status = WatchLocationStatus.Locating)
                    ask { granted ->
                        prompting = false
                        if (granted) startWatch() else applyError(1, "User denied Geolocation")
                    }
                } else if (!prompting) {
                    applyError(1, "User denied Geolocation")
                }
                return
            }
        }
        if (_state.value.status != WatchLocationStatus.Tracking) {
            _state.value = _state.value.copy(status = WatchLocationStatus.Locating)
        }
        lastWatchStartedAt = nowMs()
        // Immediate fix to minimize time-to-first-fix, then keep watching.
        oneShot()
        watchJob = scope.launch {
            try {
                provider.watch().collect { applyFix(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                normalize(t).let { applyError(it.code, it.message) }
            }
        }
    }

    private fun restartWatchIfStale() {
        if (!sessionActive || permissionDenied) return
        // A dead watch can also surface as a stuck error state.
        val needsRestart = _state.value.status == WatchLocationStatus.Error || isLocationStale(_state.value)
        if (!needsRestart) return
        val cooldown = RestartCooldownMs * minOf(failedRestartCount + 1, 6)
        if (nowMs() - lastWatchStartedAt < cooldown) return
        failedRestartCount += 1
        stopWatch()
        startWatch()
    }

    /** visibilitychange → visible: re-probe (a permission change in settings is picked up). */
    fun onResume() {
        permissionDenied = false
        if (!sessionActive) return
        if (watchJob == null) startWatch() else restartWatchIfStale()
    }

    private fun startSession() {
        if (sessionActive) return
        sessionActive = true
        permissionDenied = false
        watchdogJob = scope.launch {
            while (true) {
                delay(WatchdogIntervalMs)
                restartWatchIfStale()
            }
        }
        startWatch()
    }

    private fun stopSession() {
        sessionActive = false
        watchdogJob?.cancel()
        watchdogJob = null
        stopWatch()
        // The last fix since the throttle, for the next launch to start from.
        val last = _state.value
        if (last.lastUpdatedMs > lastPersistedAt) persist(last)
    }

    fun refreshLocation() {
        permissionDenied = false
        _state.value = _state.value.copy(isManualRefreshing = true)
        if (watchJob == null) startWatch(userInitiated = true)
        if (provider.permission() == GeoPermission.Granted) oneShot()
    }

    /** Subscribe one consumer; returns its release. */
    fun acquire(): () -> Unit {
        ensureInitialized()
        refCount += 1
        if (refCount == 1) startSession()
        var released = false
        return {
            if (!released) {
                released = true
                refCount -= 1
                if (refCount <= 0) {
                    refCount = 0
                    stopSession()
                }
            }
        }
    }
}

@Immutable
data class DeviceLocationHandle(val state: WatchLocationState, val refreshLocation: () -> Unit)

/**
 * Shared continuous device geolocation. One watcher across
 * all consumers, seeded from the last known fix and kept fresh by the watchdog.
 */
@Composable
fun rememberDeviceLocation(watcher: DeviceLocation): DeviceLocationHandle {
    val requester = rememberPermissionRequester(PermissionKind.Location)
    val latestRequester by rememberUpdatedState(requester)
    // Tab pages stay composed when hidden, so holding the watcher is tied to being on screen.
    // The last fix stays in state, and a returning page's acquire asks for a new one at once.
    val onScreen = LocalPageActive.current
    LifecycleStartEffect(watcher, onScreen) {
        if (!onScreen) return@LifecycleStartEffect onStopOrDispose { }
        val ask: ((Boolean) -> Unit) -> Unit = { cb -> latestRequester.request(cb) }
        watcher.prompt = ask
        val release = watcher.acquire()
        onStopOrDispose {
            if (watcher.prompt === ask) watcher.prompt = null
            release()
        }
    }
    LifecycleResumeEffect(watcher) {
        watcher.onResume()
        onPauseOrDispose { }
    }
    val state by watcher.state.collectAsStateWithLifecycle()
    val refresh = remember(watcher) { { watcher.refreshLocation() } }
    return DeviceLocationHandle(state, refresh)
}
