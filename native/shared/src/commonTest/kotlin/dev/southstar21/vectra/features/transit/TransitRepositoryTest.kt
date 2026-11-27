package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.config.DemoAccounts
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.AuthState
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.bool
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.network.int
import dev.southstar21.vectra.core.network.json
import dev.southstar21.vectra.core.network.string
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import dev.southstar21.vectra.features.transit.data.CatalogDiskCache
import dev.southstar21.vectra.features.transit.data.FutarArrivalsResponse
import dev.southstar21.vectra.features.transit.data.FutarRouteDetailsResponse
import dev.southstar21.vectra.features.transit.data.FutarSource
import dev.southstar21.vectra.features.transit.data.FutarTripDetailsResponse
import dev.southstar21.vectra.features.transit.data.FutarVehiclesForRouteResponse
import dev.southstar21.vectra.features.transit.data.StopArrivalsBatch
import dev.southstar21.vectra.features.transit.demo.DemoFeed
import dev.southstar21.vectra.features.transit.demo.DemoFutarSource
import dev.southstar21.vectra.features.transit.demo.DemoLines
import dev.southstar21.vectra.features.transit.demo.DemoSeeder
import dev.southstar21.vectra.features.transit.demo.RealDemoTrackers
import dev.southstar21.vectra.features.transit.data.NewTrackerInput
import dev.southstar21.vectra.features.transit.data.NotSignedInException
import dev.southstar21.vectra.features.transit.data.TrackerStop
import dev.southstar21.vectra.features.transit.data.TransitRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val Owner = "user-1"

/** `transit_trackers` and the crypto route in memory; ciphertext is "vc3:" plus the clear text. */
private class TrackerBackend {
    val rows = mutableListOf<JsonObject>()
    val updates = mutableListOf<Pair<String, JsonObject>>()
    var failUpdatesFor = emptySet<String>()
    /** Decrypt answers with the ciphertext unchanged, as when the key is unavailable. */
    var decryptBroken = false
    private var nextId = 1

    fun favorites() = rows.filter { it.bool("is_favorite") == true }.map { it.string("id")!! }

    fun row(id: String) = rows.first { it.string("id") == id }

