package dev.southstar21.vectra.core.platform

import androidx.compose.runtime.Composable

/** Whether the OS asks apps to minimise motion (Android: animations off; iOS: Reduce Motion). */
@Composable
expect fun rememberSystemReduceMotion(): Boolean

/** Tints the status and navigation bar icons for the app's resolved theme, not the system's. */
@Composable
expect fun SystemBarsAppearance(darkTheme: Boolean)
