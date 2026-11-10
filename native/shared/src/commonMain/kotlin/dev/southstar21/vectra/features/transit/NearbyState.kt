package dev.southstar21.vectra.features.transit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.util.roundHalfUpToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Data and loading flag of a once-per-session catalog query. */
@Immutable
data class CatalogQuery<T>(val data: T? = null, val isLoading: Boolean = true)

@Composable
private fun <T> rememberCatalogQuery(repo: TransitRepository, load: suspend TransitCatalog.() -> T): CatalogQuery<T> {
    val state by produceState(CatalogQuery<T>(), repo) {
        val data = loadCatalogUntilLoaded(repo.connectivity.online, { repo.catalog.load() }) { value = CatalogQuery(null, false) }
        value = CatalogQuery(data, false)
    }
    return state
}

/** Longest wait between catalog retries while online. */
internal const val CatalogRetryMaxMs = 60_000L

/**
 * Loads the catalog until it succeeds (the catalog itself memoizes per session). After the
 * second failure [onGaveUp] lets the screens stop showing a spinner, but it keeps trying: with
 * a doubling wait while online (a server hiccup has no reconnect to wait for), never while
 * offline, and straight away when the device reconnects. Tab pages stay composed, so giving up
 * left the nearby stops and explorer dots empty until the process restarted (audit 03 #4).
 */
internal suspend fun <T> loadCatalogUntilLoaded(online: StateFlow<Boolean>, load: suspend () -> T, onGaveUp: () -> Unit): T {
    var failures = 0
    while (true) {
        try {
            return load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            failures++
        }
        if (failures == 2) onGaveUp()
        val wait = minOf(CatalogRetryMaxMs, 1000L shl minOf(failures - 1, 6))
        // A reconnect during the wait ends it early.
        withTimeoutOrNull(wait) {
            online.first { !it }
            online.first { it }
        }
        online.first { it }
    }
}

/** The full BKK stop catalog (fetched once per session). */
@Composable
fun rememberCatalogStops(repo: TransitRepository): CatalogQuery<List<CatalogStop>> =
    rememberCatalogQuery(repo) { stops() }

/** The full BKK stop catalog keyed by id. */
@Composable
fun rememberCatalogStopsById(repo: TransitRepository): CatalogQuery<Map<String, CatalogStop>> =
    rememberCatalogQuery(repo) { stopsById() }

/** The full BKK route catalog keyed by id. */
@Composable
fun rememberCatalogRoutes(repo: TransitRepository): CatalogQuery<Map<String, CatalogRoute>> =
    rememberCatalogQuery(repo) { routeMap() }

@Immutable
data class NearbyStopsAt(val stops: List<NearbyStop>, val loading: Boolean)

/** Nearest stops to the anchor, from the catalog. */
@Composable
fun rememberNearbyStops(repo: TransitRepository, lat: Double, lon: Double): NearbyStopsAt {
    val catalog = rememberCatalogStops(repo)
    val stops = remember(catalog.data, lat, lon) { catalog.data?.let { findNearbyStops(it, lat, lon) }.orEmpty() }
    return NearbyStopsAt(stops, catalog.isLoading)
}

/** About 25 m of latitude; each fix moves the raw position even when the phone is still. */
private const val AnchorCellDegrees = 0.000225

/** Which roughly 25 m cell a position falls in, to re-run position work only after a real move. */
internal fun anchorCell(degrees: Double): Long = roundHalfUpToLong(degrees / AnchorCellDegrees)

/** Where the nearby views stand on your location. */
enum class NearbyLocationStatus { Tracking, Locating, Failed, Off }

@Immutable
data class NearbyStopsState(
    val stops: List<NearbyStop>,
    val position: LatLon?,
    val geoState: WatchLocationState,
    val status: NearbyLocationStatus,
    val refreshLocation: () -> Unit,
)

