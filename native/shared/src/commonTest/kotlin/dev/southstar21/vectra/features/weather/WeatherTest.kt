package dev.southstar21.vectra.features.weather

import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.maps.nullSafeExpr
import dev.southstar21.vectra.core.maps.sanitizeOpenFreeMapStyle
import dev.southstar21.vectra.core.maps.withParkColors
import dev.southstar21.vectra.core.network.VectraJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

class WeatherApiTest {
    @Test fun mapsWmoCodes() {
        assertEquals("Clear", weatherCodeMeta(0).label)
        assertSame(RemixIcons.SunLine, weatherCodeMeta(0).icon)
        assertEquals("Overcast", weatherCodeMeta(3).label)
        assertEquals("Thunderstorm", weatherCodeMeta(95).label)
        assertSame(RemixIcons.ThunderstormsLine, weatherCodeMeta(95).icon)
    }

    @Test fun fallsBackForUnmappedCodes() {
        val f = weatherCodeMeta(999)
        assertEquals("Weather", f.label)
        assertSame(RemixIcons.CloudyLine, f.icon)
        assertEquals(WeatherSky.Cloudy, f.sky)
    }

    @Test fun parsesValidResponse() {
        val parsed = parseWeatherResponse(
            VectraJson.parseToJsonElement(
                """{"current":{"temperature_2m":21.3,"apparent_temperature":19.8,"wind_speed_10m":14.2,"weather_code":2},
                   "hourly":{"time":["2026-08-09T00:00"],"precipitation_probability":[10,20,30]},
                   "daily":{"time":["2026-08-09"],"temperature_2m_max":[24.1],"temperature_2m_min":[13.7]},
                   "utc_offset_seconds":7200,"extra":true}""",
            ),
        )
        assertEquals(21.3, parsed.temperature)
        assertEquals(3, parsed.precipitationProbability.size)
        assertEquals(24.1, parsed.dailyMax[0])
    }

    @Test fun rejectsMalformedResponse() {
        assertFailsWith<WeatherShapeException> {
            parseWeatherResponse(VectraJson.parseToJsonElement("""{"current":{"temperature_2m":"warm"}}"""))
        }
    }

    @Test fun picksCurrentHourProbability() {
        val now = Instant.parse("2026-08-09T10:30:00Z")
        val probs = List<Double?>(24) { if (it == 12) 70.0 else 0.0 }
        assertEquals(70.0, hourlyRainProbability(probs, 7200, now))
    }

    @Test fun nullOrMissingProbabilityIsZero() {
        val now = Instant.parse("2026-08-09T10:30:00Z")
        assertEquals(0.0, hourlyRainProbability(List(24) { null }, 7200, now))
        assertEquals(0.0, hourlyRainProbability(emptyList(), 7200, now))
    }

    @Test fun localHourForOffsets() {
        val now = Instant.parse("2026-08-09T10:30:00Z")
        assertEquals(12, currentHourAtOffset(7200, now))
        assertEquals(11, currentHourAtOffset(3600, now))
        assertEquals(10, currentHourAtOffset(0, now))
        assertEquals(5, currentHourAtOffset(-18000, now))
    }

    @Test fun wrapsAcrossMidnight() {
        val now = Instant.parse("2026-08-09T23:30:00Z")
        assertEquals(0, currentHourAtOffset(3600, now))
        assertEquals(22, currentHourAtOffset(-3600, now))
    }
}

private fun weather(
    temperature: Double = 20.0,
    windSpeed: Double = 10.0,
    rainProbability: Double = 0.0,
) = WeatherData(temperature, 20.0, windSpeed, 2, rainProbability, 24.0, 13.0)

class WeatherAlertsTest {
    @Test fun benign() = assertEquals(emptyList(), weatherAlertItems(weather()))

    @Test fun wind() {
        assertEquals(emptyList(), weatherAlertItems(weather(windSpeed = 24.9)))
        assertEquals(listOf(WeatherAlertItem(WeatherAlertKey.HighWind, "Strong wind")), weatherAlertItems(weather(windSpeed = 25.0)))
        assertEquals(listOf(WeatherAlertItem(WeatherAlertKey.HighWind, "Strong wind")), weatherAlertItems(weather(windSpeed = 41.2)))
    }

    @Test fun heat() {
        assertEquals(emptyList(), weatherAlertItems(weather(temperature = 29.9)))
        assertEquals(listOf(WeatherAlertItem(WeatherAlertKey.Heat, "High temperature")), weatherAlertItems(weather(temperature = 30.0)))
        assertEquals(listOf(WeatherAlertItem(WeatherAlertKey.Heat, "High temperature")), weatherAlertItems(weather(temperature = 31.2)))
    }

