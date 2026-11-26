package dev.southstar21.vectra.features.update

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.PocketBaseException
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** The server refused this build; [minVersion] is the oldest it still serves, when it said. */
data class UpdateRequired(val minVersion: String?)

/** What is kept on disk: the refusal, and which build it was for. */
@Serializable
private data class StoredRefusal(val version: String, val minVersion: String? = null)

/**
 * Whether this build may still be used. Any PocketBase call answered 426 sets it (the graph hooks
 * [report] to [PocketBaseClient.onUpdateRequired] when it builds the client, so no request goes out
 * before that). The refusal is stored with the build it was for, so the app stays locked across
 * restarts and offline launches until it is updated (a new version ignores and drops it) or the
 * server lowers its minimum (a successful [check] clears it).
 */
class UpdateGate(
    private val kv: KeyValueStore,
    private val scope: CoroutineScope,
    private val appVersion: String,
) {
    private val _state = MutableStateFlow<UpdateRequired?>(null)
    val state: StateFlow<UpdateRequired?> = _state.asStateFlow()

    /** A health check already succeeded in this process, so a stored refusal read before it is out of date. */
    private var servedThisProcess = false

    /** Before the app shows anything: a refusal stored by this same build locks it again. */
    suspend fun loadStored() {
        val raw = kv.get(Key) ?: return
        // The cold-start check can win the race against this read; its verdict is newer.
        if (servedThisProcess) return
        val stored = runCatching { VectraJson.decodeFromString(StoredRefusal.serializer(), raw) }.getOrNull()
        if (stored != null && stored.version == appVersion) {
            if (_state.value == null && !servedThisProcess) _state.value = UpdateRequired(stored.minVersion)
        } else {
            // Updated since (or unreadable): this build hasn't been refused.
            kv.set(Key, null)
        }
    }

    fun report(minVersion: String?) {
        val next = UpdateRequired(minVersion)
        servedThisProcess = false
        if (_state.value == next) return
        _state.value = next
        scope.launch {
            runSuspendCatching {
                kv.set(Key, VectraJson.encodeToString(StoredRefusal.serializer(), StoredRefusal(appVersion, minVersion)))
            }
        }
    }

    /** One request even a signed-out app makes; a 426 has already reported itself by the time it throws. */
    suspend fun check(pb: PocketBaseClient) {
        try {
            pb.send("/api/health")
        } catch (e: CancellationException) {
            throw e
        } catch (_: PocketBaseException) {
            // Refused (already reported), offline or another error: the state stays as it is.
            return
        }
        servedThisProcess = true
        if (_state.value != null) _state.value = null
        runSuspendCatching { kv.set(Key, null) }
    }

    private companion object {
        const val Key = "vectra.updateRequired"
    }
}
