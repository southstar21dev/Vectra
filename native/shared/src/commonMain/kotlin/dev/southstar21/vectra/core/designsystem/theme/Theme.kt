package dev.southstar21.vectra.core.designsystem.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import dev.southstar21.vectra.core.platform.SystemBarsAppearance
import dev.southstar21.vectra.core.preferences.AppearancePrefs
import dev.southstar21.vectra.core.preferences.isDark

val LocalVectraColors = staticCompositionLocalOf { VectraColors.dark(AppearancePrefs.Default.accent) }
val LocalVectraFonts = staticCompositionLocalOf<VectraFonts> { error("VectraTheme missing") }
val LocalAppearance = compositionLocalOf { AppearancePrefs.Default }
val LocalReduceMotion = staticCompositionLocalOf { false }
val LocalContentColor = compositionLocalOf { Color.Unspecified }
val LocalTextStyle = compositionLocalOf { VType.Body }

object Vectra {
    val colors: VectraColors
        @Composable @ReadOnlyComposable get() = LocalVectraColors.current
    val fonts: VectraFonts
        @Composable @ReadOnlyComposable get() = LocalVectraFonts.current
    val appearance: AppearancePrefs
        @Composable @ReadOnlyComposable get() = LocalAppearance.current
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
    val haptics: VectraHaptics
        @Composable @ReadOnlyComposable get() = LocalVectraHaptics.current
}

/** Tint laid over a pressed element ([PressHighlight]). */
val VectraColors.pressOverlay: Color
    get() = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

@Composable
fun VectraTheme(
    appearance: AppearancePrefs,
    systemDark: Boolean,
    reduceMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = appearance.theme.isDark(systemDark)
    val colors = remember(dark, appearance.accent) {
        if (dark) VectraColors.dark(appearance.accent) else VectraColors.light(appearance.accent)
    }
    val fonts = rememberVectraFonts()
    val hapticFeedback = LocalHapticFeedback.current
    val haptics = remember(hapticFeedback) { VectraHaptics(hapticFeedback) }
    SystemBarsAppearance(darkTheme = dark)
    CompositionLocalProvider(
        LocalVectraColors provides colors,
        LocalVectraFonts provides fonts,
        LocalAppearance provides appearance,
        LocalReduceMotion provides reduceMotion,
        LocalContentColor provides colors.textPrimary,
        LocalTextStyle provides VType.Body,
        LocalVectraHaptics provides haptics,
        LocalIndication provides PressHighlight(colors.pressOverlay),
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = colors.accentPrimary,
            backgroundColor = colors.accentPrimary.copy(alpha = 0.3f),
        ),
        content = content,
    )
}

/** Resolve the mono marker and defaults into a concrete style. */
@Composable
@ReadOnlyComposable
internal fun TextStyle.resolve(): TextStyle {
    val fonts = LocalVectraFonts.current
    return copy(fontFamily = if (fontFamily == MonoMarker) fonts.mono else fontFamily ?: fonts.sans)
}
