package dev.southstar21.vectra.features.navbar

import androidx.compose.runtime.staticCompositionLocalOf

/** One + action as the platform bar shows it (labels only; the platform picks its own icons by [id]). */
data class SystemNavbarAction(val id: String, val label: String, val disabled: Boolean)

/** Everything a platform tab bar needs to draw itself. */
data class SystemNavbarState(
    val selectedIndex: Int,
    /** The + menu; empty hides the + button. */
    val actions: List<SystemNavbarAction>,
    /** True while a dialog/sheet is open or before the app has presented. */
    val hidden: Boolean,
    val dark: Boolean,
    /** Accent colour as 0xAARRGGBB. */
    val accentArgb: Int,
)

/**
 * A tab bar drawn by the platform in place of [LiquidNavbar] (iOS: the system UITabBarController,
 * so the bar is the real Liquid Glass one). Provided through [LocalSystemNavbar]; null means the
 * Compose navbar draws.
 */
interface SystemNavbar {
    fun update(state: SystemNavbarState)

    /** How far a pushed screen covers the tabs, 0..1, every frame of a push or pop. */
    fun setCover(fraction: Float)

    /** The tab shell left composition (signed out): remove the bar. */
    fun detach()

    var onSelect: ((Int) -> Unit)?
    var onAction: ((String) -> Unit)?
}

val LocalSystemNavbar = staticCompositionLocalOf<SystemNavbar?> { null }
