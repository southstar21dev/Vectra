package dev.southstar21.vectra.features.weather

import kotlin.test.Test
import kotlin.test.assertEquals

class LocationToggleTest {
    @Test
    fun toggleIsOnOnlyWhileLocationIsReallyUsed() {
        assertEquals(true, locationToggleOn(WeatherLocationMode.Auto, WeatherLocationStatus.Located))
        assertEquals(true, locationToggleOn(WeatherLocationMode.Auto, WeatherLocationStatus.Locating))
        assertEquals(false, locationToggleOn(WeatherLocationMode.Auto, WeatherLocationStatus.Denied))
        assertEquals(false, locationToggleOn(WeatherLocationMode.Auto, WeatherLocationStatus.Fallback))
        assertEquals(false, locationToggleOn(WeatherLocationMode.Budapest, WeatherLocationStatus.Budapest))
        assertEquals(false, locationToggleOn(null, WeatherLocationStatus.Idle))
    }
}