    private fun body(req: HttpRequestData): JsonObject =
        VectraJson.parseToJsonElement((req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.list(items: List<JsonObject>) = json(JsonObject(mapOf("items" to JsonArray(items))).toString())

    fun handle(scope: io.ktor.client.engine.mock.MockRequestHandleScope, req: HttpRequestData) = with(scope) {
        val path = req.url.encodedPath
        if (path == "/api/vectra/crypto") crypto(this, body(req)) else records(this, req, path.substringAfter("/records", "").trim('/'))
    }

    private fun records(scope: io.ktor.client.engine.mock.MockRequestHandleScope, req: HttpRequestData, id: String) = with(scope) {
        when (req.method) {
            HttpMethod.Get -> if (id.isNotEmpty()) json(row(id).toString()) else list(listed(req))
            HttpMethod.Post -> {
                val saved = JsonObject(body(req) + ("id" to JsonPrimitive("t${nextId++}")))
                rows += saved
                json(saved.toString())
            }
            HttpMethod.Patch -> patch(this, id, body(req))
            else -> error("unexpected ${req.method}")
        }
    }

    private fun listed(req: HttpRequestData): List<JsonObject> {
        val filter = req.url.parameters["filter"].orEmpty()
        return when {
            "is_favorite = true" in filter -> rows.filter { it.bool("is_favorite") == true }
            req.url.parameters["sort"] == "-sort_order" -> rows.sortedByDescending { it.int("sort_order") ?: -1 }.take(1)
            else -> rows.sortedBy { it.int("sort_order") ?: -1 }
        }
    }

    private fun patch(scope: io.ktor.client.engine.mock.MockRequestHandleScope, id: String, patch: JsonObject) = with(scope) {
        if (id in failUpdatesFor) return@with json("{}", HttpStatusCode.InternalServerError)
        updates += id to patch
        val i = rows.indexOfFirst { it.string("id") == id }
        rows[i] = JsonObject(rows[i] + patch)
        json(rows[i].toString())
    }

    private fun crypto(scope: io.ktor.client.engine.mock.MockRequestHandleScope, b: JsonObject) = with(scope) {
        when (val op = (b.getValue("op") as JsonPrimitive).content) {
            "digest" -> {
                val out = b.getValue("parts").jsonArray.map { parts -> "d:" + parts.jsonArray.joinToString("|") { (it as JsonPrimitive).content } }
                json(JsonObject(mapOf("digests" to JsonArray(out.map(::JsonPrimitive)))).toString())
            }
            else -> {
                val values = b.getValue("values").jsonArray.map { (it as JsonPrimitive).content }
                val out = when {
                    op == "encrypt" -> values.map { "vc3:$it" }
                    decryptBroken -> values
                    else -> values.map { it.removePrefix("vc3:") }
                }
                json(JsonObject(mapOf("values" to JsonArray(out.map(::JsonPrimitive)))).toString())
            }
        }
    }
}

/** A feed that answers every call with an empty response and records which ids were asked for. */
private class RecordingSource : FutarSource {
    val calls = mutableListOf<String>()
    override suspend fun arrivals(stopId: String) = FutarArrivalsResponse().also { calls += "arrivals:$stopId" }
    override suspend fun arrivalsWithLimit(stopId: String, limit: Int) = FutarArrivalsResponse().also { calls += "arrivalsWithLimit:$stopId" }
    override suspend fun stopArrivals(stopId: String, minutesAfter: Int) = FutarArrivalsResponse().also { calls += "stopArrivals:$stopId" }
    override suspend fun arrivalsBatch(stopIds: List<String>, minutesAfter: Int) =
        StopArrivalsBatch(stopIds.associateWith { FutarArrivalsResponse() }, emptyMap()).also { calls += "batch:${stopIds.joinToString(",")}" }
    override suspend fun vehicles(routeId: String) = FutarVehiclesForRouteResponse().also { calls += "vehicles:$routeId" }
    override suspend fun memberVehicles(routeId: String) = FutarVehiclesForRouteResponse().also { calls += "memberVehicles:$routeId" }
    override suspend fun tripDetails(tripId: String, serviceDate: String?) = FutarTripDetailsResponse().also { calls += "trip:$tripId" }
    override suspend fun routeDetails(routeId: String) = FutarRouteDetailsResponse().also { calls += "route:$routeId" }
}

private class RepoHarness(email: String? = null) {
    val backend = TrackerBackend()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kv = KeyValueStore(FakeDataStore())
    private val http = HttpClient(MockEngine { req -> backend.handle(this, req) })
    private val store = AuthStore(kv, PlainSecretCipher)
    private val signedInAs = email
    suspend fun signOut() { store.clear() }
    private val pb = PocketBaseClient(createHttpClient(http), "https://pb.test", store)
    val auth = AuthRepository(pb, store, kv, scope, lazyOf(emptyList()))
    private val connectivity = object : Connectivity { override val online = MutableStateFlow(true) }
    val source = RecordingSource()
    lateinit var repo: TransitRepository

    suspend fun start(feed: (FutarSource) -> FutarSource = { it }) {
        val record = buildMap {
            put("id", JsonPrimitive(Owner))
            signedInAs?.let { put("email", JsonPrimitive(it)) }
        }
        store.save(AuthState("tok", JsonObject(record)))
        repo = TransitRepository(
            pb, CryptoClient(pb, scope), kv, auth, connectivity, feed(source), http, "https://web.test/",
            CatalogDiskCache(Path("vectra-transit-repo-test-unused")), scope,
        )
    }

    /** Creates a tracker for route [n] and returns its row id. */
    suspend fun add(n: Int, stopId: String = "S$n", headsign: String = "H$n"): String =
        repo.addTracker(
            NewTrackerInput(
                routeId = "R$n", routeShortName = "$n", routeLongName = "Line $n", routeType = "BUS",
                routeColor = "000000", routeTextColor = "FFFFFF",
                stops = listOf(TrackerStop(stopId, "Stop $stopId", headsign)),
            ),
        ).id
}

private fun scenario(email: String? = null, feed: (FutarSource) -> FutarSource = { it }, test: suspend RepoHarness.() -> Unit) = runTest {
    val h = RepoHarness(email)
    try {
        withContext(Dispatchers.Default) {
            withTimeout(20_000) {
                h.start(feed)
                h.test()
            }
        }
    } finally {
        h.scope.cancel()
    }
}

class TransitRepositoryTest {
    // ---- create ----------------------------------------------------------------------------

    @Test fun aNewTrackerIsStoredEncryptedWithTheServerDigest() = scenario {
        val id = add(1, stopId = "S1", headsign = "North")
        val row = backend.row(id)
        assertEquals("vc3:S1", row.string("stop_id"))
        assertEquals("vc3:Stop S1", row.string("stop_name"))
        assertEquals("vc3:R1", row.string("route_id"))
        assertEquals("vc3:1", row.string("route_short_name"))
        assertEquals("vc3:Line 1", row.string("route_long_name"))
        assertEquals("vc3:North", row.string("headsign"))
        assertTrue(row.string("stops")!!.startsWith("vc3:"))
        assertEquals("d:R1|S1|North", row.string("dedupe_key"))
        assertEquals(false, row.bool("is_favorite"))
        assertEquals(Owner, row.string("user"))
        // The plain colours and type stay readable for the list.
        assertEquals("BUS", row.string("route_type"))
        assertEquals("000000", row.string("route_color"))
    }

    @Test fun aTrackerWithoutAHeadsignStoresNone() = scenario {
        val id = repo.addTracker(
            NewTrackerInput("R9", "9", "Nine", "BUS", "000000", "FFFFFF", stops = listOf(TrackerStop("S9", "Stop 9", null))),
        ).id
        // Stored as an empty string, the same as an edit to a no-headsign stop writes.
        assertEquals(JsonPrimitive(""), backend.row(id)["headsign"])
        assertEquals("d:R9|S9|", backend.row(id).string("dedupe_key"))
    }

    @Test fun anEmptyHeadsignReadsBackTheSameWhetherAddedOrEdited() = scenario {
        val added = repo.addTracker(
            NewTrackerInput("R9", "9", "Nine", "BUS", "000000", "FFFFFF", stops = listOf(TrackerStop("S9", "Stop 9", null))),
        ).id
        val edited = add(1, headsign = "North")
        repo.updateTrackerStops(edited, listOf(TrackerStop("S1", "Stop S1", null)))
        val list = repo.trackersQuery.fetch(Unit).associateBy { it.id }
        assertEquals("", list.getValue(added).headsign)
        assertEquals("", list.getValue(edited).headsign)
        assertEquals(backend.row(added)["headsign"], backend.row(edited)["headsign"])
    }

    @Test fun anEditedTrackerRoundTripsEveryStopColumn() = scenario {
        val id = add(1)
        val stops = listOf(TrackerStop("S2", "Second", "South"), TrackerStop("S3", "Third", "West"))
        repo.updateTrackerStops(id, stops)
        val tracker = repo.trackersQuery.fetch(Unit).single()
        assertEquals("S2", tracker.stopId)
        assertEquals("Second", tracker.stopName)
        assertEquals("South", tracker.headsign)
        assertEquals(stops, tracker.stops)
        // The route's own columns are untouched by an edit.
        assertEquals("Line 1", tracker.routeLongName)
    }

    @Test fun theSignedInUserComesFromTheAuthRepository() = scenario {
        val id = add(1)
        assertEquals(Owner, backend.row(id).string("user"))
        assertEquals(Owner, repo.trackersQuery.fetch(Unit).single().userId)
    }

    // ---- feeds and the demo source -------------------------------------------------------------

    @Test fun feedsAskTheSourceForEachKindOfQuery() = scenario {
        repo.feeds.arrivals.fetch("S1")
        repo.feeds.vehicles.fetch("R1")
        repo.feeds.routeDetails.fetch("R1")
        repo.stopArrivals("S2", 30)
        repo.feeds.batchArrivals.fetch("S3,S4")
        assertEquals(listOf("arrivals:S1", "vehicles:R1", "route:R1", "stopArrivals:S2", "batch:S3,S4"), source.calls)
    }

    @Test fun aBatchSeedsEachStopsOwnEntry() = scenario {
        repo.feeds.batchArrivals.fetch("S3,S4")
        // setData lands on the query's own dispatcher.
        withTimeout(5_000) { while (repo.feeds.arrivals.peek("S3").data == null || repo.feeds.arrivals.peek("S4").data == null) kotlinx.coroutines.delay(20) }
    }

    @Test fun theDemoSourceAnswersDemoIdsItselfAndPassesTheRestOn() = scenario(feed = ::DemoFutarSource) {
        val demoStop = DemoLines.first().stops.first().stopId
        assertTrue(DemoFeed.handles(demoStop))
        repo.feeds.arrivals.fetch(demoStop)
        repo.feeds.deepArrivals.fetch(demoStop to 10)
        repo.stopArrivals(demoStop, 30)
        assertEquals(emptyList(), source.calls)
        repo.feeds.arrivals.fetch("S1")
        assertEquals(listOf("arrivals:S1"), source.calls)
    }

    @Test fun theDemoSourceLeavesDemoStopsOutOfABatch() = scenario(feed = ::DemoFutarSource) {
        val demoStop = DemoLines.first().stops.first().stopId
        repo.feeds.batchArrivals.fetch("S1,$demoStop")
        assertEquals(listOf("batch:S1"), source.calls)
    }

    @Test fun withoutTheDemoSourceDemoIdsGoToTheFeed() = scenario {
        val demoStop = DemoLines.first().stops.first().stopId
        repo.feeds.arrivals.fetch(demoStop)
        assertEquals(listOf("arrivals:$demoStop"), source.calls)
    }

    // ---- demo seeding --------------------------------------------------------------------------

    @Test fun theNoPinDemoAccountGetsTwoRealLinesAndNoPins() = scenario(email = DemoAccounts.TransitNoPin) {
        DemoSeeder(auth, repo, scope, enabled = true).seed(DemoAccounts.TransitNoPin)
        val trackers = repo.trackersQuery.fetch(Unit)
        assertEquals(2, trackers.size)
        assertTrue(trackers.none { it.isFavorite })
    }

    @Test fun theMainDemoAccountGetsItsEdgeCasesAndPinsAndSeedsOnlyOnce() = scenario(email = DemoAccounts.Transit) {
        val seeder = DemoSeeder(auth, repo, scope, enabled = true)
        seeder.seed(DemoAccounts.Transit)
        val first = repo.trackersQuery.fetch(Unit)
        assertEquals(DemoLines.size + RealDemoTrackers.size, first.size)
        assertEquals(DemoLines.count { it.pinned }, first.count { it.isFavorite })
        seeder.seed(DemoAccounts.Transit)
        assertEquals(first.size, repo.trackersQuery.fetch(Unit).size)
    }

    @Test fun aDisabledSeederNeverStartsSeeding() = scenario(email = DemoAccounts.Transit) {
        DemoSeeder(auth, repo, scope, enabled = false).start()
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(300) }
        assertTrue(backend.rows.isEmpty())
    }

    @Test fun anEnabledSeederSeedsADemoAccountOnItsOwnButNotOthers() = scenario(email = DemoAccounts.TransitNoPin) {
        DemoSeeder(auth, repo, scope, enabled = true).start()
        withTimeout(10_000) { while (backend.rows.size < 2) kotlinx.coroutines.delay(50) }
        assertEquals(2, backend.rows.size)
    }

    @Test fun anAccountThatIsNotADemoOneIsLeftAlone() = scenario(email = "someone@test.com") {
        DemoSeeder(auth, repo, scope, enabled = true).start()
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(300) }
        assertTrue(backend.rows.isEmpty())
    }

