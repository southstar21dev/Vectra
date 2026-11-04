package dev.southstar21.vectra.core.network

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CryptoClientTest {
    /** Decrypts "vc3:x" to "x"; anything starting "vc3:bad" stays encrypted, like the server's failed slot. */
    private class Server(val hold: CompletableDeferred<Unit>? = null, val status: HttpStatusCode = HttpStatusCode.OK) {
        val batches = mutableListOf<List<String>>()
        val client = CryptoClient(
            PocketBaseClient(
                createHttpClient(
                    HttpClient(
                        MockEngine { req ->
                            val bytes = (req.body as OutgoingContent.ByteArrayContent).bytes()
                            val values = VectraJson.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("values").jsonArray
                                .map { it.jsonPrimitive.content }
                            batches += values
                            hold?.await()
                            val out = buildJsonObject {
                                put("values", buildJsonArray { values.forEach { add(if (it.startsWith("vc3:bad")) it else it.removePrefix("vc3:")) } })
                            }
                            respond(out.toString(), status, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ),
                ),
                "https://pb.test",
                AuthStore(KeyValueStore(FakeDataStore()), PlainSecretCipher),
            ),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    private fun real(test: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { test() } }

    @Test fun decryptAllIsOneRoundTripInOrder() = real {
        val s = Server()
        val out = s.client.decryptAll(listOf("vc3:a", null, "plain", "", "vc3:b"))
        assertEquals(listOf("a", null, "plain", "", "b"), out)
        assertEquals(listOf(listOf("vc3:a", "vc3:b")), s.batches)
    }

    @Test fun identicalCiphertextIsSentOnce() = real {
        val s = Server()
        assertEquals(listOf("a", "a"), s.client.decryptAll(listOf("vc3:a", "vc3:a")))
        assertEquals(listOf(listOf("vc3:a")), s.batches)
    }

    @Test fun scatteredCallersShareTheWindow() = real {
        val s = Server()
        val out = coroutineScope {
            (1..200).map { i -> async { s.client.decrypt("vc3:v$i") } }.awaitAll()
        }
        assertEquals((1..200).map { "v$it" }, out)
        // Several batches would still be correct on a stalled machine; the point is not 200.
        assertTrue(s.batches.size < 20, "split into ${s.batches.size} requests")
    }

    @Test fun aRepeatNeedsNoRoundTrip() = real {
        val s = Server()
        s.client.decrypt("vc3:a")
        assertEquals("a", s.client.decrypt("vc3:a"))
        assertEquals(1, s.batches.size)
    }

    @Test fun onlyTheUnknownValuesGoOut() = real {
        val s = Server()
        s.client.decrypt("vc3:a")
        assertEquals(listOf("a", "b"), s.client.decryptAll(listOf("vc3:a", "vc3:b")))
        assertEquals(listOf("vc3:b"), s.batches.last())
    }

    @Test fun clearingForgetsEverything() = real {
        val s = Server()
        s.client.decrypt("vc3:a")
        s.client.clearMemo()
        s.client.decrypt("vc3:a")
        assertEquals(2, s.batches.size)
    }

    @Test fun aDecryptInFlightCannotRefillAClearedMemo() = real {
        val hold = CompletableDeferred<Unit>()
        val s = Server(hold)
        coroutineScope {
            val call = async { s.client.decrypt("vc3:a") }
            while (s.batches.isEmpty()) delay(1)
            s.client.clearMemo()
            hold.complete(Unit)
            call.await()
        }
        s.client.decrypt("vc3:a")
        assertEquals(2, s.batches.size)
    }

    @Test fun aFailedDecryptIsNotRemembered() = real {
        val s = Server()
        assertEquals("vc3:bad", s.client.decrypt("vc3:bad"))
        s.client.decrypt("vc3:bad")
        assertEquals(2, s.batches.size)
    }

    @Test fun aServerFailurePassesTheRawValueThrough() = real {
        val s = Server(status = HttpStatusCode.InternalServerError)
        assertEquals(listOf("vc3:a"), s.client.decryptAll(listOf("vc3:a")))
        s.client.decrypt("vc3:a")
        assertEquals(2, s.batches.size)
    }
}
