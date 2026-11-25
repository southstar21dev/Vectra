package dev.southstar21.vectra.features.weather

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.platform.GeoFix
import dev.southstar21.vectra.core.platform.GeoPermission
import dev.southstar21.vectra.core.platform.LocationProvider
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val Now = 1_900_000_000_000L

private const val ForecastBody =
    """{"current":{"temperature_2m":21.3,"apparent_temperature":19.8,"wind_speed_10m":14.2,"weather_code":2},
       "hourly":{"time":["2026-08-09T00:00"],"precipitation_probability":[10,20,30]},
       "daily":{"time":["2026-08-09"],"temperature_2m_max":[24.1],"temperature_2m_min":[13.7]},
       "utc_offset_seconds":7200}"""

private class FakeLocation(
    @Volatile var permission: GeoPermission = GeoPermission.Granted,
    @Volatile var fix: GeoFix? = GeoFix(46.2531, 20.1484, 50.0, timeMs = Now),
    @Volatile var failure: Throwable? = null,
) : LocationProvider {
    override fun permission() = permission
    override suspend fun current(): GeoFix = failure?.let { throw it } ?: fix!!
    override fun watch(): Flow<GeoFix> = emptyFlow()
}

private class WeatherHarness(val location: FakeLocation = FakeLocation()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val kv = KeyValueStore(FakeDataStore())
    /** The first this many forecast requests fail with HTTP 500. */
    @Volatile var forecastFailures = 0
    @Volatile var geocodeBody = """{"city":"Szeged"}"""
    val forecastLatitudes = mutableListOf<String?>()

    private val http = HttpClient(
        MockEngine { req ->
            val json = headersOf("Content-Type", "application/json")
            when {
                req.url.host.contains("open-meteo") -> {
                    forecastLatitudes += req.url.parameters["latitude"]
                    if (forecastLatitudes.size <= forecastFailures) {
                        respond("{}", HttpStatusCode.InternalServerError, json)
                    } else {
                        respond(ForecastBody, HttpStatusCode.OK, json)
                    }
                }
                req.url.host.contains("bigdatacloud") -> respond(geocodeBody, HttpStatusCode.OK, json)
                else -> error("unexpected ${req.url}")
            }
        },
    )

    fun repo(): WeatherRepository = WeatherRepository(http, kv, location, scope, nowMs = { Now })

    suspend fun storedMode() = kv.get(LocationModeKey)
    suspend fun snapshot() = parseWeatherSnapshot(kv.get(WeatherSnapshotKey))
}

private fun scenario(location: FakeLocation = FakeLocation(), test: suspend WeatherHarness.() -> Unit) = runTest {
    val h = WeatherHarness(location)
    try {
        withContext(Dispatchers.Default) { withTimeout(30_000) { h.test() } }
    } finally {
        h.scope.cancel()
    }
}

private suspend fun until(condition: suspend () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(2) }

class WeatherRepositoryTest {
    @Test fun aFreshInstallShowsBudapestWeatherAndStoresASnapshot() = scenario {
        val repo = repo()
        until { repo.weather.value.data != null && snapshot() != null }
        assertEquals(WeatherLocationStatus.Idle, repo.location.value?.status)
        assertEquals(BudapestCoords, repo.currentCoords)
        assertEquals(BudapestCoords, repo.weather.value.dataCoords)
        assertEquals(21.3, repo.weather.value.data?.temperature)
        assertEquals(false, repo.weather.value.isFetching)
        assertEquals(Now, snapshot()?.fetchedAt)
    }

    @Test fun switchingToAutoLocatesRoundsTheFixAndNamesTheCity() = scenario {
        val repo = repo()
        until { repo.weather.value.data != null }
        repo.setMode(WeatherLocationMode.Auto)
        until { repo.location.value?.city == "Szeged" }
        val state = assertNotNull(repo.location.value)
        assertEquals(WeatherLocationMode.Auto, state.mode)
        assertEquals(WeatherLocationStatus.Located, state.status)
        // About 1 km precision: all that ever leaves the device.
        assertEquals(WeatherCoords(46.25, 20.15), state.coords)
        assertEquals("auto", storedMode())
        // The weather follows the new place.
        until { repo.weather.value.dataCoords == WeatherCoords(46.25, 20.15) }
        assertEquals("46.25", forecastLatitudes.last())
        // The stored snapshot carries the city so the next launch paints at once.
        until { snapshot()?.city == "Szeged" }
    }

