package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.decodePolyline

/* Picks the polyline and stops of a route variant out of Futar responses. */

private val Spaces = Regex("\\s+")

/** Normalize a headsign for matching: trim, lowercase, strip diacritics, collapse whitespace. */
private fun normalizeHeadsign(value: String): String =
    normalizeSearchText(value.trim()).replace(Spaces, " ")

private fun <T> matchByHeadsign(entries: List<T>, headsign: String?, headsignOf: (T) -> String?): List<T> {
    if (entries.isEmpty()) return emptyList()
    val wanted = headsign?.trim()
    if (!wanted.isNullOrEmpty()) {
        val exact = entries.filter { (headsignOf(it) ?: "") == wanted }
        if (exact.isNotEmpty()) return exact
        val normalized = normalizeHeadsign(wanted)
        val fuzzy = entries.filter { normalizeHeadsign(headsignOf(it) ?: "") == normalized }
        if (fuzzy.isNotEmpty()) return fuzzy
    }
    return entries
}

private fun <T> matchingVariants(
    variants: List<T>,
    headsign: String?,
    routeId: String?,
    headsignOf: (T) -> String?,
    routeIdOf: (T) -> String?,
): List<T> {
    val scoped = if (!routeId.isNullOrEmpty()) variants.filter { routeIdOf(it) == routeId } else emptyList()
    return matchByHeadsign(if (scoped.isNotEmpty()) scoped else variants, headsign, headsignOf)
}

private class DecodedVariant(val headsign: String, val routeId: String?, val points: List<LatLon>)

/** Decode the variant polylines matching `headsign` (and `routeId` when tagged). <2-point variants are dropped. */
fun selectVariantPolylines(
    variants: List<FutarRouteVariant>,
    headsign: String? = null,
    routeId: String? = null,
): List<List<LatLon>> {
    val decoded = variants
        .map { v ->
            DecodedVariant(
                headsign = v.headsign ?: "",
                routeId = v.routeId,
                points = v.polyline?.points?.takeIf { it.isNotEmpty() }?.let(::decodePolyline) ?: emptyList(),
            )
        }
        .filter { it.points.size >= 2 }
    return matchingVariants(decoded, headsign, routeId, { it.headsign }, { it.routeId }).map { it.points }
}

/** Stop ids of the matching variants, concatenated in order, duplicates collapsed (first wins). */
fun selectVariantStopIds(
    variants: List<FutarRouteVariant>,
    headsign: String? = null,
    routeId: String? = null,
): List<String> {
    val matched = matchingVariants(variants, headsign, routeId, { it.headsign }, { it.routeId })
    val seen = HashSet<String>()
    val ids = mutableListOf<String>()
    for (variant in matched) {
        for (id in variant.stopIds ?: emptyList()) {
            if (!seen.add(id)) continue
            ids += id
        }
    }
    return ids
}

/** Decode a trip-details response's own polyline; empty when it carries none. */
fun selectTripPolyline(trip: FutarTripDetailsResponse?): List<LatLon> {
    val points = trip?.data?.entry?.polyline?.points
    return if (!points.isNullOrEmpty()) decodePolyline(points) else emptyList()
}

/** The trip's exact ordered stop ids from trip-details, deduped (first wins). */
fun selectTripStopIds(trip: FutarTripDetailsResponse?): List<String> {
    val seen = HashSet<String>()
    val ids = mutableListOf<String>()
    for (stopTime in trip?.data?.entry?.stopTimes ?: emptyList()) {
        val id = stopTime.stopId
        if (id.isEmpty() || !seen.add(id)) continue
        ids += id
    }
    return ids
}

/** A stop resolved from `references.stops`. */
data class ResolvedStopRef(val id: String, val name: String, val lat: Double, val lon: Double)

/** Resolve ordered stop ids against a `references.stops` map, dropping missing and zero-coordinate entries. */
fun resolveStopRefs(stopIds: List<String>, refs: Map<String, FutarStop>?): List<ResolvedStopRef> {
    val out = mutableListOf<ResolvedStopRef>()
    for (id in stopIds) {
        val ref = refs?.get(id) ?: continue
        if (ref.lat == 0.0 && ref.lon == 0.0) continue
        out += ResolvedStopRef(ref.id, ref.name, ref.lat, ref.lon)
    }
    return out
}
