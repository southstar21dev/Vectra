package dev.southstar21.vectra.features.reminders

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** Two quick toggles must leave the second one on disk (audit 04 #23). */
class ReminderPrefsStoreTest {
    /** Holds the first write at [gate] until the test opens it; later writes go straight through. */
    private class SlowFirstWrite(private val inner: FakeDataStore = FakeDataStore()) : DataStore<Preferences> {
        val gate = CompletableDeferred<Unit>()
        private var writes = 0
        override val data: Flow<Preferences> = inner.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (writes++ == 0) gate.await()
            return inner.updateData(transform)
        }
    }

    @Test fun theLaterToggleWinsOnDiskWhenTheEarlierWriteIsSlow() = runTest {
        val ds = SlowFirstWrite()
        val kv = KeyValueStore(ds)
        val authStore = AuthStore(KeyValueStore(FakeDataStore()), PlainSecretCipher)
        val engine = MockEngine { throw IOException("offline") }
        val auth = AuthRepository(PocketBaseClient(createHttpClient(HttpClient(engine)), "https://pb.test", authStore), authStore, kv, backgroundScope, lazyOf(emptyList()))
        val prefs = ReminderPrefsStore(kv, auth, backgroundScope)

        prefs.update { it.copy(beforeClass = true) }
        prefs.update { it.copy(beforeClass = false) }
        runCurrent()
        ds.gate.complete(Unit)
        runCurrent()

        assertEquals(ReminderPrefs(beforeClass = false), prefs.prefs.value)
        val stored = kv.get(ReminderPrefsStore.PrefsKey)?.let { VectraJson.decodeFromString(ReminderPrefs.serializer(), it) }
        assertEquals(ReminderPrefs(beforeClass = false), stored)
    }
}
