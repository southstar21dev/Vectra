package dev.southstar21.vectra.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionScopeTest {
    private class Fixture(scope: CoroutineScope, first: String?) {
        val user = MutableStateFlow(first)
        val sessions = SessionScope(scope, { user.value }, user)
    }

    private fun TestScope.fixture(first: String? = "a") = Fixture(backgroundScope, first)

    @Test fun workDiesWhenTheSignedInUserChanges() = runTest {
        val f = fixture()
        val started = CompletableDeferred<Unit>()
        val job = f.sessions.launchFor("a") { started.complete(Unit); awaitCancellation() }
        started.await()
        f.user.value = "b"
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test fun workDiesOnSignOut() = runTest {
        val f = fixture()
        val started = CompletableDeferred<Unit>()
        val job = f.sessions.launchFor("a") { started.complete(Unit); awaitCancellation() }
        started.await()
        f.user.value = null
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test fun workOfTheSameUserKeepsRunning() = runTest {
        val f = fixture()
        val started = CompletableDeferred<Unit>()
        val job = f.sessions.launchFor("a") { started.complete(Unit); awaitCancellation() }
        started.await()
        f.user.value = "a"
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test fun anotherUsersScopeStartsAlreadyCancelled() = runTest {
        val f = fixture()
        var ran = false
        val job = f.sessions.launchFor("someone-else") { ran = true }
        job.join()
        assertFalse(ran)
        assertTrue(job.isCancelled)
    }

    @Test fun theNextUsersWorkOutlivesThePreviousScope() = runTest {
        val f = fixture()
        val old = f.sessions.launchFor("a") { awaitCancellation() }
        f.user.value = "b"
        old.join()
        val started = CompletableDeferred<Unit>()
        val next = f.sessions.launchFor("b") { started.complete(Unit); awaitCancellation() }
        started.await()
        assertTrue(next.isActive)
        next.cancel()
    }

    @Test fun aFailingChildDoesNotEndTheSession() = runTest {
        val f = fixture()
        val deferred = f.sessions.asyncFor<Int>("a") { error("boom") }
        assertFailsWith<IllegalStateException> { deferred.await() }
        val started = CompletableDeferred<Unit>()
        val survivor = f.sessions.launchFor("a") { started.complete(Unit); awaitCancellation() }
        started.await()
        assertTrue(survivor.isActive)
        survivor.cancel()
    }

    @Test fun withSessionEndsTheBlockWhenTheUserChanges() = runTest {
        val f = fixture()
        val inside = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Throwable?>()
        backgroundScope.launch {
            result.complete(runCatching { f.sessions.withSession("a") { inside.complete(Unit); awaitCancellation() } }.exceptionOrNull())
        }
        inside.await()
        f.user.value = "b"
        assertTrue(result.await() is SessionEndedException)
    }

    @Test fun withSessionOrNullReturnsNullInsteadOfThrowing() = runTest {
        val f = fixture()
        val inside = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit?>()
        backgroundScope.launch {
            answer.complete(f.sessions.withSessionOrNull("a") { inside.complete(Unit); awaitCancellation() })
        }
        inside.await()
        f.user.value = "b"
        assertNull(answer.await())
    }

    @Test fun withSessionReturnsTheBlockResultWhileTheUserStays() = runTest {
        val f = fixture()
        assertEquals(42, f.sessions.withSession("a") { 42 })
    }

    @Test fun withSessionForAUserWhoIsGoneRunsNothing() = runTest {
        val f = fixture()
        var ran = false
        assertFailsWith<SessionEndedException> { f.sessions.withSession("someone-else") { ran = true } }
        assertFalse(ran)
    }
}
