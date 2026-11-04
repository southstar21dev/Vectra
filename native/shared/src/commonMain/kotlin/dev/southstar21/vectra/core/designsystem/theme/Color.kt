package dev.southstar21.vectra.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.southstar21.vectra.core.preferences.AccentKey

/** A colour from 0–255 channels and a 0–1 alpha. */
internal fun rgba(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a * 255f + 0.5f).toInt())

/** This colour at [percent]% of its opacity (mixed with transparent). */
fun Color.mixTransparent(percent: Float): Color = copy(alpha = alpha * percent / 100f)

/** [percent]% of this colour mixed with [other] in sRGB. */
fun Color.mix(percent: Float, other: Color): Color = lerp(other, this, percent / 100f)

/** One feature-chip tint: background wash, hairline and ink. */
@Immutable
data class Tint(val bg: Color, val border: Color, val text: Color)

@Immutable
data class Tints(
    val blue: Tint,
    val purple: Tint,
    val violet: Tint,
    val amber: Tint,
    val red: Tint,
    val sky: Tint,
    val emerald: Tint,
    val green: Tint,
)

/** One shadow layer (drop or [inset]); offsets, blur and spread are in dp. */
@Immutable
data class BoxShadow(
    val x: Float = 0f,
    val y: Float = 0f,
    val blur: Float = 0f,
    val spread: Float = 0f,
    val color: Color,
    val inset: Boolean = false,
)

@Immutable
data class AccentPreset(val key: AccentKey, val name: String, val dark: Color, val light: Color)

val AccentPresets: List<AccentPreset> = listOf(
    AccentPreset(AccentKey.Blue, "Sky Blue", Color(0xFF38BDF8), Color(0xFF0284C7)),
    AccentPreset(AccentKey.Indigo, "Indigo", Color(0xFF818CF8), Color(0xFF4F46E5)),
    AccentPreset(AccentKey.Purple, "Violet", Color(0xFFA855F7), Color(0xFF7C3AED)),
    AccentPreset(AccentKey.Pink, "Rose", Color(0xFFFB7185), Color(0xFFDB2777)),
    AccentPreset(AccentKey.Red, "Coral Red", Color(0xFFF43F5E), Color(0xFFE11D48)),
    AccentPreset(AccentKey.Orange, "Amber", Color(0xFFF59E0B), Color(0xFFD97706)),
    AccentPreset(AccentKey.Green, "Emerald", Color(0xFF10B981), Color(0xFF059669)),
    AccentPreset(AccentKey.Teal, "Teal", Color(0xFF14B8A6), Color(0xFF0D9488)),
    AccentPreset(AccentKey.Cyan, "Cyan", Color(0xFF06B6D4), Color(0xFF0891B2)),
)

