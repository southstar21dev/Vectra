package dev.southstar21.vectra.features.transit

import androidx.compose.ui.graphics.vector.ImageVector
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.core.util.parseLeadingNumber
import kotlin.math.min

data class RouteTypeMeta(val label: String, val emoji: String, val icon: ImageVector)

val RouteTypeMetaByType: Map<String, RouteTypeMeta> by lazy {
    mapOf(
        "SUBWAY" to RouteTypeMeta("Metro", "🚇", RemixIcons.SubwayLine),
        "TRAM" to RouteTypeMeta("Tram", "🚋", RemixIcons.TrainLine),
        "BUS" to RouteTypeMeta("Bus", "🚌", RemixIcons.BusLine),
        "TROLLEYBUS" to RouteTypeMeta("Trolleybus", "🚎", RemixIcons.Bus2Line),
        "RAIL" to RouteTypeMeta("HÉV", "🚆", RemixIcons.TrainLine),
        "SUBURBAN_RAILWAY" to RouteTypeMeta("HÉV", "🚆", RemixIcons.TrainLine),
        "FERRY" to RouteTypeMeta("Ferry", "⛴️", RemixIcons.Ship2Line),
        "COACH" to RouteTypeMeta("Coach", "🚌", RemixIcons.BusLine),
    )
}

fun routeTypeMeta(type: String?): RouteTypeMeta =
    RouteTypeMetaByType[type.orEmpty()] ?: RouteTypeMeta(type?.takeIf { it.isNotEmpty() } ?: "Transit", "🚉", RemixIcons.TrainLine)

private val TypePriority = listOf("SUBWAY", "TRAM", "TROLLEYBUS", "BUS", "RAIL", "FERRY")

private fun parseFloatOr0(s: String): Double = parseLeadingNumber(s).let { if (it.isNaN()) 0.0 else it }

/** `String#localeCompare` approximation: case/diacritic-insensitive first, then exact. */
fun localeCompare(a: String, b: String): Int {
    val pa = normalizeSearchText(a)
    val pb = normalizeSearchText(b)
    val c = pa.compareTo(pb)
    return if (c != 0) c.coerceIn(-1, 1) else a.compareTo(b).coerceIn(-1, 1)
}

/** Sort routes so metro/tram come first, then alphabetically-ish by short name. */
fun sortRoutes(routes: List<FutarRoute>): List<FutarRoute> = routes.sortedWith { a, b ->
    val pa = TypePriority.indexOf(a.type).let { if (it == -1) 99 else it }
    val pb = TypePriority.indexOf(b.type).let { if (it == -1) 99 else it }
    if (pa != pb) return@sortedWith pa - pb
    val na = parseFloatOr0(a.shortName)
    val nb = parseFloatOr0(b.shortName)
    if (na != nb) return@sortedWith na.compareTo(nb)
    localeCompare(a.shortName, b.shortName)
}

data class CombinedRouteGroup(
    val name: String,
    val compactName: String,
    val routeIds: List<String>,
    val shortNames: List<String>,
)

val CombinedRouteGroups = listOf(
    CombinedRouteGroup("1 / 1A", "1/1A", listOf("BKK_3010", "BKK_3011"), listOf("1", "1A", "1/1A", "1 / 1A")),
    CombinedRouteGroup("4 / 6", "4/6", listOf("BKK_3040", "BKK_3060", "BKK_3046"), listOf("4", "6", "4-6", "4/6", "4 / 6")),
    CombinedRouteGroup("28 / 28A", "28/28A", listOf("BKK_3280", "BKK_3281"), listOf("28", "28A", "28/28A", "28 / 28A")),
    CombinedRouteGroup("37 / 37A", "37/37A", listOf("BKK_3370", "BKK_3371"), listOf("37", "37A", "37/37A", "37 / 37A")),
    CombinedRouteGroup("H5", "H5", listOf("BKK_H5", "volan_H5", "volan_H5A", "volan_H5E"), listOf("H5", "H5A", "H5E")),
)

fun findCombinedGroup(routeId: String? = null, shortName: String? = null): CombinedRouteGroup? {
    if (!routeId.isNullOrEmpty()) {
        CombinedRouteGroups.firstOrNull { routeId in it.routeIds }?.let { return it }
    }
    if (!shortName.isNullOrEmpty()) {
        val norm = shortName.trim().uppercase()
        CombinedRouteGroups.firstOrNull { norm in it.shortNames }?.let { return it }
    }
    return null
}

