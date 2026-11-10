package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.haversineMeters
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.core.util.roundHalfUp
import dev.southstar21.vectra.core.util.toFixed
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** A catalog stop with its straight-line distance from the anchor (meters). */
data class NearbyStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Double,
)

fun CatalogStop.withDistance(distanceMeters: Double) = NearbyStop(id, name, lat, lon, distanceMeters)

/** One route's departure board at a stop. */
data class NearbyDeparture(
    val routeId: String,
    val shortName: String,
    val routeType: String,
    val color: String,
    val textColor: String,
    val arrivals: List<Arrival>,
)

enum class MapAnchorLabel(val value: String) { You("you"), BudapestCenter("budapest-center") }

/** Where the Near Me sheet anchors, after the outside-Hungary check. */
data class MapAnchor(
    val lat: Double,
    val lon: Double,
    val label: MapAnchorLabel,
    val outsideHungary: Boolean,
)

/** Budapest center — the fallback anchor when the user is outside Hungary. */
val BudapestCenter = LatLon(47.4979, 19.0402)

/** Approximate Hungary border (clockwise from Rajka, NW). */
private val HungaryPolygon = listOf(
    LatLon(47.99, 17.2), // Rajka (NW)
    LatLon(47.87, 17.27), // Mosonmagyaróvár
    LatLon(47.68, 16.59), // Sopron
    LatLon(46.87, 16.1), // Felsőszölnök
    LatLon(46.43, 16.73), // Letenye
    LatLon(45.96, 17.46), // Barcs
    LatLon(45.95, 18.95), // Hercegszántó
    LatLon(46.25, 20.15), // Szeged
    LatLon(46.65, 21.28), // Gyula
    LatLon(48.16, 22.55), // Beregsurány
    LatLon(48.41, 22.17), // Záhony
    LatLon(48.54, 21.46), // Füzér
    LatLon(48.4, 21.66), // Sátoraljaújhely
    LatLon(48.53, 21.1), // north of Szendrő
    LatLon(48.47, 20.7), // Aggtelek
    LatLon(48.43, 20.5), // Bánréve
    LatLon(48.4, 20.3), // Ózd
    LatLon(48.33, 20.1), // Somoskőújfalu
    LatLon(48.25, 19.7), // Ipolytarnóc
    LatLon(48.17, 19.5), // Szécsény
    LatLon(48.08, 19.3), // Balassagyarmat
    LatLon(48.02, 19.2), // Diósjenő
    LatLon(47.88, 19.0), // Ipolydamásd
    LatLon(47.82, 18.87), // Szob
    LatLon(47.79, 18.74), // Esztergom
    LatLon(47.74, 18.11), // Komárom
)

/** Ray-casting point-in-polygon against the Hungary border. */
fun isInHungary(lat: Double, lon: Double): Boolean {
    var inside = false
    var j = HungaryPolygon.size - 1
    for (i in HungaryPolygon.indices) {
        val a = HungaryPolygon[i]
        val b = HungaryPolygon[j]
        val crosses = (a.lon > lon) != (b.lon > lon) &&
            lat < (b.lat - a.lat) * (lon - a.lon) / (b.lon - a.lon) + a.lat
        if (crosses) inside = !inside
        j = i
    }
    return inside
}

/** Anchor for the Near Me sheet: the user's location in Hungary, else Budapest. */
fun resolveAnchor(lat: Double, lon: Double): MapAnchor =
    if (isInHungary(lat, lon)) MapAnchor(lat, lon, MapAnchorLabel.You, false)
    else MapAnchor(BudapestCenter.lat, BudapestCenter.lon, MapAnchorLabel.BudapestCenter, true)

/** Full BKK stop catalog (memoized per session by [TransitCatalog]). */
suspend fun getCatalogStops(catalog: TransitCatalog): List<CatalogStop> = catalog.stops()

/** Per-route direction index (ordered stop names per variant). */
suspend fun getRouteVariants(catalog: TransitCatalog): Map<String, List<CatalogRouteVariant>> = catalog.routeVariants()

