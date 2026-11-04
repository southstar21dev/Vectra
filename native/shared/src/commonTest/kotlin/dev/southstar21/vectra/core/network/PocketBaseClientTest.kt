package dev.southstar21.vectra.core.network

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val Json = headersOf(HttpHeaders.ContentType, "application/json")

internal fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, Json)

class PocketBaseClientTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val store = AuthStore(KeyValueStore(FakeDataStore()), PlainSecretCipher)

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = PocketBaseClient(
        createHttpClient(HttpClient(MockEngine { req -> requests += req; handler(req) })),
        "https://pb.test/",
        store,
    )

    private suspend fun signIn() = store.save(AuthState("tok", JsonObject(mapOf("id" to JsonPrimitive("u1")))))

    @Test fun theTokenGoesOutAsIs() = runTest {
        signIn()
        client { json("{}") }.send("/x")
        assertEquals("tok", requests.single().headers["Authorization"])
        assertEquals("https://pb.test/x", requests.single().url.toString())
    }

    @Test fun noSessionMeansNoHeader() = runTest {
        client { json("{}") }.send("/x")
        assertNull(requests.single().headers["Authorization"])
    }

    @Test fun nullQueryValuesAreLeftOut() = runTest {
        client { json("{}") }.send("/x", query = mapOf("a" to "1", "b" to null))
        assertEquals("1", requests.single().url.parameters["a"])
        assertFalse(requests.single().url.parameters.contains("b"))
    }

    @Test fun aBlankBodyIsAnEmptyObject() = runTest {
        assertEquals(JsonObject(emptyMap()), client { json("") }.send("/x"))
    }

    @Test fun aNonJsonBodyComesBackAsText() = runTest {
        assertEquals("hello", client { json("hello") }.send("/x").jsonPrimitive.content)
    }

    @Test fun a401IsAnAuthRejectionAndLeavesTheSessionForTheWatchdog() = runTest {
        signIn()
        val pb = client { json("""{"message":"The request requires valid record authorization token."}""", HttpStatusCode.Unauthorized) }
        val e = assertFailsWith<PocketBaseException> { pb.send("/x") }
        assertEquals(401, e.status)
        assertTrue(e.isAuthRejection)
        assertFalse(e.isNetwork)
        assertEquals("The request requires valid record authorization token.", e.message)
        assertNotNull(store.current)
        // Nothing is retried behind the caller's back.
        assertEquals(1, requests.size)
    }

    @Test fun a403IsAnAuthRejection() = runTest {
        assertTrue(assertFailsWith<PocketBaseException> { client { json("{}", HttpStatusCode.Forbidden) }.send("/x") }.isAuthRejection)
    }

    @Test fun a500IsNotAnAuthRejectionAndIsNotRetried() = runTest {
        val e = assertFailsWith<PocketBaseException> { client { respondError(HttpStatusCode.InternalServerError) }.send("/x") }
        assertEquals(500, e.status)
        assertFalse(e.isAuthRejection)
        assertEquals("Something went wrong while processing your request.", e.message)
        assertEquals(1, requests.size)
    }

    @Test fun aNonJsonErrorStillThrows() = runTest {
        assertEquals(502, assertFailsWith<PocketBaseException> { client { json("<html>", HttpStatusCode.BadGateway) }.send("/x") }.status)
    }

    @Test fun fieldErrorsAreReadable() = runTest {
        val body = """{"message":"bad","data":{"email":{"code":"x","message":"Taken"},"odd":3}}"""
        val e = assertFailsWith<PocketBaseException> { client { json(body, HttpStatusCode.BadRequest) }.send("/x") }
        assertEquals(mapOf("email" to "Taken"), e.fieldErrors)
    }

    @Test fun noAnswerIsStatusZero() = runTest {
        val e = assertFailsWith<PocketBaseException> { client { throw IOException("no route") }.send("/x") }
        assertTrue(e.isNetwork)
        assertEquals("no route", e.message)
        assertNotNull(e.cause)
    }

    @Test fun cancellationIsNotReportedAsANetworkError() = runTest {
        val pb = client { CompletableDeferred<Unit>().await(); json("{}") }
        val job = withContext(Dispatchers.Default) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<CancellationException> { pb.send("/x") }
            }.also {
                while (requests.isEmpty()) delay(1)
                it.cancel()
            }
        }
        job.join()
    }

    @Test fun signingInSavesTheSession() = runTest {
        val out = client { json("""{"token":"new","record":{"id":"u9","email":"a@b.c"}}""") }.authWithPassword("a@b.c", "pw")
        assertEquals("new", store.token)
        assertEquals("u9", out.userId)
        assertEquals("a@b.c", store.current?.email)
    }

    @Test fun aSignInResponseWithoutARecordFailsWithoutSaving() = runTest {
        assertFailsWith<IllegalStateException> { client { json("""{"token":"new"}""") }.authWithPassword("a", "b") }
        assertNull(store.current)
    }

    @Test fun aSignInResponseWithANullTokenFailsWithoutSaving() = runTest {
        assertFailsWith<IllegalStateException> { client { json("""{"token":null,"record":{"id":"u1"}}""") }.authWithPassword("a", "b") }
        assertNull(store.current)
    }

    @Test fun refreshSavesARealToken() = runTest {
        signIn()
        client { json("""{"token":"next","record":{"id":"u1"}}""") }.authRefresh()
        assertEquals("next", store.token)
    }

    @Test fun refreshIgnoresAnUnusableBody() = runTest {
        signIn()
        assertNull(client { json("{}") }.authRefresh())
        assertEquals("tok", store.token)
    }

    @Test fun refreshRejectsANullTokenAndKeepsTheSession() = runTest {
        signIn()
        assertFailsWith<IllegalStateException> { client { json("""{"token":null,"record":{"id":"u1"}}""") }.authRefresh() }
        assertEquals("tok", store.token)
    }

    @Test fun aFullListWalksEveryPage() = runTest {
        val pb = client { req ->
            val page = req.url.parameters["page"]!!.toInt()
            json(if (page < 3) """{"items":[{"id":"a$page"},{"id":"b$page"}]}""" else """{"items":[{"id":"last"}]}""")
        }
        val rows = pb.collection("c").getFullList(batch = 2)
        assertEquals(listOf("a1", "b1", "a2", "b2", "last"), rows.map { it.string("id") })
        assertEquals(3, requests.size)
    }

    @Test fun recordWritesUseTheRightVerbsAndPaths() = runTest {
        val col = client { json("""{"id":"r1"}""") }.collection("c")
        col.create(buildJsonObject { put("a", 1) })
        col.update("r1", buildJsonObject { put("a", 2) })
        col.delete("r1")
        assertEquals(
            listOf(
                HttpMethod.Post to "/api/collections/c/records",
                HttpMethod.Patch to "/api/collections/c/records/r1",
                HttpMethod.Delete to "/api/collections/c/records/r1",
            ),
            requests.map { it.method to it.url.encodedPath },
        )
    }

    @Test fun filterStringsAreEscaped() {
        assertEquals("""'it\'s \\ ok'""", pbString("""it's \ ok"""))
    }
}

