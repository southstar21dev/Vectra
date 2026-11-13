package dev.southstar21.vectra.features.transit

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.PlacedVehicle
import dev.southstar21.vectra.core.maps.VehiclePlacementInput
import dev.southstar21.vectra.core.maps.placeVehiclesOnPolyline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.math.max

/** Departures are not fetched below this zoom (the map is too wide to matter). */
const val ExplorerMinArrivalsZoom = 12.0
/** Max stops polled for arrivals per settled viewport. */
private const val MaxArrivalStops = 10
/** Max feed rows. */
private const val MaxFeedItems = 40

/** `{ lat, lon, zoom, bounds }` of the map area the sheet leaves uncovered. */
typealias ExplorerViewport = VisibleViewport

/** The selected route's own stop (`{ id, name, lat, lon }`). */
typealias ExplorerRouteStop = ResolvedStopRef

@Immutable
data class ExplorerSelection(
    val stopId: String,
    val stopName: String,
    val lat: Double?,
    val lon: Double?,
    val routeId: String,
    val shortName: String,
    /** Mode of the latched route, used to drop the selection on filter changes. */
    val routeType: String,
    val headsign: String,
    val color: String,
    val textColor: String,
    /** The exact trip serving this departure — preferred geometry source. */
    val tripId: String? = null,
    /** Physical stop the departure leaves from (its route-stop dot hides under the pulse). */
    val platformStopId: String? = null,
    val distanceMeters: Double,
    val arrivalMs: Long,
    val isPredicted: Boolean,
    /** [tripId]'s service day (YYYYMMDD), for its trip-details. */
    val serviceDate: String? = null,
)

/** How long a departure stays followable after its time, as on the boards. */
private const val DepartedGraceMs = 60_000L

/**
 * [selection] kept in step with its stop's latest departures ([boards]; null while that stop's
 * arrivals aren't loaded, which keeps the latched values): the same trip's current time, else,
 * once that trip has left the feed, the next departure of the same line and direction. Null when
 * the line has nothing left there in that direction.
 */
internal fun refreshExplorerSelection(selection: ExplorerSelection, boards: List<NearbyDeparture>?, now: Long): ExplorerSelection? {
    if (boards == null) return selection
    val upcoming = boards.filter { it.routeId == selection.routeId }
        .flatMap { it.arrivals }
        .filter { it.arrivalMs >= now - DepartedGraceMs }
    val next = upcoming.firstOrNull { !selection.tripId.isNullOrEmpty() && it.tripId == selection.tripId }
        ?: upcoming.filter { it.headSign == selection.headsign }.minByOrNull { it.arrivalMs }
    return next?.let { a ->
        selection.copy(
            tripId = a.tripId.ifEmpty { null },
            arrivalMs = a.arrivalMs,
            isPredicted = a.isPredicted,
            serviceDate = a.serviceDate,
            platformStopId = a.platformStopId ?: selection.platformStopId,
            lat = a.platformLat ?: selection.lat,
            lon = a.platformLon ?: selection.lon,
        )
    }
}

/** Where the selected departure leaves from: its platform, else every platform of its station in [refs]. */
internal fun explorerServingStopIds(selection: ExplorerSelection, refs: Map<String, FutarStop>?): Set<String> =
    selection.platformStopId?.takeIf { it.isNotEmpty() }?.let(::setOf)
        ?: (refs.orEmpty().values.filter { it.parentStationId == selection.stopId }.map { it.id } + selection.stopId).toSet()

/** The selected stop's latched identity — survives leaving the viewport. */
@Immutable
data class ExplorerSelectedStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val platformStopId: String? = null,
)

/** What the nearby explorer screen renders from. */
@Immutable
data class NearbyExplorerState(
    val geoState: WatchLocationState,
    val refreshLocation: () -> Unit,
    val anchor: MapAnchor?,
    val initialCenter: LatLon,
    val viewport: ExplorerViewport?,
    val stopsInView: List<NearbyStop>,
    val visibleStopCount: Int,
    val polledStopCount: Int,
    val mapStops: List<ExplorerMapStop>,
    val focusedStop: NearbyStop?,
    /** The exact physical dot that was pressed. */
    val focusedStopId: String?,
    val focusStop: (stopId: String, stationId: String?) -> Unit,
    val clearFocus: () -> Unit,
    val selection: ExplorerSelection?,
    val selectedStop: ExplorerSelectedStop?,
    val polylines: List<List<LatLon>>,
    val routeStops: List<ExplorerRouteStop>,
    val vehicles: List<PlacedVehicle>,
    val feed: List<RadarDepartureItem>,
    val feedCount: Int,
    /** Every polled stop's soonest departure, nearest first: the map's chips. */
    val chips: List<NearbyChip>,
    val arrivalsEnabled: Boolean,
    val isArrivalsLoading: Boolean,
    val modeFilter: List<String>,
    val handleToggleMode: (String) -> Unit,
    val handleSelectItem: (RadarDepartureItem) -> Unit,
    val handleViewportChange: (ExplorerViewport) -> Unit,
    val clearSelection: () -> Unit,
    val trackedCountdownMinutes: Int?,
)

