package dev.southstar21.vectra.features.weather

import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.time.Budapest
import dev.southstar21.vectra.core.time.pad2
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.math.floor
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Instant

/** RainViewer public API. */
const val RainViewerApiUrl = "https://api.rainviewer.com/public/weather-maps.json"
const val RainViewerDefaultHost = "https://tilecache.rainviewer.com"

data class RadarFrame(val time: Long, val path: String)

data class RainViewerResponse(val generated: Long, val host: String, val past: List<RadarFrame>)

data class NormalizedRadarData(
    val generated: Long,
    val host: String,
    val radarFrames: List<RadarFrame>,
    val latestPastTime: Long,
)

class RadarShapeException(message: String) : IllegalStateException(message)

/** z.union([number, string→Number||0]) */
private fun JsonElement?.looseNumber(): Long? = when {
    this !is JsonPrimitive -> null
    isString -> content.toDoubleOrNull()?.roundToLong() ?: 0L
    else -> doubleOrNull?.roundToLong()
}

/** rainViewerResponseSchema.parse: defaults for generated/host/radar, strict frames. */
fun parseRainViewerResponse(json: JsonElement, nowSeconds: Long = Clock.System.now().epochSeconds): RainViewerResponse {
    val root = json as? JsonObject ?: throw RadarShapeException("Expected object")
    val generated = if (root["generated"] == null) nowSeconds
    else root["generated"].looseNumber() ?: throw RadarShapeException("Invalid generated")
    val host = when (val h = root["host"]) {
        null -> RainViewerDefaultHost
        is JsonPrimitive -> if (h.isString) h.content else throw RadarShapeException("Invalid host")
        else -> throw RadarShapeException("Invalid host")
    }
    val radar = root["radar"]
    val past = when {
        radar == null -> emptyList()
        radar !is JsonObject -> throw RadarShapeException("Invalid radar")
        radar["past"] == null -> emptyList()
        else -> (radar["past"] as? JsonArray ?: throw RadarShapeException("Invalid radar.past")).map { f ->
            val o = f as? JsonObject ?: throw RadarShapeException("Invalid frame")
            val time = o["time"].looseNumber() ?: throw RadarShapeException("Invalid frame time")
            val path = (o["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw RadarShapeException("Invalid frame path")
            RadarFrame(time, path)
        }
    }
    return RainViewerResponse(generated, host, past)
}

/** Past-only timeline: RainViewer's free API no longer serves nowcast frames (Jan 2026). */
fun normalizeRadarData(data: RainViewerResponse): NormalizedRadarData = NormalizedRadarData(
    generated = data.generated,
    host = data.host,
    radarFrames = data.past,
    latestPastTime = data.past.lastOrNull()?.time ?: data.generated,
)

/** Tile URL template for a radar frame. */
fun getRadarTileUrl(host: String, path: String, colorScheme: Int = 1, options: String = "1_1", size: Int = 256): String {
    val cleanHost = host.trimEnd('/')
    val cleanPath = if (path.startsWith("/")) path else "/$path"
    return "$cleanHost$cleanPath/$size/{z}/{x}/{y}/$colorScheme/$options.png"
}

data class RadarFrameLabel(val timeStr: String, val relative: String)

/**
 * The frame's clock time (2-digit hour/minute, Budapest wall clock) and its offset from [referenceTime] ("-30m" / "Now").
 */
fun formatRadarFrameLabel(
    frame: RadarFrame,
    referenceTime: Long = Clock.System.now().epochSeconds,
    hour12: Boolean = false,
    zone: TimeZone = Budapest,
): RadarFrameLabel {
    val t = Instant.fromEpochSeconds(frame.time).toLocalDateTime(zone)
    val timeStr = if (hour12) {
        val h = (t.hour % 12).let { if (it == 0) 12 else it }
        "${pad2(h)}:${pad2(t.minute)} ${if (t.hour >= 12) "PM" else "AM"}"
    } else "${pad2(t.hour)}:${pad2(t.minute)}"
    val diffMinutes = floor((frame.time - referenceTime) / 60.0 + 0.5).toLong()
    val relative = if (diffMinutes <= -5) "-${abs(diffMinutes)}m" else "Now"
    return RadarFrameLabel(timeStr, relative)
}

/** Latest radar metadata and frame timestamps. */
suspend fun fetchRainViewerData(http: HttpClient): NormalizedRadarData {
    val res = withTimeout(10_000) { http.get(RainViewerApiUrl) }
    if (!res.status.isSuccess()) error("RainViewer API returned HTTP ${res.status.value}")
    return normalizeRadarData(parseRainViewerResponse(VectraJson.parseToJsonElement(res.bodyAsText())))
}
