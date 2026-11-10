package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.LocalContentColor
import dev.southstar21.vectra.core.designsystem.theme.LocalTextStyle
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * Chrome shared by pushed screens and sheets: a top bar (leading button, centred title,
 * trailing action), a scrolling body that fades content under its edges, and a docked
 * bottom bar. On an edge-to-edge surface ([LocalSurfaceEdgeToEdge]) the bars pad the
 * status and navigation bars themselves.
 */

/** Horizontal gutter of every screen and sheet section. */
val SurfaceGutter = 20.dp

enum class BarLeading { Close, Back }

/** Round icon button used in top bars. */
@Composable
fun BarButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .background(Vectra.colors.surfaceOverlay, CircleShape)
            .tappable(CircleShape, onClickLabel = contentDescription, pressedScale = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VIcon(icon, size = 20.dp, tint = Vectra.colors.textSecondary, contentDescription = contentDescription)
    }
}

@Composable
private fun edgeTop(): Dp =
    if (LocalSurfaceEdgeToEdge.current) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp else 8.dp

@Composable
private fun edgeBottom(): Dp =
    if (LocalSurfaceEdgeToEdge.current) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp else 12.dp

/**
 * Top bar. A back arrow sits on the left; a close X sits on the right, and any trailing
 * action then takes the left slot. The bar grows a hairline once the body scrolls.
 */
@Composable
fun TopBar(
    title: String,
    leading: BarLeading,
    onLeading: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** A second row under the title that stays pinned with the bar. */
    accessory: (@Composable () -> Unit)? = null,
    scrolled: Boolean = false,
    /** With a [BarLeading.Back] button, also show a close X on the right. */
    onClose: (() -> Unit)? = null,
    wrapTitle: Boolean = false,
    background: Color = Vectra.colors.surfaceSecondary,
    /** Replaces the plain [title] text (a route badge beside a name, say); [title] stays its label. */
    titleContent: (@Composable () -> Unit)? = null,
) {
    val c = Vectra.colors
    val border by animateColorAsState(if (scrolled) c.borderSubtle else Color.Transparent, tween(200, easing = VectraEasing.Fade), label = "barBorder")
    val closeOnRight = leading == BarLeading.Close
    val leadingButton: @Composable () -> Unit = {
        if (leading == BarLeading.Close) BarButton(RemixIcons.CloseLine, "Close", onLeading)
        else BarButton(RemixIcons.ArrowLeftLine, "Back", onLeading)
    }
    val rightSlot: (@Composable () -> Unit)? = when {
        closeOnRight -> leadingButton
        trailing != null -> trailing
        onClose != null -> { { BarButton(RemixIcons.CloseLine, "Close", onClose) } }
        else -> null
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind {
                val h = 1.dp.toPx()
                drawRect(border, Offset(0f, size.height - h), Size(size.width, h))
            }
            .padding(start = SurfaceGutter, end = SurfaceGutter, top = edgeTop(), bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.defaultMinSize(minWidth = 40.dp)) {
                if (closeOnRight) trailing?.invoke() else leadingButton()
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val titleStyle = vtext(TypeScale.BodyLarge, Weight.Extrabold, Tracking.Tight, lineHeight = if (wrapTitle) 22.sp else null)
                if (titleContent != null) {
                    Box(Modifier.semantics(mergeDescendants = true) { heading() }) {
                        CompositionLocalProvider(LocalContentColor provides c.textPrimary, LocalTextStyle provides titleStyle) { titleContent() }
                    }
                } else {
                    VText(
                        title,
                        style = titleStyle,
                        color = c.textPrimary,
                        align = TextAlign.Center,
                        maxLines = if (wrapTitle) Int.MAX_VALUE else 1,
                    )
                }
                if (subtitle != null) {
                    CompositionLocalProvider(LocalContentColor provides c.textSecondary, LocalTextStyle provides vtext(TypeScale.Caption)) { subtitle() }
                }
            }
            Box(Modifier.defaultMinSize(minWidth = 40.dp), contentAlignment = Alignment.CenterEnd) { rightSlot?.invoke() }
        }
        if (accessory != null) Box(Modifier.padding(top = 12.dp, bottom = 4.dp)) { accessory() }
    }
}

@Composable
fun TopBarSubtitle(text: String) {
    VText(text, style = vtext(TypeScale.Caption), color = Vectra.colors.textSecondary, maxLines = 1)
}

private val EdgeFade = 24.dp

/**
 * The scrolling body between the bars. Reports whether it has left the top, and fades
 * content under whichever edge it has scrolled past so cards dissolve instead of clipping.
 */
@Composable
fun ColumnScope.ScrollBody(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    onScrolledChange: ((Boolean) -> Unit)? = null,
    /** Fade content under the bottom edge. Off when a docked control sits there. */
    fadeBottom: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val edge = with(LocalDensity.current) { 2.dp.toPx() }
    val fades = rememberEdgeFades(
        pastTop = { scrollState.value > edge },
        beforeBottom = { fadeBottom && scrollState.value < scrollState.maxValue - edge },
        scrollKey = scrollState,
        onScrolledChange = onScrolledChange,
    )
    Column(
        modifier
            .weight(1f, fill = true)
            .fillMaxWidth()
            .then(fades)
            .verticalScroll(scrollState)
            .padding(contentPadding),
        content = content,
    )
}