    @Test fun newTrackersGoToTheEndOfTheManualOrder() = scenario {
        val a = add(1)
        val b = add(2)
        val c = add(3)
        assertEquals(listOf(0, 1, 2), listOf(a, b, c).map { backend.row(it).int("sort_order") })
    }

    @Test fun creatingWhileSignedOutIsRejected() = scenario {
        signOut()
        assertFailsWith<NotSignedInException> { add(1) }
    }

    // ---- favourites ------------------------------------------------------------------------

    @Test fun theFifthFavouriteIsAllowedAndTheSixthRejected() = scenario {
        val ids = (1..6).map { add(it) }
        ids.take(MaxFavoriteTrackers).forEach { assertTrue(repo.toggleFavorite(it)) }
        assertEquals(MaxFavoriteTrackers, backend.favorites().size)
        val error = assertFailsWith<IllegalStateException> { repo.toggleFavorite(ids[5]) }
        assertEquals("Maximum of $MaxFavoriteTrackers favourite trackers allowed.", error.message)
        assertFalse(ids[5] in backend.favorites())
    }

    @Test fun unfavouritingStillWorksAtTheLimitAndFreesASlot() = scenario {
        val ids = (1..6).map { add(it) }
        ids.take(MaxFavoriteTrackers).forEach { repo.toggleFavorite(it) }
        assertFalse(repo.toggleFavorite(ids[0]))
        assertTrue(repo.toggleFavorite(ids[5]))
        assertEquals(setOf(ids[1], ids[2], ids[3], ids[4], ids[5]), backend.favorites().toSet())
    }