class AuthStoreTest {
    /** Publishing the session removes the login screen, which cancels the scope that is saving it. */
    @Test
    fun aSaveCancelledByItsOwnPublishStillReachesDisk() = runTest {
        val kv = KeyValueStore(FakeDataStore())
        val store = AuthStore(kv, PlainSecretCipher)
        val job = launch(Dispatchers.Default) { store.save(AuthState("token", buildJsonObject { put("id", "u1") })) }
        store.state.first { it != null }
        job.cancel()
        job.join()
        val restored = AuthStore(kv, PlainSecretCipher)
        restored.restore()
        assertEquals("u1", restored.current?.userId)
    }

    private val kv = KeyValueStore(FakeDataStore())
    private fun store() = AuthStore(kv, PlainSecretCipher)
    private val record = JsonObject(mapOf("id" to JsonPrimitive("u1")))

    @Test fun aSessionSurvivesARestart() = runTest {
        store().save(AuthState("tok", record))
        val next = store()
        next.restore()
        assertEquals(AuthState("tok", record), next.current)
    }

    @Test fun aClearedSessionDoesNotComeBack() = runTest {
        store().apply { save(AuthState("tok", record)); clear() }
        val next = store()
        next.restore()
        assertNull(next.current)
    }

    @Test fun anUnreadableStoredSessionIsNoSession() = runTest {
        for (stored in listOf("{bad", """{"token":"t"}""", """{"token":null,"record":{}}""", """{"record":{}}""", "[]")) {
            kv.set("pocketbase_auth", stored)
            val next = store()
            next.restore()
            assertNull(next.current, stored)
        }
    }

    @Test fun updatingTheRecordKeepsTheToken() = runTest {
        val s = store()
        s.save(AuthState("tok", record))
        val updated = JsonObject(mapOf("id" to JsonPrimitive("u1"), "email" to JsonPrimitive("e")))
        s.updateRecord(updated)
        assertEquals(AuthState("tok", updated), s.current)
    }

    @Test fun updatingTheRecordWithoutASessionDoesNothing() = runTest {
        val s = store()
        s.updateRecord(record)
        assertNull(s.current)
    }
}
