package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Radius
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.tappable
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How much of a covered card's top edge stays visible above the card in front of it. */
private val PileGap = 16.dp
/** Over how far a card's approach to the pile its stuck-on extras (stamps) fade out. */
private val ApproachFade = 40.dp
private val GlassCorner = 24.dp
/** How far above a flat group in front a covered card fades out. */
private val SoftEdge = 48.dp
private const val ShrinkPerLevel = 0.05f
/** Black laid over a covered card per level of cover; a card two deep sits at twice this. */
private const val DimPerLevel = 0.3f
/** Light cards take a soft slate shade instead: black at dark-mode strength turns white glass muddy grey. */
private const val DimPerLevelLight = 0.1f
private const val MaxDepth = 2f

/**
 * The scrolling page a [ScrollPile] lives in, provided by the tab page. [viewport] is the
 * scroll container's own (unscrolled) layout; [topPad] is where content starts below the status
 * bar and [bottomReserve] what the floating navbar covers, both in px.
 */
@Stable
class PageScrollInfo(val scroll: ScrollState) {
    var viewport: LayoutCoordinates? = null
    var topPad by mutableFloatStateOf(0f)
    var bottomReserve by mutableFloatStateOf(0f)
}

val LocalPageScroll = staticCompositionLocalOf<PageScrollInfo?> { null }

/**
 * Inside a piled card: 1 while the card scrolls freely, fading to 0 as it settles into the pile.
 * Things stuck over a card's edge (a [PostageStamp]) fade with it so the pile's edges stay clean.
 */
val LocalPileApproach = staticCompositionLocalOf<(() -> Float)?> { null }

/**
 * One card in a [ScrollPile]. [topInset] and [bottomInset] are the transparent padding the card
 * keeps around its glass (room for a stamp, a gap below), so the pile lines up and clips by the
 * glass edges.
 *
 * A [flat] entry is not one glass card but a group of opaque cards straight on the page (a day's
 * classes). It gets no page-coloured underlay while it slides over the card above, and when it is
 * covered its shade darkens only its own pixels, so the gaps between its cards stay page-coloured
 * instead of turning into a dark slab.
 */
@Immutable
class PileCard(
    val key: Any,
    val label: String,
    val topInset: Dp = 0.dp,
    val bottomInset: Dp = 0.dp,
    val flat: Boolean = false,
    val content: @Composable () -> Unit,
)

/** Where one card sits this frame, in the page viewport's pixels. */
private class CardFrame(
    val translation: Float,
    val cover: Float,
    val depth: Float,
    /** Bottom of the visible part, in the card's own coordinates; covered cards stop at the card in front. */
    val clipBottom: Float,
    val underlay: Boolean,
    val approach: Float,
    /** The card in front is a flat group: fade out toward [clipBottom] instead of a hard cut. */
    val softEdge: Boolean = false,
)

/**
 * The inputs a pile's frames were last computed from. Each call stores the new values and says
 * whether any differed; the arrays are replaced, never mutated, so they compare by identity.
 */
internal class FrameKey {
    private var seen = false
    private var scroll = 0
    private var viewport = 0
    private var contentTop = 0f
    private var topPad = 0f
    private var bottomReserve = 0f
    private var naturalY: FloatArray? = null
    private var heights: IntArray? = null

    /** The first call always reports a change: nothing has been computed yet. */
    fun ints(scroll: Int, viewport: Int): Boolean {
        val changed = !seen || scroll != this.scroll || viewport != this.viewport
        seen = true
        this.scroll = scroll
        this.viewport = viewport
        return changed
    }

    fun floats(contentTop: Float, topPad: Float, bottomReserve: Float): Boolean {
        val changed = contentTop != this.contentTop || topPad != this.topPad || bottomReserve != this.bottomReserve
        this.contentTop = contentTop
        this.topPad = topPad
        this.bottomReserve = bottomReserve
        return changed
    }

    fun arrays(naturalY: FloatArray, heights: IntArray): Boolean {
        val changed = naturalY !== this.naturalY || heights !== this.heights
        this.naturalY = naturalY
        this.heights = heights
        return changed
    }
}

