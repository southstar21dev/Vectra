package dev.southstar21.vectra.features.transit.trackers

import androidx.compose.runtime.mutableStateOf
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.designsystem.components.ToastType
import dev.southstar21.vectra.core.designsystem.components.Toaster
import dev.southstar21.vectra.core.network.AuthState
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.network.json
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.platform.GeoFix
import dev.southstar21.vectra.core.platform.GeoPermission
import dev.southstar21.vectra.core.platform.LocationProvider
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import dev.southstar21.vectra.features.transit.MaxTrackerStops
import dev.southstar21.vectra.features.transit.data.CatalogDiskCache
import dev.southstar21.vectra.features.transit.data.FutarRoute
import dev.southstar21.vectra.features.transit.data.FutarStop
import dev.southstar21.vectra.features.transit.data.TrackerStop
import dev.southstar21.vectra.features.transit.data.TransitRepository
import dev.southstar21.vectra.features.transit.data.TransitTracker
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val Owner = "user-1"

private object NoLocation : LocationProvider {
    override fun permission() = GeoPermission.Denied
    override suspend fun current(): GeoFix = error("no location in tests")
    override fun watch(): Flow<GeoFix> = emptyFlow()
}

private fun arrivals(vararg routes: FutarRoute): String {
    val map = routes.joinToString(",") { """"${it.id}":{"id":"${it.id}","shortName":"${it.shortName}","type":"${it.type}","color":"${it.color}","textColor":"${it.textColor}"}""" }
    return """{"status":"OK","currentTime":1,"data":{"references":{"routes":{$map}}}}"""
}

private val Tram6 = FutarRoute(id = "BKK_3060", shortName = "6", type = "TRAM", color = "FFD800", textColor = "000000")
private val Bus7 = FutarRoute(id = "BKK_0070", shortName = "7", type = "BUS", color = "009EE3", textColor = "FFFFFF")

