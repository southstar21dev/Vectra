package dev.southstar21.vectra.features.transit

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.data.QueryState
import dev.southstar21.vectra.core.designsystem.components.BarLeading
import dev.southstar21.vectra.core.designsystem.components.FormGroup
import dev.southstar21.vectra.core.designsystem.components.ScreenColumn
import dev.southstar21.vectra.core.designsystem.components.ScrollBody
import dev.southstar21.vectra.core.designsystem.components.SectionTitle
import dev.southstar21.vectra.core.designsystem.components.SurfaceGutter
import dev.southstar21.vectra.core.designsystem.components.TopBar
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.pullUp
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.isValidLatLon
import dev.southstar21.vectra.core.maps.decodePolyline
import dev.southstar21.vectra.core.time.formatBudapestHm
import dev.southstar21.vectra.navigation.LocalNavigator
import dev.southstar21.vectra.navigation.Screen
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** Live-vehicle poll cadence — keep in sync with the repository's vehicles refetch interval. */
private const val VehiclePollMs = 10_000L

private const val MapHeightDp = 264

/** Data landed and the latest fetch did not fail. */
private val QueryState<*>.isSuccess: Boolean get() = data != null && !isError

/** A background fetch with data already present. */
private val QueryState<*>.isRefetching: Boolean get() = isFetching && !isLoading

/**
 * How far the live tracker's last snapshots can be trusted. Offline, or with no fresh data for
 * [ArrivalsStaleMs], the countdowns stop at the snapshot's time instead of running down to "NOW".
 */
internal data class LiveFreshness(
    /** The clock countdowns run against: the arrivals snapshot's time once it is stale. */
    val countdownNow: Long,
    val arrivalsStale: Boolean,
    val vehiclesStale: Boolean,
    val offline: Boolean,
)

internal fun liveFreshness(arrivalsUpdatedAt: Long, vehiclesUpdatedAt: Long, now: Long, offline: Boolean): LiveFreshness {
    // Nothing loaded yet has nothing to freeze.
    val arrivalsStale = arrivalsUpdatedAt > 0 && isArrivalsStale(arrivalsUpdatedAt, now, offline)
    return LiveFreshness(
        countdownNow = if (arrivalsStale) arrivalsStaleNow(arrivalsUpdatedAt, now, offline) else now,
        arrivalsStale = arrivalsStale,
        vehiclesStale = vehiclesUpdatedAt > 0 && isArrivalsStale(vehiclesUpdatedAt, now, offline),
        offline = offline,
    )
}

/** The note over the map's lower edge, or null for none. */
internal fun liveMapNotice(f: LiveFreshness, arrivalsUpdatedAt: Long, arrivalsUnavailable: Boolean, noDepartures: Boolean): String? = when {
    f.arrivalsStale && f.offline -> "Offline · departures as of ${formatBudapestHm(arrivalsUpdatedAt)}"
    f.arrivalsStale -> "Departure times delayed · as of ${formatBudapestHm(arrivalsUpdatedAt)}"
    arrivalsUnavailable -> "Departure times unavailable · showing route"
    noDepartures -> "No upcoming departures right now"
    else -> null
}

/** The live-position line under the cards. */
internal fun liveFooterText(f: LiveFreshness, vehiclesUpdatedAt: Long, loaded: Boolean, refetching: Boolean, secondsToNext: Int?): String = when {
    f.offline -> "Offline · live position paused"
    f.vehiclesStale -> "Live position delayed · last update ${formatBudapestHm(vehiclesUpdatedAt)}"
    !loaded -> "Preparing live position…"
    refetching -> "Updating live position…"
    else -> "Next update in ${secondsToNext ?: "—"}s"
}

/** A trip entry's geometry, or the route-variant stand-in for it. */
private data class GeoEntry(val stopTimes: List<FutarTripStopTime>, val polyline: FutarPolyline?)

private fun FutarRouteVariant.toGeoEntry() = GeoEntry(stopIds.orEmpty().map { FutarTripStopTime(stopId = it) }, polyline)

