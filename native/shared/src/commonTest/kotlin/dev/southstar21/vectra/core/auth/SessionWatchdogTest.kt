package dev.southstar21.vectra.core.auth

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.AuthState
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.network.json
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

private val nowSeconds get() = Clock.System.now().epochSeconds

private fun jwt(exp: Long): String {
    val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("""{"exp":$exp}""".encodeToByteArray())
    return "h.$payload.s"
}

class SessionWatchdogTest {
    private val store = AuthStore(KeyValueStore(FakeDataStore()), PlainSecretCipher)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshes = 0
    private val fresh get() = """{"token":"${jwt(nowSeconds + 30 * 86_400)}","record":{"id":"u1"}}"""

    private lateinit var repo: AuthRepository

    private fun watchdog(reply: suspend MockRequestHandleScope.() -> HttpResponseData): SessionWatchdog {
        val http = HttpClient(MockEngine { refreshes++; reply() })
        repo = AuthRepository(PocketBaseClient(createHttpClient(http), "https://pb.test", store), store, KeyValueStore(FakeDataStore()), scope, lazyOf(emptyList()))
        return SessionWatchdog(repo, store, scope)
    }

    private suspend fun signIn(exp: Long = nowSeconds + 30 * 86_400) =
        store.save(AuthState(jwt(exp), JsonObject(mapOf("id" to JsonPrimitive("u1")))))

    @Test fun noSessionDoesNothing() = runTest {
        watchdog { json(fresh) }.refreshIfNeeded()
        assertEquals(0, refreshes)
    }

    @Test fun anExpiredTokenSignsOutWithoutCallingTheServer() = runTest {
        val w = watchdog { json(fresh) }
        signIn(nowSeconds - 1)
        w.refreshIfNeeded()
        assertNull(store.current)
        assertTrue(repo.sessionExpired.value)
        assertEquals(0, refreshes)
    }

    @Test fun anUnreadableTokenCountsAsExpired() = runTest {
        val w = watchdog { json(fresh) }
        store.save(AuthState("garbage", JsonObject(emptyMap())))
        w.refreshIfNeeded()
        assertNull(store.current)
    }

    @Test fun aGoodRefreshReplacesTheToken() = runTest {
        signIn()
        val rotated = "${jwt(nowSeconds + 40 * 86_400)}"
        val w2 = watchdog { json("""{"token":"$rotated","record":{"id":"u1"}}""") }
        w2.refreshIfNeeded()
        assertEquals(1, refreshes)
        assertEquals(rotated, store.token)
        assertFalse(repo.sessionExpired.value)
    }

    @Test fun aSecondTriggerSoonAfterIsSkipped() = runTest {
        val w = watchdog { json(fresh) }
        signIn()
        w.refreshIfNeeded()
        w.refreshIfNeeded()
        assertEquals(1, refreshes)
    }

    @Test fun aTokenCloseToExpiryAlwaysRefreshes() = runTest {
        val w = watchdog { json("""{"token":"${jwt(nowSeconds + 3_600)}","record":{"id":"u1"}}""") }
        signIn(nowSeconds + 3_600)
        w.refreshIfNeeded()
        w.refreshIfNeeded()
        assertEquals(2, refreshes)
    }

    @Test fun a401SignsOut() = runTest {
        val w = watchdog { json("{}", HttpStatusCode.Unauthorized) }
        signIn()
        w.refreshIfNeeded()
        assertNull(store.current)
        assertTrue(repo.sessionExpired.value)
    }

    @Test fun a403SignsOut() = runTest {
        val w = watchdog { json("{}", HttpStatusCode.Forbidden) }
        signIn()
        w.refreshIfNeeded()
        assertNull(store.current)
    }

    /** Runs a refresh whose answer is held until [during] has run (02 #4). */
    private suspend fun refreshAround(w: SessionWatchdog, answer: CompletableDeferred<Unit>, during: suspend () -> Unit) =
        withContext(Dispatchers.Default) {
            val call = launch { w.refreshIfNeeded() }
            withTimeout(5_000) { while (refreshes == 0) delay(1) }
            during()
            answer.complete(Unit)
            call.join()
        }

    private val other = AuthState("other", JsonObject(mapOf("id" to JsonPrimitive("u2"))))

    @Test fun aRefreshAnsweredAfterSignOutLeavesTheUserSignedOut() = runTest {
        val answer = CompletableDeferred<Unit>()
        val w = watchdog { answer.await(); json(fresh) }
        signIn()
        refreshAround(w, answer) { repo.signOut() }
        assertNull(store.current)
    }

    @Test fun aRejectionAnsweredAfterAnotherSignInKeepsTheNewAccount() = runTest {
        val answer = CompletableDeferred<Unit>()
        val w = watchdog { answer.await(); json("{}", HttpStatusCode.Unauthorized) }
        signIn()
        refreshAround(w, answer) { repo.signOut(); store.save(other) }
        assertEquals(other, store.current)
        assertFalse(repo.sessionExpired.value)
    }

    @Test fun aServerErrorKeepsTheSessionAndRetriesNextTime() = runTest {
        val w = watchdog { json("{}", HttpStatusCode.InternalServerError) }
        signIn()
        w.refreshIfNeeded()
        w.refreshIfNeeded()
        assertNotNull(store.current)
        assertEquals(2, refreshes)
    }

    @Test fun aNetworkErrorKeepsTheSession() = runTest {
        val w = watchdog { throw IOException("offline") }
        signIn()
        w.refreshIfNeeded()
        assertNotNull(store.current)
    }

    @Test fun anUnreadableSuccessKeepsTheSession() = runTest {
        for (body in listOf("{}", "[]", "not json", """{"token":null,"record":{"id":"u1"}}""")) {
            val w = watchdog { json(body) }
            signIn()
            w.refreshIfNeeded()
            assertNotNull(store.current, body)
        }
    }

    @Test fun cancellingARefreshKeepsTheSessionAndReleasesTheLock() = runTest {
        val reached = CompletableDeferred<Unit>()
        var hang = true
        val w = watchdog {
            if (hang) {
                hang = false
                reached.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            json(fresh)
        }
        signIn()
        withContext(Dispatchers.Default) {
            val call = launch { w.refreshIfNeeded() }
            reached.await()
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            assertNotNull(store.current)
            withTimeout(5_000) { w.refreshIfNeeded() }
        }
        assertEquals(2, refreshes)
    }

    @Test fun simultaneousTriggersRefreshOnce() = runTest {
        val w = watchdog { delay(5); json(fresh) }
        signIn()
        withContext(Dispatchers.Default) {
            repeat(5) { w.trigger() }
            withTimeout(5_000) { while (refreshes == 0) delay(1) }
            delay(50)
        }
        assertEquals(1, refreshes)
    }
}
