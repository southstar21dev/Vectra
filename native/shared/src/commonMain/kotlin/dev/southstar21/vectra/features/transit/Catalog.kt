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
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

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
    private val clock: () -> Long = ::nowMs,
) {
    private val base = catalogOrigin.trimEnd('/') + "/catalog/"
    private val dir = Path(SystemTemporaryDirectory, "vectra-catalog")

    private fun <T> lazyLoad(file: String, serializer: KSerializer<T>): suspend () -> T {
        val lock = Mutex()
        var promise: Deferred<T>? = null
        return {
            val p = lock.withLock {
                promise ?: scope.async(Dispatchers.Default) { load(file, serializer) }.also { promise = it }
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

    private suspend fun <T> load(file: String, serializer: KSerializer<T>): T {
        val cached = readCache(file)
        val age = cached?.let { clock() - it.first }
        if (cached != null && age != null && age < MaxStaleMs) {
            runCatching { VectraJson.decodeFromString(serializer, cached.second) }.onSuccess { parsed ->
                if (age >= FreshMs) scope.launch { runSuspendCatching { revalidate(file, serializer) } }
                return parsed
            }
        }
        return try {
            fetchFile(file, serializer, etag = null) ?: error("Failed to load catalog $file")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            cached?.let { runCatching { return VectraJson.decodeFromString(serializer, it.second) } }
            throw t
        }
    }

    /** Downloads [file] and caches it, or returns null when [etag] is still current. */
    private suspend fun <T> fetchFile(file: String, serializer: KSerializer<T>, etag: String?): T? {
        val res = http.get(base + file) { etag?.let { header(HttpHeaders.IfNoneMatch, it) } }
        if (res.status == HttpStatusCode.NotModified) return null
        if (!res.status.isSuccess()) error("Failed to load catalog $file (HTTP ${res.status.value})")
        val text = res.bodyAsText()
        val parsed = VectraJson.decodeFromString(serializer, text)
        writeCache(file, text, res.headers[HttpHeaders.ETag])
        return parsed
    }

    private suspend fun <T> revalidate(file: String, serializer: KSerializer<T>) {
        if (fetchFile(file, serializer, readEtag(file)) == null) touchCache(file)
    }

    private suspend fun readEtag(file: String): String? = withContext(Dispatchers.Default) {
        runCatching {
            val p = Path(dir, "$file.etag")
            if (SystemFileSystem.exists(p)) SystemFileSystem.source(p).buffered().use { it.readString() }.ifEmpty { null } else null
        }.getOrNull()
    }

    private suspend fun touchCache(file: String) = withContext(Dispatchers.Default) {
        runCatching { writeAtomically("$file.ts", clock().toString()) }
    }

    private fun writeAtomically(name: String, text: String) {
        val temp = Path(dir, "$name.tmp")
        SystemFileSystem.sink(temp).buffered().use { it.writeString(text) }
        SystemFileSystem.atomicMove(temp, Path(dir, name))
    }

    private suspend fun readCache(file: String): Pair<Long, String>? = withContext(Dispatchers.Default) {
        runCatching {
            val p = Path(dir, file)
            val meta = Path(dir, "$file.ts")
            if (!SystemFileSystem.exists(p) || !SystemFileSystem.exists(meta)) return@runCatching null
            val ts = SystemFileSystem.source(meta).buffered().use { it.readString() }.trim().toLong()
            ts to SystemFileSystem.source(p).buffered().use { it.readString() }
        }.getOrNull()
    }

    private suspend fun writeCache(file: String, text: String, etag: String?) = withContext(Dispatchers.Default) {
        runCatching {
            SystemFileSystem.createDirectories(dir)
            writeAtomically(file, text)
            writeAtomically("$file.ts", clock().toString())
            writeAtomically("$file.etag", etag.orEmpty())
        }
    }

    val routes: suspend () -> List<CatalogRoute> = lazyLoad("routes.json", ListSerializer(CatalogRoute.serializer()))
    val stops: suspend () -> List<CatalogStop> = lazyLoad("stops.json", ListSerializer(CatalogStop.serializer()))
    val routeVariants: suspend () -> Map<String, List<CatalogRouteVariant>> =
        lazyLoad("route-variants.json", MapSerializer(String.serializer(), ListSerializer(CatalogRouteVariant.serializer())))

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
        val m = withContext(Dispatchers.Default) { stops().associateBy { it.id } }
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
