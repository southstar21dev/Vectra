package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.AuthState
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.network.json
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val Owner = "user-1"
private const val FeedUrl = "https://neptun.example/feed.ics"
private const val HourMs = NeptunSyncService.ONE_HOUR_MS
private const val Now = 1_900_000_000_000L

private val Feed = listOf(
    "BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:alg-1",
    "DTSTART:20990302T100000", "DTEND:20990302T113000", "RRULE:FREQ=WEEKLY;COUNT=3",
    "SUMMARY:Algebra ( - ALG101-E01) - Dr. Smith - Tanóra", "LOCATION:B.101", "END:VEVENT", "END:VCALENDAR",
).joinToString("\r\n")

/** What the proxy, the replace route, the stored Neptun rows and profile updates saw. */
private class NeptunBackend {
    @Volatile var feedFetches = 0
    @Volatile var replaces = 0
    val profilePatches = mutableListOf<JsonObject>()
    val deletedRows = mutableListOf<String>()
    val neptunRows = listOf("row-a", "row-b")
}

private class PolicyHarness(private val profile: Map<String, String?>) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kv = KeyValueStore(FakeDataStore())
    val backend = NeptunBackend()
    var nowMs = Now
    var replacedCount = 0

    private fun body(req: HttpRequestData): JsonObject =
        VectraJson.parseToJsonElement((req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    private val http = HttpClient(
        MockEngine { req ->
            val path = req.url.encodedPath
            when {
                req.url.host == "script.google.com" -> { backend.feedFetches++; json(Feed) }
                path == "/api/vectra/neptun/replace" -> { backend.replaces++; json("{}") }
                path == "/api/vectra/crypto" -> {
                    val b = body(req)
                    val values = b.getValue("values").jsonArray.map { (it as JsonPrimitive).content }
                    val encrypt = (b.getValue("op") as JsonPrimitive).content == "encrypt"
                    val out = if (encrypt) values.map { "vc3:$it" } else values.map { it.removePrefix("vc3:") }
                    json(JsonObject(mapOf("values" to JsonArray(out.map(::JsonPrimitive)))).toString())
                }
                path.startsWith("/api/collections/users/") && req.method == HttpMethod.Patch -> {
                    val b = body(req)
                    backend.profilePatches += b
                    json(JsonObject(b + mapOf("id" to JsonPrimitive(Owner))).toString())
                }
                path.startsWith("/api/collections/neptun_schedule_events/") && req.method == HttpMethod.Delete -> {
                    backend.deletedRows += path.substringAfterLast('/')
                    json("")
                }
                path.startsWith("/api/collections/neptun_schedule_events/") ->
                    json(JsonObject(mapOf("items" to JsonArray(backend.neptunRows.map { JsonObject(mapOf("id" to JsonPrimitive(it))) }))).toString())
                path.startsWith("/api/collections/schedule_events/") -> json(JsonObject(mapOf("items" to JsonArray(emptyList()))).toString())
                else -> error("unexpected $path")
            }
        },
    )
    private val store = AuthStore(kv, PlainSecretCipher)
    private val pb = PocketBaseClient(createHttpClient(http), "https://pb.test", store)
    private val connectivity = object : Connectivity { override val online = MutableStateFlow(true) }
    private val auth = AuthRepository(pb, store, kv, scope, lazyOf(emptyList()))
    private val session = ScheduleSession(auth, connectivity)
    private val crypto = CryptoClient(pb, scope)
    private val classes = ScheduleEventsRepository(pb, crypto, kv, session)
    val service = NeptunSyncService(pb, crypto, kv, auth, http, session, classes, { }, { replacedCount++ }, { nowMs })

    suspend fun signIn() {
        val record = JsonObject(
            mapOf("id" to JsonPrimitive(Owner)) + profile.mapValues { (_, v) -> v?.let(::JsonPrimitive) ?: JsonNull },
        )
        store.save(AuthState("tok", record))
        // The profile is derived from the session on another coroutine.
        until { auth.profile.value?.let { it.neptunIcsUrl == profile["neptun_ics_url"] && it.neptunLastSync == profile["neptun_last_sync"] } == true }
    }

    suspend fun storedStamps(): List<Long> =
        kv.get(NeptunSyncService.RATE_LIMIT_STORAGE_KEY)
            ?.let { VectraJson.decodeFromString(ListSerializer(Long.serializer()), it) }.orEmpty()

    suspend fun seedStamps(vararg stamps: Long) =
        kv.set(NeptunSyncService.RATE_LIMIT_STORAGE_KEY, VectraJson.encodeToString(ListSerializer(Long.serializer()), stamps.toList()))

    /** Gives a started auto-sync time to finish, so "did not sync" means something. */
    suspend fun settle() = delay(150)
}

private fun scenario(profile: Map<String, String?> = emptyMap(), test: suspend PolicyHarness.() -> Unit) = runTest {
    val h = PolicyHarness(profile)
    try {
        withContext(Dispatchers.Default) {
            withTimeout(20_000) {
                h.signIn()
                h.test()
            }
        }
    } finally {
        h.scope.cancel()
    }
}

private suspend fun until(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(2) }

private fun iso(ms: Long) = Instant.fromEpochMilliseconds(ms).toString()

class NeptunSyncPolicyTest {
    // ---- rate limit ------------------------------------------------------------------------

    @Test fun twoRecentSyncsStillLeaveQuota() = scenario {
        seedStamps(Now - 10 * 60_000, Now - 5 * 60_000)
        assertTrue(service.checkSyncRateLimit().allowed)
    }

    @Test fun thirdSyncInAnHourBlocksTheNextAndReportsTheMinutesToWait() = scenario {
        seedStamps(Now - 50 * 60_000, Now - 20 * 60_000, Now - 5 * 60_000)
        val limit = service.checkSyncRateLimit()
        assertFalse(limit.allowed)
        // The oldest sync leaves the window in 10 minutes.
        assertEquals(10, limit.remainingMinutes)
    }

    @Test fun syncsOlderThanAnHourNoLongerCount() = scenario {
        seedStamps(Now - HourMs - 1, Now - 2 * HourMs, Now - 90 * 60_000, Now - 1_000)
        assertTrue(service.checkSyncRateLimit().allowed)
    }

    @Test fun aSyncExactlyAnHourOldIsOutsideTheWindow() = scenario {
        seedStamps(Now - HourMs, Now - 30 * 60_000, Now - 10 * 60_000)
        assertTrue(service.checkSyncRateLimit().allowed)
    }

    @Test fun aManualSyncConsumesQuotaButAnAutoSyncDoesNot() = scenario {
        assertEquals("2098/99/2", service.runSync(FeedUrl, isAutoSync = true))
        assertEquals(emptyList(), storedStamps())
        assertEquals("2098/99/2", service.runSync(FeedUrl))
        assertEquals(listOf(Now), storedStamps())
    }

    @Test fun threeManualSyncsExhaustTheQuota() = scenario {
        repeat(3) { service.runSync(FeedUrl) }
        assertFalse(service.checkSyncRateLimit().allowed)
    }

    // ---- auto-sync gate ----------------------------------------------------------------------

    @Test fun neverSyncedAccountWithALinkSyncsOnLaunch() = scenario(mapOf("neptun_ics_url" to FeedUrl)) {
        service.maybeAutoSync()
        until { backend.replaces == 1 }
        assertEquals(1, backend.feedFetches)
        assertEquals(emptyList(), storedStamps())
    }

    @Test fun noLinkMeansNoAutoSync() = scenario {
        service.maybeAutoSync()
        settle()
        assertEquals(0, backend.feedFetches)
    }

    @Test fun aSyncUnderADayOldIsNotRepeated() = scenario(
        mapOf("neptun_ics_url" to FeedUrl, "neptun_last_sync" to iso(Now - 23 * HourMs)),
    ) {
        service.maybeAutoSync()
        settle()
        assertEquals(0, backend.feedFetches)
    }

    @Test fun aSyncOverADayOldIsRepeated() = scenario(
        mapOf("neptun_ics_url" to FeedUrl, "neptun_last_sync" to iso(Now - 25 * HourMs)),
    ) {
        service.maybeAutoSync()
        until { backend.replaces == 1 }
    }

    @Test fun aPocketBaseSpaceSeparatedTimestampIsUnderstood() = scenario(
        mapOf("neptun_ics_url" to FeedUrl, "neptun_last_sync" to iso(Now - HourMs).replace('T', ' ')),
    ) {
        service.maybeAutoSync()
        settle()
        assertEquals(0, backend.feedFetches)
    }

    @Test fun anUnreadableTimestampCountsAsNeverSynced() = scenario(
        mapOf("neptun_ics_url" to FeedUrl, "neptun_last_sync" to "garbage"),
    ) {
        service.maybeAutoSync()
        until { backend.replaces == 1 }
    }

    @Test fun autoSyncStartsOnlyOncePerSignIn() = scenario(mapOf("neptun_ics_url" to FeedUrl)) {
        service.maybeAutoSync()
        service.maybeAutoSync()
        until { backend.replaces >= 1 }
        service.maybeAutoSync()
        settle()
        assertEquals(1, backend.feedFetches)
    }

    @Test fun anotherSignInMayAutoSyncAgain() = scenario(mapOf("neptun_ics_url" to FeedUrl)) {
        service.maybeAutoSync()
        until { backend.replaces == 1 }
        // Let the first sync finish stamping the profile, or its reply would overwrite the next sign-in.
        until { service.neptunSyncStatus.value == NeptunSyncStatus.Success }
        settle()
        // A day later the next sign-in (the profile as the server hands it back) syncs again.
        nowMs = Now + 25 * HourMs
        signIn()
        service.onUserChanged()
        service.maybeAutoSync()
        until { backend.replaces == 2 }
    }

    // ---- saving and clearing the link --------------------------------------------------------

    @Test fun clearingTheLinkRemovesTheSyncedRowsAndTheProfileFields() = scenario(mapOf("neptun_ics_url" to FeedUrl)) {
        service.saveNeptunUrl("   ")
        assertEquals(listOf("row-a", "row-b"), backend.deletedRows.sorted())
        val patch = backend.profilePatches.single()
        assertEquals(JsonNull, patch["neptun_ics_url"])
        assertEquals(JsonNull, patch["neptun_last_sync"])
        assertEquals(1, replacedCount)
    }

    @Test fun savingALinkStoresItEncrypted() = scenario {
        service.saveNeptunUrl("  $FeedUrl ")
        assertEquals("vc3:$FeedUrl", (backend.profilePatches.single()["neptun_ics_url"] as JsonPrimitive).content)
        assertEquals(emptyList(), backend.deletedRows)
    }

    @Test fun claimingTheFirstSyncKeepsTheProfileUpdateFromStartingOne() = scenario(mapOf("neptun_ics_url" to FeedUrl)) {
        service.saveNeptunUrl(FeedUrl, claimFirstSync = true)
        service.maybeAutoSync()
        settle()
        assertEquals(0, backend.feedFetches)
    }
}
