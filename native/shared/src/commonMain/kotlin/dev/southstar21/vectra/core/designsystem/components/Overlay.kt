package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * Root-level layer for dialogs, menus and sheets. Entries render above the page and the
 * navbar, in the order they opened.
 */
@Stable
class OverlayHost {
    internal class Entry(val id: Long, val content: @Composable () -> Unit)

    internal val entries = mutableStateListOf<Entry>()
    private var nextId = 0L

    internal fun add(content: @Composable () -> Unit): Entry = Entry(nextId++, content).also { entries.add(it) }
    internal fun remove(entry: Entry) {
        entries.remove(entry)
    }
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost> { error("OverlayHost missing") }

/** Wraps the app and paints the overlay layer on top. */
@Composable
fun OverlayRoot(
    host: OverlayHost,
    modifier: Modifier = Modifier,
    /** Drawn above every overlay (toasts sit over dialogs). */
    topLayer: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        content()
        for (entry in host.entries) {
            key(entry.id) { entry.content() }
        }
        topLayer()
    }
}

/** Puts [content] into the overlay layer for as long as this call stays composed. */
@Composable
fun Overlay(content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    val latest = rememberUpdatedState(content)
    DisposableEffect(host) {
        val entry = host.add { latest.value() }
        onDispose { host.remove(entry) }
    }
}

/** System back / predictive back gesture handler. */
@Composable
fun VBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val latest = rememberUpdatedState(onBack)
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(state = state, isBackEnabled = enabled, onBackCompleted = { latest.value() })
}
