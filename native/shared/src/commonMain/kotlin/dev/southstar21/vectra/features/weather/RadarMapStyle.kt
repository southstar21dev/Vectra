package dev.southstar21.vectra.features.weather

import dev.southstar21.vectra.core.maps.openFreeMapStyleUrl
import dev.southstar21.vectra.core.maps.sanitizeOpenFreeMapStyle
import dev.southstar21.vectra.core.maps.withParkColors
import dev.southstar21.vectra.core.network.VectraJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject

const val RainViewerAttribution = "© RainViewer"

/** The OpenFreeMap style, sanitized and with the park overlays, as a style document string. */
suspend fun fetchRadarBaseStyle(http: HttpClient, isLight: Boolean): String {
    val res = http.get(openFreeMapStyleUrl(isLight)) { header("Cache-Control", "no-store") }
    if (!res.status.isSuccess()) error("OpenFreeMap style request failed (${res.status.value})")
    val style = VectraJson.parseToJsonElement(res.bodyAsText()) as? JsonObject ?: error("OpenFreeMap style is not an object")
    return withParkColors(sanitizeOpenFreeMapStyle(style), isLight).toString()
}
