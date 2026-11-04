package dev.southstar21.vectra.features.transit

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.southstar21.vectra.core.designsystem.components.Toaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A stop the By stop flow can open: a curated quick pick or any catalog stop. */
internal data class PickedStop(val id: String, val name: String, val group: String?)

/** The pane on screen, derived from the picks so far; the bar's title and back button follow it. */
internal sealed interface AddTrackerPane {
    val key: String

    /** Slide direction follows depth: deeper panes enter from the right. */
    val depth: Int

    data object Browse : AddTrackerPane {
        override val key get() = "browse"
        override val depth get() = 0
    }

    data class Metro(val group: MetroView) : AddTrackerPane {
        override val key get() = "metro-${group.key}"
        override val depth get() = 1
    }

    /** One opened from a metro list sits a level deeper than one from search, so Back lands on the list. */
    data class Stop(val stop: PickedStop, val fromMetro: Boolean) : AddTrackerPane {
        override val key get() = "stop-${stop.id}"
        override val depth get() = if (fromMetro) 2 else 1
    }

    data class Line(val route: FutarRoute) : AddTrackerPane {
        override val key get() = "line-${route.id}"
        override val depth get() = 1
    }
}

private fun plural(n: Int) = if (n == 1) "" else "s"

internal fun stopCountLabel(n: Int) = "$n stop${plural(n)}"

