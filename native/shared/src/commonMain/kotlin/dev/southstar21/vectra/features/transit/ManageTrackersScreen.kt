package dev.southstar21.vectra.features.transit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.southstar21.vectra.core.designsystem.components.BarLeading
import dev.southstar21.vectra.core.designsystem.components.BottomBar
import dev.southstar21.vectra.core.designsystem.components.InlineLoader
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.ScreenColumn
import dev.southstar21.vectra.core.designsystem.components.SurfaceGutter
import dev.southstar21.vectra.core.designsystem.components.TopBar
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VTabs
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.LocalContentColor
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.navigation.LocalNavigator
import dev.southstar21.vectra.navigation.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** Which list the manager shows: every tracker, or the dashboard favourites. */
private enum class ReorderTab { All, Favorites }

private fun sameOrder(a: List<TransitTracker>, b: List<TransitTracker>) =
    a.size == b.size && a.indices.all { a[it].id == b[it].id }

/**
 * Manage trackers: drag (or star) to set what the dashboard shows and in what order. The
 * order is saved explicitly; starring acts immediately and the chevron opens the editor.
 */
@Composable
fun ManageTrackersScreen(repo: TransitRepository, onBack: () -> Unit) {
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val haptics = Vectra.haptics
    val scope = rememberCoroutineScope()
    val reduceMotion = Vectra.reduceMotion
    val c = Vectra.colors

    val sourceTrackers = repo.trackersQuery.collect(Unit).data.orEmpty()
    var activeTab by rememberSaveable { mutableStateOf(ReorderTab.All) }
    // The unsaved order as ids, null until the first move. Saveable so it is still there
    // when the editor pushed on top pops again.
    var orderIds by rememberSaveable { mutableStateOf<String?>(null) }
    var isPending by remember { mutableStateOf(false) }
    var favoriteBusyId by remember { mutableStateOf<String?>(null) }
    var scrolled by remember { mutableStateOf(false) }

    // A refetch (after a star toggle, an edit) refreshes each row's data but keeps the
    // unsaved order; added and removed trackers follow along.
    val allItems = remember(orderIds, sourceTrackers) {
        val ids = orderIds?.split(',') ?: return@remember sourceTrackers
        val fresh = sourceTrackers.associateBy { it.id }
        ids.mapNotNull { fresh[it] } + sourceTrackers.filter { it.id !in ids }
    }
    fun setOrder(next: List<TransitTracker>) {
        orderIds = next.joinToString(",") { it.id }
    }

    val favItems = remember(allItems) { allItems.filter { it.isFavorite } }
    val isFavoritesTab = activeTab == ReorderTab.Favorites
    val displayList = if (isFavoritesTab) favItems else allItems
    val isDirty = !sameOrder(allItems, sourceTrackers)
    val favoritesFull = favItems.size >= MaxFavoriteTrackers

    val handleReorder: (List<TransitTracker>) -> Unit = { next ->
        setOrder(if (isFavoritesTab) applyFavoriteOrder(allItems, next) else next)
    }
    val moveItem: (Int, Int) -> Unit = { index, direction ->
        setOrder(if (isFavoritesTab) reorderFavoriteSlots(allItems, index, direction) else reorderAllSlots(allItems, index, direction))
    }
    val handleSortByNumber: () -> Unit = {
        setOrder(if (isFavoritesTab) applyFavoriteOrder(allItems, sortTrackersByNumber(favItems)) else sortTrackersByNumber(allItems))
        haptics.tick()
    }
    val handleToggleFavorite: (TransitTracker) -> Unit = { tracker ->
        if (!tracker.isFavorite && sourceTrackers.count { it.isFavorite } >= MaxFavoriteTrackers) {
            toaster.error("Maximum of $MaxFavoriteTrackers favorite trackers allowed.")
        } else {
            favoriteBusyId = tracker.id
            scope.launch {
                try {
                    val next = repo.toggleFavorite(tracker.id)
                    toaster.success(if (next) "Added to dashboard favorites" else "Removed from dashboard favorites")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    toaster.error("Could not update favorite", t.message)
                } finally {
                    favoriteBusyId = null
                }
            }
        }
    }
    val handleSave: () -> Unit = {
        val ids = allItems.map { it.id }
        val favTab = isFavoritesTab
        scope.launch {
            isPending = true
            try {
                repo.reorderTrackers(ids)
                isPending = false
                toaster.success(if (favTab) "Favorites order updated" else "Transit trackers order updated")
                haptics.confirm()
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                isPending = false
                toaster.error("Failed to update order", trackerErrorMessage(e))
            }
        }
    }

    ScreenColumn {
        TopBar(
            title = "Manage trackers",
            leading = BarLeading.Back,
            onLeading = onBack,
            scrolled = scrolled && displayList.isNotEmpty(),
            accessory = {
                VTabs(
                    selected = activeTab,
                    options = listOf(ReorderTab.All, ReorderTab.Favorites),
                    onSelect = { activeTab = it },
                    modifier = Modifier.fillMaxWidth(),
                    columns = 2,
                    triggerHeight = 36.dp,
                    textSize = TypeScale.Body,
                ) { tab, _ ->
                    val ink = LocalContentColor.current
                    val countStyle = vtext(TypeScale.Body, Weight.Semibold).copy(fontFeatureSettings = "tnum")
                    if (tab == ReorderTab.All) {
                        VText("All", maxLines = 1)
                        VText("${allItems.size}", style = countStyle, color = ink.copy(alpha = ink.alpha * 0.6f), maxLines = 1)
                    } else {
                        VText("Dashboard", maxLines = 1)
                        VText("${favItems.size}/$MaxFavoriteTrackers", style = countStyle, color = ink.copy(alpha = ink.alpha * 0.6f), maxLines = 1)
                    }
                }
            },
        )

        if (displayList.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = SurfaceGutter, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Box(Modifier.size(48.dp).background(c.surfaceOverlay, CircleShape), contentAlignment = Alignment.Center) {
                    if (isFavoritesTab) VIcon(RemixIcons.StarLine, size = 24.dp, tint = Palette.Amber400)
                    else VIcon(RemixIcons.StackLine, size = 24.dp, tint = c.textMuted)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VText(
                        if (isFavoritesTab) "Nothing on your dashboard yet" else "No trackers saved yet",
                        style = vtext(TypeScale.Body, Weight.Bold),
                        color = c.textPrimary,
                        align = TextAlign.Center,
                    )
                    VText(
                        if (isFavoritesTab) "Star a tracker under All to pin it to your dashboard."
                        else "Add trackers from the transit page to organize them here.",
                        Modifier.padding(top = 4.dp).widthIn(max = 260.dp),
                        vtext(TypeScale.Caption),
                        c.textMuted,
                        align = TextAlign.Center,
                    )
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = SurfaceGutter, end = SurfaceGutter, top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VText(
                    if (isFavoritesTab) "The order they appear on your dashboard" else "Drag to reorder · star to pin",
                    Modifier.weight(1f),
                    vtext(TypeScale.Caption),
                    c.textMuted,
                )
                SortByLineButton(enabled = displayList.size > 1 && !isPending, onClick = handleSortByNumber)
            }

            // Keyed by tab: a row in both lists would otherwise keep its
            // layout and fly from its old slot to its new one.
            key(activeTab) {
                val listState = rememberLazyListState()
                LaunchedEffect(listState) {
                    snapshotFlow { listState.canScrollBackward }.collect { scrolled = it }
                }
                val fade = remember { Animatable(if (reduceMotion) 1f else 0f) }
                LaunchedEffect(Unit) { fade.animateTo(1f, tween(180, easing = VectraEasing.Decelerate)) }
                ReorderList(
                    items = displayList,
                    listState = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = fade.value },
                    disabled = isPending,
                    onReorder = handleReorder,
                    onMove = moveItem,
                    onToggleFavorite = handleToggleFavorite,
                    favoriteBusyId = favoriteBusyId,
                    favoritesFull = favoritesFull,
                    onEdit = { navigator.push(Screen.EditTracker(it.id)) },
                )
            }
        }

        BottomBar {
            SheetPrimaryButton(onClick = handleSave, Modifier.fillMaxWidth(), pressedScale = 0.98f, enabled = !isPending && isDirty) {
                SheetPrimaryLabel(isPending, if (isDirty) "Save order" else "No changes")
            }
        }
    }
}

