package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.VectraJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The stops of the commute legs, which leave the device only as one ciphertext blob. */
@Serializable
private data class CommuteBlob(
    @Serializable(with = CommuteLegList::class) val toUni: List<CommuteLeg> = emptyList(),
    @Serializable(with = CommuteLegList::class) val backHome: List<CommuteLeg> = emptyList(),
)

/**
 * How [ReminderPrefs] sit in the profile's `preferences.reminders`. The commute legs name the
 * student's home and campus stops, so, like the trackers they were copied from, they are stored as
 * ciphertext (`commute`) and never as plain `toUni` / `backHome` arrays.
 */
internal class ReminderPrefsCloud(private val crypto: CryptoClient) {
    /** The profile value for [prefs]; the legs are left out when they cannot be encrypted. */
    suspend fun encode(prefs: ReminderPrefs): JsonObject {
        val plain = VectraJson.encodeToJsonElement(
            ReminderPrefs.serializer(),
            prefs.copy(toUni = emptyList(), backHome = emptyList()),
        ) as JsonObject
        if (prefs.toUni.isEmpty() && prefs.backHome.isEmpty()) return plain
        val blob = VectraJson.encodeToString(CommuteBlob.serializer(), CommuteBlob(prefs.toUni, prefs.backHome))
        val cipher = runCatching { crypto.encrypt(blob) }.getOrNull() ?: return plain
        return JsonObject(plain + (CommuteKey to JsonPrimitive(cipher)))
    }

    /** The settings a profile value holds, or null when it is not readable. Legacy plain legs are still read. */
    suspend fun decode(element: JsonElement): ReminderPrefs? {
        val obj = element as? JsonObject ?: return null
        val base = runCatching {
            VectraJson.decodeFromJsonElement(ReminderPrefs.serializer(), JsonObject(obj - CommuteKey))
        }.getOrNull() ?: return null
        val cipher = (obj[CommuteKey] as? JsonPrimitive)?.contentOrNull ?: return base
        val blob = runCatching { crypto.decrypt(cipher) }.getOrNull()
            ?.let { runCatching { VectraJson.decodeFromString(CommuteBlob.serializer(), it) }.getOrNull() }
            ?: return base
        return base.copy(toUni = blob.toUni, backHome = blob.backHome)
    }

    /** True when [element] still carries the plain legs an older version wrote. */
    fun hasPlainLegs(element: JsonElement?): Boolean {
        val obj = element as? JsonObject ?: return false
        return listOf("toUni", "backHome").any { key -> obj[key].let { it is JsonObject || (it is JsonArray && it.isNotEmpty()) } }
    }

    private companion object {
        const val CommuteKey = "commute"
    }
}