    @Test fun cold() {
        assertEquals(emptyList(), weatherAlertItems(weather(temperature = 5.1)))
        assertEquals(listOf(WeatherAlertItem(WeatherAlertKey.Cold, "Low temperature")), weatherAlertItems(weather(temperature = 5.0)))
        assertEquals(listOf(WeatherAlertItem(WeatherAlertKey.Cold, "Low temperature")), weatherAlertItems(weather(temperature = 4.4)))
    }

    @Test fun rain() {
        assertEquals(emptyList(), weatherAlertItems(weather(rainProbability = 49.0)))
        assertEquals(listOf(WeatherAlertItem(WeatherAlertKey.Rain, "High rain chance")), weatherAlertItems(weather(rainProbability = 50.0)))
        assertEquals(listOf(WeatherAlertItem(WeatherAlertKey.Rain, "High rain chance")), weatherAlertItems(weather(rainProbability = 88.0)))
    }

    @Test fun multipleInFixedOrder() {
        val keys = weatherAlertItems(weather(temperature = 31.2, windSpeed = 33.7, rainProbability = 88.0)).map { it.key }
        assertEquals(listOf(WeatherAlertKey.HighWind, WeatherAlertKey.Heat, WeatherAlertKey.Rain), keys)
    }
}

private val WeatherSample = WeatherData(21.0, 20.0, 12.0, 3, 25.0, 24.0, 14.0)

private fun snapshot(
    coords: WeatherCoords = WeatherCoords(47.497912, 19.040256),
    city: String? = "Budapest",
    fetchedAt: Long = 1_000,
) = WeatherSnapshot(coords, city, WeatherSample, fetchedAt)

class WeatherSnapshotTest {
    @Test fun roundTrips() {
        val s = snapshot()
        assertEquals(s, parseWeatherSnapshot(encodeWeatherSnapshot(s)))
    }

    @Test fun readsTheWebShape() {
        val raw = """{"coords":{"latitude":47.5,"longitude":19.05},"city":null,"data":{"temperature":21,"feelsLike":20,"windSpeed":12,"weatherCode":3,"rainProbability":25,"high":24,"low":14},"fetchedAt":1000}"""
        val s = assertNotNull(parseWeatherSnapshot(raw))
        assertNull(s.city)
        assertEquals(3, s.data.weatherCode)
    }

    @Test fun rejectsIncompleteOrGarbage() {
        assertNull(parseWeatherSnapshot(null))
        assertNull(parseWeatherSnapshot("not json"))
        assertNull(parseWeatherSnapshot("""{"coords":{"latitude":1},"data":{},"fetchedAt":1}"""))
        assertNull(parseWeatherSnapshot("""{"coords":{"latitude":1,"longitude":2},"data":{"temperature":"hot"},"fetchedAt":1}"""))
    }

    @Test fun roundsCoordsTo4Places() {
        assertEquals(WeatherCoords(47.4979, 19.0403), roundCoords(WeatherCoords(47.497912, 19.040256)))
        assertEquals(true, coordsMatch(WeatherCoords(47.49791, 19.04031), WeatherCoords(47.49789, 19.04034)))
        assertEquals(false, coordsMatch(WeatherCoords(47.5, 19.0), WeatherCoords(47.6, 19.0)))
    }

    @Test fun freshness() {
        assertEquals(true, isSnapshotFresh(snapshot(), 1_000 + WeatherSnapshotMaxAgeMs))
        assertEquals(false, isSnapshotFresh(snapshot(), 1_001 + WeatherSnapshotMaxAgeMs))
    }

    @Test fun cityUpdates() {
        val base = snapshot(city = "Budapest")
        assertEquals("Buda", snapshotWithCity(base, base.coords, "Buda")?.city)
        // Different location: untouched.
        assertNull(snapshotWithCity(base, WeatherCoords(1.0, 2.0), "Elsewhere"))
        // Same name: nothing to write.
        assertNull(snapshotWithCity(base, base.coords, "Budapest"))
        // A failed geocode never downgrades a known name.
        assertNull(snapshotWithCity(base, base.coords, null))
        assertEquals("Pest", snapshotWithCity(snapshot(city = null), base.coords, "Pest")?.city)
    }
}

