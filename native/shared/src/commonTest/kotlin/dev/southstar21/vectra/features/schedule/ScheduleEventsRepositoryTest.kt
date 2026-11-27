package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.data.OfflineCache
import dev.southstar21.vectra.core.data.UserKeys
import dev.southstar21.vectra.core.network.AuthState
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.network.json
import dev.southstar21.vectra.core.network.string
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val Owner = "user-1"

private fun classRow(id: String, name: String) = JsonObject(
    mapOf(
        "id" to JsonPrimitive(id),
        "user" to JsonPrimitive(Owner),
        "course_name" to JsonPrimitive("vc3:$name"),
        "course_code" to JsonPrimitive("A1"),
        "instructor" to JsonPrimitive("vc3:Dr. Smith"),
        "location" to JsonPrimitive("vc3:B.101"),
        "day_of_week" to JsonPrimitive(1),
        "start_time" to JsonPrimitive("10:00"),
        "end_time" to JsonPrimitive("11:00"),
        "semester" to JsonPrimitive("2098/99/2"),
        "event_type" to JsonPrimitive("class"),
    ),
)

private class EventsBackend {
    val rows = mutableListOf(classRow("c1", "Algebra"))
    @Volatile var listRequests = 0
    @Volatile var down = false
    /** Runs after the class list was read, before it is answered. */
    var afterRead: (suspend () -> Unit)? = null
    val creates = mutableListOf<JsonObject>()
    val patches = mutableListOf<JsonObject>()
}

private class EventsHarness(online: Boolean = true) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val kv = KeyValueStore(FakeDataStore())
    val backend = EventsBackend()
    val connectivity = object : Connectivity { override val online = MutableStateFlow(online) }

    private fun body(req: HttpRequestData): JsonObject =
        VectraJson.parseToJsonElement((req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    private val http = HttpClient(
        MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path == "/api/vectra/crypto" -> {
                    val b = body(req)
                    val values = b.getValue("values").jsonArray.map { it.jsonPrimitive.content }
                    val out = if (b.getValue("op").jsonPrimitive.content == "encrypt") values.map { "vc3:$it" } else values.map { it.removePrefix("vc3:") }
                    json(JsonObject(mapOf("values" to JsonArray(out.map(::JsonPrimitive)))).toString())
                }
                path.startsWith("/api/collections/schedule_events/") && req.method == HttpMethod.Get -> {
                    backend.listRequests++
                    if (backend.down) {
                        json("{}", HttpStatusCode.InternalServerError)
                    } else {
                        val snapshot = JsonObject(mapOf("items" to JsonArray(backend.rows.toList()))).toString()
                        backend.afterRead?.invoke()
                        json(snapshot)
                    }
                }
                path.startsWith("/api/collections/schedule_events/") && req.method == HttpMethod.Post -> {
                    val b = body(req)
                    backend.creates += b
                    val row = JsonObject(b + ("id" to JsonPrimitive("c-new")))
                    backend.rows += row
                    json(row.toString())
                }
                path.startsWith("/api/collections/schedule_events/") && req.method == HttpMethod.Patch -> {
                    val b = body(req)
                    backend.patches += b
                    val id = path.substringAfterLast('/')
                    val row = JsonObject(b + ("id" to JsonPrimitive(id)))
                    backend.rows.replaceAll { if (it.string("id") == id) row else it }
                    json(row.toString())
                }
                path.startsWith("/api/collections/schedule_events/") && req.method == HttpMethod.Delete -> {
                    backend.rows.removeAll { it.string("id") == path.substringAfterLast('/') }
                    json("")
                }
                path.startsWith("/api/collections/neptun_schedule_events/") -> json("""{"items":[]}""")
                else -> error("unexpected $path")
            }
        },
    )
    val store = AuthStore(kv, PlainSecretCipher)
    private val pb = PocketBaseClient(createHttpClient(http), "https://pb.test", store)
    val auth = AuthRepository(pb, store, kv, scope, lazyOf(emptyList()))
    private val session = ScheduleSession(auth, connectivity)
    val repo = ScheduleEventsRepository(pb, CryptoClient(pb, scope), kv, session)
    val cache = OfflineCache(kv, UserKeys.SCHEDULE_EVENTS, ListSerializer(ScheduleEvent.serializer()))

    suspend fun signIn() {
        store.save(AuthState("tok", JsonObject(mapOf("id" to JsonPrimitive(Owner)))))
        repo.onUserChanged(Owner)
    }

    suspend fun signOut() {
        store.clear()
        repo.onUserChanged(null)
    }

    private fun clear(name: String) = ScheduleEvent(
        id = "c1", userId = Owner, courseName = name, courseCode = "A1", instructor = "", location = "", dayOfWeek = 1,
        startTime = "10:00", endTime = "11:00", semester = "2098/99/2", eventType = "class", source = "manual",
    )

    suspend fun seedCache(name: String) = cache.write(Owner, listOf(clear(name)))
}

private suspend fun until(condition: suspend () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(2) }

/** The loading and error flags a screen renders from, through each way a class load can go. */
class ScheduleEventsRepositoryTest {
    private fun scenario(online: Boolean = true, test: suspend EventsHarness.() -> Unit) = runTest {
        val h = EventsHarness(online)
        try {
            withContext(Dispatchers.Default) { withTimeout(20_000) { h.test() } }
        } finally {
            h.scope.cancel()
        }
    }

    @Test fun startsLoadingWithNothingToShow() = scenario {
        assertTrue(repo.eventsLoading.value)
        assertEquals(emptyList(), repo.events.value)
        assertNull(repo.eventsError.value)
    }

