package dev.southstar21.vectra.features.settings

import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.AuthState
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.network.json
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.core.preferences.AccentKey
import dev.southstar21.vectra.core.preferences.AppearancePrefs
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An appearance change whose profile save failed survives the next launch (audit 02 #9). */
class AppearanceRepositoryTest {
    /** The app's DataStore: it outlives each simulated launch. */
    private val kv = KeyValueStore(FakeDataStore())
    private val scopes = mutableListOf<CoroutineScope>()
    private val patchLock = Mutex()
    private val patches = mutableListOf<String>()

    @AfterTest fun stop() = scopes.forEach { it.cancel() }

    private fun record(id: String, accent: AccentKey) =
        """{"id":"$id","appearance":${AppearancePrefs(accent = accent).toJson()}}"""

    /** Stores a signed-in session, as an earlier launch would have. */
    private suspend fun signedIn(id: String, accent: AccentKey) =
        AuthStore(kv, PlainSecretCipher).save(AuthState("tok", VectraJson.parseToJsonElement(record(id, accent)).jsonObject))

    /** One launch, as AppStartup does it: stored prefs first, then the stored session. */
    private suspend fun launch(online: Boolean, serverAccent: AccentKey = AccentKey.Blue): AppearanceRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = AuthStore(kv, PlainSecretCipher)
        val engine = MockEngine { req ->
            if (!online) throw IOException("offline")
            if (req.method == HttpMethod.Patch) patchLock.withLock { patches += (req.body as TextContent).text }
            json(record(store.current?.userId ?: "u1", serverAccent))
        }
        val auth = AuthRepository(PocketBaseClient(createHttpClient(HttpClient(engine)), "https://pb.test", store), store, kv, scope, lazyOf(emptyList()))
        val repo = AppearanceRepository(kv, auth, scope)
        repo.loadStored()
        auth.restore()
        return repo
    }

    private suspend fun until(what: String, check: suspend () -> Boolean) {
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (!check()) delay(5) } }
        assertTrue(check(), what)
    }

    private suspend fun storedAccent(): AccentKey? =
        kv.get("vectra.appearance")?.let { AppearancePrefs.normalize(VectraJson.parseToJsonElement(it)).accent }

    private suspend fun unsynced(): String? = kv.get("vectra.appearance.unsynced")

    private suspend fun patched(key: String) = patchLock.withLock { patches.any { key in it } }

    @Test fun anOfflineChangeSurvivesTheNextLaunchAndIsSentAgain() = runTest {
        signedIn("u1", AccentKey.Blue)
        val first = launch(online = false)
        until("the profile's accent applied") { first.prefs.value.accent == AccentKey.Blue }
        first.update { it.copy(accent = AccentKey.Pink) }
        until("the change stored with its mark") { storedAccent() == AccentKey.Pink && unsynced() == "u1" }
        // Let the failed save finish before the "process" ends.
        delay(100)
        scopes.forEach { it.cancel() }

        // The next launch is online; the stored session record still says blue.
        val second = launch(online = true, serverAccent = AccentKey.Pink)
        until("pink sent to the profile") { patched("pink") }
        assertEquals(AccentKey.Pink, second.prefs.value.accent)
        assertEquals(AccentKey.Pink, storedAccent())
        until("the mark cleared") { unsynced() == null }
    }

    @Test fun withNothingUnsavedTheProfileWins() = runTest {
        signedIn("u1", AccentKey.Teal)
        kv.set("vectra.appearance", AppearancePrefs(accent = AccentKey.Pink).toJson().toString())
        val repo = launch(online = true)
        until("the profile's accent applied") { repo.prefs.value.accent == AccentKey.Teal }
        assertEquals(AccentKey.Teal, storedAccent())
        assertTrue(patchLock.withLock { patches.isEmpty() })
    }

    @Test fun anotherAccountsUnsavedChangeIsNotApplied() = runTest {
        signedIn("u2", AccentKey.Teal)
        kv.set("vectra.appearance", AppearancePrefs(accent = AccentKey.Pink).toJson().toString())
        kv.set("vectra.appearance.unsynced", "u1")
        val repo = launch(online = true)
        until("the profile's accent applied") { repo.prefs.value.accent == AccentKey.Teal }
        assertTrue(patchLock.withLock { patches.isEmpty() })
    }

    @Test fun aSavedChangeLeavesNoMark() = runTest {
        signedIn("u1", AccentKey.Blue)
        val repo = launch(online = true, serverAccent = AccentKey.Green)
        until("the profile's accent applied") { repo.prefs.value.accent == AccentKey.Blue }
        repo.update { it.copy(accent = AccentKey.Green) }
        until("green sent") { patched("green") }
        until("the mark cleared") { unsynced() == null }
        assertEquals(AccentKey.Green, repo.prefs.value.accent)
    }
}