/**
 * Your position and the catalog stops
 * around it — no departures, so nothing polls. The map strips use this on its
 * own; [rememberNearbyData] builds on it.
 */
@Composable
fun rememberNearbyStops(repo: TransitRepository, deviceLocation: DeviceLocation): NearbyStopsState {
    val (geoState, refreshLocation) = rememberDeviceLocation(deviceLocation)
    val catalogStops = rememberCatalogStops(repo)

    val isTracking = geoState.status == WatchLocationStatus.Tracking
    val anchor = if (isTracking) resolveAnchor(geoState.lat, geoState.lon) else null
    val effectiveLat = anchor?.lat ?: BudapestCenter.lat
    val effectiveLon = anchor?.lon ?: BudapestCenter.lon

    // A linear scan of the catalog, so it runs off Main and only once the anchor changes cell.
    val latestLat by rememberUpdatedState(effectiveLat)
    val latestLon by rememberUpdatedState(effectiveLon)
    val stops by produceState(emptyList<NearbyStop>(), catalogStops.data, anchorCell(effectiveLat), anchorCell(effectiveLon)) {
        val catalog = catalogStops.data ?: return@produceState
        val lat = latestLat
        val lon = latestLon
        value = withContext(Dispatchers.Default) { findNearbyStops(catalog, lat, lon, radiusMeters = 500.0, limit = 16) }
    }
    val anchorLat = anchor?.lat
    val anchorLon = anchor?.lon
    val position = remember(anchorLat, anchorLon) {
        if (anchorLat != null && anchorLon != null) LatLon(anchorLat, anchorLon) else null
    }
    val status = when {
        isTracking && position != null -> NearbyLocationStatus.Tracking
        geoState.status == WatchLocationStatus.Locating -> NearbyLocationStatus.Locating
        geoState.status == WatchLocationStatus.Error -> NearbyLocationStatus.Failed
        else -> NearbyLocationStatus.Off
    }
    return NearbyStopsState(stops, position, geoState, status, refreshLocation)
}

@Immutable
data class NearbyDataState(
    val stops: List<NearbyStop>,
    val position: LatLon?,
    val geoState: WatchLocationState,
    val status: NearbyLocationStatus,
    val refreshLocation: () -> Unit,
    val radarItems: List<RadarDepartureItem>,
)

/**
 * Compact nearby-departures feed for the dashboard transit card.
 * [modeFilter] narrows the departures to the chosen transit modes (empty = all).
 */
@Composable
fun rememberNearbyData(
    repo: TransitRepository,
    deviceLocation: DeviceLocation,
    modeFilter: List<String>? = null,
    /** False keeps the stops but skips the departures poll, for a card that shows none. */
    departures: Boolean = true,
): NearbyDataState {
    val nearby = rememberNearbyStops(repo, deviceLocation)
    val stops = nearby.stops
    // Departures read in whole minutes, so re-deriving them every second would be wasted.
    val now = rememberNowMillis(10_000)
    val catalogRoutes = rememberCatalogRoutes(repo)

    val candidateStopIds = remember(stops, departures) { if (departures) stops.map { it.id } else emptyList() }
    val stopArrivalsResults = rememberBatchStopArrivals(repo, candidateStopIds)

    val boards = remember(stops, stopArrivalsResults, catalogRoutes.data) {
        stops.associate { stop -> stop.id to parseAllNearbyBoards(stopArrivalsResults[stop.id]?.data, catalogRoutes.data) }
    }
    val stopDeparturesMap = remember(boards, now) { boards.mapValues { dropDepartedBoards(it.value, now) } }
    val radarItems = remember(stops, stopDeparturesMap, now, modeFilter) {
        buildRadarDepartures(stops = stops, stopDeparturesMap = stopDeparturesMap, now = now, modeFilter = modeFilter, maxItems = 24)
    }
    return NearbyDataState(nearby.stops, nearby.position, nearby.geoState, nearby.status, nearby.refreshLocation, radarItems)
}
