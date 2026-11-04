package dev.southstar21.vectra.features.weather

import androidx.compose.ui.graphics.vector.ImageVector
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.util.formatCompactNumber
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/** Open-Meteo forecast API (keyless). */
@Serializable
data class WeatherCoords(val latitude: Double, val longitude: Double)

/** Fixed Budapest location — fallback when geolocation is off or not granted. */
val BudapestCoords = WeatherCoords(47.4979, 19.0402)

@Serializable
data class WeatherData(
    /** Current temperature, °C. */
    val temperature: Double,
    /** Feels-like temperature, °C. */
    val feelsLike: Double,
    /** Wind speed, km/h. */
    val windSpeed: Double,
    /** WMO weather code (0–99). */
    val weatherCode: Int,
    /** Chance of precipitation in the current hour, percent. */
    val rainProbability: Double,
    /** Today's max temperature, °C. */
    val high: Double,
    /** Today's min temperature, °C. */
    val low: Double,
)

/** Sky family of a condition — drives the weather card's tint and artwork. */
enum class WeatherSky { Clear, Partly, Cloudy, Fog, Rain, Snow, Storm }

data class WeatherConditionMeta(val label: String, val icon: ImageVector, val sky: WeatherSky)

private fun meta(label: String, icon: () -> ImageVector, sky: WeatherSky) = Triple(label, icon, sky)

private val WmoMeta: Map<Int, Triple<String, () -> ImageVector, WeatherSky>> = mapOf(
    0 to meta("Clear", { RemixIcons.SunLine }, WeatherSky.Clear),
    1 to meta("Mostly clear", { RemixIcons.SunCloudyLine }, WeatherSky.Partly),
    2 to meta("Partly cloudy", { RemixIcons.SunCloudyLine }, WeatherSky.Partly),
    3 to meta("Overcast", { RemixIcons.CloudyLine }, WeatherSky.Cloudy),
    45 to meta("Fog", { RemixIcons.FoggyLine }, WeatherSky.Fog),
    48 to meta("Rime fog", { RemixIcons.FoggyLine }, WeatherSky.Fog),
    51 to meta("Light drizzle", { RemixIcons.DrizzleLine }, WeatherSky.Rain),
    53 to meta("Drizzle", { RemixIcons.DrizzleLine }, WeatherSky.Rain),
    55 to meta("Dense drizzle", { RemixIcons.DrizzleLine }, WeatherSky.Rain),
    56 to meta("Freezing drizzle", { RemixIcons.DrizzleLine }, WeatherSky.Rain),
    57 to meta("Freezing drizzle", { RemixIcons.DrizzleLine }, WeatherSky.Rain),
    61 to meta("Light rain", { RemixIcons.RainyLine }, WeatherSky.Rain),
    63 to meta("Rain", { RemixIcons.RainyLine }, WeatherSky.Rain),
    65 to meta("Heavy rain", { RemixIcons.RainyLine }, WeatherSky.Rain),
    66 to meta("Freezing rain", { RemixIcons.RainyLine }, WeatherSky.Rain),
    67 to meta("Freezing rain", { RemixIcons.RainyLine }, WeatherSky.Rain),
    71 to meta("Light snow", { RemixIcons.SnowyLine }, WeatherSky.Snow),
    73 to meta("Snow", { RemixIcons.SnowyLine }, WeatherSky.Snow),
    75 to meta("Heavy snow", { RemixIcons.SnowyLine }, WeatherSky.Snow),
    77 to meta("Snow grains", { RemixIcons.SnowyLine }, WeatherSky.Snow),
    80 to meta("Light showers", { RemixIcons.RainyLine }, WeatherSky.Rain),
    81 to meta("Showers", { RemixIcons.RainyLine }, WeatherSky.Rain),
    82 to meta("Heavy showers", { RemixIcons.RainyLine }, WeatherSky.Rain),
    85 to meta("Snow showers", { RemixIcons.SnowyLine }, WeatherSky.Snow),
    86 to meta("Snow showers", { RemixIcons.SnowyLine }, WeatherSky.Snow),
    95 to meta("Thunderstorm", { RemixIcons.ThunderstormsLine }, WeatherSky.Storm),
    96 to meta("Thunderstorm", { RemixIcons.ThunderstormsLine }, WeatherSky.Storm),
    99 to meta("Severe thunderstorm", { RemixIcons.ThunderstormsLine }, WeatherSky.Storm),
)

/** WMO weather code → condition label + icon. Unmapped codes get a generic fallback. */
fun weatherCodeMeta(code: Int): WeatherConditionMeta {
    val m = WmoMeta[code] ?: return WeatherConditionMeta("Weather", RemixIcons.CloudyLine, WeatherSky.Cloudy)
    return WeatherConditionMeta(m.first, m.second(), m.third)
}

