package dev.southstar21.vectra.features.weather

import dev.southstar21.vectra.core.maps.openFreeMapStyleUrl
import dev.southstar21.vectra.core.maps.prepareOpenFreeMapStyle
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

const val RainViewerAttribution = "© RainViewer"

/** The OpenFreeMap style, sanitized and with the park overlays, as a style document string. */
suspend fun fetchRadarBaseStyle(http: HttpClient, isLight: Boolean): String {
    val res = http.get(openFreeMapStyleUrl(isLight)) { header("Cache-Control", "no-store") }
    if (!res.status.isSuccess()) error("OpenFreeMap style request failed (${res.status.value})")
    return prepareOpenFreeMapStyle(res.bodyAsText(), isLight)
}
