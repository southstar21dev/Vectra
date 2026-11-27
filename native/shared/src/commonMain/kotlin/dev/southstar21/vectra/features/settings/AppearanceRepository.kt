package dev.southstar21.vectra.features.settings

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.preferences.AppearancePrefs
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.auth.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Appearance prefs: the stored prefs paint first; once the profile loads its
 * `appearance` wins (and again whenever a newer record brings a different one), unless this device holds a change it never
 * managed to save there; changes apply instantly and sync to the profile in the background.
 */
class AppearanceRepository(
    private val kv: KeyValueStore,
    private val auth: AuthRepository,
    private val scope: CoroutineScope,
) {
    private val _prefs = MutableStateFlow(AppearancePrefs.Default)
    val prefs: StateFlow<AppearancePrefs> = _prefs.asStateFlow()
    private var hydratedFor: String? = null
    /** The profile's `appearance` as last applied here, to tell a newer one (from another device) apart. */
    private var hydratedAppearance: JsonElement? = null

    private val _loaded = MutableStateFlow(false)
    /** The stored theme is in [prefs]. Until then the first frames would paint the default one. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun loadStored() {
        try {
            _prefs.value = readStored() ?: AppearancePrefs.Default
        } finally {
            _loaded.value = true
        }
    }

    private suspend fun readStored(): AppearancePrefs? =
        kv.get(KEY)?.let { runCatching { AppearancePrefs.normalize(VectraJson.parseToJsonElement(it)) }.getOrNull() }

    init {
        scope.launch {
            auth.profile.collect { profile ->
                if (profile == null) {
                    if (hydratedFor != null) {
                        hydratedFor = null
                        hydratedAppearance = null
                        kv.set(UNSYNCED_KEY, null)
                        set(AppearancePrefs.Default)
                    }
                } else if (hydratedFor != profile.id) {
                    hydratedFor = profile.id
                    hydratedAppearance = profile.appearance
                    hydrate(profile)
                } else if (hydratedAppearance != profile.appearance) {
                    // A fresher record (token refresh, another device's change): follow it unless a local change is unsaved.
                    hydratedAppearance = profile.appearance
                    if (kv.get(UNSYNCED_KEY) != profile.id) {
                        set(profile.appearance?.let(AppearancePrefs::normalize) ?: AppearancePrefs.Default)
                    }
                }
            }
        }
    }

    /**
     * The profile's appearance, unless this account changed it here and the save never reached
     * the profile (offline, say): then the local prefs stay and are sent again (audit 02 #9).
     */
    private suspend fun hydrate(profile: Profile) {
        val unsaved = if (kv.get(UNSYNCED_KEY) == profile.id) readStored() else null
        if (unsaved == null) {
            set(profile.appearance?.let(AppearancePrefs::normalize) ?: AppearancePrefs.Default)
            return
        }
        set(unsaved)
        // From the profile collector: a sign-out ends the save quietly instead of failing the collector.
        auth.sessionScope.withSessionOrNull(profile.id) { push(profile.id, unsaved) }
    }

    private suspend fun set(next: AppearancePrefs) {
        _prefs.value = next
        kv.set(KEY, next.toJson().toString())
    }

    fun update(transform: (AppearancePrefs) -> AppearancePrefs) {
        val next = transform(_prefs.value)
        val uid = auth.userId
        if (uid == null) {
            scope.launch { set(next) }
            return
        }
        // In the account's session: a sign-out mid-save must not write its prefs or mark after the wipe.
        auth.sessionScope.launchFor(uid) {
            set(next)
            // Marked before the request, so a process killed mid-save still knows.
            kv.set(UNSYNCED_KEY, uid)
            push(uid, next)
        }
    }

    /** Saves [next] to [uid]'s profile; the mark goes once the newest change is saved. */
    private suspend fun push(uid: String, next: AppearancePrefs) {
        val saved = runSuspendCatching { auth.updateProfile(mapOf("appearance" to next.toJson())) }.isSuccess
        // Kept: clears the mark only if no newer change replaced [next] while the save ran.
        if (saved && _prefs.value == next && kv.get(UNSYNCED_KEY) == uid) kv.set(UNSYNCED_KEY, null)
    }

    private companion object {
        const val KEY = "vectra.appearance"

        /** The account whose appearance change this device has not yet saved to its profile. */
        const val UNSYNCED_KEY = "vectra.appearance.unsynced"
    }
}
