package dev.southstar21.vectra.features.weather

import kotlin.test.Test
import kotlin.test.assertEquals

class WeatherAgeTest {
    private val min = 60_000L

    @Test
    fun freshDataHasNoLabel() {
        assertEquals(null, weatherAgeLabel(fetchedAt = 0, now = 14 * min))
    }

    @Test
    fun staleDataSaysHowOldItIs() {
        assertEquals("Updated 15 min ago", weatherAgeLabel(0, 15 * min))
        assertEquals("Updated 2 h ago", weatherAgeLabel(0, 150 * min))
    }

    @Test
    fun dataPastTheSnapshotLimitIsTooOld() {
        assertEquals(false, isWeatherTooOld(0, WeatherSnapshotMaxAgeMs - 1))
        assertEquals(true, isWeatherTooOld(0, WeatherSnapshotMaxAgeMs))
    }
}