fun getCombinedRouteIds(routeId: String?): List<String>? = findCombinedGroup(routeId)?.routeIds

/**
 * Route matching that combines paired lines (1/1A, 37/37A, 28/28A, 4/6, H5)
 * while keeping all other lines strictly isolated.
 */
fun isRouteMatch(
    tripRouteId: String?,
    targetRouteId: String,
    tripShortName: String? = null,
    targetShortName: String? = null,
): Boolean {
    if (!tripShortName.isNullOrEmpty() && !targetShortName.isNullOrEmpty()) {
        val gTrip = findCombinedGroup(null, tripShortName)
        val gTarget = findCombinedGroup(null, targetShortName)
        if (gTrip != null && gTarget != null && gTrip.name == gTarget.name) return true
    }
    if (tripRouteId.isNullOrEmpty()) return false
    val group = findCombinedGroup(targetRouteId)
    if (group != null) return tripRouteId in group.routeIds
    return tripRouteId == targetRouteId
}

fun formatRouteDisplayName(shortName: String, routeId: String? = null, compact: Boolean = false): String {
    val group = findCombinedGroup(routeId, shortName) ?: return shortName
    return if (compact) group.compactName else group.name
}

private val ViaSplit = Regex("""\s*(?:►|>|->)\s*""")

/** "Salgótarjáni utca ► Sörgyár" → "Sörgyár" */
fun extractHeadsignDestination(headsign: String?): String {
    if (headsign.isNullOrEmpty()) return ""
    val trimmed = headsign.trim()
    val parts = trimmed.split(ViaSplit)
    return parts.last().ifEmpty { trimmed }
}

private val ParenNotes = Regex("""\([^)]*\)""")
private val StationSuffixes = Regex("""\b(m|h|pu|vasutallomas|allomas)\b""")
private val Whitespace = Regex("""\s+""")
private val RouteTitleSeparator = Regex("""\s*\|\s*""")
private val SixDigitStopId = Regex("""^BKK_\d{6}$""")
private val EightDigitStopId = Regex("""^BKK_\d{8}$""")

private fun normalizeHeadsignForMatch(s: String): String =
    normalizeSearchText(s)
        .replace(ParenNotes, "")
        .replace(StationSuffixes, "")
        .replace(Whitespace, " ")
        .trim()

/**
 * Robust headsign matching for live arrivals: empty target matches all; exact,
 * via-destination and normalized (diacritics, parentheses, M/H suffix) matches.
 */
fun isHeadsignMatch(targetHeadsign: String?, stopHeadsign: String?, tripHeadsign: String? = null): Boolean {
    if (targetHeadsign.isNullOrBlank()) return true
    val targetRaw = targetHeadsign.trim().lowercase()
    val stopRaw = stopHeadsign.orEmpty().trim().lowercase()
    val tripRaw = tripHeadsign.orEmpty().trim().lowercase()

    if (stopRaw.isNotEmpty() && stopRaw == targetRaw) return true
    if (tripRaw.isNotEmpty() && tripRaw == targetRaw) return true

    val targetDest = extractHeadsignDestination(targetHeadsign).lowercase()
    val stopDest = extractHeadsignDestination(stopHeadsign).lowercase()
    val tripDest = extractHeadsignDestination(tripHeadsign).lowercase()

    if (targetDest.isNotEmpty() && stopDest.isNotEmpty() && stopDest == targetDest) return true
    if (targetDest.isNotEmpty() && tripDest.isNotEmpty() && tripDest == targetDest) return true
    if (targetRaw.isNotEmpty() && stopDest.isNotEmpty() && stopDest == targetRaw) return true
    if (targetRaw.isNotEmpty() && tripDest.isNotEmpty() && tripDest == targetRaw) return true

    val normTarget = normalizeHeadsignForMatch(targetDest.ifEmpty { targetHeadsign })
    val normStop = normalizeHeadsignForMatch(stopDest.ifEmpty { stopHeadsign.orEmpty() })
    val normTrip = normalizeHeadsignForMatch(tripDest.ifEmpty { tripHeadsign.orEmpty() })
    if (normTarget.isNotEmpty()) {
        if (normStop.isNotEmpty() && (normStop == normTarget || normStop.contains(normTarget) || normTarget.contains(normStop))) return true
        if (normTrip.isNotEmpty() && (normTrip == normTarget || normTrip.contains(normTarget) || normTarget.contains(normTrip))) return true
    }
    return false
}

