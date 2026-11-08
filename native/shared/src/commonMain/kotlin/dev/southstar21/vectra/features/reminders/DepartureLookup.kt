package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.platform.DepartureQuery
import dev.southstar21.vectra.core.platform.ScheduledNotification
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.transit.TransitRepository
import dev.southstar21.vectra.features.transit.parseStopArrivals

/** Departure times for reminders, from the same arrivals feed the Transit tab uses. */
internal class DepartureLookup(private val transit: TransitRepository) {
    /** The next [count] departures of [query] from [fromMs]; null when they can't be fetched. */
    suspend fun times(query: DepartureQuery, fromMs: Long, count: Int = 4): List<Long>? = runSuspendCatching {
        val minutesAfter = ((fromMs - nowMs()) / MinuteMs + 60).toInt().coerceIn(30, FeedReachMinutes)
        val response = transit.api.fetchStopArrivals(query.stopId, minutesAfter)
        parseStopArrivals(response, query.routeId, query.headsign, now = fromMs).take(count).map { it.arrivalMs }
    }.getOrNull()

    /**
     * iOS can't run code when a notification fires, so departures go into the body now: the
     * timetable for reminders close enough for the feed to cover them, a pointer to the app otherwise.
     */
    suspend fun bake(plan: List<ScheduledNotification>, now: Long): List<ScheduledNotification> = plan.map { n ->
        val q = n.departures ?: return@map n
        val line = if (n.atMs - now <= BakeWindowMs) {
            times(q, n.atMs)?.let { departuresLine(q, it) + "\nTimetable · tap for live times" }
        } else null
        n.copy(body = n.body + "\n" + (line ?: departuresFallbackLine(q)), departures = null)
    }

    private companion object {
        /** The arrivals feed answers up to this far ahead. */
        const val FeedReachMinutes = 180
        /** How far ahead a reminder's departures can be baked: the feed's reach less the time it lists. */
        const val BakeWindowMs = 150 * MinuteMs
    }
}
