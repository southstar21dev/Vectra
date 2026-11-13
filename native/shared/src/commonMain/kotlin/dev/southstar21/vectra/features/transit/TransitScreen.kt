package dev.southstar21.vectra.features.transit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Stable
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.designsystem.components.ActionSheet
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.OfflineBanner
import dev.southstar21.vectra.core.designsystem.components.Reveal
import dev.southstar21.vectra.core.designsystem.components.SheetAction
import dev.southstar21.vectra.core.designsystem.components.SleepingIcon
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.ContentMaxWidth
import dev.southstar21.vectra.core.designsystem.theme.LocalWideLayout
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Radius
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.WideContentMaxWidth
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.navbar.NavbarAction
import dev.southstar21.vectra.features.navbar.NavbarActionConfig
import dev.southstar21.vectra.features.navbar.NavbarActionVariant
import dev.southstar21.vectra.features.navbar.RegisterNavbarActions
import dev.southstar21.vectra.features.schedule.rememberDayColors
import dev.southstar21.vectra.features.shell.PagePane
import dev.southstar21.vectra.features.shell.PaneSide
import dev.southstar21.vectra.features.shell.VectraPage
import dev.southstar21.vectra.navigation.LocalNavigator
import dev.southstar21.vectra.navigation.Screen
import kotlinx.coroutines.launch

private const val ShowInactiveKey = "vectra_show_inactive_transits"

/** Badges the not-running stack shows before "+N". */
private const val StackBadges = 8

/**
 * Always composed (so arrivals keep polling), but only [fraction] of its height is laid out:
 * 0 takes no space, 1 is its full height. Only the bottom is clipped (to what is laid out), so
 * the cards' shadows still spill out at the sides.
 */
private fun Modifier.unfold(fraction: () -> Float, onFullHeight: (Int) -> Unit): Modifier = drawWithContent {
    // Fully open, nothing is cut: the last card's shadow spills below too.
    if (fraction() >= 1f) drawContent()
    else clipRect(-size.width, -size.height, size.width * 2, size.height) { this@drawWithContent.drawContent() }
}.layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    onFullHeight(p.height)
    val f = fraction().coerceIn(0f, 1f)
    layout(p.width, (p.height * f).roundToInt()) { if (f > 0f) p.place(0, 0) }
}

/**
 * Brings the not-running stack's cards into view when the user opens it: the stack sits at the
 * bottom of the page, so they would otherwise unfold off screen. Opening it by restoring the saved
 * state doesn't scroll; only [open] does.
 */
@Stable
private class StackReveal {
    val requester = BringIntoViewRequester()
    /** The cards' full height (px), however far they have unfolded. */
    var fullHeight = 0
    var requests by mutableIntStateOf(0)

    fun open() {
        requests++
    }
}

/** [fan]: how far the cards have unfolded, 0 to 1. */
@Composable
private fun rememberStackReveal(fan: () -> Float): StackReveal {
    val reveal = remember { StackReveal() }
    val barRoom = with(LocalDensity.current) { RevealBarRoom.toPx() }
    val unfolded by rememberUpdatedState(fan)
    LaunchedEffect(reveal.requests) {
        if (reveal.requests == 0) return@LaunchedEffect
        // A request on cards still laid out at no height is dropped, so wait until they show,
        // then ask for their full height: the page glides up while they finish unfolding.
        snapshotFlow { unfolded() }.first { it >= RevealFrom }
        reveal.requester.bringIntoView(Rect(0f, 0f, 1f, reveal.fullHeight + barRoom))
    }
    return reveal
}

/** How far the not-running cards unfold before the page starts scrolling them into view. */
private const val RevealFrom = 0.25f

/** Room asked for below the last card, so it comes to rest above the floating tab bar. */
private val RevealBarRoom = 110.dp

/**
 * One edge peeking out below the folded stack's front card, [inset] narrower on each side and
 * [drop] lower. [folded] (1 folded, 0 open) slides it up under the front card and fades it.
 */
private fun Modifier.peekEdge(inset: Dp, drop: Dp, fill: Color, border: Color, corner: Dp, folded: () -> Float): Modifier =
    padding(horizontal = inset)
        .graphicsLayer { translationY = drop.toPx() * folded(); alpha = folded() }
        .background(fill, RoundedCornerShape(corner))
        .border(1.dp, border, RoundedCornerShape(corner))