    @Test fun aFirstOnlineLoadWithNoCacheStaysLoadingUntilTheFetchLands() = scenario {
        val inRead = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        backend.afterRead = { inRead.complete(Unit); release.await() }
        signIn()
        assertTrue(repo.eventsLoading.value)
        val refresh = scope.async { repo.refreshEvents() }
        inRead.await()
        assertTrue(repo.eventsLoading.value)
        assertEquals(emptyList(), repo.events.value)
        release.complete(Unit)
        refresh.await()
        assertFalse(repo.eventsLoading.value)
        assertEquals(listOf("Algebra"), repo.events.value.map { it.courseName })
        assertNull(repo.eventsError.value)
    }

    @Test fun aCachedSnapshotPaintsAtOnceAndNeverShowsLoading() = scenario {
        seedCache("Cached Algebra")
        signIn()
        assertFalse(repo.eventsLoading.value)
        assertEquals(listOf("Cached Algebra"), repo.events.value.map { it.courseName })
        repo.refreshEvents()
        assertEquals(listOf("Algebra"), repo.events.value.map { it.courseName })
    }

    @Test fun aSuccessfulFetchRefreshesTheCache() = scenario {
        signIn()
        repo.refreshEvents()
        assertEquals(listOf("Algebra"), cache.read(Owner)?.map { it.courseName })
    }

    @Test fun offlineReadsTheCacheAndStopsLoadingWithoutARequest() = scenario(online = false) {
        seedCache("Cached Algebra")
        signIn()
        repo.refreshEvents()
        assertFalse(repo.eventsLoading.value)
        assertEquals(listOf("Cached Algebra"), repo.events.value.map { it.courseName })
        assertEquals(0, backend.listRequests)
    }

    @Test fun offlineWithNothingCachedIsNotLoadingAndNotAnError() = scenario(online = false) {
        signIn()
        assertFalse(repo.eventsLoading.value)
        repo.refreshEvents()
        assertFalse(repo.eventsLoading.value)
        assertNull(repo.eventsError.value)
        assertEquals(emptyList(), repo.events.value)
    }

    @Test fun aFailedFirstLoadReportsAnErrorAndStopsLoading() = scenario {
        backend.down = true
        signIn()
        repo.refreshEvents()
        assertFalse(repo.eventsLoading.value)
        assertNotNull(repo.eventsError.value)
        assertEquals(emptyList(), repo.events.value)
    }

    @Test fun theErrorClearsWhenTheNextLoadSucceeds() = scenario {
        backend.down = true
        signIn()
        repo.refreshEvents()
        assertNotNull(repo.eventsError.value)
        backend.down = false
        repo.refreshEvents(force = true)
        assertNull(repo.eventsError.value)
        assertEquals(listOf("Algebra"), repo.events.value.map { it.courseName })
    }

    @Test fun aFailedRefreshWithACacheKeepsTheCachedClassesQuietly() = scenario {
        seedCache("Cached Algebra")
        backend.down = true
        signIn()
        repo.refreshEvents()
        assertNull(repo.eventsError.value)
        assertEquals(listOf("Cached Algebra"), repo.events.value.map { it.courseName })
        assertFalse(repo.eventsLoading.value)
    }

    @Test fun signingOutEmptiesTheClassesAndLoadsAgainForTheNextAccount() = scenario {
        signIn()
        repo.refreshEvents()
        signOut()
        assertEquals(emptyList(), repo.events.value)
        assertTrue(repo.eventsLoading.value)
        assertNull(repo.eventsError.value)
    }

    @Test fun aFreshLoadIsNotRepeatedUntilForcedOrStale() = scenario {
        signIn()
        repo.refreshEvents()
        repo.refreshEvents()
        assertEquals(1, backend.listRequests)
        repo.refreshEvents(force = true)
        assertEquals(2, backend.listRequests)
    }

    @Test fun simultaneousRefreshesShareOneRequest() = scenario {
        signIn()
        val release = CompletableDeferred<Unit>()
        backend.afterRead = { release.await() }
        val all = List(8) { scope.async { repo.refreshEvents(force = true) } }
        until { backend.listRequests >= 1 }
        delay(50)
        release.complete(Unit)
        all.awaitAll()
        assertEquals(1, backend.listRequests)
        assertEquals(listOf("Algebra"), repo.events.value.map { it.courseName })
    }

    @Test fun aWriteDuringALoadDiscardsItsStaleAnswerAndReloads() = scenario {
        signIn()
        val inRead = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        backend.afterRead = { if (inRead.complete(Unit)) release.await() }
        val load = scope.async { repo.refreshEvents(force = true) }
        inRead.await()
        // The first answer was read before this delete and still lists the class.
        val delete = scope.async { repo.deleteEvent("c1") }
        delay(50)
        release.complete(Unit)
        delete.await()
        load.await()
        assertEquals(emptyList(), repo.events.value)
    }

    @Test fun creatingAClassFlagsItWhileItRunsAndReloadsAfter() = scenario {
        signIn()
        repo.refreshEvents()
        assertFalse(repo.isCreatingEvent.value)
        val created = repo.createEvent(formData("Geometry"))
        assertEquals("Geometry", created.courseName)
        assertFalse(repo.isCreatingEvent.value)
        assertEquals(setOf("Algebra", "Geometry"), repo.events.value.map { it.courseName }.toSet())
    }

    private fun formData(name: String) = ScheduleFormData(
        courseName = name, attendanceType = "lecture", courseCode = "G1", instructor = "Dr. Who", location = "C.1", dayOfWeek = 2,
        startTime = "12:00", endTime = "13:00", color = "#3B82F6", semester = "2098/99/2",
    )
}
