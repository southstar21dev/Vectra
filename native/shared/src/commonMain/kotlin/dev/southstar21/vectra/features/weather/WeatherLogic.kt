package dev.southstar21.vectra.features.weather

import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.util.roundHalfUp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.pow
import kotlin.math.roundToLong

// ---------------------------------------------------------------------------
// Weather alerts
// ---------------------------------------------------------------------------

enum class WeatherAlertKey { HighWind, Heat, Cold, Rain }

data class WeatherAlertItem(val key: WeatherAlertKey, val label: String)

/** Fixed v1 thresholds — baked in per the approved design (no settings UI). */
object WeatherAlertThresholds {
    const val WindKmh = 25.0
    const val HeatC = 30.0
    const val ColdC = 5.0
    const val RainPercent = 50.0
}

/** Alert chips for the conditions; order is fixed: highWind → heat → cold → rain. */
fun weatherAlertItems(weather: WeatherData): List<WeatherAlertItem> = buildList {
    if (weather.windSpeed >= WeatherAlertThresholds.WindKmh) add(WeatherAlertItem(WeatherAlertKey.HighWind, "Strong wind"))
    if (weather.temperature >= WeatherAlertThresholds.HeatC) add(WeatherAlertItem(WeatherAlertKey.Heat, "High temperature"))
    if (weather.temperature <= WeatherAlertThresholds.ColdC) add(WeatherAlertItem(WeatherAlertKey.Cold, "Low temperature"))
    if (weather.rainProbability >= WeatherAlertThresholds.RainPercent) add(WeatherAlertItem(WeatherAlertKey.Rain, "High rain chance"))
}

// ---------------------------------------------------------------------------
// Snapshot: the last successful fetch, persisted so the card paints
// immediately on app open instead of flashing its skeleton.
// ---------------------------------------------------------------------------

const val WeatherSnapshotKey = "vectra.weather.snapshot"

/** Weather older than this is not worth showing — a fresh fetch is awaited. */
const val WeatherSnapshotMaxAgeMs = 24 * 60 * 60_000L

@Serializable
data class WeatherSnapshot(
    val coords: WeatherCoords,
    val city: String? = null,
    val data: WeatherData,
    val fetchedAt: Long,
)

/** Coordinates rounded to ~11 m: precise enough for a forecast, stable across reloads. */
fun roundCoords(coords: WeatherCoords, precision: Int = 4): WeatherCoords {
    val factor = 10.0.pow(precision)
    return WeatherCoords(roundHalfUp(coords.latitude * factor) / factor, roundHalfUp(coords.longitude * factor) / factor)
}

fun coordsMatch(a: WeatherCoords, b: WeatherCoords, precision: Int = 4): Boolean = roundCoords(a, precision) == roundCoords(b, precision)