    @Test fun togglingAFavouriteLeavesTheManualOrderAlone() = scenario {
        val ids = (1..3).map { add(it) }
        repo.toggleFavorite(ids[2])
        assertEquals(2, backend.row(ids[2]).int("sort_order"))
        assertTrue(backend.updates.all { (_, body) -> body.keys == setOf("is_favorite") })
    }

    // ---- reorder and edit ------------------------------------------------------------------

    @Test fun reorderingWritesEachTrackersPosition() = scenario {
        val ids = (1..3).map { add(it) }
        repo.reorderTrackers(listOf(ids[2], ids[0], ids[1]))
        assertEquals(listOf(1, 2, 0), ids.map { backend.row(it).int("sort_order") })
    }

    @Test fun aReorderWithOneFailedWriteReportsItAndKeepsTheOthers() = scenario {
        val ids = (1..3).map { add(it) }
        backend.failUpdatesFor = setOf(ids[1])
        assertFailsWith<Throwable> { repo.reorderTrackers(listOf(ids[2], ids[1], ids[0])) }
        // The row that could not be written keeps its old position; the refetch shows the mix.
        assertEquals(1, backend.row(ids[1]).int("sort_order"))
        // The orderLock is released: a later toggle still goes through.
        backend.failUpdatesFor = emptySet()
        assertTrue(repo.toggleFavorite(ids[0]))
    }

