package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.LocalContentColor
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.VectraMotion
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.cardDepthShadows
import dev.southstar21.vectra.core.designsystem.theme.insetShadow
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import dev.southstar21.vectra.core.designsystem.theme.consumeTouches
import dev.southstar21.vectra.core.designsystem.theme.Radius

/**
 * Centred dialog for confirmations and alerts: a scrim and a card that pops in with a spring
 * (a short fade under reduced motion). Anything larger belongs on a pushed screen or a sheet.
 */
@Composable
fun VDialog(
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showClose: Boolean = true,
    scrollable: Boolean = true,
    maxWidth: Dp = 448.dp,
    dismissOnScrim: Boolean = true,
    /** Read out by screen readers as the dialog opens. */
    title: String? = null,
    onExited: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var mounted by remember { mutableStateOf(open) }
    if (open) mounted = true
    val reduceMotion = Vectra.reduceMotion
    val exited = rememberUpdatedState(onExited)
    val scrimAlpha = remember { Animatable(0f) }
    val panelAlpha = remember { Animatable(0f, DialogRestDelta) }
    val panelScale = remember { Animatable(1f, DialogRestDelta) }
    LaunchedEffect(open) {
        val spec: AnimationSpec<Float> = if (reduceMotion) VectraMotion.reducedFade() else VectraSprings.pop()
        val scrimSpec = tween<Float>(200, easing = VectraEasing.Fade)
        if (open) {
            // A fresh open starts small; reopening mid-exit springs back from where it is.
            if (panelAlpha.value == 0f) panelScale.snapTo(if (reduceMotion) 1f else 0.92f)
            coroutineScope {
                launch { scrimAlpha.animateTo(1f, scrimSpec) }
                launch { panelAlpha.animateTo(1f, spec) }
                launch { panelScale.animateTo(1f, spec) }
            }
        } else if (mounted) {
            coroutineScope {
                launch { scrimAlpha.animateTo(0f, scrimSpec) }
                launch { panelAlpha.animateTo(0f, spec) }
                launch { panelScale.animateTo(if (reduceMotion) 1f else 0.95f, spec) }
            }
            mounted = false
            exited.value?.invoke()
        }
    }
    if (!mounted) return

    Overlay {
        val c = Vectra.colors
        VBackHandler(enabled = open, onBack = onDismiss)
        val scrimDismisses = open && dismissOnScrim
        BoxWithConstraints(Modifier.fillMaxSize().modalPane(title ?: "Dialog", open, onDismiss)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrimAlpha.value }
                    .background(c.dialogScrim)
                    .consumeTouches(enabled = scrimDismisses) { onDismiss() }
                    .scrimSemantics(enabled = scrimDismisses, onDismiss = onDismiss),
            )
            val shape = Radius.Card
            Box(Modifier.fillMaxSize().padding(16.dp).imePadding(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .graphicsLayer {
                            alpha = panelAlpha.value.coerceIn(0f, 1f)
                            scaleX = panelScale.value; scaleY = panelScale.value
                        }
                        .widthIn(max = maxWidth)
                        .fillMaxWidth()
                        .heightIn(max = this@BoxWithConstraints.maxHeight * 0.9f)
                        .boxShadow(shape, cardDepthShadows(c.isDark))
                        .background(c.surfaceSecondary, shape)
                        .insetShadow(shape, cardDepthShadows(c.isDark))
                        .then(if (c.isDark) Modifier.border(1.dp, c.borderSubtle, shape) else Modifier)
                        .consumeTouches()
                        .then(modifier),
                ) {
                    Column(
                        Modifier
                            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .padding(20.dp),
                        content = content,
                    )
                    if (showClose) DialogCloseButton(onDismiss, Modifier.align(Alignment.TopEnd).padding(12.dp))
                }
            }
        }
    }
}

/** Opacity and scale settle within half a percent. */
private const val DialogRestDelta = 0.005f

@Composable
fun DialogCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    BarButton(RemixIcons.CloseLine, "Close", onClick, modifier)
}

@Composable
fun DialogHeader(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(top = 4.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
fun DialogTitle(text: String, modifier: Modifier = Modifier) =
    VText(text, modifier.semantics { heading() }, vtext(TypeScale.Body, Weight.Bold, lineHeight = 21.sp), Vectra.colors.textPrimary)

@Composable
fun DialogDescription(text: String, modifier: Modifier = Modifier) =
    VText(text, modifier.padding(top = 6.dp), vtext(TypeScale.Caption, lineHeight = 19.5.sp), Vectra.colors.textSecondary)

/** Buttons stacked full-width under the dialog text. */
@Composable
fun DialogFooter(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom), content = content)
}

/** Asks before a consequential action; [onConfirm] runs with the dialog showing progress. */
@Composable
fun ConfirmDialog(
    open: Boolean,
    onClose: () -> Unit,
    onConfirm: suspend () -> Unit,
    title: String,
    description: String,
    confirmText: String = "CONFIRM",
    cancelText: String = "CANCEL",
    destructive: Boolean = true,
    hideCancel: Boolean = false,
) {
    val c = Vectra.colors
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val close = { if (!pending) { error = null; onClose() } }
    VDialog(open, close, showClose = false, title = title) {
        DialogHeader(Modifier.padding(top = 4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (destructive) IconChip(RemixIcons.AlertLine, c.tints.red, size = 32, iconSize = 16)
                DialogTitle(title)
            }
            DialogDescription(description, Modifier.padding(top = 2.dp))
        }
        if (error != null) {
            val shape = Radius.Control
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth()
                    .background(Palette.Red500.copy(alpha = 0.1f), shape)
                    .border(1.dp, c.tints.red.border, shape).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VIcon(RemixIcons.ErrorWarningLine, tint = c.tints.red.text)
                VText(error!!, style = vtext(TypeScale.Caption), color = c.tints.red.text)
            }
        }
        DialogFooter(Modifier.padding(top = 16.dp)) {
            VButton(
                onClick = {
                    error = null
                    pending = true
                    scope.launchCatching(
                        block = { onConfirm(); pending = false; onClose() },
                        onError = { pending = false; error = it.message?.takeIf(String::isNotBlank) ?: "Something went wrong. Try again." },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                variant = if (destructive) ButtonVariant.Destructive else ButtonVariant.Default,
                size = ButtonSize.Sm,
                enabled = !pending,
            ) {
                if (pending) InlineLoader(tint = LocalContentColor.current)
                VText(confirmText)
            }
            if (!hideCancel) {
                VButton(close, Modifier.fillMaxWidth(), ButtonVariant.Ghost, ButtonSize.Sm, enabled = !pending) { VText(cancelText) }
            }
        }
    }
}

/** Launch [block]; report failures (not cancellation) to [onError]. */
internal fun CoroutineScope.launchCatching(block: suspend () -> Unit, onError: (Throwable) -> Unit) =
    launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e)
        }
    }
