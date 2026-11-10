package dev.southstar21.vectra.features.transit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import dev.southstar21.vectra.core.network.createHttpClient
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [TransitCatalog] against a MockEngine, including the lazy catalog getters. The catalog
 * files themselves aren't validated here: the app only downloads them at runtime.
 * Each test gets its own cache directories, and background work is awaited, not slept on.
 */
class CatalogDataTest {
    private val route = """{"id":"BKK_5400","shortName":"M4","description":"Kelenföld vasútállomás M | Keleti pályaudvar M","type":"SUBWAY","color":"12A13C","textColor":"FFFFFF"}"""
    private val route2 = """{"id":"BKK_3280","shortName":"28"}"""
    private val root = Path(SystemTemporaryDirectory, "vectra-catalog-test-${Random.nextLong().toULong()}")
    private val dir = Path(root, "files")
    private val legacyDir = Path(root, "tmp")
    private val oneEach = CatalogFloor(routes = 1, stops = 1, routeVariants = 1)

    @AfterTest fun cleanUp() {
        for (d in listOf(dir, legacyDir, root)) {
            runCatching {
                if (SystemFileSystem.exists(d)) SystemFileSystem.list(d).forEach { SystemFileSystem.delete(it, mustExist = false) }
                SystemFileSystem.delete(d, mustExist = false)
            }
        }
    }

    private fun TestScope.disk() = CatalogDiskCache(dir, legacyDir, io = StandardTestDispatcher(testScheduler))