/** Adds [fraction] of [extra] as room below the content (the folded stack's peeking edges). */
private fun Modifier.roomBelow(extra: Dp, fraction: () -> Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height + (extra.toPx() * fraction()).roundToInt()) { p.place(0, 0) }
}

/** The Transit tab: the map around you, then your trackers as line strips straight on the page. */
@Composable
fun TransitScreen(repo: TransitRepository, deviceLocation: DeviceLocation, kv: KeyValueStore) {
    val scope = rememberCoroutineScope()
    val c = Vectra.colors

    LifecycleResumeEffect(repo) {
        repo.onAppResumed()
        onPauseOrDispose { }
    }

    val trackersState = repo.trackersQuery.collect(Unit)
    val trackers = trackersState.data.orEmpty()
    // First ever fetch still in flight (no offline cache to seed from yet).
    val firstLoad = trackersState.isFetching && !trackersState.isFetched
    DemoSeedEffect(repo, trackers, trackersState.isFetched && trackersState.data != null)

    val navigator = LocalNavigator.current
    var menuTracker by remember { mutableStateOf<TransitTracker?>(null) }
    val nearbyStops = rememberNearbyStops(repo, deviceLocation)
    val peekStops = remember(nearbyStops.stops) { nearbyStops.stops.take(8).map { PeekStop(it.id, it.lat, it.lon) } }

    // Departures verdicts separate active trackers from those with no upcoming departures.
    val hasDepartures = remember { mutableStateMapOf<String, Boolean>() }
    var showInactive by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { showInactive = runSuspendCatching { kv.get(ShowInactiveKey) == "true" }.getOrDefault(false) }

    // The active/inactive split waits until every tracker has reported once, so
    // no-departure trackers don't jump into the stack mid-glance.
    val departuresSettled = trackers.isNotEmpty() && trackers.all { hasDepartures[it.id] != null }
    val activeTrackers = if (departuresSettled) trackers.filter { hasDepartures[it.id] != false } else trackers
    val inactiveTrackers = if (departuresSettled) trackers.filter { hasDepartures[it.id] == false } else emptyList()
    val allInactive = trackers.isNotEmpty() && activeTrackers.isEmpty() && inactiveTrackers.isNotEmpty()

    val isOffline = !rememberOnline(repo.connectivity)

    RegisterNavbarActions(
        NavbarActionConfig(
            icon = RemixIcons.AddLine,
            actions = buildList {
                if (trackers.isNotEmpty()) add(NavbarAction("reorder", "Manage Trackers", RemixIcons.Equalizer2Line, NavbarActionVariant.Gray) {
                    navigator.push(Screen.ManageTrackers)
                })
                add(NavbarAction("nearby", "Show Nearby", RemixIcons.MapPinLine, NavbarActionVariant.Emerald) { navigator.push(Screen.NearbyExplorer) })
                add(NavbarAction("add-tracker", "Add Tracker", RemixIcons.AddLine, NavbarActionVariant.Primary) { navigator.push(Screen.AddTracker) })
            },
        ),
    )

    @Composable
    fun TrackerCard(tracker: TransitTracker) {
        LineStripCard(
            repo = repo,
            tracker = tracker,
            position = nearbyStops.position,
            onOpen = { navigator.openLiveTracker(it.id, it.stopId) },
            onLongPress = { menuTracker = it },
            onHasDeparturesChange = { id, has -> if (hasDepartures[id] != has) hasDepartures[id] = has },
            // Without location, the first card asks once for walking times.
            onAskLocation = if (tracker.id == (activeTrackers.firstOrNull() ?: trackers.firstOrNull())?.id) nearbyStops.refreshLocation else null,
        )
    }

    val wide = LocalWideLayout.current
    val shape = Radius.Card

    @Composable
    fun MapStrip(height: Dp, modifier: Modifier = Modifier) {
        NearbyMapStrip(
            status = nearbyStops.status,
            position = nearbyStops.position,
            stops = peekStops,
            onOpen = { navigator.push(Screen.NearbyExplorer) },
            onLocate = nearbyStops.refreshLocation,
            modifier = modifier,
            height = height,
        )
    }

    /** Your trackers under a "Trackers" header, the lines with nothing coming folded into a stack. */
    @Composable
    fun Trackers() {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val count = trackers.size
            TransitSectionHeader("Trackers", if (count > 0) "$count ${if (count == 1) "line" else "lines"}" else null)
            when {
                trackers.isEmpty() && firstLoad -> TrackersLoading()
                trackers.isEmpty() -> NoTrackersCard(onAdd = { navigator.push(Screen.AddTracker) })
                allInactive -> {
                    // Every tracked line is off-service (late at night): say so, and show them all.
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).background(Palette.Indigo500.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                            SleepingIcon(size = 16.dp, tint = if (c.isDark) Palette.Indigo300 else Palette.Indigo400)
                        }
                        Column {
                            VText("All tracked lines are off-service", style = vtext(15.sp to 20.sp, Weight.Bold), color = c.textPrimary)
                            VText("Nothing is running right now.", style = vtext(13.sp to 18.sp), color = c.textSecondary)
                        }
                    }
                    trackers.forEach { t -> key(t.id) { TrackerCard(t) } }
                }
                else -> {
                    activeTrackers.forEach { t -> key(t.id) { TrackerCard(t) } }
                    if (inactiveTrackers.isNotEmpty()) {
                        InactiveStack(inactiveTrackers, showInactive, onToggle = {
                            showInactive = !showInactive
                            val next = showInactive
                            scope.launch { runSuspendCatching { kv.set(ShowInactiveKey, next.toString()) } }
                        }) { TrackerCard(it) }
                    }
                }
            }
        }
    }

    VectraPage(
        maxWidth = if (wide) WideContentMaxWidth else ContentMaxWidth,
        // Wide: the map stays put on the left while the trackers scroll beside it.
        pane = if (!wide) null else PagePane(PaneSide.Start, weight = 0.5f) {
            BoxWithConstraints(Modifier.fillMaxSize().glass(shape).clip(shape)) { MapStrip(maxHeight) }
        },
        onRefresh = { repo.refresh() },
    ) {
        if (isOffline) {
            OfflineBanner(Modifier.widthIn(max = ContentMaxWidth), message = "No internet connection — live arrivals may be stale", badgeLabel = "Offline")
        }
        Reveal(0, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().widthIn(max = ContentMaxWidth), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // The map around you as its own card (tap for the nearby sheet).
                if (!wide) Box(Modifier.fillMaxWidth().glass(shape).clip(shape)) { MapStrip(NearbyMapStripHeight) }
                Trackers()
            }
        }
    }

    val toaster = LocalToaster.current
    val menu = menuTracker
    ActionSheet(
        open = menu != null,
        onDismiss = { menuTracker = null },
        title = menu?.let { "${it.routeShortName} · ${it.stopName}" },
        actions = if (menu == null) emptyList() else listOf(
            SheetAction("Live departures", RemixIcons.TimeLine) { navigator.openLiveTracker(menu.id, menu.stopId) },
            SheetAction("Edit tracker", RemixIcons.PencilLine) { navigator.push(Screen.EditTracker(menu.id)) },
            SheetAction(if (menu.isFavorite) "Remove from favourites" else "Add to favourites", if (menu.isFavorite) RemixIcons.StarFill else RemixIcons.StarLine) {
                scope.launch { runSuspendCatching { repo.toggleFavorite(menu.id) }.onFailure { toaster.error("Could not update favourites", it.message) } }
            },
            SheetAction("Remove tracker", RemixIcons.DeleteBinLine, destructive = true) {
                repo.scope.launch { runSuspendCatching { removeTrackerWithUndo(repo, toaster, menu) }.onFailure { toaster.error("Failed to remove tracker", trackerErrorMessage(it)) } }
            },
        ),
    )
}