    @Test fun editingStopsRefreshesTheDedupeKeyFromThePrimaryStop() = scenario {
        val id = add(1, stopId = "S1", headsign = "North")
        repo.updateTrackerStops(id, listOf(TrackerStop("S2", "Second", "South"), TrackerStop("S3", "Third", null)))
        val row = backend.row(id)
        assertEquals("d:R1|S2|South", row.string("dedupe_key"))
        assertEquals("vc3:S2", row.string("stop_id"))
        assertEquals("vc3:Second", row.string("stop_name"))
        assertEquals("vc3:South", row.string("headsign"))
    }

    @Test fun editingToANoHeadsignStopClearsTheStoredHeadsign() = scenario {
        val id = add(1, headsign = "North")
        repo.updateTrackerStops(id, listOf(TrackerStop("S2", "Second", null)))
        assertEquals(JsonPrimitive(""), backend.row(id)["headsign"])
    }

    // ---- reading ---------------------------------------------------------------------------

    @Test fun trackersComeBackDecryptedInManualOrder() = scenario {
        val a = add(1, headsign = "North")
        val b = add(2)
        val list = repo.trackersQuery.fetch(Unit)
        assertEquals(listOf(a, b), list.map { it.id })
        assertEquals("S1", list[0].stopId)
        assertEquals("Line 1", list[0].routeLongName)
        assertEquals("North", list[0].headsign)
        assertEquals(listOf(TrackerStop("S1", "Stop S1", "North")), list[0].stops)
    }

    @Test fun anUndecryptableFetchFallsBackToTheLastGoodCopy() = scenario {
        val a = add(1)
        assertEquals(listOf(a), repo.trackersQuery.fetch(Unit).map { it.id })
        add(2)
        backend.decryptBroken = true
        // Ciphertext is never shown: the earlier snapshot answers.
        assertEquals(listOf(a), repo.trackersQuery.fetch(Unit).map { it.id })
    }

    @Test fun anUndecryptableFetchWithNoCopyFailsInsteadOfShowingCiphertext() = scenario {
        add(1)
        backend.decryptBroken = true
        assertFailsWith<IllegalStateException> { repo.trackersQuery.fetch(Unit) }
    }
}
