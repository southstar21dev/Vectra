package dev.southstar21.vectra.core.maps

import dev.southstar21.vectra.core.network.VectraJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/*
 * The OpenFreeMap basemap shared by the transit maps and the weather radar:
 * keyless vector styles, sanitized before MapLibre sees them, with Vectra's
 * water and park colours layered under the roads.
 */

private const val OpenFreeMapStyleBase = "https://tiles.openfreemap.org/styles"

/** Attribution printed with every OpenFreeMap basemap. */
const val OpenFreeMapAttribution = "OPF © OMT © OSM"

fun openFreeMapStyleUrl(isLight: Boolean, nowMs: Long = Clock.System.now().toEpochMilliseconds()): String {
    // A daily cache-buster defeats caches that kept a broken provider style.
    val day = nowMs / 86_400_000L
    return "$OpenFreeMapStyleBase/${if (isLight) "positron" else "dark"}?v=3&d=$day"
}

private val ComparisonOps = setOf("<=", ">=", "<", ">")

/**
 * Null-safe filters: bare `["get", …]` operands of comparisons become
 * `["coalesce", ["get", …], 999]`, and literal nulls become 0.
 */
internal fun nullSafeExpr(value: JsonElement): JsonElement {
    if (value is JsonNull) return JsonPrimitive(0)
    if (value !is JsonArray || value.isEmpty()) return value
    val op = value[0]
    val args = value.drop(1).map(::nullSafeExpr)
    val isCmp = op is JsonPrimitive && op.isString && op.content in ComparisonOps
    val safeArgs = if (!isCmp) args else args.map { arg ->
        if (arg is JsonArray && (arg.firstOrNull() as? JsonPrimitive)?.content == "get") {
            buildJsonArray { add(JsonPrimitive("coalesce")); add(arg); add(JsonPrimitive(999)) }
        } else arg
    }
    return JsonArray(listOf(op) + safeArgs)
}

/** Null-safe filters, no missing pattern sprites, Noto Sans glyphs. */
fun sanitizeOpenFreeMapStyle(style: JsonObject): JsonObject {
    val layers = (style["layers"] as? JsonArray)?.map { layerEl ->
        val layer = layerEl as? JsonObject ?: return@map layerEl
        val out = layer.toMutableMap()
        layer["filter"]?.let { out["filter"] = nullSafeExpr(it) }
        (layer["paint"] as? JsonObject)?.let { paint ->
            out["paint"] = JsonObject(paint - "fill-pattern" - "line-pattern")
        }
        (layer["layout"] as? JsonObject)?.let { layout ->
            val fonts = layout["text-font"] as? JsonArray
            if (fonts != null) {
                out["layout"] = JsonObject(
                    layout + ("text-font" to JsonArray(fonts.map { f ->
                        if (f is JsonPrimitive && f.isString) JsonPrimitive(f.content.replace(Regex("OpenSans", RegexOption.IGNORE_CASE), "Noto Sans")) else f
                    })),
                )
            }
        }
        JsonObject(out)
    } ?: return style
    return JsonObject(style + ("layers" to JsonArray(layers)))
}

/**
 * applyOpenFreeMapParkColors, applied to the style document: exact-filter
 * water and park fills inserted below the first road (or label) layer.
 */
fun withParkColors(style: JsonObject, isLight: Boolean): JsonObject {
    val sources = style["sources"] as? JsonObject ?: return style
    val vectorSourceId = sources.entries.firstOrNull { (it.value as? JsonObject)?.get("type")?.let { t -> (t as? JsonPrimitive)?.content } == "vector" }?.key
        ?: return style
    val layers = (style["layers"] as? JsonArray)?.toMutableList() ?: return style
    val waterColor = if (isLight) "#c5dce8" else "#183b52"
    val parkColor = if (isLight) "#b8d7b1" else "#315f3c"

    fun fill(id: String, sourceLayer: String, color: String, opacity: Double, filter: JsonArray? = null) = buildJsonObject {
        put("id", id)
        put("type", "fill")
        put("source", vectorSourceId)
        put("source-layer", sourceLayer)
        if (filter != null) put("filter", filter)
        putJsonObject("paint") {
            put("fill-color", color)
            put("fill-opacity", opacity)
            put("fill-antialias", true)
        }
    }
    fun eq(property: String) = buildJsonArray { add(JsonPrimitive("==")); add(JsonPrimitive(property)); add(JsonPrimitive("park")) }

    val overlays = listOf(
        fill("vectra-water-fill", "water", waterColor, 0.95),
        fill("vectra-park-landcover", "landcover", parkColor, 0.82, eq("subclass")),
        fill("vectra-park-landuse", "landuse", parkColor, 0.82, eq("class")),
    )
    val ids = overlays.map { (it["id"] as JsonPrimitive).content }.toSet()
    layers.removeAll { (it as? JsonObject)?.get("id")?.let { id -> (id as? JsonPrimitive)?.content } in ids }
    fun JsonElement.str(key: String) = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.content
    val roadIndex = layers.indexOfFirst { it.str("source-layer") == "transportation" }
    val symbolIndex = layers.indexOfFirst { it.str("type") == "symbol" }
    val before = if (roadIndex >= 0) roadIndex else symbolIndex
    if (before >= 0) layers.addAll(before, overlays) else layers.addAll(overlays)
    return JsonObject(style + ("layers" to JsonArray(layers)))
}

/**
 * A downloaded OpenFreeMap style, sanitized and with the park overlays, as a style document.
 * The style is a few hundred KB of JSON and the maps fetch it from a composable's effect on
 * the main thread, so parsing and re-serializing happen on [work] (audit 03 #16).
 */
suspend fun prepareOpenFreeMapStyle(text: String, isLight: Boolean, work: CoroutineContext = Dispatchers.Default): String =
    withContext(work) {
        val style = VectraJson.parseToJsonElement(text) as? JsonObject ?: error("OpenFreeMap style is not an object")
        withParkColors(sanitizeOpenFreeMapStyle(style), isLight).toString()
    }
