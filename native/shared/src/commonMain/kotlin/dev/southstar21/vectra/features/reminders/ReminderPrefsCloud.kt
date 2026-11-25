package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.isCiphertext
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

/** The profile holds a commute blob that cannot be decrypted right now (offline, server busy). */
internal class CommuteUnreadableException : IllegalStateException("The commute legs could not be decrypted")

/**
 * How [ReminderPrefs] sit in the profile's `preferences.reminders`. The commute legs name the
 * student's home and campus stops, so, like the trackers they were copied from, they are stored as
 * ciphertext (`commute`) and never as plain `toUni` / `backHome` arrays.
 */
internal class ReminderPrefsCloud(private val crypto: CryptoClient) {
    /**
     * The profile value for [prefs]. Throws when the legs cannot be encrypted, so the caller skips
     * the write: sending the settings without them would wipe the copy other devices restore from.
     */
    suspend fun encode(prefs: ReminderPrefs): JsonObject {
        val plain = VectraJson.encodeToJsonElement(
            ReminderPrefs.serializer(),
            prefs.copy(toUni = emptyList(), backHome = emptyList()),
        ) as JsonObject
        if (prefs.toUni.isEmpty() && prefs.backHome.isEmpty()) return plain
        val blob = VectraJson.encodeToString(CommuteBlob.serializer(), CommuteBlob(prefs.toUni, prefs.backHome))
        val cipher = crypto.encrypt(blob)?.takeIf(::isCiphertext) ?: error("The commute legs could not be encrypted")
        return JsonObject(plain + (CommuteKey to JsonPrimitive(cipher)))
    }

    /**
     * The settings a profile value holds, or null when it is not readable. Legacy plain legs are
     * still read. Throws [CommuteUnreadableException] when the blob is there but cannot be
     * decrypted now, so a caller never mistakes it for settings without legs.
     */
    suspend fun decode(element: JsonElement): ReminderPrefs? {
        val obj = element as? JsonObject ?: return null
        val base = runCatching {
            VectraJson.decodeFromJsonElement(ReminderPrefs.serializer(), JsonObject(obj - CommuteKey))
        }.getOrNull() ?: return null
        val cipher = (obj[CommuteKey] as? JsonPrimitive)?.contentOrNull ?: return base
        val blob = readBlob(cipher) ?: throw CommuteUnreadableException()
        return base.copy(toUni = blob.toUni, backHome = blob.backHome)
    }

    // CryptoClient.decrypt hands the ciphertext back when it fails, and that does not parse as a blob.
    private suspend fun readBlob(cipher: String): CommuteBlob? = crypto.decrypt(cipher)
        ?.let { runCatching { VectraJson.decodeFromString(CommuteBlob.serializer(), it) }.getOrNull() }

    /** True when [element] still carries the plain legs an older version wrote. */
    fun hasPlainLegs(element: JsonElement?): Boolean {
        val obj = element as? JsonObject ?: return false
        return listOf("toUni", "backHome").any { key -> obj[key].let { it is JsonObject || (it is JsonArray && it.isNotEmpty()) } }
    }

    private companion object {
        const val CommuteKey = "commute"
    }
}
