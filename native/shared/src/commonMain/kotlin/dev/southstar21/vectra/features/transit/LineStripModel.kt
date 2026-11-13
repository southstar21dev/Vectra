package dev.southstar21.vectra.features.transit

import androidx.compose.runtime.Immutable
import dev.southstar21.vectra.core.time.formatBudapestHm
import kotlin.math.abs
import kotlin.math.ceil

/** Stops drawn on each side of your stop. */
const val StripSideStops = 3

/** Stops on the whole strip: three each side plus yours. */
const val StripStopCount = StripSideStops * 2 + 1

/** Waits longer than this read as a clock time ("13:42") instead of minutes. */
private const val MaxMinuteLabel = 99

/** Closer than this (in stops) the vehicle counts as at your stop. */
private const val ArrivingDistance = 0.15

enum class StripTimeKind { Live, Scheduled }

/** One vehicle (or, without a position, one departure time) on the strip. */
@Immutable
data class StripVehicle(
    /** In stop units, 0 (left end) to [StripStopCount] - 1 (right end). */
    val position: Float,
    /** +1 heads right (its label sits above the line), -1 heads left (label below). */
    val heading: Int,
    val label: String?,
    val sub: String? = null,
    val kind: StripTimeKind = StripTimeKind.Live,
    /** False: only the time is shown (metro, or a trip without a live position). */
    val showMarker: Boolean = true,
    /** At your stop now. */
    val arriving: Boolean = false,
    /** Last known position of out-of-date data. */
    val faded: Boolean = false,
    val metro: Boolean = false,
    /** The one after the next in the same direction: a quieter label. */
    val second: Boolean = false,
    /** A replacement bus standing in for the line: drawn as a bus. */
    val replacement: Boolean = false,
) {
    /** Drawn where it is, or due at your stop: a vehicle after it can be drawn too. */
    val atYourStopOrPlaced: Boolean get() = showMarker || arriving
}

/** Everything one line strip draws. */
@Immutable
data class StripModel(
    val vehicles: List<StripVehicle> = emptyList(),
    /** Where left-heading vehicles go. */
    val leftEnd: String? = null,
    /** Where right-heading vehicles go. */
    val rightEnd: String? = null,
    /** Parts of the line nothing you track comes from (after your stop, or before a first stop). */
    val dimRight: Boolean = false,
    val dimLeft: Boolean = false,
    /** Your stop is where a drawn direction ends: its vehicles only arrive here. */
    val terminus: Boolean = false,
    /** The line runs on past the edge (a far-away vehicle waits there). */
    val fadeLeft: Boolean = false,
    val fadeRight: Boolean = false,
    /** The line splits towards this end (a third direction): two ends there. */
    val fork: StripFork? = null,
    /** That end's direction is run by replacement buses. */
    val leftBus: Boolean = false,
    val rightBus: Boolean = false,
) {
    val hasLabelsAbove get() = vehicles.any { it.heading > 0 && (it.label != null || it.sub != null) }
    val hasLabelsBelow get() = vehicles.any { it.heading < 0 && (it.label != null || it.sub != null) }
    val hasSubsAbove get() = vehicles.any { it.heading > 0 && it.sub != null }
    val hasSubsBelow get() = vehicles.any { it.heading < 0 && it.sub != null }
}

/** One tracked direction, reduced to what the strip needs. */
@Immutable
data class StripDirection(
    val destination: String,
    val arrival: Arrival?,
    val next: Arrival?,
    /** Stops between the vehicle and your stop (fractional); null without a live position. */
    val vehicleDistance: Double?,
    /** The same for the vehicle of [next]; null without one or without a live position. */
    val nextDistance: Double? = null,
    /** When this direction's last vehicle left your stop, for a minute after it did. */
    val leftAt: Long? = null,
    /** The stop before yours on the way in: directions sharing it come from the same side. */
    val approach: String? = null,
    /** Trips start at your stop: vehicles wait here instead of coming in. */
    val origin: Boolean = false,
    /** Trips end at your stop. */
    val terminus: Boolean = false,
    /** Its next trip is a replacement bus standing in for the line. */
    val replacement: Boolean = false,
)

