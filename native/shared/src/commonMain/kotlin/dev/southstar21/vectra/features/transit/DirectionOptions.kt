package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.designsystem.components.SelectOption
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.time.nowMs

/** Delimiter joining co-directional termini in a single direction option value (e.g. "Sörgyár / Új köztemető (Kozma u.)"). */
internal const val DirectionGroupSeparator = " / "

/** One selectable direction row for a tracked stop. */
data class StopDirectionOption(
    val value: String,
    val label: String,
    val subtitle: String? = null,
    val badge: String? = null,
    val badgeTone: StatusTone? = null,
    val liveMinutes: Int? = null,
    val muted: Boolean = false,
    val memberHeadsigns: List<String> = emptyList(),
    val directionId: String? = null,
)

/** Stop-aware direction choices for one stop, plus any arrival-only termini that were hidden. */
data class StopDirectionOptions(
    val options: List<StopDirectionOption>,
    val hiddenTermini: List<String> = emptyList(),
)

/** Inputs needed to build topology- and disruption-aware direction choices for one stop. */
data class StopDirectionContext(
    val stopId: String,
    val routeId: String,
    val routeShortName: String,
    val routeType: String,
    val variants: List<FutarRouteVariant>,
    val stopRefs: Map<String, FutarStop>,
    val fallbackHeadsigns: List<String> = emptyList(),
    val arrivalsData: FutarArrivalsResponse? = null,
    val now: Long = nowMs(),
)

/** Split a stored direction value into its member headsigns ("A / B" → ["A", "B"]). */
fun splitDirectionHeadsigns(headsign: String?): List<String> {
    if (headsign.isNullOrBlank()) return emptyList()
    return headsign.split(DirectionGroupSeparator).map { it.trim() }.filter { it.isNotEmpty() }
}

private fun stationIdFor(rawStopId: String, stopRefs: Map<String, FutarStop>): String =
    stopRefs[rawStopId]?.parentStationId?.takeIf { it.isNotEmpty() } ?: normalizeStopIdToStation(rawStopId)

private fun variantStationIds(variant: FutarRouteVariant, stopRefs: Map<String, FutarStop>): List<String> {
    val out = ArrayList<String>()
    for (id in variant.stopIds.orEmpty()) {
        val station = stationIdFor(id, stopRefs)
        if (out.lastOrNull() != station) out += station
    }
    return out
}

/**
 * Resolve the Futár directionId ("0"/"1") for [headsign] using [variants] so direction-locked
 * trackers can keep following short-turned or diverted trips moving the same physical way.
 */
fun targetDirectionIdFor(
    headsign: String?,
    variants: List<FutarRouteVariant>,
    stopId: String? = null,
    stopRefs: Map<String, FutarStop> = emptyMap(),
): String? {
    if (headsign.isNullOrBlank() || variants.isEmpty()) return null
    val targetStation = stopId?.takeIf { it.isNotEmpty() }?.let { stationIdFor(it, stopRefs) }
    val matching = variants.filter { v -> !v.direction.isNullOrBlank() && isHeadsignMatch(headsign, v.headsign, v.name) }
    val departing = targetStation?.let { station ->
        matching.firstOrNull { v ->
            val stations = variantStationIds(v, stopRefs)
            stations.indexOf(station) in 0 until stations.lastIndex
        }
    }
    return (departing ?: matching.firstOrNull())?.direction
}

private data class DepartingVariant(
    val variant: FutarRouteVariant,
    val headsign: String,
    val groupKey: String,
    val nextStopName: String?,
    val routeShortName: String?,
)