private val FallbackShortNames = mapOf(
    "BKK_3371" to "37A", "BKK_3370" to "37", "BKK_3281" to "28A", "BKK_3280" to "28",
    "BKK_3011" to "1A", "BKK_3010" to "1", "BKK_3040" to "4", "BKK_3060" to "6",
)

/**
 * Upcoming arrivals for one route from a stop's Futár response, narrowed to
 * [headsign] when set. Paired lines combine.
 */
fun parseStopArrivals(data: FutarArrivalsResponse?, routeId: String, headsign: String? = null, now: Long = nowMs()): List<Arrival> {
    val stopTimes = data?.data?.entry?.stopTimes.orEmpty()
    val refs = data?.data?.references
    val trips = refs?.trips.orEmpty()
    val routes = refs?.routes.orEmpty()
    return stopTimes
        .filter { !it.canceled && isRouteMatch(trips[it.tripId]?.routeId, routeId) }
        .map { st ->
            val trip = trips[st.tripId]
            val tripRouteId = trip?.routeId
            val route = tripRouteId?.let { routes[it] }
            val predicted = st.predictedArrivalTime ?: st.predictedDepartureTime
            val scheduled = st.arrivalTime ?: st.departureTime
            val t = predicted ?: scheduled ?: 0L
            Arrival(
                arrivalMs = (if (t > 0) t else 0L) * 1000,
                headSign = st.stopHeadsign?.takeIf { it.isNotEmpty() } ?: trip?.tripHeadsign.orEmpty(),
                tripHeadsign = trip?.tripHeadsign.orEmpty(),
                isPredicted = predicted != null,
                tripId = st.tripId,
                routeId = tripRouteId,
                routeShortName = route?.shortName?.takeIf { it.isNotEmpty() } ?: FallbackShortNames[tripRouteId],
            )
        }
        .filter { it.arrivalMs > 0 && it.arrivalMs >= now - 60_000 }
        .filter { isHeadsignMatch(headsign, it.headSign, it.tripHeadsign) }
        .sortedBy { it.arrivalMs }
}

private fun Arrival.directionKey(): String = tripHeadsign?.takeIf { it.isNotEmpty() } ?: headSign.ifEmpty { "Unknown" }

data class ArrivalGroup(val name: String, val list: List<Arrival>)

/** Group arrivals by terminus (headsign), preserving time order within each group. */
fun groupArrivalsByHeadsign(arrivals: List<Arrival>): List<ArrivalGroup> {
    val map = LinkedHashMap<String, MutableList<Arrival>>()
    for (a in arrivals) map.getOrPut(a.directionKey()) { mutableListOf() }.add(a)
    return map.map { (name, list) -> ArrivalGroup(name, list) }
}

/** The [count] departures following [current] in the same direction, in time order. */
fun followingArrivals(arrivals: List<Arrival>, current: Arrival?, count: Int = 2): List<Arrival> {
    if (current == null) return emptyList()
    val key = current.directionKey()
    return arrivals.filter { it.tripId != current.tripId && it.arrivalMs > current.arrivalMs && it.directionKey() == key }.take(count)
}

private val DiacriticFolds: Map<Char, Char> by lazy {
    val pairs = mapOf(
        "a" to "àáâãäåāăą", "c" to "çćĉċč", "d" to "ďđ", "e" to "èéêëēĕėęě", "g" to "ĝğġģ", "h" to "ĥħ",
        "i" to "ìíîïĩīĭįı", "j" to "ĵ", "k" to "ķ", "l" to "ĺļľŀł", "n" to "ñńņňŉ", "o" to "òóôõöøōŏő",
        "r" to "ŕŗř", "s" to "śŝşšș", "t" to "ţťŧț", "u" to "ùúûüũūŭůűų", "w" to "ŵ", "y" to "ýÿŷ", "z" to "źżž",
    )
    buildMap { pairs.forEach { (base, chars) -> chars.forEach { put(it, base[0]) } } }
}

/** Lowercase + strip diacritics so "szell kalman" matches "Széll Kálmán tér". */
fun normalizeSearchText(s: String): String {
    val lower = s.lowercase()
    val sb = StringBuilder(lower.length)
    for (ch in lower) {
        if (ch in '̀'..'ͯ') continue
        sb.append(DiacriticFolds[ch] ?: ch)
    }
    return sb.toString()
}

