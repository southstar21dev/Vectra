package dev.southstar21.vectra.core.preferences

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

enum class ThemeMode(val key: String) { Light("light"), Dark("dark"), System("system") }

enum class AccentKey(val key: String) {
    Blue("blue"), Indigo("indigo"), Purple("purple"), Pink("pink"), Red("red"),
    Orange("orange"), Green("green"), Teal("teal"), Cyan("cyan"),
}

enum class BackgroundKey(val key: String, val label: String) {
    Mesh("mesh", "Grid"), Flat("flat", "Solid"), Aurora("aurora", "Aurora"),
}

enum class TimeFormat(val key: String) { H24("24h"), H12("12h") }

data class AppearancePrefs(
    val theme: ThemeMode = ThemeMode.Dark,
    val accent: AccentKey = AccentKey.Purple,
    val background: BackgroundKey = BackgroundKey.Aurora,
    val timeFormat: TimeFormat = TimeFormat.H24,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("theme", JsonPrimitive(theme.key))
        put("accent", JsonPrimitive(accent.key))
        put("background", JsonPrimitive(background.key))
        put("timeFormat", JsonPrimitive(timeFormat.key))
    }

    companion object {
        val Default = AppearancePrefs()

        /** Background styles removed from the picker; they migrate to aurora. */
        private val RemovedBackgroundKeys = setOf("gradient", "noise", "borealis", "northern")

        /**
         * Parse unknown server/storage input; anything invalid → full defaults. A missing
         * field falls back to its default, while a present-but-invalid field rejects the
         * whole object.
         */
        fun normalize(raw: JsonElement?): AppearancePrefs {
            if (raw == null || raw is JsonNull || raw !is JsonObject) return Default
            fun field(name: String): String? {
                val value = raw[name] ?: return null
                return (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: INVALID
            }
            val bgRaw = field("background")?.let { if (it in RemovedBackgroundKeys) "aurora" else it }
            val theme = field("theme")?.let { k -> ThemeMode.entries.firstOrNull { it.key == k } ?: return Default }
            val accent = field("accent")?.let { k -> AccentKey.entries.firstOrNull { it.key == k } ?: return Default }
            val background = bgRaw?.let { k -> BackgroundKey.entries.firstOrNull { it.key == k } ?: return Default }
            val timeFormat = field("timeFormat")?.let { k -> TimeFormat.entries.firstOrNull { it.key == k } ?: return Default }
            return AppearancePrefs(
                theme = theme ?: Default.theme,
                accent = accent ?: Default.accent,
                background = background ?: Default.background,
                timeFormat = timeFormat ?: Default.timeFormat,
            )
        }

        private const val INVALID = "\u0000invalid"
    }
}

/** 'system' resolves against the OS preference; explicit modes pass through. */
fun ThemeMode.isDark(systemPrefersDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemPrefersDark
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
}
