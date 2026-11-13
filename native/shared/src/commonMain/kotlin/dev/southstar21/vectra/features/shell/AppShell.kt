package dev.southstar21.vectra.features.shell

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import dev.southstar21.vectra.navigation.opensAsCard
import dev.southstar21.vectra.core.designsystem.theme.consumeTouches
import dev.southstar21.vectra.core.designsystem.components.LocalScreenInCard
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.southstar21.vectra.core.designsystem.theme.LocalWideLayout
import dev.southstar21.vectra.core.designsystem.theme.WideLayoutMinWidth
import dev.southstar21.vectra.core.designsystem.theme.horizontalSafeArea
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.southstar21.vectra.core.designsystem.components.AppBackground
import dev.southstar21.vectra.core.designsystem.components.LocalPageActive
import dev.southstar21.vectra.core.designsystem.components.VBackHandler
import dev.southstar21.vectra.core.designsystem.components.navFadeBrush
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.features.navbar.LiquidNavbar
import dev.southstar21.vectra.features.navbar.platformNavbar
import dev.southstar21.vectra.features.navbar.LocalNavbarActions
import dev.southstar21.vectra.features.navbar.NavbarActions
import dev.southstar21.vectra.navigation.AppNavigator
import dev.southstar21.vectra.navigation.PrimaryRoutes
import dev.southstar21.vectra.navigation.Route
import dev.southstar21.vectra.navigation.Screen

/** How far the tab shell is covered by pushed screens: 0 showing, 1 fully covered. */
@Stable
private class ShellCover {
    var fraction by mutableFloatStateOf(0f)
}

private const val PushMs = 380
private const val ParallaxFraction = 0.28f

/**
 * The signed-in app: the tab shell (pages, status-bar and nav fades, floating Liquid Glass
 * navbar) with a stack of pushed screens over it. The shell stays composed underneath, so
 * going back is instant and every tab keeps its state; it drifts left as a screen covers it.
 */