/** The stop a live view opens on: [preferred] when the tracker has it, else its primary stop. */
internal fun liveStopIdFor(tracker: TransitTracker, preferred: String?): String {
    val stops = getTrackerStops(tracker)
    return preferred?.takeIf { p -> p.isNotEmpty() && stops.any { it.stopId == p } }
        ?: stops.firstOrNull { it.stopId == tracker.stopId }?.stopId?.takeIf { it.isNotEmpty() }
        ?: stops.firstOrNull()?.stopId?.takeIf { it.isNotEmpty() }
        ?: tracker.stopId
}

internal fun liveStopFor(tracker: TransitTracker, stops: List<TrackerStop>, stopId: String): TrackerStop =
    stops.firstOrNull { it.stopId == stopId } ?: stops.firstOrNull() ?: TrackerStop(tracker.stopId, tracker.stopName, tracker.headsign)

/** What the live screen and its full map both draw for one tracker at one stop. */
internal class LiveRoute(
    val arrivalsQuery: QueryState<FutarArrivalsResponse>,
    val vehiclesQuery: QueryState<FutarVehiclesForRouteResponse>,
    val arrivals: List<Arrival>,
    val directions: List<DirectionView>,
    /** [directions] with approximate metro positions and their progress. */
    val enriched: List<DirectionView>,
    val secondaryLines: List<RouteMapSecondaryLine>,
    val leadVehicles: List<RouteMapVehicle>,
    /** Every live vehicle on the route, and on its replacement-bus route while one runs. */
    val routeVehicles: List<FutarVehicle>,
    /** The geometry source failed with nothing to render. */
    val feedError: Boolean,
    val routeColor: String,
)

/**
 * The tracked trips at [activeStop]: one direction per tracked trip (or the route's variants
 * when nothing is departing), with live — or, on the metro, schedule-approximated — vehicles.
 */
