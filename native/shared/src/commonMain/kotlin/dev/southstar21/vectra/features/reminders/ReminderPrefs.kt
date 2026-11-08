package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.platform.DepartureQuery
import kotlinx.serialization.Serializable

/** This device's reminder settings. Off until the user turns them on (they need the permission). */
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
    val toUni: CommuteLeg? = null,
    val backHome: CommuteLeg? = null,
) {
    val anyOn: Boolean get() = beforeClass || nextClass || (departures && (toUni != null || backHome != null))

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
    /** "Tram 6", "Bus 7"; metro and HÉV names ("M4", "H5") already say what they are. */
    val lineLabel: String
        get() = LineTypeLabels[routeType]
            ?.takeUnless { SelfDescribing.matches(routeShortName) }
            ?.let { "$it $routeShortName" }
            ?: routeShortName

    val query: DepartureQuery get() = DepartureQuery(stopId, stopName, routeId, headsign?.ifEmpty { null }, lineLabel)

    private companion object {
        val LineTypeLabels = mapOf("TRAM" to "Tram", "BUS" to "Bus", "TROLLEYBUS" to "Trolley", "FERRY" to "Boat")
        val SelfDescribing = Regex("^[MH]\\d+[A-Z]?$")
    }
}