@Composable
private fun SortByLineButton(enabled: Boolean, onClick: () -> Unit) {
    val c = Vectra.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .offset(x = 8.dp)
            .height(32.dp)
            .then(if (enabled) Modifier.pressScale(interaction) else Modifier)
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (pressed && enabled) c.accentPrimary.copy(alpha = 0.1f) else Color.Transparent, CircleShape)
            .tappable(enabled = enabled, role = Role.Button, onClickLabel = "Sort lowest to highest by line number", highlight = false, interactionSource = interaction, onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(RemixIcons.SortNumberAsc, size = 14.dp, tint = c.accentPrimary)
        VText("Sort by line", style = vtext(TypeScale.Caption, Weight.Semibold), color = c.accentPrimary, maxLines = 1)
    }
}

/** Neighbours make room and the dropped row lands on this spring. */
private fun <T> rowSpring(visibilityThreshold: T? = null) = VectraSprings.drag(visibilityThreshold)

/** The released row snaps back onto its slot. */
private val SnapBack = VectraSprings.drag<Float>()

private val RowGap = 8.dp

/**
 * Rows reorder live under the finger. Only the grip starts a drag, so the list
 * still scrolls under a finger elsewhere. The dragged row swaps with a neighbour
 * once its leading edge passes the neighbour's centre; neighbours spring into
 * their new slots, and the list auto-scrolls when the row is held near an edge.
 */
