package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.platform.DepartureQuery
import dev.southstar21.vectra.core.platform.ScheduledNotification
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.transit.TransitRepository
import dev.southstar21.vectra.features.transit.parseStopArrivals
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Departure times for reminders, from the same arrivals feed the Transit tab uses. */
internal class DepartureLookup(private val transit: TransitRepository) {
    /** The next [count] departures of [query] from [fromMs]; null when they can't be fetched. */
    suspend fun times(query: DepartureQuery, fromMs: Long, count: Int = 4): List<Long>? = runSuspendCatching {
        val minutesAfter = ((fromMs - nowMs()) / MinuteMs + 60).toInt().coerceIn(30, FeedReachMinutes)
        val response = transit.api.fetchStopArrivals(query.stopId, minutesAfter)
        parseStopArrivals(response, query.routeId, query.headsign, now = fromMs).take(count).map { it.arrivalMs }
    }.getOrNull()

    /** [times] for each of [queries] at once, in their order; fewer times a line when there are several. */
    suspend fun timesEach(queries: List<DepartureQuery>, fromMs: Long): List<List<Long>?> = coroutineScope {
        val count = if (queries.size > 1) 3 else 4
        queries.map { q -> async { times(q, fromMs, count) } }.awaitAll()
    }

    /**
     * iOS can't run code when a notification fires, so departures go into the body now: the
     * timetable for reminders close enough for the feed to cover them, a pointer to the app otherwise.
     */
    suspend fun bake(plan: List<ScheduledNotification>, now: Long): List<ScheduledNotification> = plan.map { n ->
        if (n.departures.isEmpty()) return@map n
        val times = if (n.atMs - now <= BakeWindowMs) timesEach(n.departures, n.atMs) else n.departures.map { null }
        val lines = n.departures.zip(times) { q, t -> t?.let { departuresLine(q, it) } ?: departuresFallbackLine(q) }
        val footer = if (times.any { it != null }) listOf("Timetable · tap for live times") else emptyList()
        n.copy(body = (listOf(n.body) + lines + footer).joinToString("\n"), departures = emptyList())
    }

    private companion object {
        /** The arrivals feed answers up to this far ahead. */
        const val FeedReachMinutes = 180
        /** How far ahead a reminder's departures can be baked: the feed's reach less the time it lists. */
        const val BakeWindowMs = 150 * MinuteMs
    }
}