/** The transit proxy, the catalog files and the tracker collection, in memory. */
private class Backend {
    /** Answers the n-th arrivals request (0-based); hold it to reorder answers. */
    var onArrivals: suspend (n: Int) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to arrivals(Tram6) }
    var arrivalRequests = 0
    var catalogDown = false
    var saveFails = false
    val saved = mutableListOf<JsonObject>()
    val patched = mutableListOf<Pair<String, JsonObject>>()
    /** Past the catalog's size floor, or it is refused as a broken download. */
    var stopsJson = "[" + listOf(
        """{"id":"BKK_S1","name":"Astoria","lat":47.49,"lon":19.06}""",
        """{"id":"BKK_S2","name":"Blaha Lujza ter","lat":47.49,"lon":19.07}""",
    ).plus((3..520).map { """{"id":"BKK_S$it","name":"Filler $it","lat":47.5,"lon":19.0}""" }).joinToString(",") + "]"
    private val routesJson = "[" + (1..120).joinToString(",") { """{"id":"BKK_R$it","shortName":"$it"}""" } + "]"

    private fun body(req: HttpRequestData): JsonObject =
        VectraJson.parseToJsonElement((req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    suspend fun handle(scope: MockRequestHandleScope, req: HttpRequestData): HttpResponseData = with(scope) {
        val path = req.url.encodedPath
        when {
            path == "/api/vectra/crypto" -> crypto(this, body(req))
            path == "/api/vectra/futar" -> {
                val (status, text) = onArrivals(arrivalRequests++)
                json(text, status)
            }
            path == "/catalog/stops.json" -> if (catalogDown) json("{}", HttpStatusCode.InternalServerError) else json(stopsJson)
            path == "/catalog/routes.json" -> if (catalogDown) json("{}", HttpStatusCode.InternalServerError) else json(routesJson)
            path.startsWith("/api/collections/transit_trackers/") && req.method == HttpMethod.Post -> {
                if (saveFails) return@with json("{}", HttpStatusCode.InternalServerError)
                val row = JsonObject(body(req) + ("id" to JsonPrimitive("t${saved.size + 1}")))
                saved += row
                json(row.toString())
            }
            path.startsWith("/api/collections/transit_trackers/") && req.method == HttpMethod.Patch -> {
                if (saveFails) return@with json("{}", HttpStatusCode.InternalServerError)
                val id = path.substringAfterLast('/')
                patched += id to body(req)
                json(JsonObject(body(req) + ("id" to JsonPrimitive(id))).toString())
            }
            path.startsWith("/api/collections/transit_trackers/") && path.substringAfter("/records").trim('/').isNotEmpty() ->
                json(JsonObject(mapOf("id" to JsonPrimitive(path.substringAfterLast('/')), "route_id" to JsonPrimitive("vc3:BKK_3060"))).toString())
            path.startsWith("/api/collections/") -> json("""{"items":[]}""")
            else -> json("{}")
        }
    }

    private fun crypto(scope: MockRequestHandleScope, b: JsonObject) = with(scope) {
        when (val op = b.getValue("op").jsonPrimitive.content) {
            "digest" -> {
                val out = b.getValue("parts").jsonArray.map { parts -> "d:" + parts.jsonArray.joinToString("|") { it.jsonPrimitive.content } }
                json(JsonObject(mapOf("digests" to JsonArray(out.map(::JsonPrimitive)))).toString())
            }
            else -> {
                val values = b.getValue("values").jsonArray.map { it.jsonPrimitive.content }
                val out = if (op == "encrypt") values.map { "vc3:$it" } else values.map { it.removePrefix("vc3:") }
                json(JsonObject(mapOf("values" to JsonArray(out.map(::JsonPrimitive)))).toString())
            }
        }
    }
}

private class Fixture {
    val backend = Backend()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kv = KeyValueStore(FakeDataStore())
    private val http = HttpClient(MockEngine { req -> backend.handle(this, req) })
    val store = AuthStore(kv, PlainSecretCipher)
    private val pb = PocketBaseClient(createHttpClient(http), "https://pb.test", store)
    private val connectivity = object : Connectivity { override val online = MutableStateFlow(true) }
    val repo = TransitRepository(
        pb, CryptoClient(pb, scope), kv, connectivity, NoLocation, http, "https://web.test/",
        CatalogDiskCache(Path(SystemTemporaryDirectory, "vectra-add-tracker-test-${Random.nextLong().toULong()}")), scope,
    )
    val toaster = Toaster()
    val draft = TrackerDraftState(mutableStateOf(emptyList()))
    val state = AddTrackerState(repo, scope, toaster, draft)

    suspend fun signIn() {
        store.save(AuthState("tok", JsonObject(mapOf("id" to JsonPrimitive(Owner)))))
    }

    val toasts get() = toaster.queue.toList()
}

private suspend fun until(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(2) }

private val M1Stop = "BKK_CSF01076"

class AddTrackerStateTest {
    private fun scenario(test: suspend Fixture.() -> Unit) = runTest {
        val f = Fixture()
        try {
            withContext(Dispatchers.Default) { withTimeout(20_000) { f.test() } }
        } finally {
            f.scope.cancel()
        }
    }

    // ---- panes ----------------------------------------------------------------

    @Test fun startsOnBrowseAndGoesDeeperWithEachPick() = scenario {
        assertEquals(AddTrackerPane.Browse, state.pane)
        state.metroView = MetroView.M1
        assertEquals(AddTrackerPane.Metro(MetroView.M1), state.pane)
        state.selectStop(M1Stop)
        val stop = state.pane as AddTrackerPane.Stop
        assertTrue(stop.fromMetro)
        assertEquals(2, stop.depth)
        assertEquals(1, AddTrackerPane.Metro(MetroView.M1).depth)
    }

    @Test fun aStopOpenedFromSearchSitsOneLevelDeeperThanBrowse() = scenario {
        state.selectStop(M1Stop)
        val stop = state.pane as AddTrackerPane.Stop
        assertFalse(stop.fromMetro)
        assertEquals(1, stop.depth)
    }

    @Test fun backWalksTheSamePathUp() = scenario {
        state.metroView = MetroView.M1
        state.selectStop(M1Stop)
        until { !state.loadingRoutes }
        state.back(state.pane)
        assertTrue(state.pane is AddTrackerPane.Metro)
        assertNull(state.routes)
        state.back(state.pane)
        assertEquals(AddTrackerPane.Browse, state.pane)
        state.back(AddTrackerPane.Browse)
        assertEquals(AddTrackerPane.Browse, state.pane)
    }

    @Test fun selectingALineOpensItsPaneAndBackClearsTheDraft() = scenario {
        state.selectRoute(Tram6, existing = emptyList())
        assertEquals(AddTrackerPane.Line(Tram6), state.pane)
        draft.stops = listOf(TrackerStop("S1", "Stop", ""))
        state.back(state.pane)
        assertEquals(AddTrackerPane.Browse, state.pane)
        assertEquals(emptyList(), draft.stops)
        assertNull(draft.routeStops)
    }

    @Test fun pickingALineStartsFromTheStopsAlreadyTracked() = scenario {
        val tracked = listOf(TrackerStop("S1", "Stop 1", "Out"), TrackerStop("S2", "Stop 2", ""))
        state.selectRoute(Tram6, existing = listOf(TransitTracker(id = "t0", routeId = Tram6.id, stops = tracked)))
        assertEquals(tracked, draft.stops)
    }

    // ---- stop routes -----------------------------------------------------------

    @Test fun aStopLoadsItsRoutes() = scenario {
        backend.onArrivals = { HttpStatusCode.OK to arrivals(Bus7, Tram6) }
        state.selectStop("BKK_S1")
        assertTrue(state.loadingRoutes)
        until { !state.loadingRoutes }
        assertNull(state.routesError)
        assertEquals(setOf("BKK_3060", "BKK_0070"), state.routes.orEmpty().map { it.id }.toSet())
    }

    @Test fun aMetroStopGetsItsLineEvenWhenTheFeedLeavesItOut() = scenario {
        backend.onArrivals = { HttpStatusCode.OK to arrivals(Bus7) }
        state.selectStop(M1Stop)
        until { !state.loadingRoutes }
        val ids = state.routes.orEmpty().map { it.shortName }
        assertTrue("M1" in ids, ids.toString())
    }

    @Test fun aStopWithNoRoutesIsAnError() = scenario {
        backend.onArrivals = { HttpStatusCode.OK to arrivals() }
        state.selectStop("BKK_S1")
        until { !state.loadingRoutes }
        assertEquals("No routes returned for this stop", state.routesError)
    }

    @Test fun aFailedLoadShowsAnErrorAndStopsLoading() = scenario {
        backend.onArrivals = { HttpStatusCode.InternalServerError to "{}" }
        state.selectStop("BKK_S1")
        until { !state.loadingRoutes }
        assertNotNull(state.routesError)
        assertNull(state.routes)
    }

    @Test fun aSlowAnswerForAnEarlierPickNeverOverwritesTheNewerOne() = scenario {
        val release = CompletableDeferred<Unit>()
        backend.onArrivals = { n ->
            if (n == 0) {
                release.await()
                HttpStatusCode.OK to arrivals(Bus7)
            } else {
                HttpStatusCode.OK to arrivals(Tram6)
            }
        }
        state.selectStop("BKK_S1")
        until { backend.arrivalRequests >= 1 }
        state.selectStop("BKK_S2")
        until { !state.loadingRoutes && state.routes != null }
        assertEquals(listOf("BKK_3060"), state.routes.orEmpty().map { it.id })
        release.complete(Unit)
        delay(100)
        assertEquals(listOf("BKK_3060"), state.routes.orEmpty().map { it.id })
        assertFalse(state.loadingRoutes)
    }

    @Test fun pickingAnotherStopClearsTheRouteAndDirectionChoice() = scenario {
        state.selectStop("BKK_S1")
        until { !state.loadingRoutes }
        state.selectedRouteId = Tram6.id
        state.selectedHeadsign = "Moricz"
        state.selectStop("BKK_S2")
        assertNull(state.selectedRouteId)
        assertEquals("", state.selectedHeadsign)
        assertNull(state.routes)
    }

    // ---- the line draft --------------------------------------------------------

    private fun stop(n: Int) = FutarStop(id = "S$n", name = "Stop $n")

    @Test fun togglingAStopAddsAndRemovesIt() = scenario {
        state.toggleLineStop(stop(1))
        assertTrue(draft.has("S1"))
        state.toggleLineStop(stop(1))
        assertFalse(draft.has("S1"))
        assertEquals(emptyList(), toasts)
    }

    @Test fun aFifthStopIsRefusedWithAWarning() = scenario {
        repeat(MaxTrackerStops) { state.toggleLineStop(stop(it)) }
        assertEquals(MaxTrackerStops, draft.stops.size)
        state.toggleLineStop(stop(99))
        assertFalse(draft.has("S99"))
        assertEquals(listOf(ToastType.Warning), toasts.map { it.type })
    }

    // ---- saving ----------------------------------------------------------------

    @Test fun savingALineCreatesATrackerAndReports() = scenario {
        signIn()
        draft.stops = listOf(TrackerStop("S1", "Stop 1", "Out"))
        var saved = false
        state.saveLine(Tram6, existing = emptyList()) { saved = true }
        until { saved }
        assertEquals(1, backend.saved.size)
        assertFalse(state.isSaving)
        val toast = toasts.single()
        assertEquals(ToastType.Success, toast.type)
        assertEquals("Line tracker added", toast.title)
        assertEquals("Tracking 1 stop on 6.", toast.description)
    }

    @Test fun savingALineThatHasATrackerUpdatesItsStops() = scenario {
        signIn()
        draft.stops = listOf(TrackerStop("S1", "Stop 1", ""), TrackerStop("S2", "Stop 2", ""))
        var saved = false
        state.saveLine(Tram6, existing = listOf(TransitTracker(id = "t0", routeId = Tram6.id))) { saved = true }
        until { saved }
        assertEquals(listOf("t0"), backend.patched.map { it.first })
        assertEquals(emptyList(), backend.saved)
        assertEquals("Line tracker updated", toasts.single().title)
        assertEquals("Updated 6 with 2 stops.", toasts.single().description)
    }

    @Test fun savingAnEmptyLineDoesNothing() = scenario {
        signIn()
        var saved = false
        state.saveLine(Tram6, existing = emptyList()) { saved = true }
        delay(100)
        assertFalse(saved)
        assertEquals(emptyList(), backend.saved)
        assertEquals(emptyList(), toasts)
    }

    @Test fun aFailedSaveShowsAnErrorAndDoesNotReportSaved() = scenario {
        signIn()
        backend.saveFails = true
        draft.stops = listOf(TrackerStop("S1", "Stop 1", ""))
        var saved = false
        state.saveLine(Tram6, existing = emptyList()) { saved = true }
        until { toasts.isNotEmpty() }
        assertEquals(ToastType.Error, toasts.single().type)
        assertEquals("Failed to save tracker", toasts.single().title)
        assertFalse(saved)
        assertFalse(state.isSaving)
    }

    private suspend fun Fixture.pickStopAndRoute(route: FutarRoute, headsign: String = "") {
        backend.onArrivals = { HttpStatusCode.OK to arrivals(route) }
        state.selectStop("BKK_S1")
        until { !state.loadingRoutes }
        state.selectedRouteId = route.id
        state.selectedHeadsign = headsign
        // The stop pane resolves the picked stop from the catalog.
        state.loadCatalog()
    }

    @Test fun savingAStopCreatesATrackerWithItsDirection() = scenario {
        signIn()
        pickStopAndRoute(Tram6, headsign = "Moricz")
        var saved = false
        state.saveStop(PickedStop("BKK_S1", "Astoria", null), existing = emptyList()) { saved = true }
        until { saved }
        assertEquals(1, backend.saved.size)
        assertEquals("Tracker added", toasts.single().title)
        assertEquals("6 at Astoria towards Moricz.", toasts.single().description)
    }

    @Test fun aStopOnALineThatHasATrackerIsAddedToIt() = scenario {
        signIn()
        pickStopAndRoute(Tram6)
        val existing = TransitTracker(id = "t0", routeId = Tram6.id, stops = listOf(TrackerStop("S0", "Stop 0", "")))
        var saved = false
        state.saveStop(PickedStop("BKK_S1", "Astoria", null), existing = listOf(existing)) { saved = true }
        until { saved }
        assertEquals(listOf("t0"), backend.patched.map { it.first })
        assertEquals("Added to existing line tracker", toasts.single().title)
    }

    @Test fun aLineTrackerAlreadyAtTheLimitRefusesAnotherStop() = scenario {
        signIn()
        pickStopAndRoute(Tram6)
        val full = TransitTracker(id = "t0", routeId = Tram6.id, stops = List(MaxTrackerStops) { TrackerStop("S$it", "Stop $it", "") })
        var saved = false
        state.saveStop(PickedStop("BKK_S1", "Astoria", null), existing = listOf(full)) { saved = true }
        until { toasts.isNotEmpty() }
        assertEquals(ToastType.Error, toasts.single().type)
        assertEquals(emptyList(), backend.patched)
        assertFalse(saved)
    }

    @Test fun aStopAlreadyOnTheLineIsUpdatedEvenAtTheLimit() = scenario {
        signIn()
        pickStopAndRoute(Tram6, headsign = "Other way")
        val full = TransitTracker(id = "t0", routeId = Tram6.id, stops = List(MaxTrackerStops) { TrackerStop("BKK_S$it", "Stop $it", "") })
        var saved = false
        state.saveStop(PickedStop("BKK_S1", "Stop 1", null), existing = listOf(full)) { saved = true }
        until { saved }
        assertEquals(listOf("t0"), backend.patched.map { it.first })
    }

    @Test fun savingAStopNeedsARouteFirst() = scenario {
        signIn()
        var saved = false
        state.saveStop(PickedStop("BKK_S1", "Astoria", null), existing = emptyList()) { saved = true }
        delay(100)
        assertFalse(saved)
        assertEquals(emptyList(), toasts)
    }

    // ---- catalog ---------------------------------------------------------------

    @Test fun theCatalogLoadsOnceAndFeedsTheStopSearch() = scenario {
        assertFalse(state.catalogLoaded)
        state.loadCatalog()
        assertTrue(state.catalogLoaded)
        assertFalse(state.catalogError)
        state.searchQuery = "astoria"
        assertEquals(listOf("BKK_S1"), state.filteredStops.map { it.id })
        state.searchQuery = "  "
        assertEquals(emptyList(), state.filteredStops)
    }

    @Test fun aFailedCatalogLoadCanBeRetried() = scenario {
        backend.catalogDown = true
        state.loadCatalog()
        assertTrue(state.catalogError)
        assertFalse(state.catalogLoaded)
        state.retryCatalog()
        assertFalse(state.catalogError)
        assertEquals(1, state.catalogAttempt)
        backend.catalogDown = false
        state.loadCatalog()
        assertTrue(state.catalogLoaded)
    }

    @Test fun aCatalogStopOpensTheSamePaneAsAQuickPick() = scenario {
        state.loadCatalog()
        state.selectStop("BKK_S2")
        val pane = state.pane as AddTrackerPane.Stop
        assertEquals(PickedStop("BKK_S2", "Blaha Lujza ter", null), pane.stop)
    }

    @Test fun theRouteStopSearchIgnoresAccentsAndCase() = scenario {
        draft.routeStops = listOf(FutarStop(id = "S1", name = "Blaha Lujza tér"), FutarStop(id = "S2", name = "Astoria"))
        state.stopQuery = "TER"
        assertEquals(listOf("S1"), state.filteredRouteStops.map { it.id })
        state.stopQuery = ""
        assertEquals(2, state.filteredRouteStops.size)
    }

    @Test fun stopCountsArePluralised() {
        assertEquals("1 stop", stopCountLabel(1))
        assertEquals("3 stops", stopCountLabel(3))
        assertEquals("0 stops", stopCountLabel(0))
    }
}
