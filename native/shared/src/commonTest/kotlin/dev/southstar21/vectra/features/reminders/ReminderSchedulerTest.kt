package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.SafeList
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
import dev.southstar21.vectra.core.platform.Notifier
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.core.platform.ScheduledNotification
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.features.coursetracker.CourseTrackerRepository
import dev.southstar21.vectra.features.coursetracker.FakeCloud
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import dev.southstar21.vectra.features.coursetracker.PassThroughCipher
import dev.southstar21.vectra.features.schedule.NeptunSyncListener
import dev.southstar21.vectra.features.schedule.ScheduleRepository
import dev.southstar21.vectra.features.transit.data.CatalogDiskCache
import dev.southstar21.vectra.features.transit.data.FutarApi
import dev.southstar21.vectra.features.transit.data.RealFutarSource
import dev.southstar21.vectra.features.transit.data.TransitRepository
import dev.southstar21.vectra.features.update.UpdateGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
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
import kotlinx.io.files.Path
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val UserId = "user-1"

/** Records what the scheduler hands to the system. */
private class RecordingNotifier : Notifier {
    val plans = SafeList<List<ScheduledNotification>>()
    val replans = SafeList<Long?>()
    override fun permissionGranted() = true
    override fun show(title: String, body: String, url: String?, tag: String?) = true
    override fun replaceScheduled(notifications: List<ScheduledNotification>) {
        plans += notifications
    }
    override fun scheduleReplan(atMs: Long?) {
        replans += atMs
    }
    val last get() = plans.last()
}

/** One manual class on every weekday of a range that spans today, so some session always lies ahead. */
private fun classRows(): List<JsonObject> = (0..6).map { day ->
    JsonObject(
        mapOf(
            "id" to JsonPrimitive("class-$day"),
            "user" to JsonPrimitive(UserId),
            "course_name" to JsonPrimitive("vc3:Algebra"),
            "course_code" to JsonPrimitive("A1"),
            "instructor" to JsonPrimitive("vc3:"),
            "location" to JsonPrimitive("vc3:B-204"),
            "day_of_week" to JsonPrimitive(day),
            "start_time" to JsonPrimitive("23:00"),
            "end_time" to JsonPrimitive("23:30"),
            "semester" to JsonPrimitive("2000/01/1"),
            "semester_start" to JsonPrimitive("2000-01-01"),
            "semester_end" to JsonPrimitive("2099-12-31"),
            "event_type" to JsonPrimitive("class"),
            "attendance_type" to JsonPrimitive("lecture"),
        ),
    )
}

private class Harness {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val kv = KeyValueStore(FakeDataStore())
    val notifier = RecordingNotifier()
    val classes = classRows()
    private val http = HttpClient(
        MockEngine { req -> handle(this, req) },
    )
    val store = AuthStore(kv, PlainSecretCipher)
    private val pb = PocketBaseClient(createHttpClient(http), "https://pb.test", store)
    private val connectivity = object : Connectivity { override val online = MutableStateFlow(true) }
    val auth = AuthRepository(pb, store, kv, scope, lazyOf(emptyList()))
    private val crypto = CryptoClient(pb, scope)
    val schedule = ScheduleRepository(pb, crypto, kv, connectivity, auth, http, scope, NeptunSyncListener { })
    private val courseTracker = CourseTrackerRepository(FakeCloud(), PassThroughCipher, kv, scope, auth.currentUserId, isOnline = { true })
    private val transit = TransitRepository(
        pb, crypto, kv, auth, connectivity, RealFutarSource(FutarApi(pb), scope), http, "https://web.test/", CatalogDiskCache(Path("vectra-reminder-test-unused")), scope,
    )
    val updateGate = UpdateGate(kv, scope, "1.0.0")
    val scheduler = ReminderScheduler(kv, schedule, courseTracker, transit, notifier, auth, scope, updateGate, crypto)

