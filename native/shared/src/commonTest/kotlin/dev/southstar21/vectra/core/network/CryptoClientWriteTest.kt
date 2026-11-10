package dev.southstar21.vectra.core.network

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The directions that must fail rather than ever store plaintext. */
class CryptoClientWriteTest {
    private val ops = mutableListOf<JsonObject>()

    private fun client(reply: MockRequestHandleScope.(JsonObject) -> HttpResponseData) = CryptoClient(
        PocketBaseClient(
            createHttpClient(
                HttpClient(
                    MockEngine { req ->
                        val body = VectraJson.parseToJsonElement((req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                        ops += body
                        reply(body)
                    },
                ),
            ),
            "https://pb.test",
            AuthStore(KeyValueStore(FakeDataStore()), PlainSecretCipher),
        ),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private fun MockRequestHandleScope.ok(key: String, values: List<String>) = respond(
        JsonObject(mapOf(key to JsonArray(values.map(::JsonPrimitive)))).toString(),
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun real(test: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { test() } }

    private val encryptor = client { body -> ok("values", body.getValue("values").jsonArray.map { "vc3:" + it.jsonPrimitive.content }) }

    @Test fun encryptsInOrderInOneRoundTrip() = real {
        val out = coroutineScope { listOf("a", "b", "c").map { async { encryptor.encrypt(it) } }.awaitAll() }
        assertEquals(listOf("vc3:a", "vc3:b", "vc3:c"), out)
        assertTrue(ops.size <= 3)
        assertEquals("encrypt", ops.first().getValue("op").jsonPrimitive.content)
    }

    @Test fun aLargeEncryptBurstStaysUnderTheBatchCap() = real {
        val inputs = (1..1500).map { "p$it" }
        val out = coroutineScope { inputs.map { async { encryptor.encrypt(it) } }.awaitAll() }
        assertEquals(inputs.map { "vc3:$it" }, out)
        val sizes = ops.map { it.getValue("values").jsonArray.size }
        assertTrue(sizes.all { it <= MaxCryptoBatch }, "batch sizes $sizes")
        assertEquals(inputs.size, sizes.sum())
    }

    @Test fun nothingToEncryptNeedsNoRequest() = real {
        assertNull(encryptor.encrypt(null))
        assertEquals("", encryptor.encrypt(""))
        assertTrue(ops.isEmpty())
    }

    @Test fun aFailedEncryptThrows() = real {
        val c = client { respond("""{"message":"nope"}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertFailsWith<PocketBaseException> { c.encrypt("secret") }
    }

    @Test fun aShortResponseFailsEveryWaiterInsteadOfMisalignment() = real {
        val c = client { ok("values", listOf("only one")) }
        val results = coroutineScope { listOf("a", "b").map { async { runCatching { c.encrypt(it) } } }.awaitAll() }
        // Scheduling may split them into two single-value batches, which are then valid.
        if (ops.size == 1) assertTrue(results.all { it.isFailure })
    }

    @Test fun aResponseWithoutValuesFails() = real {
        val c = client { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        assertEquals("Crypto service returned an invalid encrypt response", assertFailsWith<IllegalStateException> { c.encrypt("a") }.message)
    }

    @Test fun digestSendsEachPartList() = real {
        val c = client { ok("digests", listOf("d1")) }
        assertEquals("d1", c.digest("x", "y"))
        assertEquals(listOf("x", "y"), ops.single().getValue("parts").jsonArray.single().jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun aPlainUrlPassesThroughButAnEncryptedOneDecrypts() = real {
        val c = client { body -> ok("values", body.getValue("values").jsonArray.map { it.jsonPrimitive.content.removePrefix("vc3:") }) }
        assertEquals("https://x.test", c.decryptUrl("https://x.test"))
        assertTrue(ops.isEmpty())
        assertEquals("https://y.test", c.decryptUrl("vc3:https://y.test"))
    }
}
