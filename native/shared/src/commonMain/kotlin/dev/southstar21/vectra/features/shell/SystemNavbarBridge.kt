package dev.southstar21.vectra.features.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import dev.southstar21.vectra.core.designsystem.components.LocalAppPresented
import dev.southstar21.vectra.core.designsystem.components.LocalOverlayHost
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.features.navbar.NavbarActions
import dev.southstar21.vectra.features.navbar.SystemNavbar
import dev.southstar21.vectra.features.navbar.SystemNavbarAction
import dev.southstar21.vectra.features.navbar.SystemNavbarState
import dev.southstar21.vectra.navigation.ActionRoutes
import dev.southstar21.vectra.navigation.AppNavigator
import dev.southstar21.vectra.navigation.PrimaryNavItems

/**
 * Drives a platform tab bar from the shell: the selected tab, the + actions of the page on
 * screen, the theme, and hiding it under dialogs/sheets (the platform bar sits above the
 * whole Compose canvas, so overlays can't cover it themselves).
 */
@Composable
internal fun SystemNavbarBridge(bar: SystemNavbar, navigator: AppNavigator, actions: NavbarActions, coverFraction: () -> Float) {
    val c = Vectra.colors
    val overlays = LocalOverlayHost.current
    val presented = LocalAppPresented.current
    val current = navigator.current
    val config = actions.config
    val state = SystemNavbarState(
        selectedIndex = PrimaryNavItems.indexOfFirst { it.route == current }.coerceAtLeast(0),
        actions = if (current in ActionRoutes && config != null) {
            config.actions.map { SystemNavbarAction(it.id, it.label, it.disabled) }
        } else {
            emptyList()
        },
        hidden = !presented || overlays.entries.isNotEmpty(),
        dark = c.isDark,
        accentArgb = c.accentPrimary.toArgb(),
    )
    SideEffect { bar.update(state) }

    val latestCover by rememberUpdatedState(coverFraction)
    LaunchedEffect(bar) { snapshotFlow { latestCover() }.collect { bar.setCover(it) } }

    DisposableEffect(bar, navigator, actions) {
        bar.onSelect = { index -> PrimaryNavItems.getOrNull(index)?.let { navigator.navigate(it.route) } }
        bar.onAction = { id -> actions.execute(id) }
        onDispose {
            bar.onSelect = null
            bar.onAction = null
            bar.detach()
        }
    }
}
