package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.core.util.runSuspendCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.CoroutineContext

/** A BKK route (line) — /catalog/routes.json. */
@Serializable
data class CatalogRoute(
    val id: String,
    val shortName: String = "",
    val description: String = "",
    val type: String = "",
    val color: String = "",
    val textColor: String = "",
)

/** A stop area (station level; platforms fold in) — /catalog/stops.json. */
@Serializable
data class CatalogStop(val id: String, val name: String = "", val lat: Double = 0.0, val lon: Double = 0.0)

/** One route direction — /catalog/route-variants.json. h = headsign, s = ordered normalized stop names. */
@Serializable
data class CatalogRouteVariant(val h: String = "", val s: List<String> = emptyList())

/**
 * The fewest entries a downloaded catalog file may have. The live catalog has about 670
 * routes, 2,400 stop areas and 670 variant lists; the build script refuses to publish fewer
 * than 100 routes or 500 stops. A smaller file is a truncated or broken build: it is neither
 * used nor cached, so the copy on disk keeps answering (audit 03 #17).
 */
data class CatalogFloor(val routes: Int = 100, val stops: Int = 500, val routeVariants: Int = 100)

/**
 * Runtime loaders for the daily-refreshed BKK catalog served at
 * `<catalogOrigin>/catalog/`. Each file is cached on disk: a copy younger than
 * [FreshMs] is used as-is (the catalog refreshes daily). An older one still answers at
 * once while a conditional request refreshes the disk for the next launch, since nearby
 * stops should not wait on a download. Past [MaxStaleMs] the network comes first, with
 * that copy as the offline fallback.
 */
class TransitCatalog(
    private val http: HttpClient,
    catalogOrigin: String,
    private val scope: CoroutineScope,
    private val disk: CatalogDiskCache,
    private val clock: () -> Long = ::nowMs,
    floor: CatalogFloor = CatalogFloor(),
    /** Where loading and parsing run, off the main thread. */
    private val work: CoroutineContext = Dispatchers.Default,
) {
    private val base = catalogOrigin.trimEnd('/') + "/catalog/"

    private class Spec<T>(val file: String, val serializer: KSerializer<T>, val minEntries: Int, val count: (T) -> Int)

    private fun <T> lazyLoad(spec: Spec<T>): suspend () -> T {
        val lock = Mutex()
        var promise: Deferred<T>? = null
        return {
            val p = lock.withLock {
                promise ?: scope.async(work) { load(spec) }.also { promise = it }
            }
            try {
                p.await()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A failed load clears so it can be retried.
                lock.withLock { if (promise === p) promise = null }
                throw t
            }
        }
    }

    /** [text] as a catalog, or null when it doesn't parse or is too small to be a whole one. */
    private fun <T> decodeValid(spec: Spec<T>, text: String): T? =
        runCatching { VectraJson.decodeFromString(spec.serializer, text) }.getOrNull()?.takeIf { spec.count(it) >= spec.minEntries }

    private suspend fun <T> load(spec: Spec<T>): T {
        val cached = disk.read(spec.file)
        val copy = cached?.let { decodeValid(spec, it.second) }
        val age = cached?.let { clock() - it.first }
        if (copy != null && age != null && age < MaxStaleMs) {
            if (age >= FreshMs) scope.launch(work) { runSuspendCatching { revalidate(spec) } }
            return copy
        }
        return try {
            fetchFile(spec, etag = null) ?: error("Failed to load catalog ${spec.file}")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            copy ?: throw t
        }
    }

    /** Downloads the file and caches it, or returns null when [etag] is still current. */
    private suspend fun <T> fetchFile(spec: Spec<T>, etag: String?): T? {
        val res = http.get(base + spec.file) { etag?.let { header(HttpHeaders.IfNoneMatch, it) } }
        if (res.status == HttpStatusCode.NotModified) return null
        if (!res.status.isSuccess()) error("Failed to load catalog ${spec.file} (HTTP ${res.status.value})")
        val text = res.bodyAsText()
        val parsed = VectraJson.decodeFromString(spec.serializer, text)
        val entries = spec.count(parsed)
        if (entries < spec.minEntries) error("Catalog ${spec.file} has only $entries entries; expected at least ${spec.minEntries}")
        disk.write(spec.file, text, res.headers[HttpHeaders.ETag], clock())
        return parsed
    }

    private suspend fun <T> revalidate(spec: Spec<T>) {
        if (fetchFile(spec, disk.etag(spec.file)) == null) disk.touch(spec.file, clock())
    }

    val routes: suspend () -> List<CatalogRoute> =
        lazyLoad(Spec("routes.json", ListSerializer(CatalogRoute.serializer()), floor.routes) { it.size })
    val stops: suspend () -> List<CatalogStop> =
        lazyLoad(Spec("stops.json", ListSerializer(CatalogStop.serializer()), floor.stops) { it.size })
    val routeVariants: suspend () -> Map<String, List<CatalogRouteVariant>> = lazyLoad(
        Spec("route-variants.json", MapSerializer(String.serializer(), ListSerializer(CatalogRouteVariant.serializer())), floor.routeVariants) { it.size },
    )

    private val routeMapLock = Mutex()
    private var routeMapCache: Map<String, CatalogRoute>? = null

    /** id → route, memoized per session (getCatalogRoutes / loadCatalogRouteMap). */
    suspend fun routeMap(): Map<String, CatalogRoute> {
        routeMapLock.withLock { routeMapCache?.let { return it } }
        val m = routes().associateBy { it.id }
        routeMapLock.withLock { routeMapCache = m }
        return m
    }

    private val stopsByIdLock = Mutex()
    private var stopsByIdCache: Map<String, CatalogStop>? = null

    /** id → stop, memoized per session; every consumer shares this one map. */
    suspend fun stopsById(): Map<String, CatalogStop> {
        stopsByIdLock.withLock { stopsByIdCache?.let { return it } }
        val m = withContext(work) { stops().associateBy { it.id } }
        stopsByIdLock.withLock { stopsByIdCache = m }
        return m
    }

    private companion object {
        const val FreshMs = 12 * 60 * 60 * 1000L
        const val MaxStaleMs = 7 * 24 * 60 * 60 * 1000L
    }
}

/** A deferred that is already resolved (test helper). */
internal fun <T> completed(value: T): Deferred<T> = CompletableDeferred(value)