@Composable
fun AppShell(navigator: AppNavigator, page: @Composable (Route) -> Unit, screen: @Composable (Screen) -> Unit) {
    val cover = remember { ShellCover() }
    val reduceMotion = Vectra.reduceMotion
    val fullyCovered by remember { derivedStateOf { cover.fraction >= 1f } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Two columns once the room inside the safe area (less the pages' gutters) allows it.
        val sides = horizontalSafeArea()
        val dir = LocalLayoutDirection.current
        val wide = maxWidth - sides.calculateStartPadding(dir) - sides.calculateEndPadding(dir) - 24.dp >= WideLayoutMinWidth
        // A card leaves the tabs showing, dimmed and still, around it. Judged by the screen right
        // above the tabs, and kept while it animates away (the stack has already dropped it).
        val lastCover = remember { booleanArrayOf(false) }
        navigator.screens.getOrNull(1)?.let { lastCover[0] = wide && it.opensAsCard }
        val cardOnTop = lastCover[0]
        CompositionLocalProvider(LocalWideLayout provides wide) {
            TabShell(
                navigator,
                page,
                Modifier.graphicsLayer {
                    val f = cover.fraction
                    // Fully covered: skip drawing the shell under an opaque screen.
                    alpha = if (f >= 1f && !cardOnTop) 0f else 1f
                    if (!reduceMotion && !cardOnTop) translationX = -size.width * ParallaxFraction * f
                },
                covered = navigator.screens.size > 1,
                fullyCovered = fullyCovered && !cardOnTop,
                coverFraction = { cover.fraction },
            )
            // Dim the shell as a screen slides over it.
            // Deeper behind a card, where the tabs stay in view around it.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover.fraction * (if (cardOnTop) 0.55f else 0.35f) }.background(Color.Black))

            NavDisplay(
                backStack = navigator.screens,
                onBack = { navigator.pop() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                transitionSpec = { push(reduceMotion) },
                popTransitionSpec = { pop(reduceMotion) },
                predictivePopTransitionSpec = { pop(reduceMotion) },
                entryProvider = { key ->
                    when (key) {
                        Screen.Tabs -> NavEntry(key) { ShellCoverTracker(cover, navigator) }
                        else -> NavEntry(key) {
                            ScreenFrame(card = wide && key.opensAsCard, onDismiss = { navigator.pop() }) { screen(key) }
                        }
                    }
                },
            )
        }
    }
}

/** Widest a pushed screen grows when it opens as a card on a wide window. */
private val CardScreenWidth = 640.dp

/**
 * A pushed screen: the whole window, or on a wide window ([card]) a rounded card in the middle
 * with the dimmed tabs around it (tap them to go back), like an iPad form sheet. One layout
 * either way, so folding or unfolding keeps the screen's state.
 */
@Composable
private fun ScreenFrame(card: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val sides = horizontalSafeArea()
    val dir = LocalLayoutDirection.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Clear of the floating navbar (half-covered tab labels look broken), like the pages are.
    val bottom = pageBottomReserve()
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxSize().then(
            if (!card) Modifier
            else Modifier.consumeTouches(onClick = onDismiss).padding(
                start = sides.calculateStartPadding(dir) + 16.dp,
                end = sides.calculateEndPadding(dir) + 16.dp,
                top = top + 16.dp,
                bottom = bottom,
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.then(if (card) Modifier.widthIn(max = CardScreenWidth).clip(shape).consumeTouches() else Modifier).fillMaxSize()) {
            AppBackground()
            CompositionLocalProvider(LocalScreenInCard provides card) { content() }
        }
    }
}

private fun <T : Any> AnimatedContentTransitionScope<Scene<T>>.push(reduceMotion: Boolean): ContentTransform =
    if (reduceMotion) fadeIn(tween(150)) togetherWith fadeOut(tween(150))
    else slideInHorizontally(spec()) { it } togetherWith slideOutHorizontally(spec()) { -(it * ParallaxFraction).toInt() }

private fun <T : Any> AnimatedContentTransitionScope<Scene<T>>.pop(reduceMotion: Boolean): ContentTransform =
    if (reduceMotion) fadeIn(tween(150)) togetherWith fadeOut(tween(150))
    else (slideInHorizontally(spec()) { -(it * ParallaxFraction).toInt() } togetherWith slideOutHorizontally(spec()) { it })
        .apply { targetContentZIndex = -1f }

private fun <T> spec(): FiniteAnimationSpec<T> = tween(PushMs, easing = VectraEasing.Sheet)

/**
 * The stack's root entry is transparent; the real shell sits below the display. The entry's
 * own enter/exit transition (which follows the predictive back gesture) drives the shell's
 * parallax and dimming.
 */
@Composable
private fun ShellCoverTracker(cover: ShellCover, navigator: AppNavigator) {
    val transition = LocalNavAnimatedContentScope.current.transition
    val covered = transition.animateFloat(
        transitionSpec = { tween(PushMs, easing = VectraEasing.Sheet) },
        label = "shellCover",
    ) { if (it == EnterExitState.Visible) 0f else 1f }
    LaunchedEffect(transition) { snapshotFlow { covered.value }.collect { cover.fraction = it } }
    DisposableEffect(Unit) { onDispose { cover.fraction = if (navigator.screens.size > 1) 1f else 0f } }
}

@Composable
private fun TabShell(
    navigator: AppNavigator,
    page: @Composable (Route) -> Unit,
    modifier: Modifier,
    covered: Boolean,
    fullyCovered: Boolean,
    coverFraction: () -> Float,
) {
    val c = Vectra.colors
    val shell = remember { ShellState() }
    val navbarActions = remember { NavbarActions() }
    // A platform bar (iOS's system tab bar) draws its own glass: no page backdrop to record.
    val systemNavbar = platformNavbar
    val backdrop = rememberLayerBackdrop()

    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalShellState provides shell, LocalNavbarActions provides navbarActions) {
            Box(Modifier.fillMaxSize()) {
                // The fades are recorded with the page, so the navbar glass refracts the faded edge.
                Box(Modifier.fillMaxSize().then(if (systemNavbar == null) Modifier.layerBackdrop(backdrop) else Modifier)) {
                    AppBackground()
                    CompositionLocalProvider(LocalPageActive provides !covered) {
                        KeptAliveTabs(navigator.current, PrimaryRoutes, fullyCovered, page)
                    }

                    // Top fade behind the status bar while scrolled.
                    val topAlpha by animateFloatAsState(if (shell.scrolled) 1f else 0f, tween(300), label = "topFade")
                    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    Box(
                        Modifier.fillMaxWidth().height(statusTop + 80.dp).graphicsLayer { alpha = topAlpha }
                            .background(navFadeBrush(c.surfacePrimary, toTop = false)),
                    )
                    // Bottom fade behind the navbar; just a soft edge where the bar sits at the side.
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (navbarAtSide()) 48.dp else 144.dp)
                            .background(navFadeBrush(c.surfacePrimary, toTop = true)),
                    )
                }

                // Back from a tab goes Home first, then leaves the app. Registered before the
                // navbar so its open actions menu takes back first.
                VBackHandler(enabled = !covered && navigator.backStack.size > 1) { navigator.back() }
                if (systemNavbar == null) {
                    LiquidNavbar(navigator.current, { navigator.navigate(it) }, backdrop)
                } else {
                    SystemNavbarBridge(systemNavbar, navigator, navbarActions, coverFraction)
                }
            }
        }
    }
}
