package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.Dp
import dev.southstar21.vectra.core.designsystem.theme.LocalWideLayout
import dev.southstar21.vectra.core.designsystem.theme.WideContentMaxWidth
import dev.southstar21.vectra.features.shell.PagePane
import dev.southstar21.vectra.features.shell.PaneSide
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.SheetAction
import dev.southstar21.vectra.core.designsystem.components.ActionSheet
import dev.southstar21.vectra.core.designsystem.components.EmptyState
import dev.southstar21.vectra.core.designsystem.components.OfflineBanner
import dev.southstar21.vectra.core.designsystem.components.PostageStamp
import dev.southstar21.vectra.core.designsystem.components.Reveal
import dev.southstar21.vectra.core.designsystem.components.SleepingIcon
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.ContentMaxWidth
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.navbar.NavbarAction
import dev.southstar21.vectra.features.navbar.NavbarActionConfig
import dev.southstar21.vectra.features.navbar.NavbarActionVariant
import dev.southstar21.vectra.features.navbar.RegisterNavbarActions
import dev.southstar21.vectra.features.shell.VectraPage
import dev.southstar21.vectra.navigation.LocalNavigator
import dev.southstar21.vectra.navigation.Screen
import kotlinx.coroutines.launch
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Radius
import dev.southstar21.vectra.core.designsystem.theme.tappable

private const val ShowInactiveKey = "vectra_show_inactive_transits"

private enum class CoverMode { All, Map, Content }

/** Composed (so arrivals keep polling) but takes no space and draws nothing. */
private fun Modifier.hiddenButComposed(hidden: Boolean): Modifier =
    if (!hidden) this else layout { measurable, constraints ->
        measurable.measure(constraints)
        layout(0, 0) {}
    }

/** A section title as a postage stamp stuck onto the card's edge. */
@Composable
private fun SectionStamp(title: String, caption: String, covered: Boolean, modifier: Modifier) {
    val c = Vectra.colors
    val alpha by animateFloatAsState(if (covered) 0f else 1f, tween(300), label = "stamp")
    PostageStamp(
        modifier.zIndex(40f).graphicsLayer { this.alpha = alpha },
        innerModifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
    ) {
        VText(title, style = vtext(TypeScale.BodyLarge, Weight.Extrabold, Tracking.Tight, lineHeight = 16.sp), color = c.textPrimary)
        VText(caption, style = vtext(11.sp to 11.sp, Weight.Semibold), color = c.accentPrimary)
    }
}