private fun collectDepartingVariants(
    ctx: StopDirectionContext,
    targetStation: String,
): Pair<List<DepartingVariant>, List<String>> {
    val departing = mutableListOf<DepartingVariant>()
    val arrivalOnly = LinkedHashSet<String>()
    for (v in ctx.variants) {
        val headsign = v.headsign?.trim().orEmpty()
        val stations = if (headsign.isNotEmpty()) variantStationIds(v, ctx.stopRefs) else emptyList()
        val idx = stations.indexOf(targetStation)
        if (idx == stations.lastIndex && idx >= 0) {
            arrivalOnly.add(headsign)
        } else if (idx in 0 until stations.lastIndex) {
            val nextStation = stations[idx + 1]
            val nextName = ctx.stopRefs[nextStation]?.name
                ?: v.stopIds.orEmpty().firstNotNullOfOrNull { id ->
                    if (stationIdFor(id, ctx.stopRefs) == nextStation) ctx.stopRefs[id]?.name else null
                }
            val key = v.direction?.takeIf { it.isNotBlank() }?.let { "dir:$it" } ?: "next:$nextStation"
            departing += DepartingVariant(v, headsign, key, nextName, v.routeId?.let(::fallbackShortNameForRoute))
        }
    }
    val departingHeadsigns = departing.map { it.headsign }.toSet()
    return departing to arrivalOnly.filter { it !in departingHeadsigns }
}

private fun fallbackShortNameForRoute(routeId: String): String? = when (routeId) {
    "BKK_3370" -> "37"
    "BKK_3371" -> "37A"
    "BKK_3280" -> "28"
    "BKK_3281" -> "28A"
    "BKK_3010" -> "1"
    "BKK_3011" -> "1A"
    "BKK_3040" -> "4"
    "BKK_3060" -> "6"
    else -> null
}

/**
 * Build stop-aware direction options:
 * - Hides arrival-only termini where the tracked stop is the last stop of a variant.
 * - Groups co-directional branches (e.g. 37 and 37A outbound) into one selectable direction.
 * - Annotates each direction with live ETA, replacement-bus status, or muted no-departures state.
 */
fun buildStopDirectionOptions(ctx: StopDirectionContext): StopDirectionOptions {
    val allOption = StopDirectionOption(
        value = "",
        label = "All directions",
        subtitle = "Show every departure from this stop",
    )
    val (departing, hiddenTermini) = if (ctx.variants.isEmpty()) {
        emptyList<DepartingVariant>() to emptyList()
    } else {
        collectDepartingVariants(ctx, stationIdFor(ctx.stopId, ctx.stopRefs))
    }
    if (departing.isEmpty()) {
        val fallback = ctx.fallbackHeadsigns.distinct().filter { it !in hiddenTermini }.map { h ->
            StopDirectionOption(value = h, label = h, memberHeadsigns = listOf(h))
        }
        return StopDirectionOptions(listOf(allOption) + fallback, hiddenTermini)
    }

    val grouped = LinkedHashMap<String, MutableList<DepartingVariant>>()
    for (item in departing) grouped.getOrPut(item.groupKey) { mutableListOf() }.add(item)

    val alerts = ctx.arrivalsData?.let {
        parseAlerts(it, AlertContext(ctx.routeId, ctx.routeShortName, ctx.routeType, ctx.stopId), ctx.now)
    }.orEmpty()
    val hasReplacementAlert = alerts.any(::isReplacementAlert)
    val allArrivals = ctx.arrivalsData?.let {
        parseStopArrivals(it, ctx.routeId, headsign = null, now = ctx.now, shortName = ctx.routeShortName)
    }

    val directionOptions = grouped.values.map { group ->
        buildGroupedDirectionOption(group, ctx, allArrivals, hasReplacementAlert)
    }
    return StopDirectionOptions(listOf(allOption) + directionOptions, hiddenTermini)
}

