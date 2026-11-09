package dev.southstar21.vectra.features.shell

import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.rememberScrollState
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
import dev.southstar21.vectra.core.designsystem.components.PageScrollInfo
import dev.southstar21.vectra.core.designsystem.components.LocalPageScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onPlaced
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
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

/**
 * A tab page: a scrolling column that starts below the status bar and clears the floating
 * navbar. Re-selecting the tab scrolls it back to the top.
 */
@Composable
fun VectraPage(
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    spacing: Int = 16,
    /** Enables pull-to-refresh; the indicator spins until this returns. */
    onRefresh: (suspend () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shell = LocalShellState.current
    val active = LocalPageActive.current
    LaunchedEffect(scroll, active) {
        if (active) snapshotFlow { scroll.value > 10 }.collect { shell.scrolled = it }
    }
    LaunchedEffect(scroll, active) {
        if (!active) return@LaunchedEffect
        try {
            snapshotFlow { scroll.isScrollInProgress }.collect { shell.scrolling = it }
        } finally {
            shell.scrolling = false
        }
    }
    // Leaving the tab resets the page to its top (and its pile unstacked) for the next visit.
    val selected = LocalTabSelected.current
    LaunchedEffect(scroll, selected) { if (!selected) scroll.scrollTo(0) }
    val reselections = LocalNavigator.current.reselections
    LaunchedEffect(reselections) { if (active && reselections > 0) scroll.animateScrollTo(0) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    val pageScroll = remember(scroll) { PageScrollInfo(scroll) }
    pageScroll.topPad = with(density) { (top + 16.dp).toPx() }
    pageScroll.bottomReserve = with(density) { (bottom + 104.dp).toPx() }
    val page = @Composable {
        Column(
            modifier
                .fillMaxSize()
                .onPlaced { pageScroll.viewport = it }
                .verticalScroll(scroll)
                .padding(start = 12.dp, end = 12.dp, top = top + 16.dp, bottom = bottom + 104.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.dp),
            content = content,
        )
    }
    CompositionLocalProvider(LocalPageScroll provides pageScroll) {
        if (onRefresh != null) PullToRefresh(onRefresh) { page() } else page()
    }
}
