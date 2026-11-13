package dev.southstar21.vectra.features.navbar

import androidx.compose.ui.geometry.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import dev.southstar21.vectra.core.designsystem.components.VBackHandler
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.features.navbar.liquid.InteractiveHighlight
import dev.southstar21.vectra.features.navbar.liquid.LiquidTabs
import dev.southstar21.vectra.navigation.ActionRoutes
import dev.southstar21.vectra.navigation.PrimaryNavItems
import dev.southstar21.vectra.navigation.Route
import kotlin.math.tanh
import dev.southstar21.vectra.core.designsystem.theme.consumeTouches
import dev.southstar21.vectra.core.designsystem.theme.tappable

/** Height the dock and its + button share; pages clear it (ShellState). */
internal val DockHeight = 62.dp

/** The dock's height tucked away while a page scrolls down. */
internal val CompactDockHeight = 50.dp

/** Each tab's width in the tucked-away dock. */
private val CompactTabWidth = 64.dp

/** Takes all the width it's offered, narrowing toward [compact] as [collapse] goes 0..1 (read in layout only). */
private fun Modifier.narrowTo(compact: Dp, collapse: () -> Float): Modifier = layout { measurable, constraints ->
    val full = constraints.maxWidth
    val target = androidx.compose.ui.util.lerp(full.toFloat(), minOf(full, compact.roundToPx()).toFloat(), collapse().coerceIn(0f, 1f)).toInt()
    val placeable = measurable.measure(constraints.copy(minWidth = target, maxWidth = target))
    layout(target, placeable.height) { placeable.place(0, 0) }
}

/**
 * The Kaloria dock: a Liquid Glass tab bar whose selection is itself a drop of glass you can press
 * and drag between tabs (it magnifies the tab beneath with a little chromatic aberration and
 * squashes with speed), plus a separate plain glass + (like the iOS search tab) on pages that have actions. The
 * actions pop out of the + as one glass panel.
 */
