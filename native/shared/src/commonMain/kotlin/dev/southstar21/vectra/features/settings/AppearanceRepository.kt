package dev.southstar21.vectra.features.settings

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.preferences.AppearancePrefs
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.core.auth.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Appearance prefs: the stored prefs paint first; once the profile loads its
 * `appearance` wins (per user, once); changes apply instantly and sync to the
 * profile in the background.
 */
class AppearanceRepository(
    private val kv: KeyValueStore,
    private val auth: AuthRepository,
    private val scope: CoroutineScope,
) {
    private val _prefs = MutableStateFlow(AppearancePrefs.Default)
    val prefs: StateFlow<AppearancePrefs> = _prefs.asStateFlow()
    private var hydratedFor: String? = null

    private val _loaded = MutableStateFlow(false)
    /** The stored theme is in [prefs]. Until then the first frames would paint the default one. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun loadStored() {
        try {
            _prefs.value = kv.get(KEY)?.let { runCatching { AppearancePrefs.normalize(VectraJson.parseToJsonElement(it)) }.getOrNull() }
                ?: AppearancePrefs.Default
        } finally {
            _loaded.value = true
        }
    }

    init {
        scope.launch {
            auth.profile.collect { profile ->
                if (profile == null) {
                    if (hydratedFor != null) {
                        hydratedFor = null
                        set(AppearancePrefs.Default)
                    }
                } else if (hydratedFor != profile.id) {
                    hydratedFor = profile.id
                    set(profile.appearance?.let(AppearancePrefs::normalize) ?: AppearancePrefs.Default)
                }
            }
        }
    }

    private suspend fun set(next: AppearancePrefs) {
        _prefs.value = next
        kv.set(KEY, next.toJson().toString())
    }

    fun update(transform: (AppearancePrefs) -> AppearancePrefs) {
        val next = transform(_prefs.value)
        scope.launch {
            set(next)
            if (auth.session.value != null) {
                runSuspendCatching { auth.updateProfile(mapOf("appearance" to next.toJson())) }
            }
        }
    }

    private companion object {
        const val KEY = "vectra.appearance"
    }
}