@Composable
private fun ReorderList(
    items: List<TransitTracker>,
    listState: LazyListState,
    modifier: Modifier,
    disabled: Boolean,
    onReorder: (List<TransitTracker>) -> Unit,
    onMove: (Int, Int) -> Unit,
    onToggleFavorite: ((TransitTracker) -> Unit)?,
    favoriteBusyId: String?,
    favoritesFull: Boolean,
    onEdit: ((TransitTracker) -> Unit)?,
) {
    val haptics = Vectra.haptics
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reduceMotion = Vectra.reduceMotion
    val gapPx = with(density) { RowGap.toPx() }
    val edgePx = with(density) { 56.dp.toPx() }
    val maxSpeedPx = with(density) { 14.dp.toPx() }

    val latestItems = rememberUpdatedState(items)
    val latestReorder = rememberUpdatedState(onReorder)
    var draggingId by remember { mutableStateOf<String?>(null) }
    /** The row that is lifted: dragging, or still settling after release. */
    var liftedId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val lift = remember { Animatable(0f) }
    var direction by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    /** Index the dragged row moves to after a swap, until list and layout both show it. */
    val awaitIndex = remember { intArrayOf(-1) }

    fun checkReorder() {
        val id = draggingId ?: return
        val list = latestItems.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val info = listState.layoutInfo.visibleItemsInfo
        val me = info.firstOrNull { it.key == id } ?: return
        // Wait for the list and the layout to catch up with the last swap.
        if (me.index != idx) return
        if (awaitIndex[0] >= 0 && idx != awaitIndex[0]) return
        awaitIndex[0] = -1
        if (direction > 0) {
            val next = info.firstOrNull { it.index == idx + 1 } ?: return
            if (me.offset + me.size + dragOffset > next.offset + next.size / 2f) {
                val swapped = list.toMutableList().also { it[idx] = list[idx + 1]; it[idx + 1] = list[idx] }
                dragOffset -= next.size + gapPx
                awaitIndex[0] = idx + 1
                latestReorder.value(swapped)
            }
        } else if (direction < 0) {
            val prev = info.firstOrNull { it.index == idx - 1 } ?: return
            if (me.offset + dragOffset < prev.offset + prev.size / 2f) {
                val swapped = list.toMutableList().also { it[idx] = list[idx - 1]; it[idx - 1] = list[idx] }
                dragOffset += prev.size + gapPx
                awaitIndex[0] = idx - 1
                latestReorder.value(swapped)
            }
        }
    }

    fun release() {
        draggingId = null
        val from = dragOffset
        settleJob = scope.launch {
            launch { lift.animateTo(0f, if (reduceMotion) tween(0) else rowSpring()) }
            if (reduceMotion) dragOffset = 0f
            else animate(from, 0f, animationSpec = SnapBack) { v, _ -> dragOffset = v }
            liftedId = null
        }
    }

    // Auto-scroll while a row is held near the top or bottom edge.
    LaunchedEffect(draggingId) {
        val id = draggingId ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val layout = listState.layoutInfo
            val me = layout.visibleItemsInfo.firstOrNull { it.key == id }
            if (me == null) {
                checkReorder()
                continue
            }
            val top = me.offset + dragOffset
            val bottom = top + me.size
            val start = layout.viewportStartOffset.toFloat()
            val end = layout.viewportEndOffset.toFloat()
            val delta = when {
                bottom > end - edgePx && direction >= 0 -> maxSpeedPx * ((bottom - (end - edgePx)) / edgePx).coerceIn(0f, 1f)
                top < start + edgePx && direction <= 0 -> -maxSpeedPx * (((start + edgePx) - top) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (delta != 0f) {
                val consumed = listState.scrollBy(delta)
                dragOffset += consumed
                checkReorder()
            }
        }
    }

    val shape = Radius.Panel
    LazyColumn(
        modifier,
        state = listState,
        contentPadding = PaddingValues(start = SurfaceGutter, end = SurfaceGutter, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        itemsIndexed(items, key = { _, t -> t.id }) { index, tracker ->
            val isLifted = liftedId == tracker.id
            val liftValue = if (isLifted) lift.value else 0f
            TrackerRow(
                tracker = tracker,
                index = index,
                count = items.size,
                disabled = disabled,
                onMove = { dir -> onMove(index, dir) },
                onToggleFavorite = onToggleFavorite,
                favoriteBusy = favoriteBusyId == tracker.id,
                favoriteLocked = favoritesFull && !tracker.isFavorite,
                onEdit = onEdit,
                gripModifier = Modifier.pointerInput(tracker.id, disabled, items.size) {
                    if (disabled || items.size <= 1) return@pointerInput
                    detectDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            if (liftedId != tracker.id) dragOffset = 0f
                            draggingId = tracker.id
                            liftedId = tracker.id
                            direction = 0f
                            awaitIndex[0] = -1
                            haptics.longPress()
                            if (!reduceMotion) scope.launch { lift.animateTo(1f, rowSpring()) }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (amount.y != 0f) direction = amount.y
                            dragOffset += amount.y
                            checkReorder()
                        },
                        onDragEnd = { release() },
                        onDragCancel = { release() },
                    )
                },
                modifier = Modifier
                    .zIndex(if (isLifted) 1f else 0f)
                    .then(
                        if (reduceMotion || isLifted) Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
                        else Modifier.animateItem(fadeInSpec = null, placementSpec = rowSpring(IntOffset.VisibilityThreshold), fadeOutSpec = null),
                    )
                    .graphicsLayer {
                        if (isLifted) {
                            translationY = dragOffset
                            val s = 1f + 0.02f * liftValue
                            scaleX = s
                            scaleY = s
                        }
                    }
                    .then(
                        if (liftValue > 0f) {
                            Modifier.boxShadow(
                                shape,
                                listOf(BoxShadow(y = 12f, blur = 32f, spread = -12f, color = Color.Black.copy(alpha = 0.35f * liftValue))),
                            )
                        } else Modifier,
                    ),
            )
        }
    }
}

