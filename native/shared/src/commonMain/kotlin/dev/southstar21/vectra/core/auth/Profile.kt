package dev.southstar21.vectra.core.auth

import dev.southstar21.vectra.core.network.obj
import dev.southstar21.vectra.core.network.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The users auth record. */
data class Profile(
    val id: String,
    val email: String?,
    val fullName: String?,
    val avatarUrl: String?,
    val appearance: JsonElement?,
    /** Encrypted at rest (vc3:…); decrypt before use. */
    val neptunIcsUrl: String?,
    val neptunLastSync: String?,
    val preferences: JsonObject?,
    val raw: JsonObject,
) {
    val displayName: String get() = fullName?.takeIf { it.isNotBlank() } ?: email?.substringBefore('@') ?: "there"
    val initial: Char get() = (fullName?.takeIf { it.isNotBlank() } ?: email ?: "U").first().uppercaseChar()

    companion object {
        fun from(record: JsonObject) = Profile(
            id = record.string("id").orEmpty(),
            email = record.string("email"),
            fullName = record.string("full_name"),
            avatarUrl = record.string("avatar_url"),
            appearance = record["appearance"],
            neptunIcsUrl = record.string("neptun_ics_url"),
            neptunLastSync = record.string("neptun_last_sync"),
            preferences = record.obj("preferences"),
            raw = record,
        )
    }
}