/** A line that splits past the end of the strip: [heading]'s end has a second destination. */
@Immutable
data class StripFork(val heading: Int, val destination: String)

/** A departed vehicle stays on the strip, faded, this long. */
const val JustLeftMs = 60_000L

/** How far past your stop (in stops) a just-departed vehicle is drawn. */
private const val JustLeftOffset = 0.55f

/**
 * The strip's clock ticks every few seconds while a departure is stamped the moment it is seen,
 * so a departure can read as a little in the future; it shows straight away all the same.
 */
private const val ClockSlackMs = 10_000L

/** Two labels on one side closer than this (in stops) would overlap: the later one drops its time. */
internal const val LabelGapStops = 1.4f

/** "NOW", "4'" or, past 99 minutes, the clock time. */
fun stripTimeLabel(arrivalMs: Long, now: Long): String {
    val minutes = etaMinutes(arrivalMs, now)
    return when {
        minutes <= 0 -> "NOW"
        minutes > MaxMinuteLabel -> formatBudapestHm(arrivalMs)
        else -> "$minutes'"
    }
}

/**
 * The strip for up to two directions. The first (by destination name, so the sides never
 * swap between polls) heads right, the second left. Metro trains are never placed on the
 * line (the feed has no positions): their times sit at the end they come from, as do trips
 * without a live position. [frozen]: the data is out of date, so nothing reads as live.
 */
fun buildStripModel(directions: List<StripDirection>, now: Long, metro: Boolean, frozen: Boolean): StripModel {
    val dirs = drawnDirections(directions)
    val fork = forkedDirection(directions)
    val drawn = dirs.flatMapIndexed { i, d -> directionVehicles(d, if (i == 0) 1 else -1, now, metro, frozen) }
    val vehicles = if (fork == null) drawn else forkVehicles(drawn, fork, dirs, stripVehicle(fork.first, fork.second, now, metro, frozen))
    fun far(heading: Int) = vehicles.any { it.heading == heading && it.sub?.endsWith(" stops") == true && it.showMarker }
    val right = dirs.getOrNull(0)
    val left = dirs.getOrNull(1)
    return StripModel(
        vehicles = vehicles,
        leftEnd = left?.destination?.takeIf { it.isNotEmpty() },
        rightEnd = right?.destination?.takeIf { it.isNotEmpty() },
        // One direction: the other side is greyed, except at its first stop, where the way in is.
        dimRight = (dirs.size == 1 && right?.origin != true) || right?.terminus == true || left?.origin == true,
        dimLeft = right?.origin == true || left?.terminus == true,
        terminus = dirs.any { it.terminus },
        fadeLeft = far(1),
        fadeRight = far(-1),
        fork = fork?.let { (e, heading) -> StripFork(heading, e.destination) },
        leftBus = left?.replacement == true,
        rightBus = right?.replacement == true,
    )
}

/**
 * A direction's vehicles: the next one, the one after it while it is on the strip (at most
 * two, its time dropped when the labels would overlap), and for a minute after one leaves,
 * that one just past your stop, faded.
 */
private fun directionVehicles(d: StripDirection, heading: Int, now: Long, metro: Boolean, frozen: Boolean): List<StripVehicle> {
    val first = stripVehicle(d, heading, now, metro, frozen) ?: return emptyList()
    val all = listOf(first) + listOfNotNull(secondVehicle(d, first, heading, now, frozen), justLeft(d, heading, now))
    return if (d.replacement) all.map { it.copy(replacement = true) } else all
}

/**
 * The one after the next, while it is on the strip; its time dropped when the labels would
 * overlap. Drawn when the next one is (or is due at your stop), never ahead of an unplaced one.
 */
private fun secondVehicle(d: StripDirection, first: StripVehicle, heading: Int, now: Long, frozen: Boolean): StripVehicle? {
    val next = d.next
    val dist = d.nextDistance?.takeIf { it <= StripSideStops && it > (d.vehicleDistance ?: 0.0) }
    if (next == null || dist == null || !first.atYourStopOrPlaced) return null
    val pos = (StripSideStops - heading * dist).toFloat()
    val crowded = abs(pos - first.position) < LabelGapStops
    return StripVehicle(pos, heading, if (crowded) null else stripTimeLabel(next.arrivalMs, now), null, timeKind(next, false, frozen), faded = frozen, second = true)
}