@Composable
fun LiquidNavbar(
    current: Route,
    onNavigate: (Route) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    /** Tucked away while a page scrolls down: smaller, labels hidden (see [NavbarCompact]). */
    compact: Boolean = false,
    /** The tabs [onLongPress] is for; holding any other is just a tap. */
    holdable: (Route) -> Boolean = { true },
    /** A tab held down, with where it is in the window (Transit: the nearby map grows out of it). */
    onLongPress: ((Route, Rect?) -> Unit)? = null,
) {
    val c = Vectra.colors
    val items = PrimaryNavItems
    val haptics = Vectra.haptics
    val actions = LocalNavbarActions.current
    val config = actions.config
    val hasAction = current in ActionRoutes && config != null
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(current) { menuOpen = false }
    if (!hasAction && menuOpen) menuOpen = false
    VBackHandler(enabled = menuOpen) { menuOpen = false }
    val frosted = !isRenderEffectSupported()
    // Like iOS: the bar floats just above the screen edge, over the gesture handle, but clear of
    // 3-button navigation.
    val safeBottom = (WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() - 4.dp).coerceAtLeast(12.dp)
    val tuck = remember { Animatable(0f) }
    val reduced = Vectra.reduceMotion
    LaunchedEffect(compact) {
        val to = if (compact) 1f else 0f
        if (reduced) tuck.snapTo(to) else tuck.animateTo(to, spring(dampingRatio = 0.86f, stiffness = 420f))
    }
    val collapse = { tuck.value }

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(menuOpen, Modifier.fillMaxSize(), enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f))
                    .consumeTouches { menuOpen = false },
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 20.dp, bottom = safeBottom)
                .widthIn(max = 460.dp),
            horizontalAlignment = Alignment.End,
        ) {
            ActionMenu(menuOpen && hasAction, config?.actions.orEmpty(), backdrop) { action ->
                menuOpen = false
                haptics.tick()
                actions.execute(action.id)
            }
            // Tucked away, the tabs narrow to their icons; the + keeps its own animation beside
            // them and the pair stays centred, so a tab switch (the + coming or going) stays smooth.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val activeIndex = items.indexOfFirst { it.route == current }.coerceAtLeast(0)
                LiquidTabs(
                    count = items.size,
                    selectedIndex = activeIndex,
                    // The drop ticks as it crosses each tab; landing is felt by that last tick.
                    onSelect = { index -> onNavigate(items[index].route) },
                    onCross = { haptics.frequentTick() },
                    backdrop = backdrop,
                    accent = c.accentPrimary,
                    // iOS selects with a neutral grey pill; only the item itself takes the accent.
                    selection = if (c.isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.06f),
                    container = dockMaterial(),
                    modifier = Modifier.weight(1f, fill = false).narrowTo(CompactTabWidth * items.size + 8.dp, collapse),
                    height = DockHeight,
                    compactHeight = CompactDockHeight,
                    collapse = collapse,
                    frosted = frosted,
                    onLongPress = onLongPress?.let { hold -> { index, bounds -> hold(items[index].route, bounds) } },
                    holdable = { index -> holdable(items[index].route) },
                ) { index, tinted ->
                    val item = items[index]
                    val ink = if (tinted) c.accentPrimary else c.textPrimary
                    // As in the iOS tab bar: filled glyphs with the title beneath.
                    VIcon(item.activeIcon, size = 25.dp, tint = ink, contentDescription = null)
                    VText(item.label, Modifier.foldAway(collapse), vtext(TypeScale.Micro, Weight.Semibold), ink, maxLines = 1)
                }
                // The + only exists on pages with actions; it grows out beside the bar.
                AnimatedVisibility(
                    hasAction,
                    enter = expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 420f), Alignment.Start) +
                        scaleIn(spring(dampingRatio = 0.6f, stiffness = 420f), 0.4f) + fadeIn(tween(180)),
                    exit = shrinkHorizontally(spring(dampingRatio = 1f, stiffness = 600f), Alignment.Start) +
                        scaleOut(spring(dampingRatio = 1f, stiffness = 600f), 0.4f) + fadeOut(tween(140)),
                ) {
                    Row {
                        Spacer(Modifier.width(10.dp))
                        AddButton(menuOpen, config?.icon ?: RemixIcons.AddLine, backdrop, collapse) {
                            haptics.contextClick()
                            menuOpen = !menuOpen
                        }
                    }
                }
            }
        }
    }
}

