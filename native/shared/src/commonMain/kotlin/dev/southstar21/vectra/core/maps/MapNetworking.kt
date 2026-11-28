package dev.southstar21.vectra.core.maps

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.resource.MapResourceError
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceLoadRequest
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.resource.MapResourceRequest

/**
 * A map resource scheme a feature serves itself instead of plain HTTP (the weather radar's
 * `doppler://` recoloured tiles). Core knows only this seam, not what any scheme draws.
 */
internal interface MapSchemeHandler {
    /** The URL prefix this handler answers, e.g. `doppler://`. */
    val scheme: String

    /** The resource's bytes, or null when there is none; a throw is reported as a connection failure. */
    suspend fun load(url: String): ByteArray?
}

/**
 * Serves MapLibre's HTTP(S) requests (styles, tiles, glyphs, sprites) through the
 * app's HTTP client. MapLibre's built-in rustls client rejects OpenFreeMap's
 * responses ("peer closed connection without sending TLS close_notify"), which
 * left every basemap blank; the platform TLS stack accepts them. Requests for a
 * [handlers] scheme go to that handler.
 */
private class KtorMapResources(private val http: HttpClient, private val handlers: List<MapSchemeHandler>) : MapResourceProvider {
    private fun handlerFor(url: String) = handlers.firstOrNull { url.startsWith(it.scheme) }

    override fun accepts(request: MapResourceRequest): Boolean =
        request.url.startsWith("https://") || request.url.startsWith("http://") || handlerFor(request.url) != null

    override suspend fun load(request: MapResourceLoadRequest): MapResourceLoad =
        handlerFor(request.url)?.let { loadScheme(it, request.url) } ?: loadHttp(request)

    private suspend fun loadScheme(handler: MapSchemeHandler, url: String): MapResourceLoad = try {
        handler.load(url)?.let { MapResourceLoad.Bytes(bytes = it) } ?: MapResourceLoad.NoContent()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        MapResourceLoad.Failed(MapResourceError.Connection, e.message ?: "Network error")
    }

    private suspend fun loadHttp(request: MapResourceLoadRequest): MapResourceLoad = try {
        val res = http.get(request.url) {
            request.priorEtag?.let { header(HttpHeaders.IfNoneMatch, it) }
        }
        when {
            res.status == HttpStatusCode.NotModified -> MapResourceLoad.NotModified()
            res.status == HttpStatusCode.NoContent || res.status == HttpStatusCode.NotFound -> MapResourceLoad.NoContent()
            res.status.isSuccess() -> MapResourceLoad.Bytes(bytes = res.bodyAsBytes(), etag = res.headers[HttpHeaders.ETag])
            res.status == HttpStatusCode.TooManyRequests -> MapResourceLoad.Failed(MapResourceError.RateLimit, "HTTP 429")
            else -> MapResourceLoad.Failed(MapResourceError.Server, "HTTP ${res.status.value}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        MapResourceLoad.Failed(MapResourceError.Connection, e.message ?: "Network error")
    }
}

private val configured = MutableStateFlow(false)

/** Install once, before the first map composes. */
internal fun configureMapNetworking(http: HttpClient, handlers: List<MapSchemeHandler> = emptyList()) {
    if (!configured.compareAndSet(expect = false, update = true)) return
    DefaultMapRuntime.configure { resourceProvider = KtorMapResources(http, handlers) }
}
