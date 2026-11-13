package dev.southstar21.vectra.features.weather

import dev.southstar21.vectra.core.maps.openFreeMapStyleUrl
import dev.southstar21.vectra.core.maps.prepareOpenFreeMapStyle
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import dev.southstar21.vectra.core.network.VectraJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

const val RainViewerAttribution = "© RainViewer"

/**
 * The OpenFreeMap style, sanitized and with the park overlays, as a style document string. Paint
 * changes apply at once (no 300 ms transition): the radar layer switching on after the sweep has
 * to land in one frame, or it doubles up with the sweep's own copy of the rain and flashes.
 */
suspend fun fetchRadarBaseStyle(http: HttpClient, isLight: Boolean): String {
    val res = http.get(openFreeMapStyleUrl(isLight)) { header("Cache-Control", "no-store") }
    if (!res.status.isSuccess()) error("OpenFreeMap style request failed (${res.status.value})")
    val style = VectraJson.parseToJsonElement(prepareOpenFreeMapStyle(res.bodyAsText(), isLight)).jsonObject
    return JsonObject(style + ("transition" to buildJsonObject { put("duration", 0); put("delay", 0) })).toString()
}
