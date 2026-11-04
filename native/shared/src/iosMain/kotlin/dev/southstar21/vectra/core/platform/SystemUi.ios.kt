package dev.southstar21.vectra.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.southstar21.vectra.platform.keyWindow
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIUserInterfaceStyle

@Composable
actual fun rememberSystemReduceMotion(): Boolean {
    var reduce by remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }
    LifecycleResumeEffect(Unit) {
        reduce = UIAccessibilityIsReduceMotionEnabled()
        onPauseOrDispose { }
    }
    return reduce
}

/**
 * The window takes the app's resolved theme, so the status bar (default style) and UIKit
 * chrome such as the keyboard and system sheets match it rather than the system setting.
 */
@Composable
actual fun SystemBarsAppearance(darkTheme: Boolean) {
    SideEffect {
        keyWindow()?.overrideUserInterfaceStyle =
            if (darkTheme) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
    }
}
