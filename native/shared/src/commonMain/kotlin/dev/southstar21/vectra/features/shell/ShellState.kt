package dev.southstar21.vectra.features.shell

import androidx.compose.foundation.ScrollState
import dev.southstar21.vectra.core.designsystem.theme.consumeTouches
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.components.navFadeBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import dev.southstar21.vectra.core.designsystem.theme.LocalWideLayout
import dev.southstar21.vectra.core.designsystem.theme.WideColumnGap
import dev.southstar21.vectra.features.navbar.platformNavbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.southstar21.vectra.core.designsystem.theme.ContentMaxWidth
import dev.southstar21.vectra.core.designsystem.theme.horizontalSafeArea
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.LocalPageActive
import dev.southstar21.vectra.core.designsystem.components.PullToRefresh
import dev.southstar21.vectra.navigation.LocalNavigator

/** Shell-wide UI state pages report into (the scrolled flag drives the top fade). */
@Stable
class ShellState {
    var scrolled by mutableStateOf(false)

    /** The page on screen is being dragged or flung: heavy background work waits. */
    var scrolling by mutableStateOf(false)
}

val LocalShellState = staticCompositionLocalOf { ShellState() }

/**
 * Whether this page's tab is the selected one. Unlike LocalPageActive it stays true under a pushed
 * screen, so a page can reset itself when the user moves to another tab but not on a quick push and pop.
 */
val LocalTabSelected = compositionLocalOf { true }

/** Which side of a wide page its fixed pane sits on. */
enum class PaneSide { Start, End }

/**
 * A panel that stays put beside a wide page's scrolling column (the transit map, the class
 * beside the day list). [weight] is its share of the page's width.
 */
class PagePane(val side: PaneSide, val weight: Float, val content: @Composable BoxScope.() -> Unit)

/**
 * A tab page: a scrolling column that starts below the status bar and clears the floating
 * navbar. Re-selecting the tab scrolls it back to the top. On a wide screen ([LocalWideLayout])
 * a page can grow to [maxWidth] and keep a [pane] in place beside the scrolling column.
 */
@Composable
fun VectraPage(
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    spacing: Int = 16,
    /** Widest the content grows; a two-column page passes `WideContentMaxWidth`. */
    maxWidth: Dp = ContentMaxWidth,
    /** A panel beside the scrolling column that doesn't scroll with it. */
    pane: PagePane? = null,
    /** Enables pull-to-refresh; the indicator spins until this returns. */
    onRefresh: (suspend () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // With a pane the shell's full-width top fade would cover the pane too; the column fades its own top.
    PageScrollEffects(scroll, reportScrolled = pane == null)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp
    val bottom = pageBottomReserve()
    // Side cutouts, or the sensor housing and side tab bar of an unfolded iPhone Duo.
    val sides = horizontalSafeArea()
    val dir = LocalLayoutDirection.current
    val start = sides.calculateStartPadding(dir) + 12.dp
    val end = sides.calculateEndPadding(dir) + 12.dp
    val column: @Composable ColumnScope.() -> Unit = {
        Column(
            Modifier.widthIn(max = maxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.dp),
            content = content,
        )
    }
    val page = @Composable {
        if (pane == null) {
            Column(
                modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(start = start, end = end, top = top, bottom = bottom),
                // On wide screens the column stops growing and sits centred.
                horizontalAlignment = Alignment.CenterHorizontally,
                content = column,
            )
        } else {
            PanedPage(pane, PaddingValues(start = start, end = end, top = top, bottom = bottom), maxWidth, { scroll.value > 10 }, modifier) { inner ->
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(inner), content = column)
            }
        }
    }
    if (onRefresh != null) PullToRefresh(onRefresh) { page() } else page()
}

