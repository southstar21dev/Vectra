package dev.southstar21.vectra.features.weather

import dev.southstar21.vectra.core.maps.ArgbImage
import dev.southstar21.vectra.core.maps.MapSchemeHandler
import dev.southstar21.vectra.core.maps.encodePng
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The process-wide scheme behind the radar map layer, so its tile cache can be released. */
private var activeRadarScheme: RadarMapScheme? = null

/**
 * Serves the map's `doppler://` radar tiles: RainViewer's, recoloured (and past zoom 7 enlarged)
 * by [RadarTileRenderer], as a PNG. Installed through `configureMapNetworking`.
 */
internal class RadarMapScheme(http: HttpClient) : MapSchemeHandler {
    private val renderer = radarTileRenderer(http)

    init {
        activeRadarScheme = this
    }

    override val scheme: String = DopplerScheme

    override suspend fun load(url: String): ByteArray? {
        val ref = RadarTileRef.parse(dopplerToHttps(url)) ?: return null
        val px = renderer.render(ref) ?: return null
        return withContext(Dispatchers.Default) { encodePng(ArgbImage(RadarTilePixels, RadarTilePixels, px)) }
    }

    internal fun clear() = renderer.clear()
}

/** Drop the radar's cached raw tiles; call when the radar closes so they don't stay on the heap. */
internal fun releaseRadarTileCache() {
    activeRadarScheme?.clear()
}