private fun json(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

class WeatherLocationTest {
    @Test fun storedMode() {
        assertEquals(WeatherLocationMode.Budapest, parseLocationMode(null))
        assertEquals(WeatherLocationMode.Auto, parseLocationMode("auto"))
        assertEquals(WeatherLocationMode.Budapest, parseLocationMode("budapest"))
        assertEquals(WeatherLocationMode.Budapest, parseLocationMode("garbage"))
    }

    @Test fun seedsFromFreshAutoSnapshot() {
        assertEquals(
            WeatherLocationState(WeatherLocationMode.Auto, WeatherCoords(47.4979, 19.0403), "Budapest", WeatherLocationStatus.Located),
            resolveInitialWeatherLocation(WeatherLocationMode.Auto, snapshot(), 1_000 + 60_000),
        )
    }

    @Test fun ignoresStaleSnapshot() = assertEquals(
        WeatherLocationState(WeatherLocationMode.Auto, null, null, WeatherLocationStatus.Idle),
        resolveInitialWeatherLocation(WeatherLocationMode.Auto, snapshot(), 1_000 + 25 * 60 * 60_000L),
    )

    @Test fun ignoresSnapshotInBudapestMode() = assertEquals(
        WeatherLocationState(WeatherLocationMode.Budapest, null, null, WeatherLocationStatus.Idle),
        resolveInitialWeatherLocation(WeatherLocationMode.Budapest, snapshot(), 2_000),
    )

    @Test fun idleWithoutCache() = assertEquals(
        WeatherLocationState(WeatherLocationMode.Budapest, null, null, WeatherLocationStatus.Idle),
        resolveInitialWeatherLocation(WeatherLocationMode.Budapest, null, 2_000),
    )

    @Test fun nullSnapshotCityStaysNull() {
        val s = snapshot(coords = WeatherCoords(2.1111, 3.2222), city = null)
        val r = resolveInitialWeatherLocation(WeatherLocationMode.Auto, s, 1_000)
        assertNull(r.city)
        assertEquals(WeatherCoords(2.1111, 3.2222), r.coords)
    }

    @Test fun parsesGeoCity() {
        assertEquals("Budapest", parseGeoCity(json("city" to "Budapest", "locality" to "Buda Castle Quarter", "principalSubdivision" to "Budapest")))
        assertEquals("Budaörs", parseGeoCity(json("locality" to "Budaörs", "principalSubdivision" to "Pest")))
        assertEquals("Pest", parseGeoCity(json("principalSubdivision" to "Pest")))
        assertNull(parseGeoCity(JsonNull))
        assertNull(parseGeoCity(JsonPrimitive("nope")))
        assertNull(parseGeoCity(json("city" to "   ")))
        assertNull(parseGeoCity(buildJsonObject { }))
    }

    private val located = WeatherLocationState(WeatherLocationMode.Auto, WeatherCoords(47.5, 19.05), "Budapest", WeatherLocationStatus.Located)

    @Test fun positionAppliesInAutoMode() {
        val next = applyPositionState(located.copy(status = WeatherLocationStatus.Locating), WeatherCoords(1.0, 2.0))
        assertEquals(WeatherCoords(1.0, 2.0), next.coords)
        assertEquals(WeatherLocationStatus.Located, next.status)
    }

    @Test fun positionDroppedAfterSwitchingToBudapest() {
        val budapest = WeatherLocationState(WeatherLocationMode.Budapest, null, null, WeatherLocationStatus.Budapest)
        assertEquals(budapest, applyPositionState(budapest, WeatherCoords(1.0, 2.0)))
    }

    @Test fun deniedClearsLocation() =
        assertEquals(WeatherLocationState(WeatherLocationMode.Auto, null, null, WeatherLocationStatus.Denied), applyErrorState(located, GeoErrorCode.PermissionDenied))

    @Test fun transientErrorsKeepCoords() {
        assertEquals(located, applyErrorState(located.copy(status = WeatherLocationStatus.Locating), GeoErrorCode.Timeout))
        assertEquals(located, applyErrorState(located.copy(status = WeatherLocationStatus.Locating), GeoErrorCode.PositionUnavailable))
    }

    @Test fun transientErrorWithoutCoordsFallsBack() {
        val none = WeatherLocationState(WeatherLocationMode.Auto, null, null, WeatherLocationStatus.Locating)
        assertEquals(WeatherLocationStatus.Fallback, applyErrorState(none, GeoErrorCode.Timeout).status)
    }

    @Test fun errorsIgnoredOutsideAutoMode() {
        val budapest = WeatherLocationState(WeatherLocationMode.Budapest, null, null, WeatherLocationStatus.Budapest)
        assertEquals(budapest, applyErrorState(budapest, GeoErrorCode.PermissionDenied))
    }

    @Test fun titles() {
        assertEquals("Budapest", weatherLocationTitle(located.copy(coords = null)))
        assertEquals("Budapest", weatherLocationTitle(located))
        assertEquals("My location", weatherLocationTitle(located.copy(city = null)))
    }
}

class RadarApiTest {
    private val sample = VectraJson.parseToJsonElement(
        """{"version":"2.0","generated":1725576000,"host":"https://tilecache.rainviewer.com",
           "radar":{"past":[{"time":1725572400,"path":"/v2/radar/1725572400"},{"time":1725574200,"path":"/v2/radar/1725574200"},{"time":1725576000,"path":"/v2/radar/1725576000"}]}}""",
    )

    @Test fun parses() = assertEquals(3, parseRainViewerResponse(sample).past.size)

    @Test fun normalizes() {
        val n = normalizeRadarData(parseRainViewerResponse(sample))
        assertEquals(3, n.radarFrames.size)
        assertEquals(1725572400, n.radarFrames[0].time)
        assertEquals(1725576000, n.latestPastTime)
    }

    @Test fun ignoresNowcast() {
        val withNowcast = VectraJson.parseToJsonElement(
            """{"generated":1725576000,"radar":{"past":[{"time":1725576000,"path":"/a"}],"nowcast":[{"time":1725576600,"path":"/b"}]}}""",
        )
        val n = normalizeRadarData(parseRainViewerResponse(withNowcast))
        assertEquals(1, n.radarFrames.size)
        assertEquals(1725576000, n.latestPastTime)
        assertEquals(RainViewerDefaultHost, n.host)
    }

    @Test fun stringTimesAndDefaults() {
        val r = parseRainViewerResponse(VectraJson.parseToJsonElement("""{"radar":{"past":[{"time":"12","path":"/x"},{"time":"nope","path":"/y"}]}}"""), nowSeconds = 99)
        assertEquals(listOf(12L, 0L), r.past.map { it.time })
        assertEquals(99, r.generated)
        assertEquals(99, normalizeRadarData(parseRainViewerResponse(VectraJson.parseToJsonElement("{}"), nowSeconds = 99)).latestPastTime)
    }

    @Test fun tileUrls() = assertEquals(
        "https://tilecache.rainviewer.com/v2/radar/1725576000/256/{z}/{x}/{y}/1/1_1.png",
        getRadarTileUrl("https://tilecache.rainviewer.com", "/v2/radar/1725576000", 1, "1_1"),
    )

    @Test fun frameLabels() {
        val latest = 1725576000L
        assertEquals("-60m", formatRadarFrameLabel(RadarFrame(1725572400, "/p"), latest).relative)
        assertEquals("Now", formatRadarFrameLabel(RadarFrame(1725576000, "/n"), latest).relative)
        assertEquals("Now", formatRadarFrameLabel(RadarFrame(1725576000 - 240, "/n"), latest).relative)
        // 2024-09-05T22:40Z is 00:40 in Budapest.
        assertEquals("00:40", formatRadarFrameLabel(RadarFrame(1725576000, "/n"), latest).timeStr)
    }
}

class RadarStyleTest {
    @Test fun nullSafeComparisons() {
        val expr = VectraJson.parseToJsonElement("""["all",["<=",["get","ref_length"],6],["==","class",null]]""")
        assertEquals(
            """["all",["<=",["coalesce",["get","ref_length"],999],6],["==","class",0]]""",
            nullSafeExpr(expr).toString(),
        )
    }

    @Test fun sanitizesAndAddsParks() {
        val style = VectraJson.parseToJsonElement(
            """{"version":8,"sources":{"openmaptiles":{"type":"vector"}},"layers":[
                {"id":"bg","type":"background"},
                {"id":"wood","type":"fill","paint":{"fill-pattern":"wood-pattern","fill-color":"#000"}},
                {"id":"road","type":"line","source-layer":"transportation"},
                {"id":"label","type":"symbol","layout":{"text-font":["OpenSans Regular"]}}]}""",
        ) as JsonObject
        val out = withParkColors(sanitizeOpenFreeMapStyle(style), isLight = false)
        val ids = (out["layers"] as JsonArray).map { (it as JsonObject)["id"].toString().trim('"') }
        assertEquals(listOf("bg", "wood", "vectra-water-fill", "vectra-park-landcover", "vectra-park-landuse", "road", "label"), ids)
        val text = out.toString()
        assertEquals(false, "fill-pattern" in text)
        assertEquals(true, "Noto Sans Regular" in text)
    }
}