/** Scroll geometry of one pile; every frame derives from [page]'s scroll at draw time. */
@Stable
private class PileState(val page: PageScrollInfo) {
    val scroll get() = page.scroll
    /** The pile's top in content coordinates (viewport pixels at scroll 0). */
    var contentTop by mutableFloatStateOf(0f)
    var naturalY by mutableStateOf(FloatArray(0))
    var heights by mutableStateOf(IntArray(0))
    var topInsets = FloatArray(0)
    var bottomInsets = FloatArray(0)
    var flats = BooleanArray(0)
    var gap = 0f
    var approachPx = 1f

    private fun glassHeight(i: Int) = heights[i] - topInsets[i] - bottomInsets[i]
    fun active(): List<Int> = heights.indices.filter { glassHeight(it) > 0f }
    val visibleBottom get() = scroll.viewportSize - page.bottomReserve

    fun stick(rank: Int, glass: Float) = minOf(page.topPad + gap * rank, visibleBottom - glass)

    private val cacheKey = FrameKey()
    private var cache: Array<CardFrame?> = emptyArray()

    /**
     * Every slot asks several times a frame; the inputs are read on each call (so callers keep
     * observing them) and the result is reused while they are unchanged. The key is kept field by
     * field: this runs in the draw path, so a cache hit allocates nothing.
     */
    private fun frames(): Array<CardFrame?> {
        val k = cacheKey
        val ints = k.ints(scroll.value, scroll.viewportSize)
        val floats = k.floats(contentTop, page.topPad, page.bottomReserve)
        val arrays = k.arrays(naturalY, heights)
        if (ints || floats || arrays) cache = computeFrames()
        return cache
    }

    private fun computeFrames(): Array<CardFrame?> {
        val out = arrayOfNulls<CardFrame>(heights.size)
        val act = active()
        if (act.isEmpty() || naturalY.size != heights.size) return out
        val n = act.size
        val natural = FloatArray(n) { contentTop + naturalY[act[it]] + topInsets[act[it]] - scroll.value }
        val sticks = FloatArray(n) { stick(it, glassHeight(act[it])) }
        // The last card never sticks: it is the one the others pile under.
        val pinned = FloatArray(n) { if (it == n - 1) natural[it] else maxOf(natural[it], sticks[it]) }
        val cover = FloatArray(n) { r ->
            if (r == n - 1) 0f
            else (1f - (pinned[r + 1] - pinned[r] - gap) / glassHeight(act[r])).coerceIn(0f, 1f)
        }
        // A covered card never shows below the cards in front of it. A glass card hides what lies
        // under it, so that is its bottom edge; a flat group has gaps between its cards, so the card
        // under it stops at the group's top edge instead.
        val limit = FloatArray(n)
        val softLimit = BooleanArray(n)
        var front = Float.MAX_VALUE
        var frontFlat = false
        for (r in n - 1 downTo 0) {
            limit[r] = front
            softLimit[r] = frontFlat
            val i = act[r]
            val flat = flats.getOrElse(i) { false }
            val edge = if (flat) pinned[r] else pinned[r] + glassHeight(i)
            if (edge < front) {
                front = edge
                frontFlat = flat
            }
        }
        for (r in 0 until n) {
            val i = act[r]
            var later = 0f
            for (k in r + 1 until n - 1) later += cover[k]
            val visible = if (cover[r] > 0f) (limit[r] - pinned[r]).coerceIn(0f, glassHeight(i)) else -1f
            out[i] = CardFrame(
                translation = pinned[r] - natural[r],
                cover = cover[r],
                depth = minOf(MaxDepth, cover[r] * (1f + later)),
                clipBottom = if (visible < 0f) Float.MAX_VALUE else topInsets[i] + visible,
                underlay = r > 0 && cover[r - 1] > 0f,
                approach = if (r == n - 1) 1f else ((natural[r] - sticks[r]) / approachPx).coerceIn(0f, 1f),
                softEdge = visible >= 0f && softLimit[r],
            )
        }
        return out
    }

