package dev.southstar21.vectra.core.platform

import androidx.compose.runtime.Composable

/** Whether the OS asks apps to minimise motion (Android: animations off; iOS: Reduce Motion). */
@Composable
expect fun rememberSystemReduceMotion(): Boolean

/**
 * Tints the status and navigation bar icons for the app's resolved theme, not the system's.
 * [followSystem] is true when the user chose Mode = System, so a platform that pins the window to a
 * style (iOS) must leave it unpinned and keep tracking the OS.
 */
@Composable
expect fun SystemBarsAppearance(darkTheme: Boolean, followSystem: Boolean)
