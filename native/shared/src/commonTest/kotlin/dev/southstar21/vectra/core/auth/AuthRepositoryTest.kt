package dev.southstar21.vectra.core.auth

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.AuthState
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.PocketBaseException
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.network.json
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SessionOk = """{"token":"tok2","record":{"id":"u1","email":"a@b.c"}}"""

class AuthRepositoryTest {
    private val kv = KeyValueStore(FakeDataStore())
    private val store = AuthStore(kv, PlainSecretCipher)
    private val requests = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun repo(
        vararg listeners: SessionListener,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json("{}") },
    ): AuthRepository = repo(lazyOf(listeners.toList()), handler)

    private fun repo(
        listeners: Lazy<List<SessionListener>>,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json("{}") },
    ): AuthRepository {
        val http = HttpClient(MockEngine { req -> requests += req.url.encodedPath; handler(req) })
        return AuthRepository(PocketBaseClient(createHttpClient(http), "https://pb.test", store), store, kv, scope, listeners)
    }

    private suspend fun signIn() = store.save(AuthState("tok", JsonObject(mapOf("id" to JsonPrimitive("u1"), "email" to JsonPrimitive("a@b.c")))))

    private suspend fun seedUserData() {
        kv.set("vectra_schedule_items_u1", "items")
        kv.set("vectra_course_tracker_cache_u1", "trackers")
        kv.set("vectra_schedule_cache_events_u1", "events")
        kv.set("vectra_trip_plans_cache_u1", "trips")
        kv.set("unrelated_setting", "keep")
    }

    private suspend fun assertWiped() {
        assertNull(store.current)
        assertNull(kv.get("vectra_schedule_items_u1"))
        assertNull(kv.get("vectra_course_tracker_cache_u1"))
        assertNull(kv.get("vectra_schedule_cache_events_u1"))
        assertNull(kv.get("vectra_trip_plans_cache_u1"))
        assertEquals("keep", kv.get("unrelated_setting"))
    }

    private class Recording(val name: String, val log: MutableList<String>, val onClear: suspend () -> Unit = {}) : SessionListener {
        override suspend fun beforeSignOut() { log += "before:$name" }
        override suspend fun onSessionCleared() { log += "cleared:$name"; onClear() }
    }

    @Test fun signOutWipesUserDataAndRunsTheHooksInOrder() = runTest {
        val log = mutableListOf<String>()
        val r = repo(Recording("a", log), Recording("b", log))
        signIn()
        seedUserData()
        r.signOut()
        assertWiped()
        assertEquals(listOf("before:a", "before:b", "cleared:a", "cleared:b"), log)
    }

    @Test fun aListenerNothingHasUsedYetStillRunsWhenTheSessionEnds() = runTest {
        val log = mutableListOf<String>()
        var built = false
        val r = repo(lazy { built = true; listOf(Recording("a", log)) })
        signIn()
        assertFalse(built)
        r.signOut()
        assertEquals(listOf("before:a", "cleared:a"), log)
    }

    @Test fun theSessionIsGoneBeforeListenersRun() = runTest {
        var sawSession = true
        val r = repo(Recording("a", mutableListOf()) { sawSession = store.current != null })
        signIn()
        r.signOut()
        assertFalse(sawSession)
    }

    @Test fun aFailingListenerDoesNotStopTheOthers() = runTest {
        val log = mutableListOf<String>()
        val r = repo(
            Recording("bad", log) { error("boom") },
            Recording("good", log),
        )
        signIn()
        r.signOut()
        assertTrue("cleared:good" in log)
    }

    @Test fun aListenerThatThrowsCancellationDoesNotStopTheOthers() = runTest {
        val log = mutableListOf<String>()
        val r = repo(
            Recording("bad", log) { throw CancellationException("inner") },
            Recording("good", log),
        )
        signIn()
        r.signOut()
        assertTrue("cleared:good" in log)
    }

    @Test fun cancellingTheCallerCannotLeaveAHalfDoneWipe() = runTest {
        val gate = CompletableDeferred<Unit>()
        val log = mutableListOf<String>()
        val r = repo(
            Recording("slow", log) { gate.await() },
            Recording("next", log),
        )
        signIn()
        withContext(Dispatchers.Default) {
            val call = launch { r.expire() }
            while ("cleared:slow" !in log) delay(1)
            call.cancel()
            gate.complete(Unit)
            call.join()
        }
        assertTrue("cleared:next" in log)
    }

    @Test fun aStuckCleanupCannotHoldSignOutForever() = runTest {
        val r = repo(object : SessionListener { override suspend fun beforeSignOut() = awaitCancellation() })
        signIn()
        r.signOut()
        assertNull(store.current)
    }

    @Test fun aFailedCleanupStillSignsOut() = runTest {
        val r = repo(object : SessionListener { override suspend fun beforeSignOut() = error("push service down") })
        signIn()
        r.signOut()
        assertNull(store.current)
    }

    @Test fun expiryLeavesAOneShotHint() = runTest {
        val r = repo()
        signIn()
        r.expire()
        assertNull(store.current)
        assertTrue(r.sessionExpired.value)
        r.consumeSessionExpired()
        assertFalse(r.sessionExpired.value)
    }

    @Test fun aPasswordResetAsksForTheEmailWithoutSigningIn() = runTest {
        var body = ""
        val r = repo { req -> body = (req.body as TextContent).text; respond("", HttpStatusCode.NoContent) }
        r.requestPasswordReset("a@b.c")
        assertEquals(listOf("/api/collections/users/request-password-reset"), requests)
        assertEquals("""{"email":"a@b.c"}""", body)
        assertNull(store.current)
    }

    /** Everything but the offline queue is gone, and the queue is kept for this account. */
    private suspend fun assertWipedKeepingQueue() {
        assertNull(store.current)
        assertEquals("items", kv.get("vectra_schedule_items_u1"))
        assertEquals("trackers", kv.get("vectra_course_tracker_cache_u1"))
        assertNull(kv.get("vectra_schedule_cache_events_u1"))
        assertNull(kv.get("vectra_trip_plans_cache_u1"))
        assertEquals("u1", kv.get(AuthRepository.KEPT_QUEUE_OWNER_KEY))
    }

    @Test fun deletingTheAccountSendsThePasswordAndWipes() = runTest {
        var body = ""
        val r = repo { req -> body = (req.body as TextContent).text; json("{}") }
        signIn()
        seedUserData()
        r.deleteAccount("pw")
        assertEquals("""{"currentPassword":"pw"}""", body)
        assertWiped()
        assertNull(kv.get(AuthRepository.KEPT_QUEUE_OWNER_KEY))
    }

    @Test fun expiryKeepsTheOfflineQueue() = runTest {
        val r = repo()
        signIn()
        seedUserData()
        r.expire()
        assertWipedKeepingQueue()
    }

    @Test fun anExplicitSignOutAfterAnExpiryWipesTheQueueToo() = runTest {
        val r = repo { json(SessionOk) }
        signIn()
        seedUserData()
        r.expire()
        r.login("a@b.c", "pw")
        r.signOut()
        assertWiped()
        assertNull(kv.get(AuthRepository.KEPT_QUEUE_OWNER_KEY))
    }

    @Test fun theSameAccountSigningBackInGetsItsKeptQueue() = runTest {
        val r = repo { json(SessionOk) }
        signIn()
        seedUserData()
        r.expire()
        r.login("a@b.c", "pw")
        assertNull(kv.get(AuthRepository.KEPT_QUEUE_OWNER_KEY))
        assertEquals("items", kv.get("vectra_schedule_items_u1"))
        assertEquals("trackers", kv.get("vectra_course_tracker_cache_u1"))
    }

    @Test fun anotherAccountSigningInDropsTheKeptQueue() = runTest {
        val r = repo()
        signIn()
        seedUserData()
        r.expire()
        r.adoptSession("tok", JsonObject(mapOf("id" to JsonPrimitive("u2"), "email" to JsonPrimitive("x@b.c"))))
        assertNull(kv.get(AuthRepository.KEPT_QUEUE_OWNER_KEY))
        assertNull(kv.get("vectra_schedule_items_u1"))
        assertNull(kv.get("vectra_course_tracker_cache_u1"))
    }

    @Test fun flushingReportsWritesLeftBehind() = runTest {
        val flushed = object : SessionListener { override suspend fun flushPendingWrites() = true }
        val stuck = object : SessionListener { override suspend fun flushPendingWrites() = false }
        val failing = object : SessionListener { override suspend fun flushPendingWrites(): Boolean = error("offline") }
        signIn()
        assertTrue(repo(flushed, Recording("plain", mutableListOf())).flushPendingWrites())
        assertFalse(repo(flushed, stuck).flushPendingWrites())
        assertFalse(repo(failing, flushed).flushPendingWrites())
        assertNotNull(store.current, "flushing never signs out")
    }

    @Test fun aHangingFlushIsBounded() = runTest {
        val r = repo(object : SessionListener { override suspend fun flushPendingWrites(): Boolean = awaitCancellation() })
        signIn()
        assertFalse(r.flushPendingWrites())
    }

    @Test fun aFailedAccountDeleteKeepsEverything() = runTest {
        val r = repo { json("{}", HttpStatusCode.InternalServerError) }
        signIn()
        seedUserData()
        assertFailsWith<PocketBaseException> { r.deleteAccount("pw") }
        assertNotNull(store.current)
        assertEquals("items", kv.get("vectra_schedule_items_u1"))
    }

    @Test fun aRejectedUpdateDoesNotSignOutHere() = runTest {
        val r = repo { json("""{"message":"expired"}""", HttpStatusCode.Unauthorized) }
        signIn()
        assertFailsWith<PocketBaseException> { r.updateProfile(mapOf("full_name" to JsonPrimitive("X"))) }
        assertNotNull(store.current)
    }

    @Test fun aWrongCurrentPasswordChangesNothing() = runTest {
        val r = repo { json("{}", HttpStatusCode.BadRequest) }
        signIn()
        assertEquals("Current password is incorrect", assertFailsWith<IllegalStateException> { r.changePassword("wrong", "new") }.message)
        assertNotNull(store.current)
        assertEquals(1, requests.size)
    }

    @Test fun changingThePasswordReauthenticates() = runTest {
        var update: String? = null
        val r = repo { req ->
            if (req.url.encodedPath.startsWith("/api/collections/users/records/")) update = (req.body as TextContent).text
            json(SessionOk)
        }
        signIn()
        r.changePassword("old", "new")
        assertEquals("tok2", store.token)
        assertEquals(3, requests.size)
        // PocketBase rejects a self-service password change without the current password.
        assertTrue(update!!.contains(""""oldPassword":"old""""), update)
        assertTrue(update.contains(""""password":"new""""), update)
    }

    @Test fun aRateLimitedPasswordCheckIsNotCalledWrong() = runTest {
        val r = repo { json("{}", HttpStatusCode.TooManyRequests) }
        signIn()
        val e = assertFailsWith<IllegalStateException> { r.changePassword("old", "new") }
        assertEquals("Too many attempts — please wait a moment and try again", e.message)
        assertEquals(1, requests.size)
    }

    @Test fun anOfflinePasswordCheckIsNotCalledWrong() = runTest {
        val r = repo { throw IllegalStateException("no route to host") }
        signIn()
        val e = assertFailsWith<PocketBaseException> { r.changePassword("old", "new") }
        assertTrue(e.isNetwork)
        assertNotNull(store.current)
    }

    @Test fun aFailedPasswordReauthSignsOut() = runTest {
        var auths = 0
        val r = repo { req ->
            if (req.url.encodedPath.endsWith("auth-with-password") && ++auths == 2) json("{}", HttpStatusCode.BadRequest) else json(SessionOk)
        }
        signIn()
        seedUserData()
        val e = assertFailsWith<IllegalStateException> { r.changePassword("old", "new") }
        assertEquals("Password updated, but your session ended — please sign in again", e.message)
        assertWipedKeepingQueue()
    }

    @Test fun cancellingAPasswordReauthDoesNotSignOut() = runTest {
        var auths = 0
        val reached = CompletableDeferred<Unit>()
        val r = repo { req ->
            if (req.url.encodedPath.endsWith("auth-with-password") && ++auths == 2) {
                reached.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            json(SessionOk)
        }
        signIn()
        seedUserData()
        withContext(Dispatchers.Default) {
            val call = launch { r.changePassword("old", "new") }
            reached.await()
            call.cancelAndJoin()
        }
        assertNotNull(store.current)
        assertEquals("items", kv.get("vectra_schedule_items_u1"))
    }

    @Test fun cancellingAnEmailReauthDoesNotSignOut() = runTest {
        val reached = CompletableDeferred<Unit>()
        val r = repo { req ->
            if (req.url.encodedPath.endsWith("auth-with-password")) {
                reached.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            json("{}")
        }
        signIn()
        withContext(Dispatchers.Default) {
            val call = launch { r.changeEmail("pw", "new@b.c") }
            reached.await()
            call.cancelAndJoin()
        }
        assertNotNull(store.current)
    }

    @Test fun aFailedEmailReauthSignsOutKeepingTheQueue() = runTest {
        val r = repo { req -> if (req.url.encodedPath.endsWith("auth-with-password")) json("{}", HttpStatusCode.BadRequest) else json("{}") }
        signIn()
        seedUserData()
        assertFailsWith<IllegalStateException> { r.changeEmail("pw", "new@b.c") }
        assertWipedKeepingQueue()
    }

    @Test fun profileFollowsTheSession() = runTest {
        val r = repo { json("""{"id":"u1","full_name":"Ann"}""") }
        signIn()
        r.updateProfile(mapOf("full_name" to JsonPrimitive("Ann")))
        assertEquals("Ann", store.current?.record?.let(Profile::from)?.fullName)
    }
}