/** Everything the add-tracker flow holds across its panes, and what those panes can do. */
@Stable
internal class AddTrackerState(
    private val repo: TransitRepository,
    private val scope: CoroutineScope,
    private val toaster: Toaster,
    val draft: TrackerDraftState,
) {
    var searchQuery by mutableStateOf("")
    var metroView by mutableStateOf<MetroView?>(null)
    var browseMode by mutableStateOf(BrowseMode.Line)
    private var selectedStopId by mutableStateOf<String?>(null)
    var selectedRouteId by mutableStateOf<String?>(null)
    var selectedHeadsign by mutableStateOf("")

    // Routes + full arrivals for the selected stop (BY STOP mode)
    var routes by mutableStateOf<List<FutarRoute>?>(null)
        private set
    private var arrivalsData by mutableStateOf<FutarArrivalsResponse?>(null)
    var loadingRoutes by mutableStateOf(false)
        private set
    var routesError by mutableStateOf<String?>(null)
        private set

    // Lazy-loaded catalog
    var catalogRoutes by mutableStateOf<List<CatalogRoute>?>(null)
        private set
    private var catalogStops by mutableStateOf<List<CatalogStop>?>(null)
    var catalogError by mutableStateOf(false)
        private set
    var catalogAttempt by mutableIntStateOf(0)
        private set
    val catalogLoaded get() = catalogRoutes != null && catalogStops != null

    // BY LINE browser state
    var routeQuery by mutableStateOf("")
    var routeTypeFilter by mutableStateOf("ALL")
    var stopQuery by mutableStateOf("")
    private var browseRoute by mutableStateOf<FutarRoute?>(null)
    var filteredRoutes by mutableStateOf<List<FutarRoute>?>(null)
    var loadingRouteStops by mutableStateOf(false)
        private set
    var routeStopsError by mutableStateOf<String?>(null)
        private set

    private var addPending by mutableStateOf(false)
    private var updatePending by mutableStateOf(false)
    val isSaving get() = addPending || updatePending

    // Guards against fast switching: a slower response for one pick must never
    // overwrite what the newer pick already shows.
    private var routeFetchSeq = 0

    private val selectedStop by derivedStateOf {
        val id = selectedStopId
        if (id == null) null
        else CuratedStops.firstOrNull { it.id == id }?.let { PickedStop(it.id, it.name, it.group) }
            // Any catalog stop picked from search opens the same pane as a quick pick.
            ?: catalogStops?.firstOrNull { it.id == id }?.let { PickedStop(it.id, it.name, null) }
    }

    private val fallbackRoute by derivedStateOf { selectedStop?.let { fallbackRouteFor(it.id) } }

    val selectedRoute: FutarRoute? by derivedStateOf {
        routes.orEmpty().firstOrNull { it.id == selectedRouteId }
            ?: fallbackRoute?.takeIf { selectedRouteId == it.id }
    }

    val directions by derivedStateOf {
        val route = selectedRoute
        val data = arrivalsData
        if (route == null || data == null) {
            emptyList()
        } else {
            val map = LinkedHashMap<String, MutableList<Arrival>>()
            for (a in parseStopArrivals(data, route.id)) map.getOrPut(a.headSign.ifEmpty { "—" }) { mutableListOf() }.add(a)
            map.entries.map { StopDirection(it.key, it.value) }.sortedBy { it.times[0].arrivalMs }
        }
    }

    val filteredStops by derivedStateOf {
        val q = normalizeSearchText(searchQuery.trim())
        if (q.isEmpty()) emptyList()
        else catalogStops.orEmpty().filter { normalizeSearchText(it.name).contains(q) }.take(80)
    }

    val filteredRouteStops by derivedStateOf {
        val q = normalizeSearchText(stopQuery.trim())
        if (q.isEmpty()) draft.routeStops.orEmpty()
        else draft.routeStops.orEmpty().filter { normalizeSearchText(it.name).contains(q) }
    }

    val pane: AddTrackerPane by derivedStateOf {
        val route = browseRoute
        val stop = selectedStop
        val group = metroView
        when {
            route != null -> AddTrackerPane.Line(route)
            stop != null -> AddTrackerPane.Stop(stop, fromMetro = group != null)
            group != null -> AddTrackerPane.Metro(group)
            else -> AddTrackerPane.Browse
        }
    }

    suspend fun loadCatalog() {
        try {
            val r = repo.catalog.routes()
            val s = repo.catalog.stops()
            catalogRoutes = r
            catalogStops = s
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            catalogError = true
        }
    }

    fun retryCatalog() {
        catalogError = false
        catalogAttempt++
    }

    private fun fallbackRouteFor(stopId: String): FutarRoute? {
        val stop = CuratedStops.firstOrNull { it.id == stopId }
        if (stop == null || stop.group == "POPULAR") return null
        val line = stop.group
        val routeId = MetroRouteIds[line] ?: return null
        val colors = MetroFallbackColorsByLine.getValue(line)
        return FutarRoute(id = routeId, shortName = line, description = "", type = "SUBWAY", color = colors.color, textColor = colors.textColor)
    }

    fun selectStop(stopId: String) {
        selectedStopId = stopId
        val seq = ++routeFetchSeq
        loadingRoutes = true
        routesError = null
        routes = null
        arrivalsData = null
        selectedRouteId = null
        selectedHeadsign = ""
        scope.launch {
            try {
                val res = repo.api.fetchStopArrivals(stopId, 90)
                if (seq != routeFetchSeq) return@launch
                arrivalsData = res
                var routeList = res.data?.references?.routes?.values?.toList().orEmpty()
                val fallback = fallbackRouteFor(stopId)
                if (fallback != null && routeList.none { it.id == fallback.id }) routeList = routeList + fallback
                routes = sortRoutes(routeList)
                if (routeList.isEmpty()) throw IllegalStateException("No routes returned for this stop")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (seq == routeFetchSeq) routesError = trackerErrorMessage(e, "Failed to load routes")
            } finally {
                if (seq == routeFetchSeq) loadingRoutes = false
            }
        }
    }

    /** Pick a line in the By line browser: load its stops and pre-tick the ones already tracked. */
    fun selectRoute(route: FutarRoute, existing: List<TransitTracker>) {
        val seq = ++routeFetchSeq
        browseRoute = route
        draft.clearRoute()
        routeStopsError = null
        stopQuery = ""
        loadingRouteStops = true

        val existingTracker = existing.firstOrNull { isRouteMatch(it.routeId, route.id) }
        draft.stops = existingTracker?.let { getTrackerStops(it) } ?: emptyList()

        scope.launch {
            try {
                val res = repo.api.fetchCombinedRouteDetails(route.id)
                if (seq != routeFetchSeq) return@launch
                val details = parseRouteDetails(res)
                draft.showRoute(details)
                if (details.stops.isEmpty()) throw IllegalStateException("No stops returned for this line")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (seq == routeFetchSeq) routeStopsError = trackerErrorMessage(e, "Failed to load stops")
            } finally {
                if (seq == routeFetchSeq) loadingRouteStops = false
            }
        }
    }

    fun toggleLineStop(stop: FutarStop) {
        if (draft.has(stop.id)) {
            draft.remove(stop.id)
        } else if (!draft.add(stop)) {
            toaster.warning("Maximum 4 stops", "You can track up to $MaxTrackerStops stops on a line.")
        }
    }

    /** Back out of [pane] to the one before it. */
    fun back(pane: AddTrackerPane) {
        when (pane) {
            is AddTrackerPane.Line -> {
                browseRoute = null
                draft.routeStops = null
                draft.stops = emptyList()
            }
            is AddTrackerPane.Stop -> {
                selectedStopId = null
                selectedRouteId = null
                routes = null
                arrivalsData = null
            }
            is AddTrackerPane.Metro -> metroView = null
            AddTrackerPane.Browse -> Unit
        }
    }

    private suspend fun addTracker(route: FutarRoute, stops: List<TrackerStop>) {
        addPending = true
        try {
            repo.addTracker(
                NewTrackerInput(
                    routeId = route.id,
                    routeShortName = route.shortName,
                    routeLongName = route.description,
                    routeType = route.type,
                    routeColor = route.color.ifEmpty { "FFFFFF" },
                    routeTextColor = route.textColor.ifEmpty { "000000" },
                    stops = stops,
                ),
            )
        } finally {
            addPending = false
        }
    }

    private suspend fun updateStops(trackerId: String, stops: List<TrackerStop>) {
        updatePending = true
        try {
            repo.updateTrackerStops(trackerId, stops)
        } finally {
            updatePending = false
        }
    }

    /** Save from the line pane. */
    fun saveLine(route: FutarRoute, existing: List<TransitTracker>, onSaved: () -> Unit) {
        val stops = draft.stops
        if (stops.isEmpty()) return
        val existingTracker = existing.firstOrNull { isRouteMatch(it.routeId, route.id) }
        scope.launch {
            try {
                if (existingTracker != null) {
                    updateStops(existingTracker.id, stops)
                    toaster.success("Line tracker updated", "Updated ${route.shortName} with ${stopCountLabel(stops.size)}.")
                } else {
                    addTracker(route, stops)
                    toaster.success("Line tracker added", "Tracking ${stopCountLabel(stops.size)} on ${route.shortName}.")
                }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toaster.error("Failed to save tracker", trackerErrorMessage(e))
            }
        }
    }

    /** Save from the stop pane. */
    fun saveStop(stop: PickedStop, existing: List<TransitTracker>, onSaved: () -> Unit) {
        val route = selectedRoute ?: return
        val headsign = selectedHeadsign
        val existingForRoute = existing.firstOrNull { isRouteMatch(it.routeId, route.id) }
        scope.launch {
            try {
                if (existingForRoute != null) {
                    val existingStops = getTrackerStops(existingForRoute)
                    val alreadyTracked = existingStops.any { it.stopId == stop.id }
                    if (!alreadyTracked && existingStops.size >= MaxTrackerStops) {
                        toaster.error(
                            "Limit reached (4 stops)",
                            "This line tracker already has $MaxTrackerStops stops. Manage stops to replace or edit them.",
                        )
                        return@launch
                    }
                    updateStops(existingForRoute.id, addOrUpdateTrackerStop(existingStops, TrackerStop(stop.id, stop.name, headsign)))
                    toaster.success("Added to existing line tracker", "Added ${stop.name} to ${route.shortName}.")
                } else {
                    addTracker(route, listOf(TrackerStop(stop.id, stop.name, headsign)))
                    toaster.success(
                        "Tracker added",
                        "${route.shortName} at ${stop.name}${if (headsign.isNotEmpty()) " towards $headsign" else ""}.",
                    )
                }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toaster.error("Failed to add tracker", trackerErrorMessage(e))
            }
        }
    }
}
