package dev.southstar21.vectra.features.weather

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.platform.GeoFix
import dev.southstar21.vectra.core.platform.GeoPermission
import dev.southstar21.vectra.core.platform.LocationProvider
import dev.southstar21.vectra.core.util.formatCompactNumber
import dev.southstar21.vectra.core.util.runSuspendCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock

/** What the weather card renders. */
data class WeatherState(
    val data: WeatherData? = null,
    /** Coordinates [data] was fetched for. */
    val dataCoords: WeatherCoords? = null,
    val fetchedAt: Long = 0,
    val isFetching: Boolean = false,
    val error: Throwable? = null,
)

data class RadarState(
    val data: NormalizedRadarData? = null,
    val fetchedAt: Long = 0,
    val isFetching: Boolean = false,
    val error: Throwable? = null,
)

/**
 * Weather state: the device location
 * setting (persisted per device), the current conditions for it with a
 * persisted snapshot for instant first paint, and RainViewer metadata.
 */
class WeatherRepository(
    private val http: HttpClient,
    private val kv: KeyValueStore,
    private val locationProvider: LocationProvider,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _location = MutableStateFlow<WeatherLocationState?>(null)
    /** Null until the stored mode/snapshot have been read (first frames show the skeleton). */
    val location: StateFlow<WeatherLocationState?> = _location.asStateFlow()

    private val _weather = MutableStateFlow(WeatherState())
    val weather: StateFlow<WeatherState> = _weather.asStateFlow()

    private val _radar = MutableStateFlow(RadarState())
    val radar: StateFlow<RadarState> = _radar.asStateFlow()

    /** Successful geocodes keyed "lat,lon"; failures are not stored so a later read retries. */
    private val cityCache = mutableMapOf<String, String>()
    private var locating = false
    private var weatherJob: Job? = null
    private val snapshotLock = Mutex()

    init {
        scope.launch {
            val mode = parseLocationMode(kv.get(LocationModeKey))
            val snapshot = parseWeatherSnapshot(kv.get(WeatherSnapshotKey))
            val initial = resolveInitialWeatherLocation(mode, snapshot, nowMs())
            // Only a real name is worth seeding; a null must stay a cache miss.
            if (initial.coords != null && initial.city != null) cityCache[cityKey(initial.coords)] = initial.city
            val coords = initial.coords ?: BudapestCoords
            if (snapshot != null && isSnapshotFresh(snapshot, nowMs()) && coordsMatch(snapshot.coords, coords)) {
                _weather.value = WeatherState(snapshot.data, coords, snapshot.fetchedAt)
            }
            _location.value = initial
            probePermission()
        }
        scope.launch {
            _location.filterNotNull().map { it.coords ?: BudapestCoords }.distinctUntilChanged().collect { refreshWeather(it) }
        }
    }

    /** The shared client (the radar map fetches its basemap style with it). */
    val httpClient: HttpClient get() = http

    /** Coordinates the card should show weather for (device fix or the Budapest fallback). */
    val currentCoords: WeatherCoords get() = _location.value?.coords ?: BudapestCoords

    // ---- location ------------------------------------------------------------

    private fun cityKey(c: WeatherCoords) = "${formatCompactNumber(c.latitude)},${formatCompactNumber(c.longitude)}"

    /** Synchronous peek at the geocode cache. */
    fun getCachedCityName(coords: WeatherCoords): String? = cityCache[cityKey(coords)]

    private suspend fun cachedCityName(coords: WeatherCoords): String? {
        cityCache[cityKey(coords)]?.let { return it }
        return fetchCityName(coords)?.also { cityCache[cityKey(coords)] = it }
    }

    /** City name for coordinates; null on any failure or when no name exists. */
    private suspend fun fetchCityName(coords: WeatherCoords): String? = try {
        val res = withTimeout(8_000) {
            http.get(GeocodeUrl) {
                parameter("latitude", formatCompactNumber(coords.latitude))
                parameter("longitude", formatCompactNumber(coords.longitude))
                parameter("localityLanguage", "en")
            }
        }
        if (!res.status.isSuccess()) null else parseGeoCity(VectraJson.parseToJsonElement(res.bodyAsText()))
    } catch (e: CancellationException) {
        if (e is TimeoutCancellationException) null else throw e
    } catch (_: Throwable) {
        null
    }

    private fun applyPosition(fix: GeoFix) {
        // Rounded to ~11 m so reloads reuse the same snapshot.
        val coords = roundCoords(WeatherCoords(fix.lat, fix.lon))
        _location.update { it?.let { prev -> applyPositionState(prev, coords) } }
        scope.launch {
            val city = cachedCityName(coords)
            // A failed geocode is "unknown", not "no name": keep what is on screen.
            if (city != null) {
                updateSnapshotCity(coords, city)
                _location.update { prev ->
                    if (prev?.coords == coords) prev.copy(city = city) else prev
                }
            }
        }
    }

    private suspend fun updateSnapshotCity(coords: WeatherCoords, city: String?) = snapshotLock.withLock {
        val next = snapshotWithCity(parseWeatherSnapshot(kv.get(WeatherSnapshotKey)), coords, city) ?: return@withLock
        runSuspendCatching { kv.set(WeatherSnapshotKey, encodeWeatherSnapshot(next)) }
    }

    private fun geoErrorCode(e: Throwable): Int = when {
        e is TimeoutCancellationException -> GeoErrorCode.Timeout
        e::class.simpleName == "SecurityException" || e.message?.contains("denied", ignoreCase = true) == true -> GeoErrorCode.PermissionDenied
        else -> GeoErrorCode.PositionUnavailable
    }

    private fun readPosition() {
        if (locating) return
        locating = true
        scope.launch {
            try {
                val fix = locationProvider.current()
                locating = false
                applyPosition(fix)
            } catch (e: Throwable) {
                locating = false
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                val code = geoErrorCode(e)
                _location.update { it?.let { prev -> applyErrorState(prev, code) } }
            }
        }
    }

    /** Passive check — never prompts. Runs at start and whenever the app returns to the foreground. */
    private suspend fun probePermission() {
        val mode = parseLocationMode(kv.get(LocationModeKey))
        if (mode == WeatherLocationMode.Budapest) return
        when (runSuspendCatching { locationProvider.permission() }.getOrDefault(GeoPermission.Unsupported)) {
            GeoPermission.Granted -> {
                _location.update { it?.copy(mode = mode, status = WeatherLocationStatus.Locating) }
                readPosition()
            }
            GeoPermission.Denied -> _location.value = WeatherLocationState(mode, null, null, WeatherLocationStatus.Denied)
            // Prompt / Unsupported: keep the Budapest fallback until the user flips the toggle.
            else -> _location.value = WeatherLocationState(mode, null, null, WeatherLocationStatus.Fallback)
        }
    }

    /** Window-focus equivalent: re-check the location setting and refetch stale weather. */
    fun onForeground() {
        scope.launch {
            if (_location.value == null) return@launch
            val mode = parseLocationMode(kv.get(LocationModeKey))
            if (mode == WeatherLocationMode.Budapest) {
                _location.value = WeatherLocationState(mode, null, null, WeatherLocationStatus.Budapest)
            } else {
                probePermission()
            }
            refreshWeather(currentCoords)
        }
    }

    /**
     * The settings toggle. 'auto' is a user gesture: the position is read (the
     * caller requests the OS permission first).
     */
    fun setMode(mode: WeatherLocationMode) {
        scope.launch {
            runSuspendCatching { kv.set(LocationModeKey, mode.stored) }
            if (mode == WeatherLocationMode.Budapest) {
                _location.value = WeatherLocationState(mode, null, null, WeatherLocationStatus.Budapest)
            } else {
                _location.update { (it ?: WeatherLocationState(mode, null, null, WeatherLocationStatus.Idle)).copy(mode = mode, status = WeatherLocationStatus.Locating) }
                readPosition()
            }
        }
    }

    // ---- weather ---------------------------------------------------------------

    /** Pull to refresh: refetches now and returns when the new conditions have landed. */
    suspend fun refreshNow() {
        refreshWeather(force = true)
        weatherJob?.join()
    }

    /**
     * Fetch the conditions for [coords] unless the data on hand is for the same
     * place and younger than Open-Meteo's ~15 min refresh. The previous data
     * stays on screen (placeholder) while a new location loads.
     */
    fun refreshWeather(coords: WeatherCoords = currentCoords, force: Boolean = false) {
        val s = _weather.value
        val sameCoords = s.dataCoords?.let { coordsMatch(it, coords) } == true
        if (!force && sameCoords && s.data != null && nowMs() - s.fetchedAt < StaleMs) return
        if (s.isFetching && sameCoords && weatherJob?.isActive == true) return
        weatherJob?.cancel()
        weatherJob = scope.launch {
            _weather.update { it.copy(isFetching = true) }
            var attempt = 0
            while (true) {
                try {
                    val data = fetchWeather(http, coords)
                    val fetchedAt = nowMs()
                    _weather.value = WeatherState(data, coords, fetchedAt)
                    writeSnapshot(coords, data, fetchedAt)
                    return@launch
                } catch (e: CancellationException) {
                    if (e !is TimeoutCancellationException) throw e
                    if (attempt++ >= 3) { fail(e); return@launch }
                } catch (e: Throwable) {
                    if (attempt++ >= 3) { fail(e); return@launch }
                }
                // Exponential backoff: 1s, 2s, 4s.
                delay(1000L shl (attempt - 1))
            }
        }
    }

    private fun fail(e: Throwable) = _weather.update { it.copy(isFetching = false, error = e) }

    private suspend fun writeSnapshot(coords: WeatherCoords, data: WeatherData, fetchedAt: Long) = snapshotLock.withLock {
        val previous = parseWeatherSnapshot(kv.get(WeatherSnapshotKey))
        // Prefer the snapshot's own city for these coords, else the geocode cache
        // (which may already hold a name that raced this fetch).
        val city = if (previous != null && coordsMatch(previous.coords, coords)) previous.city else getCachedCityName(coords)
        runSuspendCatching { kv.set(WeatherSnapshotKey, encodeWeatherSnapshot(WeatherSnapshot(coords, city, data, fetchedAt))) }
    }

    // ---- radar -------------------------------------------------------------------

    /** RainViewer metadata (stale after 2 min). */
    fun loadRadar(force: Boolean = false) {
        val s = _radar.value
        if (s.isFetching) return
        if (!force && s.data != null && nowMs() - s.fetchedAt < RadarStaleMs) return
        _radar.update { it.copy(isFetching = true, error = if (force) null else it.error) }
        scope.launch {
            try {
                val data = fetchRainViewerData(http)
                _radar.value = RadarState(data, nowMs())
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                _radar.update { it.copy(isFetching = false, error = e) }
            } catch (e: Throwable) {
                _radar.update { it.copy(isFetching = false, error = e) }
            }
        }
    }

    companion object {
        const val StaleMs = 15 * 60_000L
        const val RadarStaleMs = 2 * 60_000L
        const val RadarRefetchMs = 5 * 60_000L
        private const val GeocodeUrl = "https://api.bigdatacloud.net/data/reverse-geocode-client"
    }
}

/** The app-lifetime weather repository. */