private val HtmlBreak = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val HtmlTag = Regex("""</?[^>]+>""")
private val EntityNbsp = Regex("&nbsp;", RegexOption.IGNORE_CASE)
private val EntityAmp = Regex("&amp;", RegexOption.IGNORE_CASE)
private val EntityLt = Regex("&lt;", RegexOption.IGNORE_CASE)
private val EntityGt = Regex("&gt;", RegexOption.IGNORE_CASE)
private val EntityQuot = Regex("&quot;", RegexOption.IGNORE_CASE)
private val EntityNumeric = Regex("""&#(\d+);""")
private val SpaceBeforePunctuation = Regex(""" ([.,!?;:])""")

/** Crude HTML-to-text: drop tags and decode the common entities. */
fun stripHtml(html: String): String = html
    .replace(HtmlBreak, " ")
    .replace(HtmlTag, " ")
    .replace(EntityNbsp, " ")
    .replace(EntityAmp, "&")
    .replace(EntityLt, "<")
    .replace(EntityGt, ">")
    .replace(EntityQuot, "\"")
    .replace("&#39;", "'")
    .replace(EntityNumeric) { m -> m.groupValues[1].toIntOrNull()?.let { Char(it).toString() } ?: m.value }
    .replace(Whitespace, " ")
    .replace(SpaceBeforePunctuation, "$1")
    .trim()

private fun pickText(t: FutarTranslations?): String {
    if (t == null) return ""
    return t.translations?.get("en")?.takeIf { it.isNotEmpty() }
        ?: t.translations?.get("hu")?.takeIf { it.isNotEmpty() }
        ?: t.someTranslation.orEmpty()
}

/** Identity of the tracked route/stop, used to scope alerts. */
data class AlertContext(val routeId: String, val routeShortName: String, val routeType: String, val stopId: String)

private fun FutarAlert.affectedRouteIds(): List<String> = routeIds ?: routes?.mapNotNull { it.routeId?.takeIf(String::isNotEmpty) }.orEmpty()

/**
 * Active disruption alerts for a tracker from `references.alerts`: attached to
 * the route, to the stop (same vehicle type), or agency-wide (same vehicle type).
 */
fun parseAlerts(
    data: FutarArrivalsResponse?,
    ctx: AlertContext,
    now: Long = nowMs(),
    extraResolver: ((String) -> String?)? = null,
    extraTypeResolver: ((String) -> String?)? = null,
): List<TransitAlert> {
    val refs = data?.data?.references
    val alerts = refs?.alerts.orEmpty()
    val routes = refs?.routes.orEmpty()
    val stops = refs?.stops.orEmpty()
    val entryStopId = data?.data?.entry?.stopId
    val nowSec = now / 1000.0

    fun shortNameOf(routeId: String): String? = routes[routeId]?.shortName ?: extraResolver?.invoke(routeId)

    fun routeMatch(a: FutarAlert): Boolean {
        val ids = a.affectedRouteIds()
        if (ids.any { isRouteMatch(it, ctx.routeId) }) return true
        val types = a.vehicleRouteTypes
        return ids.any { id ->
            val name = shortNameOf(id) ?: return@any false
            val matchesName = name == ctx.routeShortName || isRouteMatch(null, ctx.routeId, name, ctx.routeShortName)
            if (!matchesName) return@any false
            !(types != null && types.isNotEmpty() && ctx.routeType !in types)
        }
    }

    fun stopMatch(a: FutarAlert): Boolean {
        val ids = a.stopIds.orEmpty()
        if (ctx.stopId in ids || (entryStopId != null && entryStopId in ids)) return true
        val parent = stops[ctx.stopId]?.parentStationId
        return parent != null && parent in ids
    }

    fun agencyWide(a: FutarAlert): Boolean = a.routeIds.orEmpty().isEmpty() && a.stopIds.orEmpty().isEmpty()

    fun vehicleTypeMatch(a: FutarAlert): Boolean {
        val declared = a.vehicleRouteTypes
        if (declared != null && declared.isNotEmpty()) return ctx.routeType in declared
        val ids = a.affectedRouteIds()
        if (ids.isEmpty()) return true
        val affected = ids.mapNotNull { routes[it]?.type?.takeIf(String::isNotEmpty) ?: extraTypeResolver?.invoke(it) }.toSet()
        if (affected.isEmpty()) return true
        return ctx.routeType in affected
    }

    val out = mutableListOf<TransitAlert>()
    for (a in alerts.values) {
        if (a.start != null && nowSec < a.start) continue
        if (a.end != null && nowSec > a.end) continue
        if (!routeMatch(a) && !(stopMatch(a) && vehicleTypeMatch(a)) && !(agencyWide(a) && vehicleTypeMatch(a))) continue
        out += TransitAlert(
            id = a.id,
            header = pickText(a.header).ifEmpty { "Service alert ${a.id}" },
            description = stripHtml(pickText(a.description)),
            url = pickText(a.url).ifEmpty { null },
            effectType = a.effectType ?: a.routes?.firstOrNull()?.effectType,
            routeIds = a.routeIds ?: a.routes?.mapNotNull { it.routeId?.takeIf(String::isNotEmpty) },
            stopIds = a.stopIds,
        )
    }
    return out.sortedWith { x, y ->
        val px = effectWeight(x.effectType)
        val py = effectWeight(y.effectType)
        if (px != py) px - py else localeCompare(x.header, y.header)
    }
}

