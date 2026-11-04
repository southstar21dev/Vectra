package dev.southstar21.vectra.features.transit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import dev.southstar21.vectra.core.network.createHttpClient
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [TransitCatalog] against a MockEngine, including the lazy catalog getters. The catalog
 * files themselves aren't validated here: the app only downloads them at runtime.
 */
class CatalogDataTest {
    private val route = """{"id":"BKK_5400","shortName":"M4","description":"Kelenföld vasútállomás M | Keleti pályaudvar M","type":"SUBWAY","color":"12A13C","textColor":"FFFFFF"}"""

    @BeforeTest fun clearDiskCache() {
        // The catalog's disk cache (stale-copy offline fallback) must not leak between tests.
        val dir = Path(SystemTemporaryDirectory, "vectra-catalog")
        runCatching { SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) } }
    }

    private class Server(val handler: (String) -> Pair<HttpStatusCode, String>) {
        val paths = mutableListOf<String>()
    }

    private fun TestScope.catalog(server: Server): TransitCatalog {
        val engine = MockEngine { req ->
            server.paths += req.url.encodedPath
            val (status, body) = server.handler(req.url.encodedPath)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        var t = 0L
        // Every clock read is 13h later, so a cached copy is never "fresh".
        return TransitCatalog(createHttpClient(HttpClient(engine)), "https://web.test/", CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job)), clock = { t += 13 * 3_600_000L; t })
    }

    @Test fun fetchesRoutesAndMemoizes() = runTest {
        val server = Server { HttpStatusCode.OK to "[$route]" }
        val c = catalog(server)
        val first = c.routes()
        val second = c.routes()
        assertEquals(listOf("/catalog/routes.json"), server.paths)
        assertSame(first, second)
        assertEquals(listOf(CatalogRoute("BKK_5400", "M4", "Kelenföld vasútállomás M | Keleti pályaudvar M", "SUBWAY", "12A13C", "FFFFFF")), first)
    }

    @Test fun fetchesStops() = runTest {
        val server = Server { HttpStatusCode.OK to """[{"id":"BKK_CSF01108","name":"Astoria","lat":47.494479,"lon":19.060643}]""" }
        val c = catalog(server)
        assertEquals(1, getCatalogStops(c).size)
        assertEquals(listOf("/catalog/stops.json"), server.paths)
    }

    @Test fun fetchesRouteVariants() = runTest {
        val server = Server { HttpStatusCode.OK to """{"BKK_3280":[{"h":"Blaha Lujza tér M","s":["teleki laszlo ter"]}]}""" }
        val c = catalog(server)
        assertTrue("BKK_3280" in getRouteVariants(c))
        assertEquals(listOf("/catalog/route-variants.json"), server.paths)
    }

    @Test fun mapsRoutesById() = runTest {
        val c = catalog(Server { HttpStatusCode.OK to "[$route]" })
        val map = getCatalogRouteMap(c)
        assertEquals("M4", map["BKK_5400"]?.shortName)
        assertSame(map, getCatalogRouteMap(c))
    }

    @Test fun aStaleCopyAnswersAtOnceAndRevalidates() = runTest {
        var time = 0L
        val requests = mutableListOf<String?>()
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job))
        var hang = false
        val engine = MockEngine { req ->
            requests += req.headers[HttpHeaders.IfNoneMatch]
            if (hang) awaitCancellation()
            if (req.headers[HttpHeaders.IfNoneMatch] == "v1") respond("", HttpStatusCode.NotModified)
            else respond("[$route]", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "v1"))
        }
        val http = createHttpClient(HttpClient(engine))
        TransitCatalog(http, "https://web.test/", scope, clock = { time }).routes()
        assertEquals(listOf<String?>(null), requests)

        suspend fun awaitRequests(count: Int) = withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (requests.size < count) delay(5) }
        }

        time = 13 * 3_600_000L
        hang = true
        // A hanging server must not hold the stale copy back.
        assertEquals("BKK_5400", TransitCatalog(http, "https://web.test/", scope, clock = { time }).routes().single().id)
        awaitRequests(2)
        assertEquals(listOf(null, "v1"), requests)

        hang = false
        TransitCatalog(http, "https://web.test/", scope, clock = { time }).routes()
        awaitRequests(3)
        assertEquals("v1", requests.last())
        withContext(Dispatchers.Default) { delay(200) }

        // The 304 restarted the copy's age: nothing to revalidate next time.
        TransitCatalog(http, "https://web.test/", scope, clock = { time }).routes()
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals(3, requests.size)
    }

    @Test fun aVeryOldCopyWaitsForTheNetwork() = runTest {
        var time = 0L
        var body = "[$route]"
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job))
        val http = createHttpClient(HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }))
        TransitCatalog(http, "https://web.test/", scope, clock = { time }).routes()
        time = 8 * 24 * 3_600_000L
        body = "[]"
        assertEquals(emptyList(), TransitCatalog(http, "https://web.test/", scope, clock = { time }).routes())
    }

    @Test fun stopsByIdIsSharedAndMemoized() = runTest {
        val server = Server { HttpStatusCode.OK to """[{"id":"S1","name":"One"}]""" }
        val c = catalog(server)
        val first = c.stopsById()
        assertEquals("One", first["S1"]?.name)
        assertSame(first, c.stopsById())
    }

    @Test fun failedLoadClearsForRetry() = runTest {
        var calls = 0
        val server = Server { calls += 1; if (calls == 1) HttpStatusCode.ServiceUnavailable to "nope" else HttpStatusCode.OK to "[]" }
        val c = catalog(server)
        val err = runCatching { c.routes() }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("HTTP 503"), "got ${err?.message}")
        assertEquals(emptyList(), c.routes())
        assertEquals(2, server.paths.size)
    }
}
