package dev.southstar21.vectra.features.transit


private const val MaxShortName = 12

/** "Móricz Zsigmond körtér M" → "Móricz": a short name for a label beside a marker. */
fun shortDestination(name: String): String =
    name.split(' ', ',', '/').firstOrNull { it.isNotBlank() }?.take(MaxShortName).orEmpty()

/**
 * The two directions the strip draws: ordered by destination, so the sides never swap between
 * polls, and coming in from opposite sides (two that share an approach would be one side).
 */
internal fun drawnDirections(directions: List<StripDirection>): List<StripDirection> {
    val unique = directions.distinctBy { it.destination }.sortedBy { it.destination }
    val first = unique.firstOrNull() ?: return emptyList()
    val second = unique.drop(1).firstOrNull { it.approach == null || it.approach != first.approach } ?: unique.getOrNull(1)
    return listOfNotNull(first, second).sortedBy { it.destination }
}

/** Directions past the two the strip draws, skipping repeats of a drawn destination. */
private fun undrawn(directions: List<StripDirection>): List<StripDirection> {
    val drawn = drawnDirections(directions).map { it.destination }.toSet()
    return directions.filter { it.arrival != null && it.destination !in drawn }.distinctBy { it.destination }.sortedBy { it.destination }
}

/** The first undrawn direction that comes in the same way as a drawn one: the strip forks for it. */
internal fun forkedDirection(directions: List<StripDirection>): Pair<StripDirection, Int>? {
    val drawn = drawnDirections(directions)
    return undrawn(directions).firstNotNullOfOrNull { e ->
        val i = drawn.indexOfFirst { it.approach != null && it.approach == e.approach }
        if (i < 0) null else e to (if (i == 0) 1 else -1)
    }
}

/** Directions the strip can't draw (not even as a fork): listed under it instead. */
fun extraDirections(directions: List<StripDirection>): List<StripDirection> {
    val forked = forkedDirection(directions)?.first?.destination
    return undrawn(directions).filter { it.destination != forked }
}

/**
 * With a fork two directions share one side, and a strip shows one vehicle each way: on that side
 * the sooner of the two stays, named after where it goes ("Újbuda", "Móricz") so the branch reads.
 */
internal fun forkVehicles(
    drawn: List<StripVehicle>,
    fork: Pair<StripDirection, Int>,
    dirs: List<StripDirection>,
    forkedVehicle: StripVehicle?,
): List<StripVehicle> {
    val (e, heading) = fork
    val sibling = dirs[if (heading > 0) 0 else 1]
    val forkSooner = (e.arrival?.arrivalMs ?: Long.MAX_VALUE) < (sibling.arrival?.arrivalMs ?: Long.MAX_VALUE)
    if (forkedVehicle != null && forkSooner) {
        return drawn.filter { it.heading != heading } + forkedVehicle.copy(sub = forkedVehicle.sub ?: shortDestination(e.destination))
    }
    return drawn.map { v -> if (v.heading == heading && v.sub == null) v.copy(sub = shortDestination(sibling.destination)) else v }
}