    fun frame(i: Int): CardFrame? = frames().getOrNull(i)

    /** Per card: 0 open, 1 the next card is sliding over it, 2 it has settled into the pile. */
    fun stages(): List<Int> = frames().map { f ->
        when {
            f == null -> 0
            f.cover >= 0.98f -> 2
            f.cover > 0.02f -> 1
            else -> 0
        }
    }

    /** Records this layout pass; returns the room the pile needs after its last card. */
    fun measured(ys: FloatArray, hs: IntArray, cards: List<PileCard>, density: Density): Int = with(density) {
        topInsets = FloatArray(cards.size) { cards[it].topInset.toPx() }
        bottomInsets = FloatArray(cards.size) { cards[it].bottomInset.toPx() }
        flats = BooleanArray(cards.size) { cards[it].flat }
        gap = PileGap.toPx()
        approachPx = ApproachFade.toPx()
        if (!naturalY.contentEquals(ys)) naturalY = ys
        if (!heights.contentEquals(hs)) heights = hs
        // Room after the last card so it can scroll up over the whole pile.
        val last = active().lastOrNull() ?: return 0
        val glass = glassHeight(last)
        val stick = stick(active().size - 1, glass)
        (visibleBottom - stick - glass - bottomInsets[last]).toInt().coerceAtLeast(0)
    }

    /** Scrolls so the card at [index] sits at its place on top of the pile. */
    suspend fun reveal(index: Int, animate: Boolean) {
        val rank = active().indexOf(index)
        if (rank < 0) return
        val glass = heights[index] - topInsets[index] - bottomInsets[index]
        val target = (contentTop + naturalY[index] + topInsets[index] - stick(rank, glass)).toInt().coerceAtLeast(0)
        if (animate) scroll.animateScrollTo(target) else scroll.scrollTo(target)
    }
}

/**
 * Cards that pile up as the page scrolls: each card sticks near the top and the next one slides
 * over it, while the covered card shrinks and darkens. Tapping a covered card's edge scrolls
 * back to it. It rides the page's own scroll ([LocalPageScroll]); outside a page it is a plain column.
 */
@Composable
fun ScrollPile(cards: List<PileCard>, modifier: Modifier = Modifier, spacing: Dp = 0.dp) {
    val page = LocalPageScroll.current
    val state = remember(page) { page?.let { PileState(it) } }
    if (state != null) PileHaptics(state)
    Layout(
        content = { cards.forEachIndexed { i, card -> PileSlot(state, i, card) } },
        modifier = modifier.onGloballyPositioned { coords ->
            val pg = page ?: return@onGloballyPositioned
            val vp = pg.viewport?.takeIf { it.isAttached } ?: return@onGloballyPositioned
            // Measured against the unscrolled viewport, so it holds wherever the pile is nested.
            val top = vp.localPositionOf(coords, Offset.Zero).y + pg.scroll.value
            if (state != null && abs(state.contentTop - top) > 0.5f) state.contentTop = top
        },
    ) { measurables, constraints ->
        val child = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(child) }
        val gapPx = spacing.roundToPx()
        val ys = FloatArray(placeables.size)
        var y = 0
        var placedAny = false
        placeables.forEachIndexed { i, p ->
            // Cards that render nothing take no spacing either.
            if (p.height > 0 && placedAny) y += gapPx
            ys[i] = y.toFloat()
            y += p.height
            if (p.height > 0) placedAny = true
        }
        val tail = state?.measured(ys, IntArray(placeables.size) { placeables[it].height }, cards, this) ?: 0
        layout(constraints.maxWidth, y + tail) {
            placeables.forEachIndexed { i, p -> p.place(0, ys[i].toInt()) }
        }
    }
}