/**
 * Hour-of-day (0–23) at the location described by utc_offset_seconds, used to
 * index the hourly precipitation series — correct for any location.
 */
fun currentHourAtOffset(utcOffsetSeconds: Int, now: Instant = Clock.System.now()): Int {
    val local = now.toEpochMilliseconds().floorDiv(1000L) + utcOffsetSeconds
    return (((local % 86400) + 86400) % 86400 / 3600).toInt()
}

/** Chance of precipitation in the current hour; null / missing values read as 0. */
fun hourlyRainProbability(probabilities: List<Double?>, utcOffsetSeconds: Int, now: Instant = Clock.System.now()): Double =
    probabilities.getOrNull(currentHourAtOffset(utcOffsetSeconds, now)) ?: 0.0

/** The subset of the Open-Meteo /v1/forecast response this feature uses. */
data class WeatherResponse(
    val temperature: Double,
    val apparentTemperature: Double,
    val windSpeed: Double,
    val weatherCode: Int,
    val hourlyTime: List<String>,
    val precipitationProbability: List<Double?>,
    val dailyMax: List<Double>,
    val dailyMin: List<Double>,
    val utcOffsetSeconds: Int,
)

class WeatherShapeException(message: String) : IllegalStateException(message)

private fun JsonElement?.num(path: String): Double =
    ((this as? JsonPrimitive)?.takeIf { !it.isString })?.doubleOrNull ?: throw WeatherShapeException("Expected number at $path")

private fun JsonElement?.obj(path: String): JsonObject = this as? JsonObject ?: throw WeatherShapeException("Expected object at $path")
private fun JsonElement?.arr(path: String): JsonArray = this as? JsonArray ?: throw WeatherShapeException("Expected array at $path")

/** weatherResponseSchema.parse: throws on any shape change (the card then hides). */
fun parseWeatherResponse(json: JsonElement): WeatherResponse {
    val root = json.obj("$")
    val current = root["current"].obj("current")
    val hourly = root["hourly"].obj("hourly")
    val daily = root["daily"].obj("daily")
    return WeatherResponse(
        temperature = current["temperature_2m"].num("current.temperature_2m"),
        apparentTemperature = current["apparent_temperature"].num("current.apparent_temperature"),
        windSpeed = current["wind_speed_10m"].num("current.wind_speed_10m"),
        weatherCode = current["weather_code"].num("current.weather_code").toInt(),
        hourlyTime = hourly["time"].arr("hourly.time").map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw WeatherShapeException("Expected string at hourly.time")
        },
        precipitationProbability = hourly["precipitation_probability"].arr("hourly.precipitation_probability").map {
            if (it is JsonNull) null else it.num("hourly.precipitation_probability")
        },
        dailyMax = daily["temperature_2m_max"].arr("daily.temperature_2m_max").map { it.num("daily.temperature_2m_max") },
        dailyMin = daily["temperature_2m_min"].arr("daily.temperature_2m_min").map { it.num("daily.temperature_2m_min") },
        utcOffsetSeconds = root["utc_offset_seconds"].num("utc_offset_seconds").toInt(),
    ).also { daily["time"].arr("daily.time") }
}

fun WeatherResponse.toWeatherData(now: Instant = Clock.System.now()) = WeatherData(
    temperature = temperature,
    feelsLike = apparentTemperature,
    windSpeed = windSpeed,
    weatherCode = weatherCode,
    rainProbability = hourlyRainProbability(precipitationProbability, utcOffsetSeconds, now),
    high = dailyMax.firstOrNull() ?: temperature,
    low = dailyMin.firstOrNull() ?: temperature,
)

private const val OpenMeteoUrl = "https://api.open-meteo.com/v1/forecast"

/** Current conditions at [coords] straight from Open-Meteo. */
suspend fun fetchWeather(http: HttpClient, coords: WeatherCoords = BudapestCoords): WeatherData {
    val res = withTimeout(15_000) { http.get(OpenMeteoUrl) {
        parameter("latitude", formatCompactNumber(coords.latitude))
        parameter("longitude", formatCompactNumber(coords.longitude))
        parameter("current", "temperature_2m,apparent_temperature,wind_speed_10m,weather_code")
        parameter("hourly", "precipitation_probability")
        parameter("daily", "temperature_2m_max,temperature_2m_min")
        parameter("timezone", "auto")
        parameter("forecast_days", "1")
    } }
    if (!res.status.isSuccess()) error("Open-Meteo returned HTTP ${res.status.value}")
    return parseWeatherResponse(VectraJson.parseToJsonElement(res.bodyAsText())).toWeatherData()
}
