package dev.southstar21.vectra.features.transit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceLocationTest {
    private val tracking = WatchLocationState(
        status = WatchLocationStatus.Tracking,
        lat = 47.5,
        lon = 19.05,
        accuracy = 12.0,
        heading = null,
        speed = null,
        lastUpdatedMs = 1_000,
        isManualRefreshing = false,
        errorMessage = null,
        errorCode = null,
    )

    @Test
    fun successMovesFromIdleToTrackingAndRecordsTheFix() {
        val next = applyPositionSuccess(InitialWatchLocationState, PositionFix(47.5, 19.05, 12.0, 90.0, 1.5), 1_000)
        assertEquals(WatchLocationStatus.Tracking, next.status)
        assertEquals(47.5, next.lat)
        assertEquals(19.05, next.lon)
        assertEquals(12.0, next.accuracy)
        assertEquals(90.0, next.heading)
        assertEquals(1.5, next.speed)
        assertEquals(1_000, next.lastUpdatedMs)
        assertNull(next.errorMessage)
        assertNull(next.errorCode)
    }

    @Test
    fun successRefreshesLastUpdatedEvenWhenUnchanged() {
        val next = applyPositionSuccess(tracking, PositionFix(47.5, 19.05, 12.0), 9_000)
        assertEquals(9_000, next.lastUpdatedMs)
        assertEquals(47.5, next.lat)
    }

    @Test
    fun successClearsErrorAndManualRefresh() {
        val errored = tracking.copy(isManualRefreshing = true, errorCode = 3, errorMessage = "Location timed out")
        val next = applyPositionSuccess(errored, PositionFix(47.6, 19.06, 10.0), 2_000)
        assertNull(next.errorCode)
        assertNull(next.errorMessage)
        assertFalse(next.isManualRefreshing)
    }

    @Test
    fun errorWithoutAFixReportsError() {
        val locating = InitialWatchLocationState.copy(status = WatchLocationStatus.Locating)
        val next = applyPositionError(locating, 1, "denied")
        assertEquals(WatchLocationStatus.Error, next.status)
        assertEquals(1, next.errorCode)
        assertEquals(GeoErrorMessages[1], next.errorMessage)
    }

    @Test
    fun errorWhileTrackingKeepsTheFix() {
        val next = applyPositionError(tracking.copy(isManualRefreshing = true), 3, "Timed out")
        assertEquals(WatchLocationStatus.Tracking, next.status)
        assertEquals(47.5, next.lat)
        assertEquals(19.05, next.lon)
        assertFalse(next.isManualRefreshing)
        assertEquals(3, next.errorCode)
    }

    @Test
    fun deniedStopsTracking() {
        assertEquals(WatchLocationStatus.Error, applyPositionError(tracking, 1, "denied").status)
    }

    @Test
    fun staleness() {
        assertTrue(isLocationStale(tracking, 1_000 + 60_000, 30_000))
        assertFalse(isLocationStale(tracking, 1_000 + 5_000, 30_000))
        assertFalse(isLocationStale(InitialWatchLocationState, 999_999, 30_000))
        assertFalse(isLocationStale(InitialWatchLocationState.copy(status = WatchLocationStatus.Locating), 999_999, 30_000))
    }

    @Test
    fun persistenceRoundTrips() {
        val restored = readStoredLocation(storedLocationJson(tracking), 2_000)
        assertNotNull(restored)
        assertEquals(WatchLocationStatus.Tracking, restored.status)
        assertEquals(47.5, restored.lat)
        assertEquals(19.05, restored.lon)
        assertEquals(12.0, restored.accuracy)
        assertEquals(1_000, restored.lastUpdatedMs)
        assertNull(restored.errorMessage)
    }

    @Test
    fun persistenceDropsOldFixes() {
        assertNull(readStoredLocation(storedLocationJson(tracking), 1_000 + StoredLocationMaxAgeMs + 1))
    }

    @Test
    fun persistenceRejectsGarbage() {
        assertNull(readStoredLocation("not json"))
        assertNull(readStoredLocation("""{"lat":47.5}"""))
        assertNull(readStoredLocation("""{"lat":"x","lon":19,"lastUpdatedMs":1}"""))
        assertNull(readStoredLocation(null))
    }

    @Test
    fun noPersistenceWithoutAFix() {
        assertNull(storedLocationJson(InitialWatchLocationState))
        assertNull(storedLocationJson(InitialWatchLocationState.copy(status = WatchLocationStatus.Error)))
    }

    @Test
    fun storageKeyMatchesTheWeb() {
        assertEquals("vectra.deviceLocation", DeviceLocationStorageKey)
    }
}