/** Route catalog keyed by id. */
suspend fun getCatalogRouteMap(catalog: TransitCatalog): Map<String, CatalogRoute> = catalog.routeMap()

/** Nearest N catalog stops within the radius, sorted by distance. */
fun findNearbyStops(
    stops: List<CatalogStop>,
    lat: Double,
    lon: Double,
    radiusMeters: Double = 800.0,
    limit: Int = 6,
): List<NearbyStop> {
    val anchor = LatLon(lat, lon)
    val out = mutableListOf<NearbyStop>()
    for (s in stops) {
        val d = haversineMeters(anchor, LatLon(s.lat, s.lon))
        if (d <= radiusMeters) out += s.withDistance(d)
    }
    return out.sortedBy { it.distanceMeters }.take(limit)
}

/** A geographic bounding box (WGS84). */
data class LatLonBounds(val north: Double, val south: Double, val east: Double, val west: Double)

/** A coordinate as returned by a map `unproject` (uses the `lng` key). */
data class UnprojectedPoint(val lat: Double, val lng: Double)

/** Viewport derived from the map rectangle the sheet does not cover. */
data class VisibleViewport(val lat: Double, val lon: Double, val zoom: Double, val bounds: LatLonBounds)

/**
 * Viewport for the map area left uncovered by the departures sheet: bounds are the
 * min/max of the uncovered corners (padded to [MinViewSpanMeters]), center is the uncovered center.
 */
fun viewportFromUncoveredRect(
    topLeft: UnprojectedPoint,
    bottomRight: UnprojectedPoint,
    center: UnprojectedPoint,
    zoom: Double,
): VisibleViewport {
    val raw = LatLonBounds(
        north = max(topLeft.lat, bottomRight.lat),
        south = min(topLeft.lat, bottomRight.lat),
        east = max(topLeft.lng, bottomRight.lng),
        west = min(topLeft.lng, bottomRight.lng),
    )
    return VisibleViewport(center.lat, center.lng, zoom, padBoundsToMinSpan(raw, MinViewSpanMeters))
}

private const val MetersPerDegreeLat = 111_320.0

/** High-zoom floor for the uncovered-rect bounds. */
const val MinViewSpanMeters = 500.0

/** Expand `bounds` around its center until each axis spans at least `minSpanMeters`. */
fun padBoundsToMinSpan(bounds: LatLonBounds, minSpanMeters: Double): LatLonBounds {
    val (north, south, east, west) = bounds
    if (!minSpanMeters.isFinite() || minSpanMeters <= 0 || !north.isFinite() || !south.isFinite() ||
        !east.isFinite() || !west.isFinite()
    ) return bounds
    val centerLat = (north + south) / 2
    val cosLat = cos(centerLat * PI / 180)
    val metersPerDegreeLon = MetersPerDegreeLat * max(cosLat, 1e-6)
    val latSpanMeters = (north - south) * MetersPerDegreeLat
    val lonSpanMeters = (east - west) * metersPerDegreeLon
    val padLat = max(0.0, minSpanMeters - latSpanMeters) / 2 / MetersPerDegreeLat
    val padLon = max(0.0, minSpanMeters - lonSpanMeters) / 2 / metersPerDegreeLon
    return LatLonBounds(north + padLat, south - padLat, east + padLon, west - padLon)
}

/** Clamp the sheet's reported coverage to 0..80% of the map canvas height. */
fun clampBottomInset(insetPx: Double, canvasHeightPx: Double): Double {
    if (!insetPx.isFinite() || !canvasHeightPx.isFinite() || canvasHeightPx <= 0) return 0.0
    return max(0.0, min(insetPx, canvasHeightPx * 0.8))
}