/** Menus "simply pop open" from the control (WWDC25): one glass panel growing out of the +. */
@Composable
private fun ActionMenu(open: Boolean, actions: List<NavbarAction>, backdrop: Backdrop, onPick: (NavbarAction) -> Unit) {
    val c = Vectra.colors
    val progress by animateFloatAsState(
        if (open) 1f else 0f,
        if (open) spring(dampingRatio = 0.72f, stiffness = 420f) else spring(dampingRatio = 1f, stiffness = 900f),
        label = "menu",
    )
    // Composition only cares whether the menu is showing; the per-frame progress is read in the layer.
    val visible by remember { derivedStateOf { progress > 0.001f } }
    if (!visible) return
    Column(
        Modifier
            .padding(bottom = 12.dp)
            .graphicsLayer {
                transformOrigin = TransformOrigin(1f, 1f)
                val s = lerp(0.35f, 1f, progress)
                scaleX = s; scaleY = s
                alpha = progress.coerceIn(0f, 1f)
            }
            .width(250.dp)
            .dockGlass(RoundedRectangle(28.dp), backdrop, GlassThickness.Bar)
            .padding(vertical = 8.dp),
    ) {
        actions.forEach { action ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (action.disabled) 0.5f else 1f)
                    .tappable(enabled = !action.disabled, role = Role.Button) { onPick(action) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VIcon(action.icon, size = 22.dp, tint = c.accentPrimary)
                Spacer(Modifier.width(14.dp))
                VText(action.label, style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AddButton(open: Boolean, icon: ImageVector, backdrop: Backdrop, collapse: () -> Float, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    // The + turns into a ×; any other glyph stays upright.
    val turn = if (icon == RemixIcons.AddLine) 135f else 0f
    val rotation by animateFloatAsState(if (open) turn else 0f, spring(dampingRatio = 0.55f, stiffness = 380f), label = "add")
    Box(
        Modifier
            // Shrinks with the bar as it tucks away.
            .layout { measurable, _ ->
                val px = androidx.compose.ui.unit.lerp(DockHeight, CompactDockHeight, collapse().coerceIn(0f, 1f)).roundToPx()
                val placeable = measurable.measure(Constraints.fixed(px, px))
                layout(px, px) { placeable.place(0, 0) }
            }
            .dockGlass(Capsule(), backdrop, GlassThickness.Control, highlight)
            .tappable(Capsule(), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (open) "Close page actions" else "Page actions" },
        contentAlignment = Alignment.Center,
    ) {
        VIcon(icon, Modifier.rotate(rotation), size = 26.dp, tint = Vectra.colors.textPrimary)
    }
}

/** The fill of the bar, the + and the menu: iOS's tab bar material, a translucent grey. */
@Composable
internal fun dockMaterial(): Color {
    val c = Vectra.colors
    return when {
        !isRenderEffectSupported() -> c.glassBg.copy(alpha = (c.glassBg.alpha * 1.8f).coerceAtMost(1f))
        c.isDark -> Color(0xFF1C1C1E).copy(alpha = 0.7f)
        else -> Color.White.copy(alpha = 0.7f)
    }
}

/** How thick the material is. Bigger glass bends more and scatters light more softly. */
private enum class GlassThickness(val blur: Dp, val refractionHeight: Dp, val refractionAmount: Dp, val shadowRadius: Dp) {
    /** Floating bars and panels, matching the dock. */
    Bar(10.dp, 20.dp, 28.dp, 24.dp),

    /** Small controls: the + button. */
    Control(10.dp, 14.dp, 20.dp, 16.dp),
}

/**
 * The bar's glass for the + and its menu (same material, blur, rim and shadow) and, with [interaction],
 * a gel-like swell that leans toward the finger and glows from it while pressed.
 */
@Composable
private fun Modifier.dockGlass(
    shape: Shape,
    backdrop: Backdrop,
    thickness: GlassThickness,
    interaction: InteractiveHighlight? = null,
): Modifier {
    val frosted = !isRenderEffectSupported()
    val surface = dockMaterial()
    val shadowColor = Color.Black.copy(alpha = 0.10f)
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                if (frosted) blur(32.dp.toPx()) else {
                    vibrancy()
                    blur(thickness.blur.toPx())
                    lens(thickness.refractionHeight.toPx(), thickness.refractionAmount.toPx())
                }
            },
            // A faint rim: iOS glass barely catches the light at its edge.
            highlight = { Highlight.Default.copy(alpha = 0.3f) },
            shadow = { Shadow(radius = thickness.shadowRadius, color = shadowColor) },
            layerBlock = interaction?.let { ih ->
                {
                    // Gel-like: swells a few dp and leans toward the finger while pressed.
                    val p = ih.pressProgress
                    val s = lerp(1f, 1f + 6.dp.toPx() / size.height.coerceAtLeast(1f), p).coerceAtMost(1.04f)
                    scaleX = s; scaleY = s
                    val max = size.minDimension
                    translationX = max * tanh(0.04f * ih.offset.x / max)
                    translationY = max * tanh(0.04f * ih.offset.y / max)
                }
            },
            onDrawSurface = { drawRect(surface) },
        )
        .then(interaction?.let { it.modifier.then(it.gestureModifier) } ?: Modifier)
}
