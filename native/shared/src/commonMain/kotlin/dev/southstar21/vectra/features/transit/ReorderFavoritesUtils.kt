package dev.southstar21.vectra.features.transit

/** Swap a favorite with its neighbour (direction -1/1) among the favorites, keeping non-favorites in place. */
fun reorderFavoriteSlots(allItems: List<TransitTracker>, favIndex: Int, direction: Int): List<TransitTracker> {
    val currentFavs = allItems.filter { it.isFavorite }
    val target = favIndex + direction
    if (target < 0 || target >= currentFavs.size) return allItems
    val newFavs = currentFavs.toMutableList()
    val temp = newFavs[favIndex]
    newFavs[favIndex] = newFavs[target]
    newFavs[target] = temp
    var favCounter = 0
    return allItems.map { if (it.isFavorite) newFavs[favCounter++] else it }
}

data class RouteNumber(val num: Long, val prefix: String, val raw: String)

private val RouteNumberRe = Regex("^([A-Za-z]*)\\s*(\\d+)")

/** Numeric part of a route name ('M1' → 1, '28 / 28A' → 28); Long.MAX_VALUE-like sentinel when none. */
fun extractRouteNumber(name: String): RouteNumber {
    val m = RouteNumberRe.find(name) ?: return RouteNumber(MaxSafeInteger, "", name)
    return RouteNumber(m.groupValues[2].toLongOrNull() ?: MaxSafeInteger, m.groupValues[1].uppercase(), name)
}

/** Largest integer a double represents exactly (2^53 - 1). */
private const val MaxSafeInteger = 9_007_199_254_740_991L

/** Sort trackers by line number, lowest first; ties by numeric-aware name compare. */
fun sortTrackersByNumber(trackers: List<TransitTracker>): List<TransitTracker> = trackers.sortedWith { a, b ->
    val nameA = formatRouteDisplayName(a.routeShortName, a.routeId)
    val nameB = formatRouteDisplayName(b.routeShortName, b.routeId)
    val na = extractRouteNumber(nameA).num
    val nb = extractRouteNumber(nameB).num
    if (na != nb) na.compareTo(nb) else routeNameCompare(nameA, nameB)
}

/** Move any item one slot (direction -1/1) in the full list. Same instance when out of bounds. */
fun reorderAllSlots(allItems: List<TransitTracker>, index: Int, direction: Int): List<TransitTracker> {
    val target = index + direction
    if (target < 0 || target >= allItems.size) return allItems
    val next = allItems.toMutableList()
    val temp = next[index]
    next[index] = next[target]
    next[target] = temp
    return next
}

/** Drop a new favorites order into the favorite slots, leaving non-favorites in place. */
fun applyFavoriteOrder(allItems: List<TransitTracker>, orderedFavs: List<TransitTracker>): List<TransitTracker> {
    var favCounter = 0
    return allItems.map { item ->
        if (item.isFavorite) (orderedFavs.getOrNull(favCounter) ?: item).also { favCounter++ } else item
    }
}
