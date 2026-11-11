package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.auth.Profile
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * The reminder settings, on the device and in the profile (`preferences.reminders`): a device that
 * hasn't held this account's settings yet (a reinstall, a new phone) takes them from there; after
 * that the device's own copy wins and every change is pushed back.
 */
internal class ReminderPrefsStore(
    private val kv: KeyValueStore,
    private val auth: AuthRepository,
    private val scope: CoroutineScope,
) {
    private val _prefs = MutableStateFlow(ReminderPrefs())
    val prefs: StateFlow<ReminderPrefs> = _prefs.asStateFlow()

    private val pushLock = Mutex()
    private val writeLock = Mutex()
    /** The account whose profile was compared with [prefs] in this process. */
    private var syncedFor: String? = null

    /** Reads the stored settings, then keeps them in step with the signed-in profile. */
    suspend fun load(onLoaded: () -> Unit) {
        _prefs.value = kv.get(PrefsKey)?.let { runCatching { VectraJson.decodeFromString(ReminderPrefs.serializer(), it) }.getOrNull() } ?: ReminderPrefs()
        onLoaded()
        auth.profile.collect { profile -> if (profile != null) runSuspendCatching { syncWith(profile) } }
    }

    fun update(transform: (ReminderPrefs) -> ReminderPrefs) {
        _prefs.update(transform)
        scope.launch {
            persist()
            push()
        }
    }

    /** Signed out: the next account brings its own settings from its profile. */
    suspend fun reset() {
        syncedFor = null
        kv.set(OwnerKey, null)
        store(ReminderPrefs())
    }

    /** Once per account and process: take the profile's settings on a fresh device, otherwise push ours if they differ. */
    private suspend fun syncWith(profile: Profile) {
        if (syncedFor == profile.id) return
        syncedFor = profile.id
        val remote = profile.preferences?.get(ProfileKey)
            ?.let { runCatching { VectraJson.decodeFromJsonElement(ReminderPrefs.serializer(), it) }.getOrNull() }
        val owner = kv.get(OwnerKey)
        if (owner != profile.id) {
            kv.set(OwnerKey, profile.id)
            when {
                remote != null -> return store(remote)
                // Another account's settings; with no owner yet they are this account's, from before syncing.
                owner != null -> store(ReminderPrefs())
            }
        }
        if (remote != _prefs.value) push()
    }

    private suspend fun store(next: ReminderPrefs) {
        _prefs.value = next
        persist()
    }

    /**
     * Writes the settings as they are now, one write at a time: each write follows the change that
     * caused it, so the last one to land always holds the latest settings, however two quick
     * toggles' writes interleave.
     */
    private suspend fun persist() = writeLock.withLock {
        kv.set(PrefsKey, VectraJson.encodeToString(ReminderPrefs.serializer(), _prefs.value))
    }

    /** Writes the latest [prefs] into the profile, beside the other preferences there. */
    private suspend fun push() = pushLock.withLock {
        if (auth.session.value == null) return@withLock
        runSuspendCatching {
            val current = auth.profile.value?.preferences ?: JsonObject(emptyMap())
            val mine = VectraJson.encodeToJsonElement(ReminderPrefs.serializer(), _prefs.value)
            if (current[ProfileKey] != mine) auth.updateProfile(mapOf("preferences" to JsonObject(current + (ProfileKey to mine))))
        }
    }

    companion object {
        const val PrefsKey = "vectra.reminders"
        /** The account [PrefsKey] belongs to. */
        private const val OwnerKey = "vectra.reminders.owner"
        /** Where they live in the profile's `preferences`. */
        private const val ProfileKey = "reminders"
    }
}