/** A tick as the next card starts sliding over one, a threshold as that card settles into the pile. */
@Composable
private fun PileHaptics(state: PileState) {
    val haptics = Vectra.haptics
    LaunchedEffect(state, haptics) {
        var prev = emptyList<Int>()
        snapshotFlow { state.stages() }.collect { stages ->
            if (state.scroll.isScrollInProgress && stages.size == prev.size) {
                when (stages.indices.filter { stages[it] > prev[it] }.maxOfOrNull { stages[it] }) {
                    2 -> haptics.threshold()
                    1 -> haptics.tick()
                }
            }
            prev = stages
        }
    }
}

@Composable
private fun PileSlot(state: PileState?, index: Int, card: PileCard) {
    if (state == null) {
        card.content()
        return
    }
    val c = Vectra.colors
    val shade = if (c.isDark) Palette.Black else Palette.Slate900
    val reduceMotion = Vectra.reduceMotion
    val scope = rememberCoroutineScope()
    val covered by remember(state, index) { derivedStateOf { (state.frame(index)?.cover ?: 0f) > 0.5f } }
    val approach = remember(state, index) { { state.frame(index)?.approach ?: 1f } }
    Box(
        Modifier
            .fadeLayer {
                val f = state.frame(index) ?: return@fadeLayer
                translationY = f.translation
                // The soft edge masks with DstIn, which needs this layer offscreen (its bleed keeps shadows).
                compositingStrategy = if (f.softEdge) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                if (!reduceMotion) {
                    val s = 1f - ShrinkPerLevel * f.depth
                    scaleX = s
                    scaleY = s
                    // Shrink toward the glass's top edge so the visible strip stays put.
                    val bleed = (size.height - state.heights.getOrElse(index) { 0 }) / 2f
                    val pivot = (bleed + state.topInsets.getOrElse(index) { 0f }) / size.height
                    transformOrigin = TransformOrigin(0.5f, pivot.coerceIn(0f, 1f))
                }
            }
            .drawWithContent {
                val f = state.frame(index)
                val top = card.topInset.toPx()
                val glass = Size(size.width, size.height - top - card.bottomInset.toPx())
                val corner = CornerRadius(GlassCorner.toPx())
                val bottom = f?.clipBottom ?: Float.MAX_VALUE
                val dim = (if (c.isDark) DimPerLevel else DimPerLevelLight) * (f?.depth ?: 0f)
                // The underlay and shade are clipped with the card, or they would hang below the card in front.
                clipRect(-size.width, -size.height, size.width * 2, minOf(bottom, size.height * 2)) {
                    // The card sliding over another is made opaque, or the one below shows through its glass.
                    if (f?.underlay == true && !card.flat) drawRoundRect(c.surfacePrimary, Offset(0f, top), glass, corner)
                    this@drawWithContent.drawContent()
                    // A flat group is not shaded: it fades out at the soft edge instead.
                    if (dim > 0f && !card.flat) drawRoundRect(shade.copy(alpha = dim), Offset(0f, top), glass, corner)
                    if (f?.softEdge == true) {
                        // Fade out over the last stretch above the group in front, so nothing is cut on a hard line.
                        val fade = SoftEdge.toPx()
                        val end = minOf(bottom, size.height * 2)
                        val start = (end - fade).coerceAtLeast(-size.height)
                        drawRect(
                            Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = start, endY = end),
                            topLeft = Offset(-size.width, start),
                            size = Size(size.width * 3, end - start),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
            }
            // This draw block reads the scroll position, so it runs every scroll frame. The card gets its
            // own layer so those frames replay its recording: re-recording it rebuilds its blurred shadows,
            // which Compose rasterizes on the CPU on iOS (that held Schedule's scroll at ~20 fps).
            .graphicsLayer(),
    ) {
        // Claims touches on the glass so taps there never reach a covered card underneath.
        Box(Modifier.matchParentSize().padding(top = card.topInset, bottom = card.bottomInset).pointerInput(Unit) {})
        CompositionLocalProvider(LocalPileApproach provides approach) { card.content() }
        if (covered) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(top = card.topInset, bottom = card.bottomInset)
                    .tappable(Radius.Card, onClickLabel = "Show ${card.label}", highlight = false, haptic = { tick() }) {
                        scope.launch { state.reveal(index, animate = !reduceMotion) }
                    },
            )
        }
    }
}
