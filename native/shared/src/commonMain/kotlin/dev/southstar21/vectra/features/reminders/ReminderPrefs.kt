package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.platform.DepartureQuery
import dev.southstar21.vectra.features.transit.findCombinedGroup
import dev.southstar21.vectra.features.transit.formatRouteDisplayName
import dev.southstar21.vectra.core.network.OneOrManySerializer
import kotlinx.serialization.Serializable

/** Settings saved when each leg held a single line. */
object CommuteLegList : OneOrManySerializer<CommuteLeg>(CommuteLeg.serializer())

/**
 * The reminder settings. Off until the user turns them on (they need the permission). Kept on the
 * device and in the profile's `preferences.reminders`, so they come back after a reinstall.
 */
@Serializable
data class ReminderPrefs(
    /** Before the first class of the day (and the first after a long break): the room, [leadMinutes] ahead. */
    val beforeClass: Boolean = false,
    val leadMinutes: Int = 60,
    /** Five minutes before a class ends: the next class and its room. */
    val nextClass: Boolean = false,
    val lectures: Boolean = true,
    /** Seminars and practice classes. */
    val seminars: Boolean = true,
    val labs: Boolean = true,
    val exams: Boolean = true,
    /** Shifts and tasks with a time count as sessions too. */
    val personalItems: Boolean = false,
    /** Departures of [toUni] / [backHome] in the reminders of the day's first and last sessions. */
    val departures: Boolean = true,
    /** Every line that gets you there ("28A / 37A / 62"), in the order picked; each gets its own line of times. */
    @Serializable(with = CommuteLegList::class)
    val toUni: List<CommuteLeg> = emptyList(),
    @Serializable(with = CommuteLegList::class)
    val backHome: List<CommuteLeg> = emptyList(),
) {
    val anyOn: Boolean get() = beforeClass || nextClass || (departures && (toUni.isNotEmpty() || backHome.isNotEmpty()))

    companion object {
        val LeadOptions = listOf(15, 30, 60)
    }
}

/**
 * One stop of a saved transit tracker, copied when picked so reminders can be planned offline
 * and at boot without the tracker list. The tracker id only marks which row is selected.
 */
@Serializable
data class CommuteLeg(
    val trackerId: String,
    val stopId: String,
    val stopName: String,
    val routeId: String,
    val routeShortName: String,
    val routeType: String = "",
    val routeColor: String = "",
    val routeTextColor: String = "",
    val headsign: String? = null,
) {
    /** The name the Transit tab shows: paired lines read as one ("28 / 28A", "37 / 37A"). */
    val displayName: String get() = formatRouteDisplayName(routeShortName, routeId)

    /** Paired lines share it, so a stop served by 28 and 28A is one pick. */
    val pickKey: String get() = "${findCombinedGroup(routeId, routeShortName)?.name ?: routeId}|$stopId|${headsign.orEmpty()}"

    /** "Tram 6", "Tram 28 / 28A"; metro and HÉV names ("M4", "H5") already say what they are. */
    val lineLabel: String
        get() = LineTypeLabels[routeType]
            ?.takeUnless { SelfDescribing.matches(routeShortName) }
            ?.let { "$it $displayName" }
            ?: displayName

    val query: DepartureQuery get() = DepartureQuery(stopId, stopName, routeId, headsign?.ifEmpty { null }, lineLabel)

    companion object {
        private val LineTypeLabels = mapOf("TRAM" to "Tram", "BUS" to "Bus", "TROLLEYBUS" to "Trolley", "FERRY" to "Boat")
        private val SelfDescribing = Regex("^[MH]\\d+[A-Z]?$")
    }
}