    /** A scope of its own per catalog, so a test can wait for (or cancel) just that catalog's background work. */
    private fun TestScope.childScope() = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job))

    private fun TestScope.catalog(http: HttpClient, scope: CoroutineScope = childScope(), floor: CatalogFloor = oneEach, clock: () -> Long) =
        TransitCatalog(http, "https://web.test/", scope, disk(), clock, floor, work = StandardTestDispatcher(testScheduler))

    private suspend fun awaitBackgroundWork(scope: CoroutineScope) = scope.coroutineContext.job.children.toList().joinAll()

    private class Server(val handler: (String) -> Pair<HttpStatusCode, String>) {
        /** Written from the engine's thread, read from the test's. */
        val paths = Channel<String>(Channel.UNLIMITED)
        fun drain(): List<String> = generateSequence { paths.tryReceive().getOrNull() }.toList()
    }

    private fun TestScope.catalog(server: Server, floor: CatalogFloor = oneEach): TransitCatalog {
        val engine = MockEngine { req ->
            server.paths.trySend(req.url.encodedPath)
            val (status, body) = server.handler(req.url.encodedPath)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        var t = 0L
        // Every clock read is 13h later, so a cached copy is never "fresh".
        return catalog(createHttpClient(HttpClient(engine)), floor = floor) { t += 13 * 3_600_000L; t }
    }

    private fun writeFile(d: Path, name: String, text: String) {
        SystemFileSystem.createDirectories(d)
        SystemFileSystem.sink(Path(d, name)).buffered().use { it.writeString(text) }
    }

    @Test fun fetchesRoutesAndMemoizes() = runTest {
        val server = Server { HttpStatusCode.OK to "[$route]" }
        val c = catalog(server)
        val first = c.routes()
        val second = c.routes()
        assertEquals(listOf("/catalog/routes.json"), server.drain())
        assertSame(first, second)
        assertEquals(listOf(CatalogRoute("BKK_5400", "M4", "Kelenföld vasútállomás M | Keleti pályaudvar M", "SUBWAY", "12A13C", "FFFFFF")), first)
    }

    @Test fun fetchesStops() = runTest {
        val server = Server { HttpStatusCode.OK to """[{"id":"BKK_CSF01108","name":"Astoria","lat":47.494479,"lon":19.060643}]""" }
        val c = catalog(server)
        assertEquals(1, getCatalogStops(c).size)
        assertEquals(listOf("/catalog/stops.json"), server.drain())
    }

    @Test fun fetchesRouteVariants() = runTest {
        val server = Server { HttpStatusCode.OK to """{"BKK_3280":[{"h":"Blaha Lujza tér M","s":["teleki laszlo ter"]}]}""" }
        val c = catalog(server)
        assertTrue("BKK_3280" in getRouteVariants(c))
        assertEquals(listOf("/catalog/route-variants.json"), server.drain())
    }

    @Test fun mapsRoutesById() = runTest {
        val c = catalog(Server { HttpStatusCode.OK to "[$route]" })
        val map = getCatalogRouteMap(c)
        assertEquals("M4", map["BKK_5400"]?.shortName)
        assertSame(map, getCatalogRouteMap(c))
    }

    @Test fun aStaleCopyAnswersAtOnceAndRevalidates() = runTest {
        var time = 0L
        val requests = Channel<String?>(Channel.UNLIMITED)
        var hang = false
        val engine = MockEngine { req ->
            requests.trySend(req.headers[HttpHeaders.IfNoneMatch])
            if (hang) awaitCancellation()
            if (req.headers[HttpHeaders.IfNoneMatch] == "v1") respond("", HttpStatusCode.NotModified)
            else respond("[$route]", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "v1"))
        }
        val http = createHttpClient(HttpClient(engine))
        catalog(http) { time }.routes()
        assertNull(requests.receive())

        time = 13 * 3_600_000L
        hang = true
        // A hanging server must not hold the stale copy back.
        val hanging = childScope()
        assertEquals("BKK_5400", catalog(http, hanging) { time }.routes().single().id)
        assertEquals("v1", requests.receive())
        hanging.cancel()

        hang = false
        val revalidating = childScope()
        catalog(http, revalidating) { time }.routes()
        awaitBackgroundWork(revalidating)
        assertEquals("v1", requests.tryReceive().getOrNull())

        // The 304 restarted the copy's age: nothing to revalidate next time.
        val next = childScope()
        catalog(http, next) { time }.routes()
        awaitBackgroundWork(next)
        assertTrue(requests.tryReceive().isFailure, "no further request")
    }

    @Test fun aVeryOldCopyWaitsForTheNetwork() = runTest {
        var time = 0L
        var body = "[$route]"
        val http = createHttpClient(HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }))
        catalog(http) { time }.routes()
        time = 8 * 24 * 3_600_000L
        body = "[$route,$route2]"
        assertEquals(listOf("BKK_5400", "BKK_3280"), catalog(http) { time }.routes().map { it.id })
    }

    @Test fun aTruncatedDownloadIsNeitherUsedNorCached() = runTest {
        var time = 0L
        var body = "[$route,$route2]"
        val http = createHttpClient(HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }))
        val floor = CatalogFloor(routes = 2, stops = 1, routeVariants = 1)
        catalog(http, floor = floor) { time }.routes()
        time = 8 * 24 * 3_600_000L
        body = "[]"
        // The old copy answers instead of an empty catalog...
        assertEquals(2, catalog(http, floor = floor) { time }.routes().size)
        // ...and is still the copy on disk.
        assertEquals("[$route,$route2]", disk().read("routes.json")?.second)
    }

    @Test fun aTruncatedFirstDownloadFails() = runTest {
        val c = catalog(Server { HttpStatusCode.OK to "[$route]" }, floor = CatalogFloor(routes = 2))
        val err = runCatching { c.routes() }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("only 1 entries"), "got ${err?.message}")
        assertNull(disk().read("routes.json"))
    }

    @Test fun aTruncatedCopyOnDiskIsIgnored() = runTest {
        // Older builds cached any 200, including an empty file.
        disk().write("routes.json", "[]", etag = null, now = 0)
        val server = Server { HttpStatusCode.OK to "[$route]" }
        assertEquals(1, catalog(server).routes().size)
        assertEquals(listOf("/catalog/routes.json"), server.drain())
    }

    @Test fun aCopyInTheOldTempDirectoryMovesOver() = runTest {
        writeFile(legacyDir, "routes.json", "[$route]")
        writeFile(legacyDir, "routes.json.etag", "v1")
        writeFile(legacyDir, "routes.json.ts", "0")
        // Fresh (an hour old): answered from the moved copy, no download.
        assertEquals(1, catalog(createHttpClient(HttpClient(MockEngine { error("no request expected") }))) { 3_600_000L }.routes().size)
        assertEquals("[$route]", disk().read("routes.json")?.second)
        assertEquals("v1", disk().etag("routes.json"))
        assertFalse(SystemFileSystem.exists(Path(legacyDir, "routes.json")))
        assertFalse(SystemFileSystem.exists(Path(legacyDir, "routes.json.ts")))
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
        val server = Server { calls += 1; if (calls == 1) HttpStatusCode.ServiceUnavailable to "nope" else HttpStatusCode.OK to "[$route]" }
        val c = catalog(server)
        val err = runCatching { c.routes() }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("HTTP 503"), "got ${err?.message}")
        assertEquals(1, c.routes().size)
        assertEquals(2, server.drain().size)
    }
}