@Composable
internal fun rememberLiveRoute(repo: TransitRepository, tracker: TransitTracker?, trackerStops: List<TrackerStop>, activeStop: TrackerStop?, now: Long): LiveRoute {
    val arrivalsQuery = repo.arrivals.collect(activeStop?.stopId?.takeIf { it.isNotEmpty() })
    val vehiclesQuery = repo.vehicles.collect(tracker?.routeId?.takeIf { it.isNotEmpty() })

    // Next arrival per tracked direction → the trips we're following.
    val arrivals = remember(arrivalsQuery.data, tracker, activeStop) {
        if (tracker == null || activeStop == null) emptyList()
        else parseStopArrivals(arrivalsQuery.data, tracker.routeId, activeStop.headsign?.takeIf { it.isNotEmpty() }, shortName = tracker.routeShortName)
    }
    // While replacement buses stand in for the line, their positions come from their own route.
    val replacementQuery = repo.vehicles.collect(remember(arrivals) { arrivals.firstOrNull { it.isReplacementBus }?.routeId })
    val routeVehicles = remember(vehiclesQuery.data, replacementQuery.data) {
        vehiclesQuery.data?.data?.list.orEmpty() + replacementQuery.data?.data?.list.orEmpty()
    }
    val tripIds = remember(arrivals) { trackedTripIds(arrivals) }
    val vehicles = remember(routeVehicles, tripIds) { matchingVehicles(routeVehicles, tripIds) }

    // All directions are tracked; the LEAD one (map emphasis + first card) is
    // the one with a live vehicle, else the soonest arrival.
    val hasTrip = tripIds.isNotEmpty()
    val leadTripId = remember(tripIds, vehicles) {
        if (tripIds.size <= 1) tripIds.firstOrNull()
        else tripIds.firstOrNull { id -> vehicles.any { it.tripId == id } } ?: tripIds[0]
    }
    val otherTripId = if (tripIds.size > 1) tripIds.firstOrNull { it != leadTripId } else null
    val thirdTripId = if (tripIds.size > 2) tripIds.firstOrNull { it != leadTripId && it != otherTripId } else null

    // Geometry: the tracked trips, with the route's variants as the fallback
    // (no departure, trip-details missing, …). Each trip is asked for on its own service day.
    fun tripKey(tripId: String?) = if (hasTrip) tripDetailsKey(tripId, arrivals.firstOrNull { it.tripId == tripId }?.serviceDate) else null
    val tripQueryA = repo.tripDetails.collect(tripKey(leadTripId))
    val tripQueryB = repo.tripDetails.collect(tripKey(otherTripId))
    val tripQueryC = repo.tripDetails.collect(tripKey(thirdTripId))
    val routeQuery = repo.routeDetails.collect(tracker?.routeId?.takeIf { it.isNotEmpty() })
    val stopRefs = routeQuery.data?.data?.references?.stops.orEmpty()

    // Direction-aware fallback geometry for an arrival's headsign.
    fun variantEntryForArrival(arr: Arrival?, fallbackIdx: Int = 0): GeoEntry? {
        val variants = routeQuery.data?.data?.entry?.variants.orEmpty()
        if (variants.isEmpty()) return null
        val refs = stopRefs
        val trackedId = activeStop?.stopId?.takeIf { it.isNotEmpty() } ?: tracker?.stopId?.takeIf { it.isNotEmpty() }
        fun servesTracked(v: FutarRouteVariant): Boolean {
            val ids = v.stopIds.orEmpty()
            return trackedId in ids || ids.any { refs[it]?.parentStationId == trackedId }
        }
        if (arr != null) {
            fun matches(v: FutarRouteVariant) =
                isHeadsignMatch(v.headsign, arr.headSign, arr.tripHeadsign) ||
                    (!v.name.isNullOrEmpty() && isHeadsignMatch(v.name, arr.headSign, arr.tripHeadsign))
            variants.firstOrNull { v -> matches(v) && (trackedId == null || servesTracked(v)) }?.let { return it.toGeoEntry() }
            variants.firstOrNull { matches(it) }?.let { return it.toGeoEntry() }
        }
        if (trackedId != null) {
            val matching = variants.filter(::servesTracked)
            if (matching.isNotEmpty()) return matching[fallbackIdx % matching.size].toGeoEntry()
        }
        return (variants.getOrNull(fallbackIdx % variants.size) ?: variants[0]).toGeoEntry()
    }

    val trackedStopIdsSet = remember(trackerStops) { trackerStops.map { it.stopId }.toSet() }

    // One DirectionView per tracked direction (or the route fallback).
    val directions: List<DirectionView> = remember(
        hasTrip, leadTripId, otherTripId, thirdTripId, tripQueryA.data, tripQueryB.data, tripQueryC.data,
        routeQuery.data, activeStop, vehicles, arrivals, trackedStopIdsSet,
    ) {
        val out = mutableListOf<DirectionView>()
        fun push(entry: GeoEntry?, refs: Map<String, FutarStop>, tripId: String?, dirVehicles: List<RouteMapVehicle>, arrival: Arrival?) {
            val stopTimes = entry?.stopTimes.orEmpty()
            if (stopTimes.isEmpty()) return
            val trackedIndex = resolveTrackedStopIndex(stopTimes.map { it.stopId }, refs, activeStop?.stopId)
            val stops = stopTimes.mapIndexed { i, st ->
                val ref = refs[st.stopId]
                val isThisTracked = st.stopId in trackedStopIdsSet ||
                    (ref?.parentStationId != null && ref.parentStationId in trackedStopIdsSet)
                RouteMapStop(
                    name = ref?.name ?: st.stopHeadsign ?: st.stopId,
                    lat = ref?.lat ?: 0.0,
                    lon = ref?.lon ?: 0.0,
                    isTracked = isThisTracked || i == trackedIndex,
                    isSelected = i == trackedIndex,
                )
            }
            val polyline = entry?.polyline?.points?.takeIf { it.isNotEmpty() }?.let(::decodePolyline).orEmpty()
            out += DirectionView(
                tripId = tripId,
                stops = stops,
                polyline = polyline,
                vehicles = dirVehicles,
                arrival = arrival,
                upcoming = followingArrivals(arrivals, arrival, 3),
                trackedIndex = trackedIndex,
                progress = routeProgress(stops.map { LatLon(it.lat, it.lon) }, polyline, dirVehicles.firstOrNull()?.location, trackedIndex),
                stopTimes = stopTimes,
            )
        }
        // A vehicle assigned to the trip but without a GPS position has nowhere to be drawn: as (0,0)
        // it would project onto a line end and freeze a bogus stops-left count.
        fun vehiclesFor(tripId: String) = vehicles.filter { it.tripId == tripId && isValidLatLon(it.location) }
            .map { RouteMapVehicle(it.vehicleId, it.location!!, bearing = it.bearing) }
        fun tripEntry(q: QueryState<FutarTripDetailsResponse>): GeoEntry? =
            q.data?.data?.entry?.let { GeoEntry(it.stopTimes.orEmpty(), it.polyline) }?.takeIf { it.stopTimes.isNotEmpty() }

        if (hasTrip) {
            listOf(Triple(leadTripId, tripQueryA, 0), Triple(otherTripId, tripQueryB, 1), Triple(thirdTripId, tripQueryC, 2))
                .forEach { (id, q, idx) ->
                    if (id == null) return@forEach
                    val arr = arrivals.firstOrNull { it.tripId == id }
                    push(
                        tripEntry(q) ?: variantEntryForArrival(arr, idx),
                        q.data?.data?.references?.stops ?: stopRefs,
                        id,
                        vehiclesFor(id),
                        arr,
                    )
                }
        } else {
            val variants = routeQuery.data?.data?.entry?.variants.orEmpty()
            if (variants.size > 1) {
                for (idx in 0 until minOf(2, variants.size)) push(variants[idx].toGeoEntry(), stopRefs, null, emptyList(), null)
            } else {
                push(variantEntryForArrival(null, 0), stopRefs, null, emptyList(), null)
            }
        }
        out
    }

    // Approximate metro positions on a 10s clock (the vehicles poll cadence).
    val approxNowMs = now / 10_000 * 10_000
    val enriched = remember(directions, tracker?.routeType, approxNowMs) {
        directions.map { d ->
            val location = if (tracker?.routeType == "SUBWAY" && d.vehicles.isEmpty() && d.polyline.size >= 2) {
                approximateVehiclePosition(ApproximateVehicleInput(d.stopTimes, d.polyline, approxNowMs, 0))
            } else null
            val approxVehicle = location?.let { RouteMapVehicle(d.tripId ?: "approx", it, isApproximate = true) }
            d.copy(
                approxVehicle = approxVehicle,
                progress = routeProgress(
                    d.stops.map { LatLon(it.lat, it.lon) },
                    d.polyline,
                    approxVehicle?.location ?: d.vehicles.firstOrNull()?.location,
                    d.trackedIndex,
                ),
            )
        }
    }

    // Every direction beyond the lead, with all its vehicles.
    val secondaryLines = remember(enriched) {
        if (enriched.size < 2) emptyList()
        else enriched.drop(1).map { d ->
            RouteMapSecondaryLine(
                stops = d.stops,
                polyline = if (d.polyline.size >= 2) d.polyline else enriched[0].polyline,
                vehicles = d.approxVehicle?.let { d.vehicles + it } ?: d.vehicles,
            )
        }
    }

    val leadVehicles = remember(enriched) {
        enriched.firstOrNull()?.let { d -> d.approxVehicle?.let { d.vehicles + it } ?: d.vehicles }.orEmpty()
    }
    val feedError = (arrivalsQuery.isError && directions.isEmpty()) ||
        ((if (hasTrip) tripQueryA.isError else routeQuery.isError) && directions.isEmpty())
    return LiveRoute(
        arrivalsQuery = arrivalsQuery,
        vehiclesQuery = vehiclesQuery,
        arrivals = arrivals,
        directions = directions,
        enriched = enriched,
        secondaryLines = secondaryLines,
        leadVehicles = leadVehicles,
        routeVehicles = routeVehicles,
        feedError = feedError,
        routeColor = tracker?.routeColor?.takeIf { it.isNotEmpty() } ?: "3b82f6",
    )
}