private fun JsonObject.finite(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

/** readWeatherSnapshot: null for anything that is not a complete snapshot. */
fun parseWeatherSnapshot(raw: String?): WeatherSnapshot? {
    if (raw.isNullOrEmpty()) return null
    return runCatching {
        val o = VectraJson.parseToJsonElement(raw) as? JsonObject ?: return null
        val coords = o["coords"] as? JsonObject ?: return null
        val lat = coords.finite("latitude") ?: return null
        val lon = coords.finite("longitude") ?: return null
        val fetchedAt = o.finite("fetchedAt") ?: return null
        val d = o["data"] as? JsonObject ?: return null
        val data = WeatherData(
            temperature = d.finite("temperature") ?: return null,
            feelsLike = d.finite("feelsLike") ?: return null,
            windSpeed = d.finite("windSpeed") ?: return null,
            weatherCode = (d.finite("weatherCode") ?: return null).toInt(),
            rainProbability = d.finite("rainProbability") ?: return null,
            high = d.finite("high") ?: return null,
            low = d.finite("low") ?: return null,
        )
        val city = (o["city"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        WeatherSnapshot(WeatherCoords(lat, lon), city, data, fetchedAt.roundToLong())
    }.getOrNull()
}

fun encodeWeatherSnapshot(snapshot: WeatherSnapshot): String = VectraJson.encodeToString(WeatherSnapshot.serializer(), snapshot)

/**
 * updateWeatherSnapshotCity: the snapshot to write after a geocode, or null
 * when nothing should change (different location, same city, or a null that
 * would downgrade a name we already know).
 */
fun snapshotWithCity(snapshot: WeatherSnapshot?, coords: WeatherCoords, city: String?): WeatherSnapshot? {
    if (snapshot == null || !coordsMatch(snapshot.coords, coords)) return null
    if (snapshot.city == city) return null
    if (city == null && snapshot.city != null) return null
    return snapshot.copy(city = city)
}

fun isSnapshotFresh(snapshot: WeatherSnapshot, nowMs: Long, maxAgeMs: Long = WeatherSnapshotMaxAgeMs): Boolean =
    nowMs - snapshot.fetchedAt <= maxAgeMs

// ---------------------------------------------------------------------------
// Location setting
// ---------------------------------------------------------------------------

/** 'auto' = device location, 'budapest' = always Budapest (the fresh-install default). */
enum class WeatherLocationMode(val stored: String) { Auto("auto"), Budapest("budapest") }

const val LocationModeKey = "vectra.weather.locationMode"

fun parseLocationMode(raw: String?): WeatherLocationMode = if (raw == "auto") WeatherLocationMode.Auto else WeatherLocationMode.Budapest

enum class WeatherLocationStatus {
    /** Initial, before the first permission check. */
    Idle,
    /** Position read in flight. */
    Locating,
    /** Using device coordinates. */
    Located,
    /** Mode is off — weather is Budapest-fixed. */
    Budapest,
    /** Auto mode, not granted — Budapest fallback in effect. */
    Fallback,
    /** Auto mode, permission explicitly denied. */
    Denied,
    /** Device has no geolocation. */
    Unsupported,
}

data class WeatherLocationState(
    val mode: WeatherLocationMode,
    /** Device coordinates, or null when the caller should fall back to [BudapestCoords]. */
    val coords: WeatherCoords?,
    /** Reverse-geocoded city (null while loading or unknown → "My location"). */
    val city: String?,
    val status: WeatherLocationStatus,
)

/** BigDataCloud reverse geocode → display name: city → locality → principalSubdivision. */
fun parseGeoCity(json: JsonElement?): String? {
    val o = json as? JsonObject ?: return null
    for (key in listOf("city", "locality", "principalSubdivision")) {
        val v = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (!v.isNullOrEmpty()) return v
    }
    return null
}

/**
 * Initial location state. With device location on and a fresh snapshot, its
 * rounded coordinates and city are used right away so the card paints
 * instantly instead of doing the Budapest → device-title dance.
 */
fun resolveInitialWeatherLocation(mode: WeatherLocationMode, snapshot: WeatherSnapshot?, nowMs: Long): WeatherLocationState {
    if (mode == WeatherLocationMode.Auto && snapshot != null && isSnapshotFresh(snapshot, nowMs)) {
        return WeatherLocationState(mode, roundCoords(snapshot.coords), snapshot.city, WeatherLocationStatus.Located)
    }
    return WeatherLocationState(mode, null, null, WeatherLocationStatus.Idle)
}

/** A fix landing after the user switched to Budapest mode is dropped. */
fun applyPositionState(prev: WeatherLocationState, coords: WeatherCoords): WeatherLocationState =
    if (prev.mode != WeatherLocationMode.Auto) prev else prev.copy(coords = coords, status = WeatherLocationStatus.Located)

/** Why a position read failed. */
object GeoErrorCode {
    const val PermissionDenied = 1
    const val PositionUnavailable = 2
    const val Timeout = 3
}

/**
 * Permission denied (1) clears the device location; a transient failure (2/3)
 * keeps the last good coordinates instead of snapping the card to Budapest.
 */
fun applyErrorState(prev: WeatherLocationState, code: Int?): WeatherLocationState {
    if (prev.mode != WeatherLocationMode.Auto) return prev
    if ((code == GeoErrorCode.PositionUnavailable || code == GeoErrorCode.Timeout) && prev.coords != null) {
        return prev.copy(status = WeatherLocationStatus.Located)
    }
    return prev.copy(
        coords = null,
        city = null,
        status = if (code == GeoErrorCode.PermissionDenied) WeatherLocationStatus.Denied else WeatherLocationStatus.Fallback,
    )
}

/** The header: "Budapest" without device coords, else the city or "My location". */
fun weatherLocationTitle(state: WeatherLocationState): String = when {
    state.coords == null -> "Budapest"
    state.city != null -> state.city
    else -> "My location"
}