/** First load with nothing cached: two cards in the real shape, no spinner. */
@Composable
private fun TrackersLoading() {
    val d = rememberDayColors()
    repeat(2) {
        Box(Modifier.fillMaxWidth().height(156.dp).stripCardSurface()) {
            Box(Modifier.padding(16.dp).size(32.dp).background(d.placeholder, RoundedCornerShape(8.dp)))
        }
    }
}

/** Before the first tracker: what a tracker is, and a way to add one. */
@Composable
private fun NoTrackersCard(onAdd: () -> Unit) {
    val c = Vectra.colors
    // What a tracker looks like: a greyed line with your stop and one vehicle on the way.
    val sample = remember { StripModel(vehicles = listOf(StripVehicle(1.4f, 1, null))) }
    val colors = stripColors(null, null, rememberDayColors().card, grey = true)
    Column(
        Modifier.fillMaxWidth().stripCardSurface().padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LineStrip(sample, colors, Modifier.padding(bottom = 6.dp), labels = false)
        VText("No trackers yet", style = vtext(17.sp to 22.sp, Weight.Bold), color = c.textPrimary)
        VText(
            "Pick a line and a stop, and it shows up here with its vehicles on the way.",
            style = vtext(13.sp to 18.sp),
            color = c.textSecondary,
            align = TextAlign.Center,
        )
        VButton("Add a tracker", onClick = onAdd, Modifier.padding(top = 8.dp), icon = RemixIcons.AddLine)
    }
}

