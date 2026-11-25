package dev.southstar21.vectra.features.update

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.network.json
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The cold-start health check and the stored-refusal read run together (VEC-87). */
class UpdateGateTest {
    private val kv = KeyValueStore(FakeDataStore())

    private fun client() = PocketBaseClient(
        createHttpClient(HttpClient(MockEngine { json("""{"message":"ok"}""") })),
        "https://pb.test",
        AuthStore(kv, PlainSecretCipher),
    )

    @Test fun aStoredRefusalLocksTheNextLaunchUntilTheServerAccepts() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        UpdateGate(kv, scope, "1.0").report("2.0")
        val next = UpdateGate(kv, scope, "1.0")
        next.loadStored()
        assertEquals(UpdateRequired("2.0"), next.state.value)
        next.check(client())
        assertNull(next.state.value)
        scope.cancel()
    }

    @Test fun aHealthCheckThatBeatsTheStoredReadKeepsTheGateOpen() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        UpdateGate(kv, scope, "1.0").report("2.0")
        val next = UpdateGate(kv, scope, "1.0")
        // The check lands first, then the startup read that began before it finishes.
        next.check(client())
        next.loadStored()
        assertNull(next.state.value)
        scope.cancel()
    }
}
