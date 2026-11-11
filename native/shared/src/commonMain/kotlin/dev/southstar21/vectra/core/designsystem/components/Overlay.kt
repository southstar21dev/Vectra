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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * Root-level layer for dialogs, menus and sheets. Entries render above the page and the
 * navbar, in the order they opened.
 */
@Stable
class OverlayHost {
    internal class Entry(val id: Long, val modal: Boolean, val content: @Composable () -> Unit)

    internal val entries = mutableStateListOf<Entry>()
    private var nextId = 0L

    internal fun add(modal: Boolean = true, content: @Composable () -> Unit): Entry =
        Entry(nextId++, modal, content).also { entries.add(it) }
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
    // A modal overlay takes the page, and every overlay under it, out of the accessibility tree,
    // so a screen reader can't wander behind an open sheet or dialog. Toasts (topLayer) stay.
    val coveredBelow = topModalIndex(host.entries.map { it.modal })
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (coveredBelow >= 0) Modifier.clearAndSetSemantics { } else Modifier)) {
            content()
        }
        host.entries.forEachIndexed { i, entry ->
            key(entry.id) {
                Box(if (i < coveredBelow) Modifier.clearAndSetSemantics { } else Modifier) { entry.content() }
            }
        }
        topLayer()
    }
}

/** Index of the topmost modal layer in [modal] (bottom first), or -1: everything below it is covered. */
internal fun topModalIndex(modal: List<Boolean>): Int = modal.indexOfLast { it }

/**
 * Puts [content] into the overlay layer for as long as this call stays composed. A [modal]
 * overlay hides everything under it from screen readers.
 */
@Composable
fun Overlay(modal: Boolean = true, content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    val latest = rememberUpdatedState(content)
    DisposableEffect(host, modal) {
        val entry = host.add(modal) { latest.value() }
        onDispose { host.remove(entry) }
    }
}

/**
 * Screen-reader semantics for a modal surface's layer: a pane named [title] (announced as it
 * appears), read as one group, with a dismiss action while [open] standing in for the scrim
 * tap, the drag and the back gesture.
 */
fun Modifier.modalPane(title: String, open: Boolean, onDismiss: () -> Unit): Modifier = semantics {
    paneTitle = title
    isTraversalGroup = true
    if (open) dismiss("Close") { onDismiss(); true }
}

/**
 * A dismissing scrim as screen readers meet it: a "Close" button read after the surface's own
 * content (as Material's modal sheet does), so VoiceOver users without a back gesture can
 * always leave. Nothing while [enabled] is false (closing, or a scrim that doesn't dismiss).
 */
fun Modifier.scrimSemantics(enabled: Boolean, onDismiss: () -> Unit): Modifier =
    if (!enabled) this else semantics {
        contentDescription = "Close"
        traversalIndex = 1f
        onClick { onDismiss(); true }
    }

/** System back / predictive back gesture handler. */
@Composable
fun VBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val latest = rememberUpdatedState(onBack)
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(state = state, isBackEnabled = enabled, onBackCompleted = { latest.value() })
}
