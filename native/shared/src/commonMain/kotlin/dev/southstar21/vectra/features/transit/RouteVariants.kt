package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.decodePolyline

/* Picks the polyline and stops of a route variant out of Futar responses. */

private val Spaces = Regex("\\s+")

/** Normalize a headsign for matching: trim, lowercase, strip diacritics, collapse whitespace. */
private fun normalizeHeadsign(value: String): String =
    normalizeSearchText(value.trim()).replace(Spaces, " ")

/** The entries whose headsign matches (exactly, else normalized); null when none does or none is wanted. */
private fun <T> matchByHeadsign(entries: List<T>, headsign: String?, headsignOf: (T) -> String?): List<T>? {
    val wanted = headsign?.trim()
    if (entries.isEmpty() || wanted.isNullOrEmpty()) return null
    val normalized = normalizeHeadsign(wanted)
    return entries.filter { (headsignOf(it) ?: "") == wanted }
        .ifEmpty { entries.filter { normalizeHeadsign(headsignOf(it) ?: "") == normalized } }
        .ifEmpty { null }
}

/**
 * The variants for [headsign] (and [routeId] when tagged). With no headsign match, the ones
 * calling at one of [servingStopIds] (the departure's platform) win, so a missing trip and an
 * unfamiliar headsign don't draw the opposite direction; only failing that, every variant.
 */
private fun <T> matchingVariants(
    variants: List<T>,
    headsign: String?,
    routeId: String?,
    servingStopIds: Set<String>,
    headsignOf: (T) -> String?,
    routeIdOf: (T) -> String?,
    stopIdsOf: (T) -> List<String>,
): List<T> {
    val scoped = if (!routeId.isNullOrEmpty()) variants.filter { routeIdOf(it) == routeId } else emptyList()
    val pool = scoped.ifEmpty { variants }
    return matchByHeadsign(pool, headsign, headsignOf)
        ?: pool.filter { v -> stopIdsOf(v).any { it in servingStopIds } }.ifEmpty { pool }
}

private class DecodedVariant(val headsign: String, val routeId: String?, val stopIds: List<String>, val points: List<LatLon>)

/**
 * Decode the variant polylines matching `headsign` (and `routeId` when tagged), falling back to
 * the ones serving [servingStopIds]. <2-point variants are dropped.
 */
fun selectVariantPolylines(
    variants: List<FutarRouteVariant>,
    headsign: String? = null,
    routeId: String? = null,
    servingStopIds: Set<String> = emptySet(),
): List<List<LatLon>> {
    val decoded = variants
        .map { v ->
            DecodedVariant(
                headsign = v.headsign ?: "",
                routeId = v.routeId,
                stopIds = v.stopIds.orEmpty(),
                points = v.polyline?.points?.takeIf { it.isNotEmpty() }?.let(::decodePolyline) ?: emptyList(),
            )
        }
        .filter { it.points.size >= 2 }
    return matchingVariants(decoded, headsign, routeId, servingStopIds, { it.headsign }, { it.routeId }, { it.stopIds }).map { it.points }
}

/** Stop ids of the variants [selectVariantPolylines] would pick, concatenated in order, duplicates collapsed (first wins). */
fun selectVariantStopIds(
    variants: List<FutarRouteVariant>,
    headsign: String? = null,
    routeId: String? = null,
    servingStopIds: Set<String> = emptySet(),
): List<String> {
    val matched = matchingVariants(variants, headsign, routeId, servingStopIds, { it.headsign }, { it.routeId }, { it.stopIds.orEmpty() })
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
