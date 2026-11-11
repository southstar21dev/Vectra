package dev.southstar21.vectra.features.transit

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.maps.prepareOpenFreeMapStyle
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Large responses are decoded on the context they are given (Dispatchers.Default in the
 * app), not on the caller's main thread (audit 03 #16). [CountingDispatcher] shows the work
 * was handed to that context.
 */
@OptIn(ExperimentalAtomicApi::class)
class OffMainDecodeTest {
    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val dispatched = AtomicInt(0)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched.addAndFetch(1)
            delegate.dispatch(context, block)
        }
    }

    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private fun TestScope.counting() = CountingDispatcher(StandardTestDispatcher(testScheduler))

    private fun api(answer: String, decodeContext: CoroutineContext): FutarApi {
        val engine = MockEngine { respond(answer, headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        val pb = PocketBaseClient(createHttpClient(HttpClient(engine)), "https://pb.test", AuthStore(KeyValueStore(MemoryDataStore()), PlainSecretCipher))
        return FutarApi(pb, decodeContext)
    }

    // The Futár calls run on real threads (as in FutarApiTest): their 40 s timeout must not run on virtual time.
    @Test fun futarResponsesDecodeOnTheirOwnContext() = runTest { withContext(Dispatchers.Default) {
        val decode = CountingDispatcher(Dispatchers.Default)
        val res = api("""{"status":"OK","currentTime":1,"data":{"entry":{"stopId":"x","stopTimes":[]}}}""", decode).fetchStopArrivals("BKK_F1")
        assertEquals("x", res.data?.entry?.stopId)
        assertTrue(decode.dispatched.load() > 0, "decoded on the caller's thread")
    } }

    @Test fun routeDetailsParseOnTheirOwnContext() = runTest { withContext(Dispatchers.Default) {
        val work = CountingDispatcher(Dispatchers.Default)
        val answer = """{"status":"OK","data":{"entry":{"variants":[{"headsign":"Blaha","polyline":{"points":"_p~iF~ps|U_ulLnnqC"},"stopIds":["S1"]}]},"references":{"stops":{"S1":{"id":"S1","name":"One"}}}}}"""
        val details = api(answer, Dispatchers.Default).fetchParsedRouteDetails("BKK_3280", work)
        assertTrue("Blaha" in details.headsigns)
        assertTrue(details.polylines.isNotEmpty())
        assertTrue(work.dispatched.load() > 0, "parsed on the caller's thread")
    } }

    @Test fun theBasemapStyleIsPreparedOnItsOwnContext() = runTest {
        val work = counting()
        val out = prepareOpenFreeMapStyle("""{"version":8,"sources":{},"layers":[{"id":"bg","type":"background"}]}""", isLight = true, work = work)
        assertTrue(out.contains("\"bg\""))
        assertTrue(work.dispatched.load() > 0, "prepared on the caller's thread")
    }
}