/** Runtime theme tokens, with a dark and a light set. */
@Immutable
data class VectraColors(
    val isDark: Boolean,
    val surfacePrimary: Color,
    val surfaceSecondary: Color,
    val surfaceTertiary: Color,
    val surfaceOverlay: Color,
    val borderSubtle: Color,
    val borderPrimary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val dialogScrim: Color,
    val meshLine: Color,
    val glowPrimary: Color,
    val glowSecondary: Color,
    val glassBg: Color,
    val glassBgElevated: Color,
    val glassBorder: Color,
    val glassBorderSpecular: Color,
    val glassShadow: List<BoxShadow>,
    val glassShadowElevated: List<BoxShadow>,
    val mapSurface: Color,
    val mapControlBg: Color,
    val mapAttributionBg: Color,
    val mapLineCasing: Color,
    val mapLineCasingSecondary: Color,
    val accentPrimary: Color,
    val tints: Tints,
) {
    val success get() = Success
    val warning get() = Warning
    val danger get() = Danger

    companion object {
        val AccentSecondary = Color(0xFFA855F7)
        val Success = Color(0xFF22C55E)
        val Warning = Color(0xFFF59E0B)
        val Danger = Color(0xFFEF4444)

        fun dark(accent: AccentKey) = VectraColors(
            isDark = true,
            surfacePrimary = Color(0xFF07080A),
            surfaceSecondary = Color(0xFF0F1015),
            surfaceTertiary = Color(0xFF161720),
            surfaceOverlay = rgba(255, 255, 255, 0.035f),
            borderSubtle = rgba(255, 255, 255, 0.08f),
            borderPrimary = rgba(255, 255, 255, 0.14f),
            textPrimary = Color(0xFFF8FAFC),
            textSecondary = Color(0xFF94A3B8),
            textMuted = Color(0xFF7C8AA0),
            dialogScrim = rgba(0, 0, 0, 0.85f),
            meshLine = rgba(255, 255, 255, 0.03f),
            glowPrimary = rgba(56, 189, 248, 0.12f),
            glowSecondary = rgba(168, 85, 247, 0.10f),
            glassBg = rgba(15, 16, 21, 0.88f),
            glassBgElevated = rgba(18, 19, 26, 0.98f),
            glassBorder = rgba(255, 255, 255, 0.08f),
            glassBorderSpecular = rgba(255, 255, 255, 0.22f),
            glassShadow = listOf(
                BoxShadow(y = 16f, blur = 36f, spread = -8f, color = rgba(0, 0, 0, 0.5f)),
                BoxShadow(y = 1f, color = rgba(255, 255, 255, 0.1f), inset = true),
            ),
            glassShadowElevated = listOf(
                BoxShadow(y = 24f, blur = 48f, spread = -12f, color = rgba(0, 0, 0, 0.7f)),
                BoxShadow(y = 1f, color = rgba(255, 255, 255, 0.12f), inset = true),
            ),
            mapSurface = Color(0xFF101014),
            mapControlBg = Color(0xFF121215),
            mapAttributionBg = rgba(9, 9, 11, 0.7f),
            mapLineCasing = Color(0xFF223A5E),
            mapLineCasingSecondary = Color(0xFF1F2C45),
            accentPrimary = AccentPresets.first { it.key == accent }.dark,
            tints = Tints(
                blue = Tint(rgba(56, 189, 248, 0.15f), rgba(56, 189, 248, 0.3f), Color(0xFF38BDF8)),
                purple = Tint(rgba(168, 85, 247, 0.15f), rgba(168, 85, 247, 0.3f), Color(0xFFC084FC)),
                violet = Tint(rgba(139, 92, 246, 0.15f), rgba(139, 92, 246, 0.35f), Color(0xFFC4B5FD)),
                amber = Tint(rgba(245, 158, 11, 0.15f), rgba(245, 158, 11, 0.3f), Color(0xFFFBBF24)),
                red = Tint(rgba(239, 68, 68, 0.15f), rgba(239, 68, 68, 0.3f), Color(0xFFF87171)),
                sky = Tint(rgba(14, 165, 233, 0.15f), rgba(14, 165, 233, 0.3f), Color(0xFF38BDF8)),
                emerald = Tint(rgba(16, 185, 129, 0.15f), rgba(16, 185, 129, 0.3f), Color(0xFF34D399)),
                green = Tint(rgba(34, 197, 94, 0.15f), rgba(34, 197, 94, 0.3f), Color(0xFF4ADE80)),
            ),
        )

        fun light(accent: AccentKey) = VectraColors(
            isDark = false,
            surfacePrimary = Color(0xFFF8FAFC),
            surfaceSecondary = Color(0xFFFFFFFF),
            surfaceTertiary = Color(0xFFF1F5F9),
            surfaceOverlay = rgba(0, 0, 0, 0.03f),
            borderSubtle = rgba(0, 0, 0, 0.08f),
            borderPrimary = rgba(0, 0, 0, 0.12f),
            textPrimary = Color(0xFF0F172A),
            textSecondary = Color(0xFF334155),
            textMuted = Color(0xFF64748B),
            dialogScrim = rgba(0, 0, 0, 0.45f),
            meshLine = rgba(0, 0, 0, 0.05f),
            glowPrimary = rgba(37, 99, 235, 0.08f),
            glowSecondary = rgba(124, 58, 237, 0.06f),
            glassBg = rgba(255, 255, 255, 0.95f),
            glassBgElevated = rgba(255, 255, 255, 0.98f),
            glassBorder = Color.Transparent,
            glassBorderSpecular = rgba(255, 255, 255, 0.80f),
            glassShadow = listOf(
                BoxShadow(blur = 16f, spread = -4f, color = rgba(15, 23, 42, 0.12f)),
                BoxShadow(y = 1f, blur = 2f, color = rgba(15, 23, 42, 0.05f)),
                BoxShadow(y = 8f, blur = 20f, spread = -6f, color = rgba(15, 23, 42, 0.14f)),
                BoxShadow(y = 1f, color = rgba(255, 255, 255, 0.9f), inset = true),
            ),
            glassShadowElevated = listOf(
                BoxShadow(blur = 24f, spread = -8f, color = rgba(15, 23, 42, 0.14f)),
                BoxShadow(y = 2f, blur = 4f, color = rgba(15, 23, 42, 0.07f)),
                BoxShadow(y = 18f, blur = 36f, spread = -12f, color = rgba(15, 23, 42, 0.2f)),
                BoxShadow(y = 1f, color = rgba(255, 255, 255, 0.95f), inset = true),
            ),
            mapSurface = Color(0xFFE9E9EE),
            mapControlBg = Color(0xFFFFFFFF),
            mapAttributionBg = rgba(255, 255, 255, 0.85f),
            mapLineCasing = rgba(105, 112, 130, 0.9f),
            mapLineCasingSecondary = rgba(105, 112, 130, 0.6f),
            accentPrimary = AccentPresets.first { it.key == accent }.light,
            tints = Tints(
                blue = Tint(rgba(59, 130, 246, 0.12f), rgba(59, 130, 246, 0.3f), Color(0xFF0284C7)),
                purple = Tint(rgba(147, 51, 234, 0.12f), rgba(147, 51, 234, 0.3f), Color(0xFF7C3AED)),
                violet = Tint(rgba(109, 40, 217, 0.1f), rgba(109, 40, 217, 0.28f), Color(0xFF6D28D9)),
                amber = Tint(rgba(217, 119, 6, 0.12f), rgba(217, 119, 6, 0.3f), Color(0xFFB45309)),
                red = Tint(rgba(220, 38, 38, 0.12f), rgba(220, 38, 38, 0.3f), Color(0xFFDC2626)),
                sky = Tint(rgba(2, 132, 199, 0.12f), rgba(2, 132, 199, 0.3f), Color(0xFF0284C7)),
                emerald = Tint(rgba(5, 150, 105, 0.12f), rgba(5, 150, 105, 0.3f), Color(0xFF059669)),
                green = Tint(rgba(22, 163, 74, 0.12f), rgba(22, 163, 74, 0.3f), Color(0xFF16A34A)),
            ),
        )
    }
}