private fun effectWeight(effectType: String?): Int = when (effectType) {
    "NO_SERVICE" -> 0
    "DETOUR", "MODIFIED_SERVICE", "REDUCED_SERVICE" -> 1
    "SIGNIFICANT_DELAYS" -> 2
    else -> 3
}

/** "3 MIN" — total-minute countdown text for departure cards. */
fun formatCountdown(minutes: Int): String = if (minutes <= 0) "NOW" else "$minutes MIN"

/** "2'" — prime-marked total minutes for compact readouts. */
fun formatPrimeCountdown(minutes: Int): String = if (minutes <= 0) "NOW" else "$minutes'"

const val CompactCountdownMaxMinutes = 99

/** Compact minutes for dashboard tiles; null past the horizon. */
fun formatCompactCountdown(minutes: Int): String? = if (minutes > CompactCountdownMaxMinutes) null else formatPrimeCountdown(minutes)

/** Snapshots older than this (or offline) are stale: 3× the 20s poll. */
const val ArrivalsStaleMs = 60_000L

fun isArrivalsStale(dataUpdatedAt: Long?, now: Long, offline: Boolean): Boolean {
    if (offline) return true
    val at = dataUpdatedAt ?: 0L
    return at > 0 && now - at > ArrivalsStaleMs
}

/** Real now, or the snapshot time when the snapshot is stale. */
fun arrivalsStaleNow(dataUpdatedAt: Long?, now: Long, offline: Boolean): Long {
    val at = dataUpdatedAt ?: 0L
    return if (isArrivalsStale(dataUpdatedAt, now, offline)) min(now, at) else now
}

const val MaxTrackerStops = 4
const val MaxFavoriteTrackers = 5

/** The tracker's stops, falling back to the legacy single-stop columns. */
fun getTrackerStops(tracker: TransitTracker): List<TrackerStop> {
    val stops = tracker.stops
    if (!stops.isNullOrEmpty()) return stops.take(MaxTrackerStops)
    return listOf(TrackerStop(tracker.stopId, tracker.stopName, tracker.headsign))
}

fun addOrUpdateTrackerStop(stops: List<TrackerStop>, newStop: TrackerStop): List<TrackerStop> {
    val i = stops.indexOfFirst { it.stopId == newStop.stopId }
    if (i >= 0) return stops.toMutableList().also { it[i] = newStop }
    if (stops.size >= MaxTrackerStops) return stops
    return stops + newStop
}

fun removeTrackerStop(stops: List<TrackerStop>, stopId: String): List<TrackerStop> = stops.filter { it.stopId != stopId }

fun splitRouteTitle(title: String?): List<String> =
    if (title.isNullOrEmpty()) emptyList() else title.split(RouteTitleSeparator).filter { it.isNotEmpty() }

/**
 * Normalizes a platform stop ID into its station-level area ID
 * (BKK_F… → BKK_CSF…, BKK_123456 → BKK_CS123456, BKK_12345678 → BKK_12345).
 */
fun normalizeStopIdToStation(stopId: String?): String {
    if (stopId.isNullOrEmpty()) return ""
    if (stopId.startsWith("BKK_CS") || stopId.startsWith("AREA_CS")) return stopId
    if (stopId.startsWith("BKK_F")) return "BKK_CSF" + stopId.removePrefix("BKK_F")
    if (SixDigitStopId.matches(stopId)) return "BKK_CS" + stopId.removePrefix("BKK_")
    if (EightDigitStopId.matches(stopId)) return stopId.substring(0, 9)
    return stopId
}
