package dev.southstar21.vectra.core.maps

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.resource.MapResourceError
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceLoadRequest
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.resource.MapResourceRequest

/**
 * Serves MapLibre's HTTP(S) requests (styles, tiles, glyphs, sprites) through the
 * app's HTTP client. MapLibre's built-in rustls client rejects OpenFreeMap's
 * responses ("peer closed connection without sending TLS close_notify"), which
 * left every basemap blank; the platform TLS stack accepts them.
 */
private class KtorMapResources(private val http: HttpClient) : MapResourceProvider {
    private val radar = radarTileRenderer(http)

    override fun accepts(request: MapResourceRequest): Boolean =
        request.url.startsWith("https://") || request.url.startsWith("http://") || request.url.startsWith(DopplerScheme)

    override suspend fun load(request: MapResourceLoadRequest): MapResourceLoad =
        if (request.url.startsWith(DopplerScheme)) loadDoppler(request.url) else loadHttp(request)

    /** Radar tiles: RainViewer's, recoloured (and past zoom 7 enlarged) by [RadarTileRenderer], as a PNG. */
    private suspend fun loadDoppler(url: String): MapResourceLoad = try {
        val ref = RadarTileRef.parse(dopplerToHttps(url))
        val px = ref?.let { radar.render(it) }
        val png = px?.let { withContext(Dispatchers.Default) { encodePng(ArgbImage(RadarTilePixels, RadarTilePixels, it)) } }
        if (png == null) MapResourceLoad.NoContent() else MapResourceLoad.Bytes(bytes = png)
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
fun configureMapNetworking(http: HttpClient) {
    if (!configured.compareAndSet(expect = false, update = true)) return
    DefaultMapRuntime.configure { resourceProvider = KtorMapResources(http) }
}