/** For a minute after a vehicle leaves your stop, it stays just past it, faded. */
private fun justLeft(d: StripDirection, heading: Int, now: Long): StripVehicle? =
    d.leftAt?.takeIf { now - it in -ClockSlackMs until JustLeftMs }?.let {
        StripVehicle(StripSideStops + heading * JustLeftOffset, heading, null, "just left", StripTimeKind.Scheduled, faded = true)
    }

/** Only a live prediction (or the metro, which runs to its timetable) reads as live. */
private fun timeKind(a: Arrival, metro: Boolean, frozen: Boolean) =
    if ((a.isPredicted || metro) && !frozen) StripTimeKind.Live else StripTimeKind.Scheduled

/** The clock time under a minutes label; none when the label is already a clock time or "NOW". */
private fun clockSub(a: Arrival, minutes: Int): String? =
    if (minutes > MaxMinuteLabel || minutes <= 0) null else formatBudapestHm(a.arrivalMs)

/** One direction's next departure on the strip; [heading] +1 comes in from the left. */
private fun stripVehicle(d: StripDirection, heading: Int, now: Long, lineIsMetro: Boolean, frozen: Boolean): StripVehicle? {
    val a = d.arrival ?: return null
    // A replacement bus for the metro reports its own position like any bus.
    val metro = lineIsMetro && !d.replacement
    val minutes = etaMinutes(a.arrivalMs, now)
    val label = stripTimeLabel(a.arrivalMs, now)
    val kind = timeKind(a, metro, frozen)
    val edge = unplacedAt(heading, minutes)
    val dist = d.vehicleDistance
    // The metro reports no GPS: its distance is the timetable estimate, drawn like a live one.
    val vehicle = when {
        d.origin -> originVehicle(a, dist, heading, label, kind, minutes, frozen)
        dist == null && metro -> StripVehicle(
            edge, heading, label, d.next?.let { "then ${stripTimeLabel(it.arrivalMs, now)}" }, kind,
            showMarker = false, arriving = minutes <= 0,
        )
        dist == null -> StripVehicle(edge, heading, label, clockSub(a, minutes), kind, showMarker = false, arriving = minutes <= 0)
        else -> placedVehicle(dist, heading, label, kind, minutes, frozen)
    }
    return if (metro) vehicle.copy(metro = true) else vehicle
}

/** At a trip's first stop the vehicle waits at your stop (when it reports a position) until it leaves. */
private fun originVehicle(a: Arrival, dist: Double?, heading: Int, label: String, kind: StripTimeKind, minutes: Int, frozen: Boolean) =
    StripVehicle(
        StripSideStops.toFloat(), heading, label, if (dist != null && minutes > 0) "waiting" else clockSub(a, minutes), kind,
        showMarker = dist != null, arriving = minutes <= 0, faded = frozen,
    )

/** A live vehicle on the way in: at your stop, on the strip, or waiting at its edge when far off. */
private fun placedVehicle(dist: Double, heading: Int, label: String, kind: StripTimeKind, minutes: Int, frozen: Boolean): StripVehicle {
    val you = StripSideStops.toFloat()
    val edge = if (heading > 0) 0f else (StripStopCount - 1).toFloat()
    return when {
        minutes <= 0 -> StripVehicle(you, heading, "NOW", null, kind, arriving = true, faded = frozen)
        dist < ArrivingDistance -> StripVehicle(you, heading, label, null, kind, faded = frozen)
        dist > StripSideStops -> StripVehicle(edge, heading, label, "${ceil(dist).toInt()} stops", kind, faded = frozen)
        else -> StripVehicle((you - heading * dist).toFloat(), heading, label, null, kind, faded = frozen)
    }
}

/** What the line's vehicles are called, for "No trams coming right now"; null for a generic line. */
fun vehicleNoun(routeType: String?): String? = when (routeType) {
    "TRAM" -> "trams"
    "BUS", "COACH" -> "buses"
    "TROLLEYBUS" -> "trolleybuses"
    "SUBWAY", "RAIL", "SUBURBAN_RAILWAY" -> "trains"
    "FERRY" -> "boats"
    else -> null
}
