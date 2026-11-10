package dev.southstar21.vectra.features.transit

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.PocketBaseException
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The PocketBase transport is a ktor MockEngine. */
class FutarApiTest {
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    /** The batch fans out in parallel on Dispatchers.Default; a bare list add could drop a request. */
    private val recording = Mutex()

    private val ok = """{"status":"OK","currentTime":1,"data":{"entry":{"stopId":"x","stopTimes":[]}}}"""

    private fun api(requests: MutableList<HttpRequestData>): FutarApi {
        val engine = MockEngine { req ->
            recording.withLock { requests += req }
            respond(ok, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val pb = PocketBaseClient(
            createHttpClient(HttpClient(engine)),
            "https://pb.test",
            AuthStore(KeyValueStore(MemoryDataStore()), PlainSecretCipher),
        )
        return FutarApi(pb)
    }

    private fun body(req: HttpRequestData): JsonObject {
        val bytes = (req.body as OutgoingContent.ByteArrayContent).bytes()
        return VectraJson.parseToJsonElement(bytes.decodeToString()).jsonObject
    }

    @Test fun postsToFutarRoute() = runTest { withContext(Dispatchers.Default) {
        val reqs = mutableListOf<HttpRequestData>()
        api(reqs).fetchStopArrivals("BKK_123", 90)
        assertEquals("/api/vectra/futar", reqs[0].url.encodedPath)
        assertEquals(HttpMethod.Post, reqs[0].method)
    } }

    @Test fun forwardsDeepLimit() = runTest { withContext(Dispatchers.Default) {
        val reqs = mutableListOf<HttpRequestData>()
        api(reqs).fetchStopArrivals("BKK_123", 90, 200)
        assertEquals(
            VectraJson.parseToJsonElement(
                """{"endpoint":"arrivals-and-departures-for-stop","params":{"stopId":"BKK_123","minutesBefore":0,"minutesAfter":90,"limit":200}}""",
            ),
            body(reqs[0]),
        )
    } }

    /** Futár's own default is 60 stop-times, which truncates the 90-minute window at a big interchange. */
    @Test fun shallowFetchAsksForTheFullCap() = runTest { withContext(Dispatchers.Default) {
        val reqs = mutableListOf<HttpRequestData>()
        val res = api(reqs).fetchStopArrivals("BKK_123", 90)
        assertEquals(
            VectraJson.parseToJsonElement(
                """{"endpoint":"arrivals-and-departures-for-stop","params":{"stopId":"BKK_123","minutesBefore":0,"minutesAfter":90,"limit":200}}""",
            ),
            body(reqs[0]),
        )
        assertEquals("x", res.data?.entry?.stopId)
    } }

    /** After midnight a trip can belong to the previous service day, which Futár can't guess. */
    @Test fun tripDetailsCarryTheServiceDate() = runTest { withContext(Dispatchers.Default) {
        val reqs = mutableListOf<HttpRequestData>()
        api(reqs).fetchTripDetails("BKK_T1", "20261109")
        api(reqs).fetchTripDetails("BKK_T1")
        val (dated, undated) = reqs.map(::body)
        assertEquals(
            VectraJson.parseToJsonElement(
                """{"endpoint":"trip-details","params":{"tripId":"BKK_T1","includeSchedule":true,"includeStatus":true}}""",
            ),
            undated,
        )
        assertEquals(
            VectraJson.parseToJsonElement(
                """{"endpoint":"trip-details","params":{"tripId":"BKK_T1","includeSchedule":true,"includeStatus":true,"date":"20261109"}}""",
            ),
            dated,
        )
    } }

    private fun apiAnswering(status: HttpStatusCode = HttpStatusCode.OK, requests: MutableList<HttpRequestData> = mutableListOf(), answer: (HttpRequestData) -> String): FutarApi {
        val engine = MockEngine { req ->
            recording.withLock { requests += req }
            respond(answer(req), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return FutarApi(PocketBaseClient(createHttpClient(HttpClient(engine)), "https://pb.test", AuthStore(KeyValueStore(MemoryDataStore()), PlainSecretCipher)))
    }

    @Test fun decodesAResponseDirectly() = runTest { withContext(Dispatchers.Default) {
        val response = apiAnswering { ok }.fetchStopArrivals("BKK_F1")
        assertEquals("x", response.data?.entry?.stopId)
        assertEquals(1, response.currentTime)
    } }

    @Test fun aNotOkStatusSurfacesTheProxyMessage() = runTest { withContext(Dispatchers.Default) {
        val failure = runCatching { apiAnswering { """{"status":"ERROR","error":"upstream down"}""" }.fetchStopArrivals("BKK_F1") }.exceptionOrNull()
        assertIs<FutarException>(failure)
        assertEquals("upstream down", failure.message)
    } }

    @Test fun aNonJsonBodyIsAFutarFailure() = runTest { withContext(Dispatchers.Default) {
        val failure = runCatching { apiAnswering { "<html>" }.fetchStopArrivals("BKK_F1") }.exceptionOrNull()
        assertIs<FutarException>(failure)
    } }

    @Test fun anHttpFailureKeepsItsStatus() = runTest { withContext(Dispatchers.Default) {
        val failure = runCatching { apiAnswering(HttpStatusCode.BadGateway) { """{"message":"bad"}""" }.fetchStopArrivals("BKK_F1") }.exceptionOrNull()
        assertIs<PocketBaseException>(failure)
        assertEquals(502, failure.status)
    } }

    @Test fun aFailedBatchFallsBackPerStop() = runTest { withContext(Dispatchers.Default) {
        val requests = mutableListOf<HttpRequestData>()
        val batch = apiAnswering(requests = requests) { req ->
            if (body(req)["endpoint"]?.jsonPrimitive?.content == "stop-arrivals") """{"status":"ERROR"}""" else ok
        }.fetchStopArrivalsBatch(listOf("BKK_CS1", "BKK_CS2"))
        assertEquals(setOf("BKK_CS1", "BKK_CS2"), batch.responses.keys)
        assertEquals(3, requests.size)
    } }

    @Test fun aRateLimitedBatchDoesNotFanOut() = runTest { withContext(Dispatchers.Default) {
        val requests = mutableListOf<HttpRequestData>()
        val failure = runCatching {
            apiAnswering(HttpStatusCode.TooManyRequests, requests) { """{"message":"slow down"}""" }
                .fetchStopArrivalsBatch(listOf("BKK_CS1", "BKK_CS2"))
        }.exceptionOrNull()
        assertIs<PocketBaseException>(failure)
        assertEquals(429, failure.status)
        assertEquals(1, requests.size)
    } }

    @Test fun aServerErrorOnTheBatchStillFallsBack() = runTest { withContext(Dispatchers.Default) {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            recording.withLock { requests += req }
            val batch = body(req)["endpoint"]?.jsonPrimitive?.content == "stop-arrivals"
            respond(if (batch) """{"message":"bad"}""" else ok, if (batch) HttpStatusCode.BadGateway else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = FutarApi(PocketBaseClient(createHttpClient(HttpClient(engine)), "https://pb.test", AuthStore(KeyValueStore(MemoryDataStore()), PlainSecretCipher)))
        val batch = api.fetchStopArrivalsBatch(listOf("BKK_CS1", "BKK_CS2"))
        assertEquals(setOf("BKK_CS1", "BKK_CS2"), batch.responses.keys)
        assertEquals(3, requests.size)
    } }

    @Test fun aBatchWhereEveryStopFailedIsAFailure() = runTest { withContext(Dispatchers.Default) {
        val failure = runCatching {
            apiAnswering { """{"status":"ERROR","error":"down"}""" }.fetchStopArrivalsBatch(listOf("BKK_CS1", "BKK_CS2"))
        }.exceptionOrNull()
        assertIs<FutarException>(failure)
    } }

    @Test fun noAnswerAtAllSkipsTheFanOut() = runTest { withContext(Dispatchers.Default) {
        var calls = 0
        val engine = MockEngine { calls++; throw IOException("offline") }
        val api = FutarApi(PocketBaseClient(createHttpClient(HttpClient(engine)), "https://pb.test", AuthStore(KeyValueStore(MemoryDataStore()), PlainSecretCipher)))
        val failure = runCatching { api.fetchStopArrivalsBatch(listOf("BKK_CS1", "BKK_CS2")) }.exceptionOrNull()
        assertIs<PocketBaseException>(failure)
        assertEquals(1, calls)
    } }
}
