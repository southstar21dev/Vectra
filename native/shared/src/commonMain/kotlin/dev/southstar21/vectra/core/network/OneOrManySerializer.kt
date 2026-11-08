package dev.southstar21.vectra.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * A list that used to be a single (nullable) value: an old object reads as a one-item list and
 * null as an empty one, so values stored before the change still load.
 */
open class OneOrManySerializer<T>(element: KSerializer<T>) : JsonTransformingSerializer<List<T>>(ListSerializer(element)) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonArray(listOf(element))
        is JsonNull -> JsonArray(emptyList())
        else -> element
    }
}