/** Fixed hues used outside the themed tokens. */
object Palette {
    val White = Color.White
    val Black = Color.Black
    val Slate300 = Color(0xFFCBD5E1)
    val Slate400 = Color(0xFF94A3B8)
    val Slate500 = Color(0xFF64748B)
    val Slate600 = Color(0xFF475569)
    val Slate700 = Color(0xFF334155)
    val Slate900 = Color(0xFF0F172A)
    val Zinc200 = Color(0xFFE4E4E7)
    val Zinc300 = Color(0xFFD4D4D8)
    val Zinc400 = Color(0xFFA1A1AA)
    val Zinc500 = Color(0xFF71717A)
    val Zinc600 = Color(0xFF52525B)
    val Zinc700 = Color(0xFF3F3F46)
    val Zinc800 = Color(0xFF27272A)
    val Zinc900 = Color(0xFF18181B)
    val Blue100 = Color(0xFFDBEAFE)
    val Blue300 = Color(0xFF93C5FD)
    val Blue400 = Color(0xFF60A5FA)
    val Blue500 = Color(0xFF3B82F6)
    val Blue600 = Color(0xFF2563EB)
    val Blue950 = Color(0xFF172554)
    val Indigo300 = Color(0xFFA5B4FC)
    val Indigo400 = Color(0xFF818CF8)
    val Indigo500 = Color(0xFF6366F1)
    val Violet400 = Color(0xFFA78BFA)
    val Violet500 = Color(0xFF8B5CF6)
    val Violet600 = Color(0xFF7C3AED)
    val Purple500 = Color(0xFFA855F7)
    val Pink400 = Color(0xFFF472B6)
    val Pink500 = Color(0xFFEC4899)
    val Rose500 = Color(0xFFF43F5E)
    val Red400 = Color(0xFFF87171)
    val Red500 = Color(0xFFEF4444)
    val Red600 = Color(0xFFDC2626)
    val Orange500 = Color(0xFFF97316)
    val Amber300 = Color(0xFFFCD34D)
    val Amber400 = Color(0xFFFBBF24)
    val Amber500 = Color(0xFFF59E0B)
    val Amber700 = Color(0xFFB45309)
    val Emerald400 = Color(0xFF34D399)
    val Emerald500 = Color(0xFF10B981)
    val Emerald600 = Color(0xFF059669)
    val Sky400 = Color(0xFF38BDF8)
    val Sky500 = Color(0xFF0EA5E9)
}