/**
 * Lines with nothing coming fold into a stack: one card with their badges and two edges
 * peeking under it. Tapping fans the full cards out below.
 */
@Composable
private fun InactiveStack(trackers: List<TransitTracker>, open: Boolean, onToggle: () -> Unit, card: @Composable (TransitTracker) -> Unit) {
    val c = Vectra.colors
    val d = rememberDayColors()
    // The same spring as today's finished-classes stack, so both fold and unfold alike.
    val fan by animateFloatAsState(
        if (open) 1f else 0f,
        if (Vectra.reduceMotion) tween(150) else spring(dampingRatio = 0.86f, stiffness = 300f),
        label = "inactiveFan",
    )
    val front = RoundedCornerShape(18.dp)
    val reveal = rememberStackReveal { fan }
    val toggle = {
        if (!open) reveal.open()
        onToggle()
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The two edges peeking out below tuck up under the front card as the stack opens.
        Box(Modifier.fillMaxWidth().roomBelow(14.dp) { 1f - fan }) {
            if (fan < 1f) {
                Box(Modifier.matchParentSize().peekEdge(22.dp, 14.dp, d.edgeFar, d.border, 14.dp) { 1f - fan })
                Box(Modifier.matchParentSize().peekEdge(11.dp, 7.dp, d.edgeNear, d.border, 15.dp) { 1f - fan })
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .stripCardSurface(front)
                    .clip(front)
                    .tappable(front, role = null, onClickLabel = if (open) "Fold away" else "Show them", onClick = toggle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VIcon(RemixIcons.MoonFill, size = 18.dp, tint = if (c.isDark) Palette.Indigo300 else Palette.Indigo400)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VText("Not running right now", style = vtext(14.sp to 18.sp, Weight.Semibold), color = c.textSecondary)
                    // One badge per line: several trackers on the same line read as one.
                    val lines = remember(trackers) { trackers.distinctBy { formatRouteDisplayName(it.routeShortName, it.routeId) } }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        lines.take(StackBadges).forEach { t ->
                            RouteBadge(formatRouteDisplayName(t.routeShortName, t.routeId), t.routeColor, t.routeTextColor, size = RouteBadgeSize.Xs)
                        }
                        if (lines.size > StackBadges) {
                            Box(Modifier.height(28.dp).background(c.surfaceOverlay, CircleShape).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                                VText("+${lines.size - StackBadges}", style = vtext(11.sp to 14.sp, Weight.Bold), color = c.textSecondary)
                            }
                        }
                    }
                }
                VIcon(RemixIcons.ArrowDownSLine, Modifier.graphicsLayer { rotationZ = 180f * fan }, tint = c.textMuted)
            }
        }
        // Folded cards stay composed (hidden) so their arrivals keep polling. They unfold from
        // under the front card: the room grows with the spring while they slide down and fade in.
        Column(
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(reveal.requester)
                .unfold({ fan }) { reveal.fullHeight = it }
                .graphicsLayer { alpha = fan; translationY = -24.dp.toPx() * (1f - fan) },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            trackers.forEach { t -> key(t.id) { card(t) } }
        }
    }
}