/** Catalog stops inside a bounding box, nearest to the box center first. */
fun findStopsInBounds(stops: List<CatalogStop>, bounds: LatLonBounds, limit: Int = 60): List<NearbyStop> {
    val center = LatLon((bounds.north + bounds.south) / 2, (bounds.east + bounds.west) / 2)
    val out = mutableListOf<NearbyStop>()
    for (s in stops) {
        if (s.lat < bounds.south || s.lat > bounds.north) continue
        if (s.lon < bounds.west || s.lon > bounds.east) continue
        out += s.withDistance(haversineMeters(center, LatLon(s.lat, s.lon)))
    }
    return out.sortedBy { it.distanceMeters }.take(limit)
}

/**
 * A catalog station dot (or a platform dot replacing it) on the explorer map.
 * `stationId`/`isPlatform` are set only for platform dots.
 */
data class ExplorerMapStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Double,
    val stationId: String? = null,
    val isPlatform: Boolean? = null,
)

fun NearbyStop.toExplorerMapStop() = ExplorerMapStop(id, name, lat, lon, distanceMeters)

/** Two physical stops closer than this are treated as the same map dot. */
const val PlatformDotDedupeMeters = 5.0

/**
 * Merge the viewport's catalog dots with the physical stops each polled station reports
 * in its arrivals response (`references.stops`).
 */
fun buildExplorerMapStops(
    stops: List<NearbyStop>,
    arrivalsByStopId: Map<String, FutarArrivalsResponse?>,
    bounds: LatLonBounds,
    dedupeMeters: Double = PlatformDotDedupeMeters,
): List<ExplorerMapStop> {
    val out = mutableListOf<ExplorerMapStop>()
    for (stop in stops) {
        val refs = arrivalsByStopId[stop.id]?.data?.references?.stops
        if (refs == null) {
            out += stop.toExplorerMapStop()
            continue
        }
        val platforms = mutableListOf<ExplorerMapStop>()
        val seenIds = HashSet<String>()
        for ((key, ref) in refs) {
            val id = ref.id.ifEmpty { key }
            if (id.isEmpty() || id == stop.id || id in seenIds) continue
            if (!ref.lat.isFinite() || !ref.lon.isFinite()) continue
            if (ref.lat == 0.0 && ref.lon == 0.0) continue
            if (ref.lat < bounds.south || ref.lat > bounds.north) continue
            if (ref.lon < bounds.west || ref.lon > bounds.east) continue
            val refPt = LatLon(ref.lat, ref.lon)
            if (platforms.any { haversineMeters(LatLon(it.lat, it.lon), refPt) <= dedupeMeters }) continue
            seenIds += id
            platforms += ExplorerMapStop(
                id = id,
                name = ref.name.ifEmpty { stop.name },
                lat = ref.lat,
                lon = ref.lon,
                distanceMeters = stop.distanceMeters,
                stationId = stop.id,
                isPlatform = true,
            )
        }
        if (platforms.isNotEmpty()) out += platforms else out += stop.toExplorerMapStop()
    }
    return out
}

/** Route-board order used when grouping boards at an individual stop. */
private val BoardTypePriority = listOf("SUBWAY", "TRAM", "TROLLEYBUS", "BUS", "RAIL", "FERRY")

/** Departure-sheet order: trams first, then metro, bus, trolley, etc. */
private val RadarModePriority = listOf("TRAM", "SUBWAY", "BUS", "TROLLEYBUS", "RAIL", "FERRY")

/**
 * Natural route-name order: case/diacritic-insensitive, digit runs compared
 * numerically ("9" before "10").
 */
fun routeNameCompare(a: String, b: String): Int {
    val x = normalizeSearchText(a)
    val y = normalizeSearchText(b)
    var i = 0
    var j = 0
    while (i < x.length && j < y.length) {
        val cx = x[i]
        val cy = y[j]
        if (cx.isDigit() && cy.isDigit()) {
            var ie = i
            while (ie < x.length && x[ie].isDigit()) ie++
            var je = j
            while (je < y.length && y[je].isDigit()) je++
            val nx = x.substring(i, ie).trimStart('0')
            val ny = y.substring(j, je).trimStart('0')
            if (nx.length != ny.length) return if (nx.length < ny.length) -1 else 1
            val c = nx.compareTo(ny)
            if (c != 0) return if (c < 0) -1 else 1
            i = ie
            j = je
        } else {
            if (cx != cy) return if (cx < cy) -1 else 1
            i++
            j++
        }
    }
    val rx = x.length - i
    val ry = y.length - j
    return if (rx == ry) 0 else if (rx < ry) -1 else 1
}