/** Reports the page's scroll to the shell, and sends it back to the top on leaving or reselecting the tab. */
@Composable
private fun PageScrollEffects(scroll: ScrollState, reportScrolled: Boolean) {
    val shell = LocalShellState.current
    val active = LocalPageActive.current
    LaunchedEffect(scroll, active, reportScrolled) {
        if (!active) return@LaunchedEffect
        if (reportScrolled) snapshotFlow { scroll.value > 10 }.collect { shell.scrolled = it } else shell.scrolled = false
    }
    LaunchedEffect(scroll, active) {
        if (!active) return@LaunchedEffect
        try {
            snapshotFlow { scroll.isScrollInProgress }.collect { shell.scrolling = it }
        } finally {
            shell.scrolling = false
        }
    }
    // Leaving the tab resets the page to its top for the next visit.
    val selected = LocalTabSelected.current
    LaunchedEffect(scroll, selected) { if (!selected) scroll.scrollTo(0) }
    val reselections = LocalNavigator.current.reselections
    LaunchedEffect(reselections) { if (active && reselections > 0) scroll.animateScrollTo(0) }
}

/**
 * A wide page: [pane] fixed on its side, the scrolling [body] beside it. The body runs the full
 * width under the pane (its content padded clear of it), so the top fade, which dissolves the
 * content itself, has no edge of its own and cuts no card shadows; the pane sits on top.
 */
@Composable
private fun PanedPage(
    pane: PagePane,
    edges: PaddingValues,
    maxWidth: Dp,
    scrolled: () -> Boolean,
    modifier: Modifier = Modifier,
    body: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    val dir = LocalLayoutDirection.current
    val latest by rememberUpdatedState(scrolled)
    val isScrolled by remember { derivedStateOf { latest() } }
    val fade = animateFloatAsState(if (isScrolled) 1f else 0f, tween(300), label = "paneColumnFade")
    val top = edges.calculateTopPadding()
    BoxWithConstraints(modifier.fillMaxSize()) {
        // The two columns stop growing at maxWidth and sit centred in the room left.
        val room = this.maxWidth - edges.calculateStartPadding(dir) - edges.calculateEndPadding(dir)
        val extra = (room - minOf(room, maxWidth)) / 2
        val start = edges.calculateStartPadding(dir) + extra
        val end = edges.calculateEndPadding(dir) + extra
        val paneWidth = (room - extra * 2 - WideColumnGap) * pane.weight
        val beside = paneWidth + WideColumnGap
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = if (fade.value > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
                .drawWithContent {
                    drawContent()
                    val f = fade.value
                    if (f > 0f) {
                        // Gone under the status bar, then back to full over 48dp below it.
                        val bar = top.toPx() - 16.dp.toPx()
                        val h = bar + 48.dp.toPx()
                        val gone = Color.Black.copy(alpha = 1f - f)
                        drawRect(
                            Brush.verticalGradient(0f to gone, (bar / h).coerceIn(0f, 1f) to gone, 1f to Color.Black, startY = 0f, endY = h),
                            size = Size(size.width, h),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
        ) {
            body(
                PaddingValues(
                    start = if (pane.side == PaneSide.Start) start + beside else start,
                    end = if (pane.side == PaneSide.End) end + beside else end,
                    top = top,
                    bottom = edges.calculateBottomPadding(),
                ),
            )
        }
        Box(
            Modifier
                .align(if (pane.side == PaneSide.Start) Alignment.TopStart else Alignment.TopEnd)
                .padding(start = if (pane.side == PaneSide.Start) start else 0.dp, end = if (pane.side == PaneSide.End) end else 0.dp)
                .padding(top = top, bottom = edges.calculateBottomPadding())
                .width(paneWidth)
                .fillMaxHeight()
                // The body runs underneath: drags on the pane's empty parts mustn't scroll it.
                .consumeTouches(),
            content = pane.content,
        )
    }
}

/**
 * Room a page leaves under its content: clear of the floating navbar, or, where the system tab
 * bar runs down the side (iOS on an unfolded iPhone Duo; the app is iPhone-only, so a wide iOS
 * window is always that), only the home indicator.
 */
@Composable
internal fun pageBottomReserve(): Dp {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return if (navbarAtSide()) bottom + 24.dp else bottom + 104.dp
}

/** True while the system tab bar sits beside the pages instead of under them. */
@Composable
internal fun navbarAtSide(): Boolean = LocalWideLayout.current && platformNavbar != null
