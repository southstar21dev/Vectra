package dev.southstar21.vectra.core.preferences

import dev.southstar21.vectra.core.designsystem.theme.AccentPresets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppearancePrefsTest {
    private fun n(json: String) = AppearancePrefs.normalize(Json.parseToJsonElement(json))
    private val default = AppearancePrefs.Default

    @Test
    fun acceptsACompleteValidObject() {
        assertEquals(
            AppearancePrefs(ThemeMode.Light, AccentKey.Green, BackgroundKey.Aurora, TimeFormat.H12),
            n("""{"theme":"light","accent":"green","background":"aurora","timeFormat":"12h"}"""),
        )
    }

    @Test
    fun fillsMissingFieldsWithDefaults() {
        assertEquals(AppearancePrefs(ThemeMode.Dark, AccentKey.Pink, BackgroundKey.Aurora, TimeFormat.H24), n("""{"accent":"pink"}"""))
    }

    @Test
    fun invalidValuesFallBackToFullDefaults() {
        assertEquals(default, n("""{"theme":"neon"}"""))
        assertEquals(default, n("""{"accent":"chartreuse"}"""))
        assertEquals(default, n("""{"background":42}"""))
        assertEquals(default, n("""{"timeFormat":"48h"}"""))
    }

    @Test
    fun garbageFallsBackToFullDefaults() {
        assertEquals(default, n("null"))
        assertEquals(default, n("\"nope\""))
        assertEquals(default, n("[]"))
        assertEquals(default, AppearancePrefs.normalize(null as JsonElement?))
    }

    @Test
    fun removedBackgroundsMigrateToAurora() {
        assertEquals(
            AppearancePrefs(ThemeMode.Light, AccentKey.Green, BackgroundKey.Aurora),
            n("""{"theme":"light","accent":"green","background":"gradient"}"""),
        )
        assertEquals(AppearancePrefs(ThemeMode.System, AccentKey.Pink), n("""{"theme":"system","accent":"pink","background":"noise"}"""))
        assertEquals(AppearancePrefs(ThemeMode.Dark, AccentKey.Teal), n("""{"theme":"dark","accent":"teal","background":"borealis"}"""))
        assertEquals(AppearancePrefs(ThemeMode.Light, AccentKey.Orange), n("""{"theme":"light","accent":"orange","background":"northern"}"""))
    }

    @Test
    fun survivingBackgroundsAreAccepted() {
        for (key in listOf("mesh", "flat", "aurora")) {
            assertEquals(key, n("""{"theme":"dark","accent":"blue","background":"$key"}""").background.key)
        }
    }

    @Test
    fun effectiveTheme() {
        assertTrue(ThemeMode.Dark.isDark(true))
        assertTrue(ThemeMode.Dark.isDark(false))
        assertFalse(ThemeMode.Light.isDark(true))
        assertFalse(ThemeMode.Light.isDark(false))
        assertTrue(ThemeMode.System.isDark(true))
        assertFalse(ThemeMode.System.isDark(false))
    }

    @Test
    fun registriesCoverEveryKeyOnce() {
        assertEquals(
            listOf("blue", "cyan", "green", "indigo", "orange", "pink", "purple", "red", "teal"),
            AccentPresets.map { it.key.key }.sorted(),
        )
        assertEquals(listOf("Grid", "Solid", "Aurora"), BackgroundKey.entries.map { it.label })
        for (p in AccentPresets) assertNotEquals(p.dark, p.light)
    }

    @Test
    fun roundTripsThroughJson() {
        val prefs = AppearancePrefs(ThemeMode.System, AccentKey.Cyan, BackgroundKey.Mesh, TimeFormat.H12)
        assertEquals(prefs, AppearancePrefs.normalize(prefs.toJson()))
    }
}