/**
 * Group a stop's arrivals into per-route departure boards (catalog → references.routes →
 * raw id metadata). Cancelled/unresolvable trips dropped; only arrivals due within the
 * 60 s grace or later are kept, sorted by time. Boards sort metro/tram first, then by name.
 */
fun parseNearbyBoards(
    data: FutarArrivalsResponse?,
    catalogRoutes: Map<String, CatalogRoute>?,
    now: Long = nowMs(),
): List<NearbyDeparture> = dropDepartedBoards(parseAllNearbyBoards(data, catalogRoutes), now)

/** The time-based half of [parseNearbyBoards]: cheap enough to re-run on each clock tick. */
fun dropDepartedBoards(boards: List<NearbyDeparture>, now: Long): List<NearbyDeparture> {
    val cutoff = now - 60_000
    if (boards.all { board -> board.arrivals.all { it.arrivalMs >= cutoff } }) return boards
    return boards.mapNotNull { board ->
        val left = board.arrivals.filter { it.arrivalMs >= cutoff }
        if (left.isEmpty()) null else board.copy(arrivals = left)
    }
}

/** Every board in [data], grouped and sorted; depends only on the data, so it needs no clock. */
fun parseAllNearbyBoards(data: FutarArrivalsResponse?, catalogRoutes: Map<String, CatalogRoute>?): List<NearbyDeparture> {
    val refs = data?.data?.references
    val trips = refs?.trips ?: emptyMap()
    val refRoutes = refs?.routes ?: emptyMap()
    val refStops = refs?.stops ?: emptyMap()
    val groups = LinkedHashMap<String, MutableList<Arrival>>()

    for (st in data?.data?.entry?.stopTimes ?: emptyList()) {
        if (st.canceled) continue
        val routeId = trips[st.tripId]?.routeId
        if (routeId.isNullOrEmpty()) continue
        val predicted = st.predictedArrivalTime ?: st.predictedDepartureTime
        val scheduled = st.arrivalTime ?: st.departureTime
        val raw = predicted ?: scheduled ?: 0L
        val arrivalMs = (if (raw > 0) raw else 0L) * 1000
        if (arrivalMs <= 0) continue
        var arrival = Arrival(
            arrivalMs = arrivalMs,
            headSign = st.stopHeadsign.orEmpty(),
            isPredicted = predicted != null && predicted != 0L,
            tripId = st.tripId,
            serviceDate = st.serviceDate,
        )
        val platform = if (st.stopId.isNotEmpty()) refStops[st.stopId] else null
        if (platform != null && platform.lat.isFinite() && platform.lon.isFinite() &&
            !(platform.lat == 0.0 && platform.lon == 0.0)
        ) {
            arrival = arrival.copy(
                platformStopId = platform.id.ifEmpty { st.stopId },
                platformLat = platform.lat,
                platformLon = platform.lon,
            )
        }
        groups.getOrPut(routeId) { mutableListOf() } += arrival
    }

    val rows = groups.map { (routeId, arrivals) ->
        val cat = catalogRoutes?.get(routeId)
        val ref = refRoutes[routeId]
        NearbyDeparture(
            routeId = routeId,
            shortName = cat?.shortName?.ifEmpty { null } ?: ref?.shortName?.ifEmpty { null } ?: routeId,
            routeType = cat?.type?.ifEmpty { null } ?: ref?.type?.ifEmpty { null } ?: "",
            color = cat?.color?.ifEmpty { null } ?: ref?.color?.ifEmpty { null } ?: "52525b",
            textColor = cat?.textColor?.ifEmpty { null } ?: ref?.textColor?.ifEmpty { null } ?: "FFFFFF",
            arrivals = arrivals.sortedBy { it.arrivalMs },
        )
    }
    return rows.sortedWith { a, b ->
        val pa = BoardTypePriority.indexOf(a.routeType)
        val pb = BoardTypePriority.indexOf(b.routeType)
        if (pa != pb) (if (pa == -1) 99 else pa) - (if (pb == -1) 99 else pb)
        else localeCompare(a.shortName, b.shortName)
    }
}