@Composable
fun rememberNearbyExplorer(repo: TransitRepository, deviceLocation: DeviceLocation, auth: AuthRepository): NearbyExplorerState {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val profile by auth.profile.collectAsStateWithLifecycle()
    // Latest preferences, read when a queued filter write actually runs.
    val preferences by rememberUpdatedState(profile?.preferences)
    val (geoState, refreshLocation) = rememberDeviceLocation(deviceLocation)
    val now = rememberNowMillis(30_000)
    val catalogStops = rememberCatalogStops(repo)
    val catalogRoutes = rememberCatalogRoutes(repo)

    var viewport by remember { mutableStateOf<ExplorerViewport?>(null) }
    // Feed filtering uses the catalog station id; map highlighting the exact physical dot pressed.
    var focusedStopIdState by remember { mutableStateOf<String?>(null) }
    var focusedMapStopId by remember { mutableStateOf<String?>(null) }
    var selectionState by remember { mutableStateOf<ExplorerSelection?>(null) }

    val persistedModeFilter = remember(profile?.preferences) { parseModeFilter(profile?.preferences) }
    // Local-first override: chip taps apply instantly while the profile write is in flight.
    var modeFilterOverride by remember { mutableStateOf<List<String>?>(null) }
    // Released as soon as the persisted filter catches up (never paints stale).
    val overrideLive = modeFilterOverride?.takeUnless { sameModeFilter(it, persistedModeFilter) }
    LaunchedEffect(modeFilterOverride, persistedModeFilter) {
        if (modeFilterOverride != null && overrideLive == null) modeFilterOverride = null
    }
    val modeFilter = overrideLive ?: persistedModeFilter
    val desiredModeFilter = remember { arrayOfNulls<List<String>>(1) }
    LaunchedEffect(overrideLive == null) { if (overrideLive == null) desiredModeFilter[0] = null }
    val writeModeFilter = remember { createModeFilterWriteQueue() }

    // Drop a latched selection whose mode the active filter no longer includes.
    val filteredSelection = selectionState?.takeUnless { sel ->
        modeFilter.isNotEmpty() && sel.routeType.uppercase() !in modeFilter.map { it.uppercase() }.toSet()
    }

    val anchor = remember(geoState.status, geoState.lat, geoState.lon) {
        if (geoState.status == WatchLocationStatus.Tracking) resolveAnchor(geoState.lat, geoState.lon) else null
    }
    val initialCenter = anchor?.let { LatLon(it.lat, it.lon) } ?: BudapestCenter

    val stopsInView = remember(catalogStops.data, viewport) {
        val data = catalogStops.data
        val vp = viewport
        // Every catalog stop inside the bounds; only arrivals polling is capped.
        if (data == null || vp == null) emptyList() else findStopsInBounds(data, vp.bounds, limit = Int.MAX_VALUE)
    }

    val arrivalsEnabled = viewport?.let { it.zoom >= ExplorerMinArrivalsZoom } ?: false
    val arrivalStops = remember(arrivalsEnabled, stopsInView) { if (arrivalsEnabled) stopsInView.take(MaxArrivalStops) else emptyList() }
    val arrivalStopIds = remember(arrivalStops) { arrivalStops.map { it.id } }
    val stopArrivalsResults = rememberBatchStopArrivals(repo, arrivalStopIds)

    val boards = remember(arrivalStops, stopArrivalsResults, catalogRoutes.data) {
        arrivalStops.associate { it.id to parseAllNearbyBoards(stopArrivalsResults[it.id]?.data, catalogRoutes.data) }
    }
    val stopDeparturesMap = remember(boards, now) { boards.mapValues { dropDepartedBoards(it.value, now) } }

    // The selected departure follows later polls: its time moves with the prediction, and once it
    // has left, the next one in the same direction takes over (or the selection clears).
    val selection = remember(filteredSelection, boards, stopArrivalsResults, now) {
        filteredSelection?.let { sel ->
            val loaded = stopArrivalsResults[sel.stopId]?.data != null
            refreshExplorerSelection(sel, if (loaded) boards[sel.stopId] else null, now)
        }
    }
    LaunchedEffect(selection == null && selectionState != null) {
        if (selection == null && selectionState != null) selectionState = null
    }
    // Latch the refreshed values, so they hold once the stop stops being polled.
    LaunchedEffect(selection) {
        // Only over the selection it was derived from, never over a newer tap.
        val source = filteredSelection ?: return@LaunchedEffect
        if (selection != null && selection != source && selectionState === source) selectionState = selection
    }

    // Map dots: every station in view, polled stations' physical stops substituted.
    val mapStops = remember(viewport, stopsInView, arrivalStops, stopArrivalsResults) {
        val vp = viewport ?: return@remember emptyList()
        val arrivalsByStopId = arrivalStops.associate { it.id to stopArrivalsResults[it.id]?.data }
        buildExplorerMapStops(stopsInView, arrivalsByStopId, vp.bounds)
    }

    val feed = remember(arrivalsEnabled, arrivalStops, stopDeparturesMap, now, modeFilter) {
        if (!arrivalsEnabled) emptyList()
        else buildRadarDepartures(stops = arrivalStops, stopDeparturesMap = stopDeparturesMap, now = now, modeFilter = modeFilter, maxItems = MaxFeedItems)
    }

    // Clear the focus when its stop leaves the viewport (never paints an off-screen focus).
    val focusedStopId = focusedStopIdState?.takeIf { id -> stopsInView.any { it.id == id } }
    LaunchedEffect(focusedStopId == null && focusedStopIdState != null) {
        if (focusedStopId == null && focusedStopIdState != null) focusedStopIdState = null
    }

    val chips = remember(feed, stopsInView) { nearbyChips(feed, stopsInView) }
    val visibleFeed = remember(feed, focusedStopId) { if (focusedStopId != null) feed.filter { it.stopId == focusedStopId } else feed }
    val focusedStop = remember(focusedStopId, stopsInView) { focusedStopId?.let { id -> stopsInView.firstOrNull { it.id == id } } }

    val selectedStop = remember(selection) {
        val s = selection ?: return@remember null
        if (s.lat == null || s.lon == null) null
        else ExplorerSelectedStop(s.stopId, s.stopName, s.lat, s.lon, s.platformStopId)
    }

    val latestSelection by rememberUpdatedState(selection)
    val latestStopsInView by rememberUpdatedState(stopsInView)
    val latestFocusedMapStopId by rememberUpdatedState(focusedMapStopId)
    val latestModeFilter by rememberUpdatedState(modeFilter)

    val handleSelectItem: (RadarDepartureItem) -> Unit = remember {
        { item ->
            // Route tracking is a toggle by route: the tracked group again dismisses its line.
            val sel = latestSelection
            if (sel != null && sel.routeId == item.routeId && sel.stopId == item.stopId && sel.headsign == item.headsign) {
                selectionState = null
            } else {
                val stop = latestStopsInView.firstOrNull { it.id == item.stopId }
                selectionState = ExplorerSelection(
                    stopId = item.stopId,
                    stopName = stop?.name ?: item.stopName,
                    // Prefer the departure's own physical stop over the station centre.
                    lat = item.platformLat ?: stop?.lat,
                    lon = item.platformLon ?: stop?.lon,
                    routeId = item.routeId,
                    shortName = item.shortName,
                    routeType = item.routeType,
                    headsign = item.headsign,
                    color = item.color,
                    textColor = item.textColor,
                    tripId = item.tripId,
                    platformStopId = item.platformStopId,
                    distanceMeters = item.distanceMeters,
                    arrivalMs = item.arrivalMs,
                    isPredicted = item.isPredicted,
                    serviceDate = item.serviceDate,
                )
            }
        }
    }

    val focusStop: (String, String?) -> Unit = remember {
        { stopId, stationId ->
            // The same dot toggles its focus off; a different stop dismisses route tracking.
            if (latestFocusedMapStopId == stopId) {
                focusedStopIdState = null
                focusedMapStopId = null
            } else {
                selectionState = null
                focusedStopIdState = stationId ?: stopId
                focusedMapStopId = stopId
            }
        }
    }
    val clearFocus: () -> Unit = remember { { focusedStopIdState = null; focusedMapStopId = null } }
    val clearSelection: () -> Unit = remember { { selectionState = null } }
    val handleViewportChange: (ExplorerViewport) -> Unit = remember { { viewport = it } }

    // Selected route geometry: the departure's own trip first, route details as fallback.
    val routeDetails = repo.routeDetails.collect(selection?.routeId)
    val tripDetails = repo.tripDetails.collect(tripDetailsKey(selection?.tripId, selection?.serviceDate))
    // Without trip details and with an unfamiliar headsign, the variant through the departure's platform.
    val servingStopIds = remember(selection, routeDetails.data) {
        selection?.let { explorerServingStopIds(it, routeDetails.data?.data?.references?.stops) }.orEmpty()
    }
    val polylines = remember(selection, routeDetails.data, tripDetails.data, servingStopIds) {
        if (selection == null) return@remember emptyList<List<LatLon>>()
        val tripPolyline = selectTripPolyline(tripDetails.data)
        if (tripPolyline.isNotEmpty()) return@remember listOf(tripPolyline)
        val variants = routeDetails.data?.data?.entry?.variants.orEmpty()
        // One fallback geometry: a departure identifies one trip/direction.
        val matching = selectVariantPolylines(variants, selection.headsign, selection.routeId, servingStopIds)
        if (matching.isNotEmpty()) listOf(matching[0]) else emptyList()
    }

    val routeStops = remember(selection, routeDetails.data, tripDetails.data, servingStopIds) {
        if (selection == null) return@remember emptyList<ExplorerRouteStop>()
        val tripData = tripDetails.data?.data
        if (!tripData?.entry?.stopTimes.isNullOrEmpty()) {
            val resolved = resolveStopRefs(selectTripStopIds(tripDetails.data), tripData.references?.stops)
            if (resolved.isNotEmpty()) return@remember resolved
        }
        val data = routeDetails.data?.data
        resolveStopRefs(
            selectVariantStopIds(data?.entry?.variants.orEmpty(), selection.headsign, selection.routeId, servingStopIds),
            data?.references?.stops,
        )
    }

    val trackedCountdownMinutes = selection?.let { max(0, ceil((it.arrivalMs - now) / 60_000.0).toInt()) }

    val vehiclesQuery = repo.vehicles.collect(selection?.routeId)
    val vehicles = remember(selection, vehiclesQuery.data, polylines) {
        if (selection == null) return@remember emptyList<PlacedVehicle>()
        val list = vehiclesQuery.data?.data?.list.orEmpty()
        val trips = vehiclesQuery.data?.data?.references?.trips.orEmpty()
        val primary = polylines.firstOrNull().orEmpty()
        val raw = list.mapNotNull { v ->
            val loc = v.location ?: return@mapNotNull null
            if (loc.lat == 0.0 && loc.lon == 0.0) return@mapNotNull null
            Triple(VehiclePlacementInput(v.vehicleId, loc.lat, loc.lon, v.bearing), v.tripId, Unit)
        }
        // The selected departure is the tracked vehicle: its exact trip, else one headsign match.
        var chosen = if (!selection.tripId.isNullOrEmpty()) raw.filter { it.second == selection.tripId }.take(1) else emptyList()
        if (chosen.isEmpty() && selection.headsign.isNotEmpty()) {
            chosen = raw.filter { (_, tripId, _) ->
                val headsign = tripId?.let { trips[it]?.tripHeadsign }
                !headsign.isNullOrEmpty() && headsign == selection.headsign
            }.take(1)
        }
        placeVehiclesOnPolyline(chosen.map { it.first }, primary)
    }

    val handleToggleMode: (String) -> Unit = remember {
        { id ->
            // Explorer chips are single-select; tapping the active mode clears back to ALL.
            val next = selectSingleModeFilter(desiredModeFilter[0] ?: latestModeFilter, id)
            desiredModeFilter[0] = next
            modeFilterOverride = next
            scope.launch {
                try {
                    writeModeFilter(next) { filter ->
                        val prefs = preferences ?: JsonObject(emptyMap())
                        auth.updateProfile(
                            mapOf("preferences" to JsonObject(prefs + ("transit_mode_filter" to JsonArray(filter.map(::JsonPrimitive))))),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    toaster.error("Could not save transit filter", "The filter is applied here but may reset after reload.")
                }
            }
        }
    }

    val isArrivalsLoading = arrivalStops.any { stopArrivalsResults[it.id]?.isLoading == true }

    return NearbyExplorerState(
        geoState = geoState,
        refreshLocation = refreshLocation,
        anchor = anchor,
        initialCenter = initialCenter,
        viewport = viewport,
        stopsInView = stopsInView,
        visibleStopCount = stopsInView.size,
        polledStopCount = arrivalStops.size,
        mapStops = mapStops,
        focusedStop = focusedStop,
        focusedStopId = focusedMapStopId,
        focusStop = focusStop,
        clearFocus = clearFocus,
        selection = selection,
        selectedStop = selectedStop,
        polylines = polylines,
        routeStops = routeStops,
        vehicles = vehicles,
        feed = visibleFeed,
        feedCount = feed.size,
        chips = chips,
        arrivalsEnabled = arrivalsEnabled,
        isArrivalsLoading = isArrivalsLoading,
        modeFilter = modeFilter,
        handleToggleMode = handleToggleMode,
        handleSelectItem = handleSelectItem,
        handleViewportChange = handleViewportChange,
        clearSelection = clearSelection,
        trackedCountdownMinutes = trackedCountdownMinutes,
    )
}
