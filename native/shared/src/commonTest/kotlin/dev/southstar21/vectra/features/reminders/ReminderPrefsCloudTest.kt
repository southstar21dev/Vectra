package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The commute legs (home and campus stops) reach the profile only as ciphertext. */
class ReminderPrefsCloudTest {
    private val cloud = ReminderPrefsCloud(
        CryptoClient(
            PocketBaseClient(
                createHttpClient(
                    HttpClient(
                        MockEngine { req ->
                            val body = VectraJson.parseToJsonElement((req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                            val encrypt = body.getValue("op").jsonPrimitive.content == "encrypt"
                            val out = body.getValue("values").jsonArray.map {
                                val v = it.jsonPrimitive.content
                                JsonPrimitive(if (encrypt) "vc3:$v" else v.removePrefix("vc3:"))
                            }
                            respond(
                                JsonObject(mapOf("values" to JsonArray(out))).toString(),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
                ),
                "https://pb.test",
                AuthStore(KeyValueStore(FakeDataStore()), PlainSecretCipher),
            ),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ),
    )

    private val leg = CommuteLeg(trackerId = "t1", stopId = "F00123", stopName = "Home stop", routeId = "BKK_3050", routeShortName = "28A", headsign = "Hungaria")
    private val prefs = ReminderPrefs(beforeClass = true, toUni = listOf(leg), backHome = listOf(leg.copy(trackerId = "t2")))

    private fun real(test: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { test() } }

    @Test fun legsNeverAppearInPlainText() = real {
        val json = cloud.encode(prefs)
        // The test cipher only prefixes, so look at everything except the blob itself.
        assertFalse("Home stop" in JsonObject(json - "commute").toString())
        assertEquals(JsonArray(emptyList()), json["toUni"])
        assertNotNull(json["commute"])
    }

    @Test fun legsRoundTripThroughTheBlob() = real {
        assertEquals(prefs, cloud.decode(cloud.encode(prefs)))
    }

    @Test fun noLegsMeansNoBlob() = real {
        val json = cloud.encode(ReminderPrefs(beforeClass = true))
        assertTrue("commute" !in json)
    }

    @Test fun legacyPlainLegsAreReadAndFlaggedForRewrite() = real {
        val legacy = VectraJson.encodeToJsonElement(ReminderPrefs.serializer(), prefs)
        assertEquals(prefs, cloud.decode(legacy))
        assertTrue(cloud.hasPlainLegs(legacy))
        assertFalse(cloud.hasPlainLegs(cloud.encode(prefs)))
    }
}