/** Nearby departure ETAs use a compact apostrophe minute marker. */
fun formatNearbyCountdown(minutes: Int): String = if (minutes <= 0) "NOW" else "$minutes'"

/** "350 m" / "1.2 km" — compact walking-distance readout. */
fun formatDistanceMeters(meters: Double): String =
    if (meters < 1000) "${roundHalfUp(meters).toLong()} m" else "${toFixed(meters / 1000, 1)} km"

/** Walking speed constant: ~1.2 m/s = 72 meters per minute. */
const val WalkingMetersPerMinute = 72

/** Walking time in minutes for a distance (minimum 1 min). */
fun calculateWalkMinutes(distanceMeters: Double): Int {
    if (distanceMeters <= 0) return 1
    return max(1, ceil(distanceMeters / WalkingMetersPerMinute).toInt())
}

enum class Feasibility(val value: String) { Catchable("catchable"), Hurry("hurry"), Missed("missed") }

/** Feasibility of catching a departure: countdown vs walking time. */
fun calculateFeasibility(countdownMinutes: Int, walkMinutes: Int): Feasibility {
    val delta = countdownMinutes - walkMinutes
    return when {
        delta >= 2 -> Feasibility.Catchable
        delta >= 0 -> Feasibility.Hurry
        else -> Feasibility.Missed
    }
}

/** Flattened imminent departure card item for the Radar hero feed. */
data class RadarDepartureItem(
    val id: String,
    val stopId: String,
    val stopName: String,
    val distanceMeters: Double,
    val walkMinutes: Int,
    val feasibility: Feasibility,
    val routeId: String,
    val shortName: String,
    val routeType: String,
    val color: String,
    val textColor: String,
    val headsign: String,
    val arrivalMs: Long,
    val countdownMinutes: Int,
    val isPredicted: Boolean,
    val tripId: String? = null,
    val platformStopId: String? = null,
    val platformLat: Double? = null,
    val platformLon: Double? = null,
    /** The trip's service day (YYYYMMDD), for its trip-details. */
    val serviceDate: String? = null,
)

/** Filter departures by transit modes; empty / null / "ALL" matches everything. */
fun filterDeparturesByMode(departures: List<NearbyDeparture>, mode: List<String>? = null): List<NearbyDeparture> {
    val targets = (mode ?: emptyList()).map { it.uppercase() }.filter { it != "ALL" }.toSet()
    if (targets.isEmpty()) return departures
    return departures.filter { it.routeType.uppercase() in targets }
}

/** Legacy single-mode form ("" matches everything). */
fun filterDeparturesByMode(departures: List<NearbyDeparture>, mode: String): List<NearbyDeparture> =
    filterDeparturesByMode(departures, if (mode.isNotEmpty()) listOf(mode) else emptyList())

private val MetroPrefix = Regex("^metro\\s*", RegexOption.IGNORE_CASE)
private val LineSeparators = Regex("[-\\s/]")
private val AnyDigit = Regex("\\d")

/**
 * Strict line search: line-like queries (with a digit, or ≤3 chars) match the short name
 * only; longer text queries also match destination/stop name, accent-insensitively.
 */