    @Test fun aFailedGeocodeKeepsTheLocationWithoutACity() = scenario {
        geocodeBody = "{}"
        val repo = repo()
        until { repo.location.value != null }
        repo.setMode(WeatherLocationMode.Auto)
        until { repo.location.value?.status == WeatherLocationStatus.Located }
        delay(100)
        assertNull(repo.location.value?.city)
    }

    @Test fun aDeniedPermissionErrorShowsDeniedAndKeepsBudapestWeather() = scenario(FakeLocation(failure = SecurityException("denied"))) {
        val repo = repo()
        until { repo.location.value != null }
        repo.setMode(WeatherLocationMode.Auto)
        until { repo.location.value?.status == WeatherLocationStatus.Denied }
        assertEquals(WeatherLocationMode.Auto, repo.location.value?.mode)
        assertEquals(BudapestCoords, repo.currentCoords)
    }

    @Test fun switchingBackToBudapestDropsTheDeviceCoordinates() = scenario {
        val repo = repo()
        until { repo.location.value != null }
        repo.setMode(WeatherLocationMode.Auto)
        until { repo.location.value?.status == WeatherLocationStatus.Located }
        repo.setMode(WeatherLocationMode.Budapest)
        until { repo.location.value?.status == WeatherLocationStatus.Budapest }
        assertNull(repo.location.value?.coords)
        assertEquals(BudapestCoords, repo.currentCoords)
        assertEquals("budapest", storedMode())
        until { repo.weather.value.dataCoords == BudapestCoords && !repo.weather.value.isFetching }
    }

    @Test fun aStoredAutoModeWithoutPermissionStartsDenied() = scenario(FakeLocation(permission = GeoPermission.Denied)) {
        kv.set(LocationModeKey, "auto")
        val repo = repo()
        until { repo.location.value?.status == WeatherLocationStatus.Denied }
    }

    @Test fun aStoredAutoModeWhereThePermissionIsOnlyPromptedFallsBack() = scenario(FakeLocation(permission = GeoPermission.Prompt)) {
        kv.set(LocationModeKey, "auto")
        val repo = repo()
        until { repo.location.value?.status == WeatherLocationStatus.Fallback }
    }

    // ---- fetching ----------------------------------------------------------------------------

    @Test fun freshDataIsNotRefetchedUntilForced() = scenario {
        val repo = repo()
        until { repo.weather.value.data != null }
        val after = forecastLatitudes.size
        repo.refreshWeather()
        delay(100)
        assertEquals(after, forecastLatitudes.size)
        repo.refreshNow()
        assertEquals(after + 1, forecastLatitudes.size)
    }

    @Test fun aFailedFetchIsRetriedWithBackoff() = scenario {
        forecastFailures = 2
        val repo = repo()
        until { repo.weather.value.data != null }
        assertEquals(3, forecastLatitudes.size)
        assertNull(repo.weather.value.error)
    }

    @Test fun afterFourFailuresTheErrorIsShownAndFetchingStops() = scenario {
        forecastFailures = 100
        val repo = repo()
        until { repo.weather.value.error != null }
        assertEquals(4, forecastLatitudes.size)
        assertEquals(false, repo.weather.value.isFetching)
        assertNull(repo.weather.value.data)
        assertTrue(repo.weather.value.error?.message.orEmpty().contains("500"))
    }

    @Test fun aFreshStoredSnapshotIsShownBeforeTheNetworkAnswers() = scenario {
        val data = seedSnapshot()
        kv.set(WeatherSnapshotKey, encodeWeatherSnapshot(data))
        forecastFailures = 100
        val repo = repo()
        until { repo.weather.value.data != null }
        assertEquals(data.data, repo.weather.value.data)
        assertEquals(Now - 60_000, repo.weather.value.fetchedAt)
    }

    private suspend fun WeatherHarness.seedSnapshot(): WeatherSnapshot {
        val repo = repo()
        until { repo.weather.value.data != null }
        return WeatherSnapshot(BudapestCoords, null, repo.weather.value.data!!, Now - 60_000)
    }
}