private val RowShape = Radius.Panel

/**
 * One draggable tracker. Only the grip ([gripModifier]) starts a drag; it also
 * offers move up/down as accessibility actions.
 */
@Composable
private fun TrackerRow(
    tracker: TransitTracker,
    index: Int,
    count: Int,
    disabled: Boolean,
    onMove: (Int) -> Unit,
    onToggleFavorite: ((TransitTracker) -> Unit)?,
    favoriteBusy: Boolean,
    favoriteLocked: Boolean,
    onEdit: ((TransitTracker) -> Unit)?,
    gripModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val c = Vectra.colors
    val stops = getTrackerStops(tracker)
    val isMultiStop = stops.size > 1
    val displayName = formatRouteDisplayName(tracker.routeShortName, tracker.routeId)
    val title = if (isMultiStop) tracker.routeLongName.ifEmpty { displayName } else stops.firstOrNull()?.stopName ?: tracker.stopName
    val subtitle = when {
        isMultiStop -> "${stops.size} stops \u00B7 ${stops.joinToString(", ") { it.stopName }}"
        !stops.firstOrNull()?.headsign.isNullOrEmpty() -> "Towards ${stops[0].headsign}"
        else -> null
    }
    val favoriteLabel = when {
        tracker.isFavorite -> "Remove from dashboard"
        favoriteLocked -> "Dashboard is full ($MaxFavoriteTrackers max)"
        else -> "Pin to dashboard"
    }
    val gripEnabled = !disabled && count > 1

    Row(
        modifier
            .fillMaxWidth()
            .glass(RowShape)
            .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 40.dp)
                .alpha(if (gripEnabled) 1f else 0.3f)
                .then(gripModifier)
                .semantics {
                    contentDescription = "Reorder $displayName, position ${index + 1} of $count."
                    if (gripEnabled) {
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction("Move up") { onMove(-1); true })
                            if (index < count - 1) add(CustomAccessibilityAction("Move down") { onMove(1); true })
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { VIcon(RemixIcons.Draggable, size = 20.dp, tint = c.textMuted) }

        RouteBadge(displayName, tracker.routeColor, tracker.routeTextColor, size = RouteBadgeSize.Sm)

        Column(Modifier.weight(1f).padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            FormattedRouteTitle(title, style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary)
            if (subtitle != null) VText(subtitle, Modifier.padding(top = 2.dp), vtext(TypeScale.Caption), c.textMuted, maxLines = 1)
        }

        if (onToggleFavorite != null) {
            val enabled = !favoriteBusy && !favoriteLocked
            RowIconButton(
                onClick = { onToggleFavorite(tracker) },
                enabled = enabled,
                label = favoriteLabel,
                pressedBg = if (tracker.isFavorite) Palette.Amber500.copy(alpha = 0.1f) else c.surfaceOverlay,
                modifier = Modifier.semantics { stateDescription = if (tracker.isFavorite) "Pinned" else "Not pinned" },
            ) {
                when {
                    favoriteBusy -> InlineLoader(size = 20.dp, tint = if (tracker.isFavorite) Palette.Amber400 else c.textMuted)
                    tracker.isFavorite -> VIcon(RemixIcons.StarFill, size = 20.dp, tint = Palette.Amber400)
                    else -> VIcon(RemixIcons.StarLine, size = 20.dp, tint = c.textMuted)
                }
            }
        }
        if (onEdit != null) {
            RowIconButton(
                onClick = { onEdit(tracker) },
                enabled = true,
                label = "Edit $displayName tracker",
                pressedBg = c.surfaceOverlay,
                width = 32.dp,
            ) { VIcon(RemixIcons.ArrowRightSLine, size = 20.dp, tint = c.textMuted) }
        }
    }
}

/** A 40dp round hit target; disabled fades to 30%. */
@Composable
private fun RowIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    label: String,
    pressedBg: Color,
    modifier: Modifier = Modifier,
    width: Dp = 40.dp,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(width = width, height = 40.dp)
            .then(if (enabled) Modifier.pressScale(interaction) else Modifier)
            .alpha(if (enabled) 1f else 0.3f)
            .background(if (pressed) pressedBg else Color.Transparent, CircleShape)
            .tappable(enabled = enabled, role = Role.Button, highlight = false, interactionSource = interaction, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}