fun matchesLineSearch(boardShortName: String, headsign: String, stopName: String, query: String): Boolean {
    val normRoute = boardShortName.trim().lowercase()
    val normQuery = query.trim().lowercase()
    if (normQuery.isEmpty()) return true
    if (normRoute == normQuery) return true
    val strippedQuery = normQuery.replace(MetroPrefix, "m").replace(LineSeparators, "")
    val strippedRoute = normRoute.replace(MetroPrefix, "m").replace(LineSeparators, "")
    if (strippedRoute == strippedQuery) return true
    val isLineQuery = AnyDigit.containsMatchIn(normQuery) || normQuery.length <= 3
    if (isLineQuery) return false
    val cleanQuery = normalizeSearchText(normQuery)
    val cleanHeadsign = normalizeSearchText(headsign)
    val cleanStop = normalizeSearchText(stopName)
    return cleanHeadsign.contains(cleanQuery) || cleanStop.contains(cleanQuery)
}

/** Build the aggregated, ranked list of imminent departures across candidate stops. */
fun buildRadarDepartures(
    stops: List<NearbyStop>,
    stopDeparturesMap: Map<String, List<NearbyDeparture>>,
    now: Long = nowMs(),
    modeFilter: List<String>? = null,
    maxItems: Int = 24,
    searchQuery: String? = null,
): List<RadarDepartureItem> {
    val items = mutableListOf<RadarDepartureItem>()
    val seenRouteCounts = HashMap<String, Int>()
    val seenTripKeys = HashSet<String>()
    val query = searchQuery?.trim()?.ifEmpty { null }

    for (stop in stops) {
        val boards = filterDeparturesByMode(stopDeparturesMap[stop.id] ?: emptyList(), modeFilter)
        val walkMinutes = calculateWalkMinutes(stop.distanceMeters)
        for (board in boards) {
            for (arrival in board.arrivals) {
                if (arrival.arrivalMs < now - 30_000) continue
                val tripKey = "${stop.id}|${board.routeId}|${arrival.tripId}|${arrival.arrivalMs}"
                if (arrival.tripId.isNotEmpty()) {
                    if (tripKey in seenTripKeys) continue
                    seenTripKeys += tripKey
                }
                if (query != null && !matchesLineSearch(board.shortName, arrival.headSign, stop.name, query)) continue

                val directionKey = "${stop.id}|${board.routeId}|${arrival.headSign}"
                val count = seenRouteCounts[directionKey] ?: 0
                if (count >= (if (query != null) 4 else 3)) continue
                seenRouteCounts[directionKey] = count + 1

                val countdownMinutes = max(0, ceil((arrival.arrivalMs - now) / 60_000.0).toInt())
                if (countdownMinutes > 99) continue
                items += RadarDepartureItem(
                    id = "${stop.id}-${board.routeId}-${arrival.tripId.ifEmpty { "anon" }}-${arrival.arrivalMs}",
                    stopId = stop.id,
                    stopName = stop.name,
                    distanceMeters = stop.distanceMeters,
                    walkMinutes = walkMinutes,
                    feasibility = calculateFeasibility(countdownMinutes, walkMinutes),
                    routeId = board.routeId,
                    shortName = board.shortName,
                    routeType = board.routeType,
                    color = board.color,
                    textColor = board.textColor,
                    headsign = arrival.headSign,
                    arrivalMs = arrival.arrivalMs,
                    countdownMinutes = countdownMinutes,
                    isPredicted = arrival.isPredicted,
                    tripId = arrival.tripId.ifEmpty { null },
                    platformStopId = arrival.platformStopId,
                    platformLat = arrival.platformLat,
                    platformLon = arrival.platformLon,
                    serviceDate = arrival.serviceDate,
                )
            }
        }
    }

    return items.sortedWith { a, b ->
        val ma = RadarModePriority.indexOf(a.routeType.uppercase()).let { if (it == -1) Int.MAX_VALUE else it }
        val mb = RadarModePriority.indexOf(b.routeType.uppercase()).let { if (it == -1) Int.MAX_VALUE else it }
        if (ma != mb) return@sortedWith ma.compareTo(mb)
        val routeOrder = routeNameCompare(a.shortName, b.shortName)
        if (routeOrder != 0) return@sortedWith routeOrder
        if (a.routeId != b.routeId) return@sortedWith localeCompare(a.routeId, b.routeId)
        a.arrivalMs.compareTo(b.arrivalMs)
    }.take(maxItems)
}
