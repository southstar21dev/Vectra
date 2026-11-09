package dev.southstar21.vectra.features.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import dev.southstar21.vectra.core.designsystem.components.LocalAppPresented
import dev.southstar21.vectra.core.designsystem.components.LocalMapLifecycleOwner
import dev.southstar21.vectra.core.designsystem.components.LocalPageActive
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.navigation.Route

/**
 * Tab pages stay composed after their first visit, so switching tabs never rebuilds a
 * page (or its maps) and every tab keeps its scroll position. A hidden page is neither
 * measured nor drawn, and its lifecycle drops to CREATED so lifecycle-aware collection
 * and polling stop. The current tab does the same once a pushed screen has [fullyCovered]
 * it for [CoverGraceMs], except for its maps, which stay alive so popping shows them at once.
 * Switching is a short cross-fade.
 */
@Composable
fun KeptAliveTabs(current: Route, routes: List<Route>, fullyCovered: Boolean, page: @Composable (Route) -> Unit) {
    val visited = remember { mutableStateListOf<Route>() }
    if (current !in visited) visited += current
    // Once the app has settled, compose the remaining tabs one per frame while they are
    // hidden (no layout, no drawing), so even a first visit is just a show. A tab is one
    // big frame, so each waits for the current page to stop scrolling.
    val presented = LocalAppPresented.current
    val shell = LocalShellState.current
    LaunchedEffect(presented) {
        if (!presented) return@LaunchedEffect
        delay(1500)
        for (route in routes) {
            if (route in visited) continue
            snapshotFlow { shell.scrolling }.first { !it }
            if (route in visited) continue
            visited += route
            withFrameNanos { }
            delay(250)
        }
    }
    val saveable = rememberSaveableStateHolder()
    val parent = LocalLifecycleOwner.current
    val reduceMotion = Vectra.reduceMotion
    // False while a pushed screen covers the shell: decorative loops pause.
    val shellVisible = LocalPageActive.current
    // A quick look at a pushed screen must come back to live departures, not stale ones.
    var longCovered by remember { mutableStateOf(false) }
    LaunchedEffect(fullyCovered) {
        if (fullyCovered) delay(CoverGraceMs)
        longCovered = fullyCovered
    }
    Box(Modifier.fillMaxSize()) {
        for (route in visited) key(route) {
            val active = route == current
            val owner = remember(parent) { PageLifecycleOwner(parent) }
            val mapOwner = remember(parent) { PageLifecycleOwner(parent) }
            DisposableEffect(owner) { onDispose { owner.dispose() } }
            DisposableEffect(mapOwner) { onDispose { mapOwner.dispose() } }
            SideEffect {
                owner.setActive(active && !longCovered)
                mapOwner.setActive(active)
            }
            val alpha = remember { Animatable(if (active) 1f else 0f) }
            LaunchedEffect(active) {
                if (!active) alpha.snapTo(0f)
                else if (reduceMotion) alpha.snapTo(1f)
                else alpha.animateTo(1f, tween(150, easing = VectraEasing.Fade))
            }
            Box(
                Modifier
                    .layout { measurable, constraints ->
                        if (!active) {
                            layout(constraints.maxWidth, constraints.maxHeight) {}
                        } else {
                            val p = measurable.measure(constraints)
                            layout(p.width, p.height) { p.place(0, 0) }
                        }
                    }
                    .graphicsLayer { this.alpha = alpha.value },
            ) {
                CompositionLocalProvider(
                    LocalPageActive provides (active && shellVisible),
                    LocalTabSelected provides active,
                    LocalLifecycleOwner provides owner,
                    LocalMapLifecycleOwner provides mapOwner,
                ) {
                    saveable.SaveableStateProvider(route.toString()) { page(route) }
                }
            }
        }
    }
}

private const val CoverGraceMs = 30_000L

/** A child lifecycle capped at the parent's state: RESUMED when shown, CREATED when hidden. */
private class PageLifecycleOwner(private val parent: LifecycleOwner) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private var active = false

    private val observer = LifecycleEventObserver { _, _ -> sync() }

    init {
        parent.lifecycle.addObserver(observer)
    }

    fun setActive(value: Boolean) {
        if (active == value && registry.currentState != Lifecycle.State.INITIALIZED) return
        active = value
        sync()
    }

    private fun sync() {
        val cap = parent.lifecycle.currentState
        val wanted = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        val target = if (wanted < cap) wanted else cap
        if (target != Lifecycle.State.DESTROYED && target != Lifecycle.State.INITIALIZED) registry.currentState = target
    }

    fun dispose() {
        parent.lifecycle.removeObserver(observer)
        if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED
    }
}
