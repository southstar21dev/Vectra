package dev.southstar21.vectra.core.network

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import dev.southstar21.vectra.features.update.UpdateGate
import dev.southstar21.vectra.features.update.UpdateRequired
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateGateTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val kv = KeyValueStore(FakeDataStore())
    private val store = AuthStore(kv, PlainSecretCipher)

    /** Writes run inline (unconfined test scope) so the stored refusal is on disk when the next line reads it. */
    private fun CoroutineScope.gate(version: String = "1.9.0", store: KeyValueStore = kv) = UpdateGate(store, this, version)

    private fun client(
        version: String? = "1.9.0",
        platform: String? = "ios",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = PocketBaseClient(
        createHttpClient(HttpClient(MockEngine { req -> requests += req; handler(req) })),
        "https://pb.test/",
        store,
        clientVersion = version,
        clientPlatform = platform,
    )

    private val refused = """{"status":426,"message":"This version of Vectra is out of date.","data":{"minVersion":"2.0.0"}}"""

    @Test
    fun everyRequestCarriesTheVersionAndPlatform() = runTest {
        val pb = client { json("{}") }
        pb.send("/api/health")
        pb.collection("schedule_items").getList()
        assertEquals(2, requests.size)
        requests.forEach {
            assertEquals("1.9.0", it.headers[PocketBaseClient.AppVersionHeader])
            assertEquals("ios", it.headers[PocketBaseClient.AppPlatformHeader])
        }
    }

    @Test
    fun aClientBuiltWithoutThemSendsNoHeaders() = runTest {
        val pb = client(version = null, platform = null) { json("{}") }
        pb.send("/api/health")
        assertNull(requests.single().headers[PocketBaseClient.AppVersionHeader])
        assertNull(requests.single().headers[PocketBaseClient.AppPlatformHeader])
    }

    @Test
    fun a426ReportsTheMinimumAndStillThrows() = runTest {
        val pb = client { json(refused, HttpStatusCode.UpgradeRequired) }
        var reported: String? = "unset"
        pb.onUpdateRequired = { reported = it }
        val e = assertFailsWith<PocketBaseException> { pb.send("/api/collections/x/records") }
        assertTrue(e.isUpdateRequired)
        assertEquals("2.0.0", reported)
    }

    @Test
    fun aBlankMinimumArrivesAsNull() = runTest {
        val pb = client { json("""{"status":426,"message":"old","data":{"minVersion":""}}""", HttpStatusCode.UpgradeRequired) }
        var calls = 0
        var reported: String? = "unset"
        pb.onUpdateRequired = { calls++; reported = it }
        assertFailsWith<PocketBaseException> { pb.send("/api/health") }
        assertEquals(1, calls)
        assertNull(reported)
    }

    @Test
    fun otherErrorsDoNotReport() = runTest {
        val pb = client { json("""{"status":403,"message":"no"}""", HttpStatusCode.Forbidden) }
        var calls = 0
        pb.onUpdateRequired = { calls++ }
        assertFailsWith<PocketBaseException> { pb.send("/api/health") }
        assertEquals(0, calls)
    }

    @Test
    fun theGateSetsOnRefusalAndClearsOnSuccess() = runTest {
        var refuse = true
        val pb = client { if (refuse) json(refused, HttpStatusCode.UpgradeRequired) else json("""{"code":200}""") }
        val gate = gate()
        pb.onUpdateRequired = gate::report

        gate.check(pb)
        assertEquals(UpdateRequired("2.0.0"), gate.state.value)

        refuse = false
        gate.check(pb)
        assertNull(gate.state.value)
    }

    @Test
    fun aFailedCheckLeavesTheStateAlone() = runTest {
        var status = HttpStatusCode.UpgradeRequired
        val pb = client { json(if (status == HttpStatusCode.UpgradeRequired) refused else "{}", status) }
        val gate = gate()
        pb.onUpdateRequired = gate::report
        gate.check(pb)

        status = HttpStatusCode.BadGateway
        gate.check(pb)
        assertEquals(UpdateRequired("2.0.0"), gate.state.value)
    }

    @Test
    fun aRefusalOutlivesARestartOfTheSameBuild() = runTest {
        val pb = client { json(refused, HttpStatusCode.UpgradeRequired) }
        val first = gate()
        pb.onUpdateRequired = first::report
        first.check(pb)
        testScheduler.advanceUntilIdle()

        val relaunched = gate()
        relaunched.loadStored()
        assertEquals(UpdateRequired("2.0.0"), relaunched.state.value)
    }

    @Test
    fun anUpdatedBuildIgnoresAndDropsTheOldRefusal() = runTest {
        val old = gate(version = "1.9.0")
        old.report("2.0.0")
        testScheduler.advanceUntilIdle()

        val updated = gate(version = "2.0.0")
        updated.loadStored()
        assertNull(updated.state.value)
        assertNull(kv.get("vectra.updateRequired"))
    }

    @Test
    fun aServedCheckUnlocksForGood() = runTest {
        val pb = client { json("""{"code":200}""") }
        val first = gate()
        first.report("2.0.0")
        testScheduler.advanceUntilIdle()
        first.check(pb)
        assertNull(first.state.value)

        val relaunched = gate()
        relaunched.loadStored()
        assertNull(relaunched.state.value)
    }
}
