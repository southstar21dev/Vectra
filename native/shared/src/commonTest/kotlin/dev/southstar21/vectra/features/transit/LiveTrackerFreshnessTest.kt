package dev.southstar21.vectra.features.transit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The live tracker's stale and offline states (audit 03 #3). */
class LiveTrackerFreshnessTest {
    /** 14:05 in Budapest. */
    private val at = 1_794_315_900_000L
    private val arrival = Arrival(arrivalMs = at + 5 * 60_000, headSign = "X", isPredicted = true, tripId = "t")

    private fun f(now: Long, offline: Boolean = false, arrivalsAt: Long = at, vehiclesAt: Long = at) =
        liveFreshness(arrivalsAt, vehiclesAt, now, offline)

    @Test fun freshDataCountsDownLive() {
        val fresh = f(at + 30_000)
        assertFalse(fresh.arrivalsStale)
        assertEquals(at + 30_000, fresh.countdownNow)
        assertNull(liveMapNotice(fresh, at, arrivalsUnavailable = false, noDepartures = false))
        assertEquals("Next update in 3s", liveFooterText(fresh, at, loaded = true, refetching = false, secondsToNext = 3))
    }

    @Test fun offlineFreezesTheCountdownAtTheSnapshot() {
        val offline = f(at + 10 * 60_000, offline = true)
        assertTrue(offline.arrivalsStale)
        assertEquals(at, offline.countdownNow)
        // Ten minutes later it still reads the snapshot's 5 minutes, not "NOW".
        assertEquals(5, etaMinutes(arrival.arrivalMs, offline.countdownNow))
        assertEquals("Offline · departures as of 14:05", liveMapNotice(offline, at, arrivalsUnavailable = false, noDepartures = false))
        assertEquals("Offline · live position paused", liveFooterText(offline, at, loaded = true, refetching = false, secondsToNext = 0))
    }

    @Test fun aSnapshotOlderThanAMinuteIsDelayed() {
        val late = f(at + ArrivalsStaleMs + 1)
        assertTrue(late.arrivalsStale)
        assertTrue(late.vehiclesStale)
        assertEquals(at, late.countdownNow)
        assertEquals("Departure times delayed · as of 14:05", liveMapNotice(late, at, arrivalsUnavailable = true, noDepartures = false))
        assertEquals("Live position delayed · last update 14:05", liveFooterText(late, at, loaded = true, refetching = false, secondsToNext = 0))
    }

    @Test fun nothingLoadedYetHasNothingToFreeze() {
        val empty = f(at, offline = true, arrivalsAt = 0, vehiclesAt = 0)
        assertFalse(empty.arrivalsStale)
        assertFalse(empty.vehiclesStale)
        assertEquals(at, empty.countdownNow)
        assertEquals("No upcoming departures right now", liveMapNotice(empty, 0, arrivalsUnavailable = false, noDepartures = true))
    }

    @Test fun aFailedFetchWithFreshDataStillSaysUnavailable() {
        assertEquals(
            "Departure times unavailable · showing route",
            liveMapNotice(f(at + 1_000), at, arrivalsUnavailable = true, noDepartures = false),
        )
    }

    @Test fun onlineFooterKeepsItsLoadingStates() {
        assertEquals("Preparing live position…", liveFooterText(f(at, vehiclesAt = 0), 0, loaded = false, refetching = false, secondsToNext = null))
        assertEquals("Updating live position…", liveFooterText(f(at + 5_000), at, loaded = true, refetching = true, secondsToNext = 5))
    }
}
