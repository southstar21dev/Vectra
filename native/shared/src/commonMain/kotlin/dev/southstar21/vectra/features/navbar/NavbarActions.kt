package dev.southstar21.vectra.features.navbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import dev.southstar21.vectra.core.designsystem.components.LocalPageActive

/** Colour of a navbar FAB action. */
enum class NavbarActionVariant { Primary, Emerald, Blue, Gray, Secondary }

data class NavbarAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val variant: NavbarActionVariant = NavbarActionVariant.Primary,
    val disabled: Boolean = false,
    val onClick: () -> Unit,
)

data class NavbarActionConfig(val icon: ImageVector? = null, val actions: List<NavbarAction>)

@Stable
class NavbarActions {
    var config by mutableStateOf<NavbarActionConfig?>(null)
        internal set

    /** Latest click handlers, refreshed every composition without re-registering. */
    internal val handlers = mutableMapOf<String, () -> Unit>()

    fun execute(id: String) {
        handlers[id]?.invoke()
    }
}

val LocalNavbarActions = staticCompositionLocalOf { NavbarActions() }

/**
 * A page registers its FAB actions while it is the page on screen. Tabs stay
 * composed while hidden, so registering whenever composed let whichever tab composed last own the +.
 */
@Composable
fun RegisterNavbarActions(config: NavbarActionConfig) {
    val actions = LocalNavbarActions.current
    val active = LocalPageActive.current
    SideEffect { if (active) config.actions.forEach { actions.handlers[it.id] = it.onClick } }
    // Structural change detection: only re-register when what the FAB shows changes.
    val structure = config.actions.map { Triple(it.id, it.label, it.disabled) } to config.icon
    DisposableEffect(actions, structure, active) {
        if (!active) return@DisposableEffect onDispose {}
        actions.config = config
        onDispose {
            if (actions.config === config) actions.config = null
        }
    }
}