/**
 * The live view of one tracker: alerts, the route map with live (or schedule-approximated)
 * vehicles, one departure card per tracked direction with its stops-to-you progress, and
 * the full stop lists on demand. The map expands into [TrackerMapScreen].
 */
@Composable
fun LiveTrackerScreen(repo: TransitRepository, stopPicks: TrackerStopPicks, trackerId: String, stopId: String?, onBack: () -> Unit) {
    val navigator = LocalNavigator.current
    val now = rememberNowMillis(1000)
    var scrolled by remember { mutableStateOf(false) }
    val tracker = rememberTracker(repo, trackerId, onMissing = onBack)

    val trackerStops = remember(tracker) { tracker?.let(::getTrackerStops) ?: emptyList() }
    var pickedStopId by rememberSaveable { mutableStateOf(stopId) }
    // A stop picked on the full map counts as soon as this screen is back on top.
    val mapPick = stopPicks.peek(trackerId)
    LaunchedEffect(mapPick) {
        if (mapPick != null) pickedStopId = stopPicks.take(trackerId)
    }
    val activeStopId = tracker?.let { liveStopIdFor(it, mapPick ?: pickedStopId) }.orEmpty()
    val activeStop = remember(tracker, trackerStops, activeStopId) { tracker?.let { liveStopFor(it, trackerStops, activeStopId) } }

    val live = rememberLiveRoute(repo, tracker, trackerStops, activeStop, now)
    val arrivalsQuery = live.arrivalsQuery
    val vehiclesQuery = live.vehiclesQuery
    val arrivals = live.arrivals
    val directions = live.directions
    val enriched = live.enriched
    val secondaryLines = live.secondaryLines
    val leadVehicles = live.leadVehicles
    val feedError = live.feedError
    val routeColor = live.routeColor

    // Route-id → short-name fallback for alert matching: references.routes
    // covers the common case; the runtime catalog fills the gaps.
    val catalogMap by produceState<Map<String, CatalogRoute>?>(null, repo) {
        value = try {
            repo.catalog.routeMap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    // Disruption alerts attached to this stop (embedded in the arrivals feed).
    val alerts = remember(arrivalsQuery.data, now / 60_000, tracker, activeStop, catalogMap) {
        if (tracker == null || activeStop == null) emptyList()
        else {
            val cm = catalogMap
            parseAlerts(
                arrivalsQuery.data,
                AlertContext(tracker.routeId, tracker.routeShortName, tracker.routeType, activeStop.stopId),
                now,
                cm?.let { m -> { id: String -> m[id]?.shortName } },
                cm?.let { m -> { id: String -> m[id]?.type } },
            )
        }
    }

    // Freeze stopsLeft per direction while the screen is up: the numbers stay
    // static during a visit (keyed by tracker + stop), filled once.
    val frozenKey = tracker?.let { "${it.id}:${activeStop?.stopId.orEmpty()}" }
    val frozenStops = remember(frozenKey) { HashMap<String, Int>() }
    if (tracker != null) {
        enriched.forEachIndexed { i, d ->
            val k = d.tripId ?: "dir-$i"
            val left = d.progress.stopsLeft
            if (k !in frozenStops && left != null) frozenStops[k] = left
        }
    }

    // "Show all stops" is opt-in.
    var showAll by rememberSaveable { mutableStateOf(false) }
    var mapReady by remember { mutableStateOf(false) }

    val noDepartures = arrivalsQuery.isSuccess && arrivals.isEmpty()
    val arrivalsUnavailable = arrivalsQuery.isError
    // Offline, LiveQuery pauses without an error: without this the countdowns ran down to "NOW".
    val freshness = liveFreshness(arrivalsQuery.dataUpdatedAt, vehiclesQuery.dataUpdatedAt, now, !rememberOnline(repo.connectivity))
    val mapNotice = liveMapNotice(freshness, arrivalsQuery.dataUpdatedAt, arrivalsUnavailable, noDepartures)
    val loading = !feedError && directions.isEmpty()
    val routeEmoji = routeTypeMeta(tracker?.routeType).emoji

    // Countdown to the next live-vehicle poll.
    val secondsToNextUpdate = vehiclesQuery.dataUpdatedAt.takeIf { it > 0 }?.let { updated ->
        max(0, ceil((updated + VehiclePollMs - now) / 1000.0).toInt())
    }

    val t = tracker
    if (t == null) {
        // Still loading the trackers: an empty screen with a way out.
        ScreenColumn { TopBar("", BarLeading.Back, onBack) }
        return
    }
    val c = Vectra.colors
    val stopName = activeStop?.stopName?.takeIf { it.isNotEmpty() } ?: t.stopName
    ScreenColumn {
        TopBar(
            title = stopName,
            leading = BarLeading.Back,
            onLeading = onBack,
            scrolled = scrolled,
            titleContent = { LiveTrackerTitle(t, stopName) },
            subtitle = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val headsign = activeStop?.headsign
                    if (!headsign.isNullOrEmpty()) {
                        VIcon(RemixIcons.ArrowRightLine, size = 12.dp, tint = c.textMuted)
                        VText(headsign, Modifier.weight(1f, fill = false), maxLines = 1)
                    } else {
                        VIcon(RemixIcons.ArrowLeftRightLine, size = 12.dp, tint = c.textMuted)
                        VText("All directions", maxLines = 1)
                    }
                }
            },
            accessory = if (trackerStops.size > 1) {
                { TrackedStopTabs(trackerStops, activeStop?.stopId ?: activeStopId, { pickedStopId = it }) }
            } else null,
        )

        // No stretch overscroll: the map is a separate surface the stretch cannot
        // distort, so it flashed while the rest of the screen stretched.
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            ScrollBody(onScrolledChange = { scrolled = it }) {
                // Service alerts lead, above the map.
                AlertsSection(alerts, Modifier.padding(start = SurfaceGutter, end = SurfaceGutter, bottom = 12.dp))

                // The route with the live vehicles on it; the departures card rests
                // on its lower edge.
                val mapShape = Radius.Card
                Box(
                    Modifier
                        .padding(horizontal = SurfaceGutter)
                        .fillMaxWidth()
                        .height(MapHeightDp.dp)
                        .clip(mapShape)
                        .background(c.surfaceOverlay, mapShape)
                        .border(1.dp, c.borderSubtle, mapShape),
                ) {
                    if (feedError) {
                        Column(
                            Modifier.fillMaxSize().background(c.surfaceOverlay).padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                        ) {
                            VIcon(RemixIcons.WifiOffLine, size = 20.dp, tint = c.tints.red.text.copy(alpha = 0.7f))
                            VText("Live feed unavailable", style = vtext(TypeScale.Body, Weight.Semibold), color = c.textSecondary)
                            VText("Retrying automatically…", style = vtext(TypeScale.Caption), color = c.textMuted)
                        }
                    } else {
                        RouteMap(
                            stops = enriched.firstOrNull()?.stops.orEmpty(),
                            polyline = enriched.firstOrNull()?.polyline.orEmpty(),
                            vehicles = leadVehicles,
                            secondaryLines = secondaryLines,
                            routeColor = routeColor,
                            height = MapHeightDp.dp,
                            onFullscreen = { navigator.push(Screen.TrackerMap(trackerId, activeStop?.stopId)) },
                            onReady = { mapReady = true },
                            onLoadError = { mapReady = true },
                        )
                    }
                    LoadingOverlay(show = !feedError && (loading || !mapReady), label = "Loading route…")
                    // Fades the map into the screen behind the card.
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.5f to c.surfaceSecondary.copy(alpha = 0.4f),
                                    1f to c.surfaceSecondary.copy(alpha = 0.9f),
                                ),
                            ),
                    )
                    if (!feedError && mapNotice != null) {
                        // Clear of the departures card, which overlaps the map's lower 48dp.
                        val pill = CircleShape
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)) {
                            VText(
                                mapNotice,
                                Modifier
                                    .boxShadow(
                                        pill,
                                        listOf(
                                            BoxShadow(y = 10f, blur = 15f, spread = -3f, color = Color.Black.copy(alpha = 0.1f)),
                                            BoxShadow(y = 4f, blur = 6f, spread = -4f, color = Color.Black.copy(alpha = 0.1f)),
                                        ),
                                    )
                                    .background(Color.Black.copy(alpha = 0.75f), pill)
                                    .border(1.dp, Palette.Amber400.copy(alpha = 0.3f), pill)
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                vtext(TypeScale.Caption, Weight.Semibold),
                                Palette.Amber300,
                            )
                        }
                    }
                }

                // The cards ride up over the map's lower edge.
                Column(
                    Modifier
                        .pullUp(48.dp)
                        .padding(start = SurfaceGutter, end = SurfaceGutter, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (directions.isNotEmpty()) {
                        Box(Modifier.padding(horizontal = 8.dp)) {
                            // Haze behind the lead card so it melts into the map.
                            Haze(c.surfaceSecondary.copy(alpha = 0.7f))
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                enriched.forEachIndexed { i, d ->
                                    key(d.tripId ?: "route-$i") {
                                        val fk = d.tripId ?: "dir-$i"
                                        DirectionStrip(
                                            direction = d,
                                            routeName = t.routeShortName,
                                            routeId = t.routeId,
                                            routeColor = routeColor,
                                            routeTextColor = t.routeTextColor,
                                            now = freshness.countdownNow,
                                            frozen = freshness.arrivalsStale,
                                            accent = if (i == 0) DirectionAccent.Emerald else DirectionAccent.Violet,
                                            lead = i == 0,
                                            frozenStopsLeft = frozenStops[fk],
                                            hasFrozenStopsLeft = fk in frozenStops,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (directions.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle("Stops", action = {
                                Box(
                                    Modifier
                                        .height(36.dp)
                                        .background(c.surfaceOverlay, CircleShape)
                                        .tappable(CircleShape, pressedScale = 0.95f, haptic = { tick() }) { showAll = !showAll }
                                        .padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    VText(
                                        when {
                                            showAll -> "Hide"
                                            directions.size == 1 -> "Show all ${directions[0].stops.size}"
                                            else -> "Show all"
                                        },
                                        style = vtext(TypeScale.Body, Weight.Bold),
                                        color = c.textPrimary,
                                    )
                                }
                            })
                            if (showAll) {
                                FormGroup(
                                    Modifier.clip(Radius.Card),
                                    rows = enriched.mapIndexed { i, d ->
                                        @Composable {
                                            Column {
                                                if (enriched.size > 1) {
                                                    VText(
                                                        "Towards ${d.arrival?.headSign?.takeIf { it.isNotEmpty() } ?: d.stops.lastOrNull()?.name.orEmpty()}",
                                                        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                                                        vtext(TypeScale.Caption, Weight.Semibold),
                                                        c.textSecondary,
                                                    )
                                                }
                                                DirectionStopList(
                                                    direction = d,
                                                    routeEmoji = routeEmoji,
                                                    routeName = t.routeShortName,
                                                    routeId = t.routeId,
                                                    accent = if (i == 0) DirectionAccent.Emerald else DirectionAccent.Violet,
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SpinningRefreshIcon(spinning = vehiclesQuery.isRefetching)
                        VText(
                            liveFooterText(freshness, vehiclesQuery.dataUpdatedAt, vehiclesQuery.isSuccess, vehiclesQuery.isRefetching, secondsToNextUpdate),
                            style = vtext(TypeScale.Caption),
                            color = c.textMuted,
                        )
                    }
                }
            }
        }
    }
}

/** A route badge beside the stop name: the live screens' bar title. */
@Composable
internal fun LiveTrackerTitle(tracker: TransitTracker, stopName: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RouteBadge(formatRouteDisplayName(tracker.routeShortName, tracker.routeId), tracker.routeColor, tracker.routeTextColor, size = RouteBadgeSize.Sm)
        VText(stopName, Modifier.weight(1f, fill = false), maxLines = 1)
    }
}

/** A soft blurred wash that overhangs its parent and takes no layout space. */
@Composable
private fun Haze(color: Color) {
    Box(
        Modifier
            .layout { measurable, constraints ->
                val extra = 16.dp.roundToPx()
                val w = (if (constraints.hasBoundedWidth) constraints.maxWidth else 0) + extra * 2
                val p = measurable.measure(Constraints.fixed(w, 144.dp.roundToPx()))
                layout(0, 0) { p.place(-extra, -20.dp.roundToPx()) }
            }
            .blur(40.dp, BlurredEdgeTreatment.Unbounded)
            .background(color, RoundedCornerShape(40.dp)),
    )
}

/** A refresh glyph, spinning once a second while a refetch is in flight. */
@Composable
private fun SpinningRefreshIcon(spinning: Boolean) {
    val tint = Vectra.colors.textMuted
    if (!spinning) {
        VIcon(RemixIcons.RefreshLine, size = 14.dp, tint = tint)
        return
    }
    val t = rememberInfiniteTransition(label = "refreshSpin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "refreshAngle")
    VIcon(RemixIcons.RefreshLine, Modifier.graphicsLayer { rotationZ = angle }, size = 14.dp, tint = tint)
}