private fun buildGroupedDirectionOption(
    group: List<DepartingVariant>,
    ctx: StopDirectionContext,
    allArrivals: List<Arrival>?,
    hasReplacementAlert: Boolean,
): StopDirectionOption {
    val headsigns = group.map { it.headsign }.distinct()
    val value = headsigns.joinToString(DirectionGroupSeparator)
    val dirId = group.firstNotNullOfOrNull { it.variant.direction?.takeIf(String::isNotBlank) }
    val memberLines = group.mapNotNull { it.routeShortName }.distinct()
    val viaName = group.firstNotNullOfOrNull { it.nextStopName?.takeIf(String::isNotBlank) }
    val baseSubtitle = buildList {
        if (memberLines.size > 1) add(memberLines.joinToString(", "))
        if (viaName != null && viaName !in headsigns) add("via $viaName")
    }.joinToString(", ").ifEmpty { null }

    if (allArrivals == null) {
        return StopDirectionOption(
            value = value,
            label = value,
            subtitle = baseSubtitle,
            memberHeadsigns = headsigns,
            directionId = dirId,
        )
    }

    val dirArrivals = parseStopArrivals(
        data = ctx.arrivalsData,
        routeId = ctx.routeId,
        headsign = value,
        now = ctx.now,
        shortName = ctx.routeShortName,
        targetDirectionId = dirId,
    )
    val regularArrivals = dirArrivals.filter { !it.isReplacementBus }
    val replacementArrivals = dirArrivals.filter { it.isReplacementBus }
    val soonestRegular = regularArrivals.firstOrNull()
    val noun = vehicleNoun(ctx.routeType)?.replaceFirstChar { it.uppercase() } ?: "Service"

    return when {
        soonestRegular != null -> StopDirectionOption(
            value = value,
            label = value,
            subtitle = baseSubtitle,
            liveMinutes = etaMinutes(soonestRegular.arrivalMs, ctx.now),
            memberHeadsigns = headsigns,
            directionId = dirId,
        )
        hasReplacementAlert || replacementArrivals.isNotEmpty() -> StopDirectionOption(
            value = value,
            label = value,
            subtitle = "$noun suspended here, auto-follows bus",
            badge = "Replacement bus",
            badgeTone = StatusTone.Sky,
            muted = true,
            memberHeadsigns = headsigns,
            directionId = dirId,
        )
        else -> StopDirectionOption(
            value = value,
            label = value,
            subtitle = "No departures right now",
            muted = true,
            memberHeadsigns = headsigns,
            directionId = dirId,
        )
    }
}

/**
 * Resolve [savedHeadsign] against [options]: if the user previously saved a single branch
 * (e.g. "Sörgyár") that is now grouped under "Sörgyár / Új köztemető (Kozma u.)", select the group.
 */
fun resolveDirectionValue(savedHeadsign: String, options: List<StopDirectionOption>): String = when {
    savedHeadsign.isBlank() -> ""
    else -> options.firstOrNull { it.value == savedHeadsign }?.value
        ?: options.firstOrNull { opt -> opt.memberHeadsigns.any { isHeadsignMatch(savedHeadsign, it) } }?.value
        ?: savedHeadsign
}

/** Convert [StopDirectionOptions] into UI [SelectOption] items for [VSelect]. */
internal fun StopDirectionOptions.toSelectOptions(savedValue: String): List<SelectOption<String>> {
    val resolved = resolveDirectionValue(savedValue, options)
    val items = options.map { opt ->
        SelectOption(
            value = opt.value,
            label = opt.label,
            subtitle = opt.subtitle,
            badge = opt.badge,
            badgeTone = opt.badgeTone,
            trailingText = opt.liveMinutes?.let { if (it <= 0) "NOW" else "$it'" },
            muted = opt.muted,
        )
    }
    if (resolved.isNotEmpty() && items.none { it.value == resolved }) {
        return items + SelectOption(value = resolved, label = resolved, subtitle = "Saved direction", muted = true)
    }
    return items
}

/** Subtle footnote explaining any arrival-only termini hidden from the sheet. */
internal fun StopDirectionOptions.hiddenFootnote(): String? = when (hiddenTermini.size) {
    0 -> null
    1 -> "${hiddenTermini[0]} hidden (arrivals only)"
    else -> "${hiddenTermini.joinToString(", ")} hidden (arrivals only)"
}