    private fun body(req: HttpRequestData): JsonObject =
        VectraJson.parseToJsonElement((req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    private fun handle(scope: MockRequestHandleScope, req: HttpRequestData): HttpResponseData = with(scope) {
        val path = req.url.encodedPath
        when {
            path == "/api/vectra/crypto" -> {
                val b = body(req)
                val values = b.getValue("values").jsonArray.map { it.jsonPrimitive.content }
                val out = if (b.getValue("op").jsonPrimitive.content == "encrypt") values.map { "vc3:$it" } else values.map { it.removePrefix("vc3:") }
                json(JsonObject(mapOf("values" to JsonArray(out.map(::JsonPrimitive)))).toString())
            }
            // A profile update answers with the record, which becomes the session's: it must keep its id.
            path.startsWith("/api/collections/users/") -> json(JsonObject(body(req) + ("id" to JsonPrimitive(UserId))).toString())
            path.startsWith("/api/collections/schedule_events/") -> json(JsonObject(mapOf("items" to JsonArray(classes))).toString())
            path.startsWith("/api/collections/") && req.method.value == "GET" -> json("""{"items":[]}""")
            else -> json("{}")
        }
    }

    suspend fun signIn() {
        store.save(AuthState("tok", JsonObject(mapOf("id" to JsonPrimitive(UserId)))))
        schedule.onForeground()
        until { schedule.localReady.value && schedule.events.value.isNotEmpty() }
    }

    suspend fun turnOn(prefs: (ReminderPrefs) -> ReminderPrefs = { it.copy(beforeClass = true) }) {
        scheduler.update(prefs)
        until { scheduler.prefs.value.anyOn }
    }
}

private suspend fun until(condition: suspend () -> Boolean) = withTimeout(30_000) { while (!condition()) delay(2) }

class ReminderSchedulerTest {
    private fun scenario(test: suspend Harness.() -> Unit) = runTest {
        val h = Harness()
        try {
            withContext(Dispatchers.Default) { withTimeout(55_000) { h.test() } }
        } finally {
            h.scope.cancel()
        }
    }

    @Test fun aSignedInAccountWithClassesGetsAWeekOfReminders() = scenario {
        signIn()
        turnOn()
        scheduler.reschedule()
        val plan = notifier.last
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { it.id.startsWith("rem:before:") }, plan.map { it.id }.toString())
        assertTrue(plan.all { it.atMs > nowMs() })
        assertTrue(plan.size <= ReminderScheduler.MaxScheduled)
        assertTrue("B-204" in plan.first().body, plan.first().body)
    }

    @Test fun theSystemIsAskedToReplanDaily() = scenario {
        signIn()
        turnOn()
        val before = nowMs()
        scheduler.reschedule()
        val at = assertNotNull(notifier.replans.last())
        assertTrue(at in (before + ReminderScheduler.ReplanIntervalMs)..(nowMs() + ReminderScheduler.ReplanIntervalMs))
    }

    @Test fun signedOutCancelsEveryReminder() = scenario {
        turnOn()
        scheduler.reschedule()
        assertEquals(emptyList(), notifier.last)
        assertNull(notifier.replans.last())
    }

    @Test fun withEverythingSwitchedOffNothingIsScheduled() = scenario {
        signIn()
        scheduler.reschedule()
        assertEquals(emptyList(), notifier.last)
        assertNull(notifier.replans.last())
    }

    @Test fun anUnchangedPlanIsNotHandedToTheSystemAgain() = scenario {
        signIn()
        turnOn()
        scheduler.reschedule()
        val handedOver = notifier.plans.size
        scheduler.reschedule()
        assertEquals(handedOver, notifier.plans.size)
    }

    @Test fun anAppUpdateLockClearsTheAlarmsAndKeepsThemClear() = scenario {
        signIn()
        turnOn()
        scheduler.reschedule()
        assertTrue(notifier.last.isNotEmpty())
        updateGate.report("9.0.0")
        scheduler.reschedule()
        assertEquals(emptyList(), notifier.last)
        assertNull(notifier.replans.last())
        assertFalse(scheduler.hasSession())
    }

    @Test fun signingOutClearsTheAlarmsAndTheSettings() = scenario {
        signIn()
        turnOn()
        scheduler.reschedule()
        scheduler.onSessionCleared()
        assertEquals(emptyList(), notifier.last)
        assertFalse(scheduler.prefs.value.anyOn)
    }

    @Test fun aSessionExistsOnlyWhileSignedIn() = scenario {
        assertFalse(scheduler.hasSession())
        signIn()
        assertTrue(scheduler.hasSession())
    }

    @Test fun theTestReminderRidesAlongWithThePlan() = scenario {
        signIn()
        turnOn()
        scheduler.reschedule()
        val planned = notifier.last.size
        scheduler.sendTest()
        val sent = notifier.last
        assertEquals(planned + 1, sent.size)
        val test = sent.single { it.id == "rem:test" }
        assertTrue(test.atMs - nowMs() in 1..6_000)
        assertTrue(test.title.startsWith("Algebra"), test.title)
    }
}