/** [ScrollBody] for long or unbounded content: only the rows on screen are composed. */
@Composable
fun ColumnScope.LazyScrollBody(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    onScrolledChange: ((Boolean) -> Unit)? = null,
    /** Fade content under the bottom edge. Off when a docked control sits there. */
    fadeBottom: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit,
) {
    val fades = rememberEdgeFades(
        pastTop = { state.canScrollBackward },
        beforeBottom = { fadeBottom && state.canScrollForward },
        scrollKey = state,
        onScrolledChange = onScrolledChange,
    )
    LazyColumn(
        modifier.weight(1f, fill = true).fillMaxWidth().then(fades),
        state = state,
        contentPadding = contentPadding,
        content = content,
    )
}

/** Dissolves content under whichever edge the body has scrolled past, and reports leaving the top. */
@Composable
private fun rememberEdgeFades(
    pastTop: () -> Boolean,
    beforeBottom: () -> Boolean,
    scrollKey: Any,
    onScrolledChange: ((Boolean) -> Unit)?,
): Modifier {
    val pastTopNow by rememberUpdatedState(pastTop)
    val beforeBottomNow by rememberUpdatedState(beforeBottom)
    // Scroll position changes every frame, these booleans rarely: derived so only they recompose.
    val top by remember { derivedStateOf { pastTopNow() } }
    val bottom by remember { derivedStateOf { beforeBottomNow() } }
    val fadeTop by animateFloatAsState(if (top) 1f else 0f, tween(200, easing = VectraEasing.Fade), label = "fadeTop")
    val fadeBot by animateFloatAsState(if (bottom) 1f else 0f, tween(200, easing = VectraEasing.Fade), label = "fadeBottom")
    val latest by rememberUpdatedState(onScrolledChange)
    LaunchedEffect(scrollKey) {
        snapshotFlow { pastTopNow() }.distinctUntilChanged().collect { latest?.invoke(it) }
    }
    if (nativeViewsUnderCanvas) {
        // iOS: a DstIn mask needs an offscreen layer, which would hide the maps in the body
        // (see nativeViewsUnderCanvas). Fade into the surface colour instead; screens and sheets
        // sit on it, so it looks the same.
        val surface = Vectra.colors.surfaceSecondary
        return Modifier.drawWithContent {
            drawContent()
            val f = EdgeFade.toPx()
            if (fadeTop > 0f) {
                drawRect(
                    Brush.verticalGradient(0f to surface.copy(alpha = fadeTop), 1f to surface.copy(alpha = 0f), startY = 0f, endY = f),
                    size = Size(size.width, f),
                )
            }
            if (fadeBot > 0f) {
                drawRect(
                    Brush.verticalGradient(0f to surface.copy(alpha = 0f), 1f to surface.copy(alpha = fadeBot), startY = size.height - f, endY = size.height),
                    topLeft = Offset(0f, size.height - f),
                    size = Size(size.width, f),
                )
            }
        }
    }
    return Modifier
        .graphicsLayer {
            // The fades punch through with DstIn, which needs an offscreen; skip it when neither shows.
            compositingStrategy = if (fadeTop > 0f || fadeBot > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }
        .drawWithContent {
            drawContent()
            val f = EdgeFade.toPx()
            if (fadeTop > 0f) {
                drawRect(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 1f - fadeTop), 1f to Color.Black, startY = 0f, endY = f),
                    size = Size(size.width, f),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (fadeBot > 0f) {
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 1f to Color.Black.copy(alpha = 1f - fadeBot), startY = size.height - f, endY = size.height),
                    topLeft = Offset(0f, size.height - f),
                    size = Size(size.width, f),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

/** Docked action area under the body. */
@Composable
fun BottomBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Vectra.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.surfaceSecondary)
            .drawBehind { drawRect(c.borderSubtle, size = Size(size.width, 1.dp.toPx())) }
            .padding(start = SurfaceGutter, end = SurfaceGutter, top = 12.dp, bottom = edgeBottom()),
        content = content,
    )
}

/** Section heading inside a screen or sheet. */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Vectra.colors.textPrimary,
    icon: ImageVector? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) VIcon(icon, size = 18.dp, tint = color)
        VText(text, Modifier.weight(1f), vtext(TypeScale.BodyLarge, Weight.Extrabold, Tracking.Tight), color)
        action?.invoke(this)
    }
}

/** Title row for a compact sheet: title left, close on the right. */
@Composable
fun SheetHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = SurfaceGutter, end = 12.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            VText(title, Modifier.semantics { heading() }, vtext(TypeScale.Subtitle, Weight.Extrabold, Tracking.Tight), Vectra.colors.textPrimary)
            if (subtitle != null) VText(subtitle, style = vtext(TypeScale.Caption), color = Vectra.colors.textSecondary)
        }
        SheetCloseButton(onClose)
    }
}

/**
 * Root of a pushed screen: fills the window, runs under the system bars (the [TopBar] and
 * [BottomBar] pad them) and lifts its content above the keyboard. Compose a [TopBar],
 * a [ScrollBody] and optionally a [BottomBar] inside it.
 */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalSurfaceEdgeToEdge provides true) {
        Column(
            modifier.fillMaxSize().background(Vectra.colors.surfaceSecondary).imePadding(),
            content = content,
        )
    }
}