/** Tracker monitors stacked in one column. */
@Composable
private fun TrackerGrid(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** The Transit tab: the map around you and your trackers as stop monitors. */
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

    val navigator = LocalNavigator.current
    var menuTracker by remember { mutableStateOf<TransitTracker?>(null) }
    val nearbyStops = rememberNearbyStops(repo, deviceLocation)
    val peekStops = remember(nearbyStops.stops) { nearbyStops.stops.take(8).map { PeekStop(it.id, it.lat, it.lon) } }

    // Departures verdicts separate active trackers from those with no upcoming departures.
    val hasDepartures = remember { mutableStateMapOf<String, Boolean>() }
    var showInactive by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { showInactive = runSuspendCatching { kv.get(ShowInactiveKey) == "true" }.getOrDefault(false) }

    // Loading / stale cover: one spinner covers everything below the map — or
    // the whole card while the map itself hasn't loaded. A loaded map is never covered again.
    val pendingMap = remember { mutableStateMapOf<String, Boolean>() }
    var mapPending by remember { mutableStateOf(false) }

    // The active/inactive split waits until every tracker has reported once, so
    // no-departure trackers don't jump into the folded row mid-glance.
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

    val dataPending = firstLoad || trackers.any { pendingMap[it.id] ?: true }
    val coverMode = when {
        dataPending && mapPending -> CoverMode.All
        dataPending -> CoverMode.Content
        mapPending -> CoverMode.Map
        else -> null
    }
    val coverLabel = when {
        coverMode == CoverMode.Map -> "Loading map…"
        firstLoad || coverMode == CoverMode.All -> "Loading transit…"
        else -> "Updating departures…"
    }
    val sectionsCovered = coverMode == CoverMode.Content || coverMode == CoverMode.All

    @Composable
    fun BoxScope.Cover(vararg regions: CoverMode) {
        val pinned = CoverMode.Map !in regions
        LoadingOverlay(
            show = coverMode in regions,
            modifier = Modifier.zIndex(30f),
            label = coverLabel,
            maxDurationMs = 15_000,
            // Trackers can run below the fold: one large orb near the top instead of centred.
            contentAlignment = if (pinned) Alignment.TopCenter else Alignment.Center,
            contentModifier = if (pinned) Modifier.padding(top = 96.dp) else Modifier,
            contentSpacing = if (pinned) 40.dp else 12.dp,
            indicator = {
                ThinkingOrb(Modifier.graphicsLayer { if (pinned) { scaleX = 1.75f; scaleY = 1.75f } }, size = 64.dp)
            },
        )
    }

    @Composable
    fun TrackerCard(tracker: TransitTracker) {
        TransitCard(
            repo = repo,
            tracker = tracker,
            onOpen = { navigator.push(Screen.LiveTracker(it.id, it.stopId)) },
            onLongPress = { menuTracker = it },
            onHasDeparturesChange = { id, has -> if (hasDepartures[id] != has) hasDepartures[id] = has },
            onPendingChange = { id, p -> if (pendingMap[id] != p) pendingMap[id] = p },
        )
    }

    val wide = LocalWideLayout.current

    @Composable
    fun MapStrip(height: Dp, modifier: Modifier = Modifier) {
        Box(modifier.fillMaxWidth()) {
            NearbyMapStrip(
                status = nearbyStops.status,
                position = nearbyStops.position,
                stops = peekStops,
                onOpen = { navigator.push(Screen.NearbyExplorer) },
                onLocate = nearbyStops.refreshLocation,
                onMapPendingChange = { mapPending = it },
                showLoadingOverlay = false,
                height = height,
            )
            Cover(CoverMode.Map)
        }
    }

    /** Your trackers as stop monitors, under their postage stamp. */
    @Composable
    fun Trackers() {
        Box(Modifier.fillMaxWidth()) {
            // Trackers
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 44.dp, bottom = 16.dp)) {
                    when {
                        trackers.isEmpty() -> EmptyState("No trackers yet", Modifier.offset(y = (-16).dp), emoji = "🚋")
                        allInactive -> {
                            // When all tracked lines are off-service (e.g. late night).
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(36.dp).background(Palette.Indigo500.copy(alpha = 0.1f), CircleShape).border(1.dp, Palette.Indigo500.copy(alpha = 0.2f), CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { SleepingIcon(size = 16.dp, tint = if (c.isDark) Palette.Indigo300 else Palette.Indigo400) }
                                    Column {
                                        VText("All tracked lines are currently off-service", style = vtext(TypeScale.Body, Weight.Bold), color = c.textPrimary)
                                        VText("No departures are running right now.", style = vtext(TypeScale.Caption), color = c.textMuted)
                                    }
                                }
                                TrackerGrid { trackers.forEach { t -> key(t.id) { TrackerCard(t) } } }
                            }
                        }
                        else -> {
                            TrackerGrid { activeTrackers.forEach { t -> key(t.id) { TrackerCard(t) } } }
                            if (inactiveTrackers.isNotEmpty()) {
                                InactiveFold(inactiveTrackers, showInactive, onToggle = {
                                    showInactive = !showInactive
                                    val next = showInactive
                                    scope.launch { runSuspendCatching { kv.set(ShowInactiveKey, next.toString()) } }
                                }) { TrackerCard(it) }
                            }
                        }
                    }
                }
                SectionStamp(
                    "Trackers",
                    "${trackers.size} ${if (trackers.size == 1) "line" else "lines"}",
                    sectionsCovered,
                    Modifier.offset(x = 16.dp, y = (-28).dp).rotate(-3f),
                )
            }
            // Wide, the trackers have a card of their own, so a full cover reaches them too.
            if (wide) Cover(CoverMode.Content, CoverMode.All) else Cover(CoverMode.Content)
        }
    }

    val shape = Radius.Card
    VectraPage(
        maxWidth = if (wide) WideContentMaxWidth else ContentMaxWidth,
        // Wide: the map stays put on the left while the trackers scroll beside it.
        pane = if (!wide) null else PagePane(PaneSide.Start, weight = 0.5f) {
            BoxWithConstraints(Modifier.fillMaxSize().glass(shape).clip(shape)) {
                MapStrip(maxHeight)
                Cover(CoverMode.All)
            }
        },
        onRefresh = { repo.refresh() },
    ) {
        if (isOffline) {
            OfflineBanner(Modifier.widthIn(max = ContentMaxWidth), message = "No internet connection — live arrivals may be stale", badgeLabel = "Offline")
        }
        Reveal(0, Modifier.fillMaxWidth()) {
            if (wide) {
                // The stamp hangs above the trackers; the card clips, so it starts inside the card.
                Box(Modifier.fillMaxWidth().glass(shape).clip(shape).padding(top = 30.dp)) { Trackers() }
            } else {
                // One card: the map around you (tap for the nearby sheet), then your
                // trackers as stop monitors.
                Box(Modifier.fillMaxWidth().widthIn(max = ContentMaxWidth).glass(shape).clip(shape)) {
                    Column(Modifier.fillMaxWidth()) {
                        MapStrip(NearbyMapStripHeight)
                        Trackers()
                    }
                    Cover(CoverMode.All)
                }
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
            SheetAction("Live departures", RemixIcons.TimeLine) { navigator.push(Screen.LiveTracker(menu.id, menu.stopId)) },
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

/** Lines with no upcoming departures fold into one quiet row that opens to their monitors. */
@Composable
private fun InactiveFold(trackers: List<TransitTracker>, open: Boolean, onToggle: () -> Unit, card: @Composable (TransitTracker) -> Unit) {
    val c = Vectra.colors
    Column(Modifier.padding(top = 10.dp, bottom = 12.dp)) {
        val chevron by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "chev")
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Radius.Control)
                .tappable(role = null, onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                VText("Not running right now", Modifier.weight(1f), vtext(TypeScale.Body, Weight.Medium), c.textSecondary)
                VIcon(RemixIcons.ArrowDownSLine, Modifier.rotate(chevron), tint = c.textMuted)
            }
            // Any number of lines wraps instead of squeezing the label.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                trackers.forEach { t ->
                    RouteBadge(formatRouteDisplayName(t.routeShortName, t.routeId), t.routeColor, t.routeTextColor, size = RouteBadgeSize.Xs)
                }
            }
        }
        // Inactive monitors stay composed (hidden) so their arrivals keep polling.
        TrackerGrid(Modifier.padding(top = if (open) 8.dp else 0.dp).hiddenButComposed(!open)) {
            trackers.forEach { t -> key(t.id) { card(t) } }
        }
    }
}
