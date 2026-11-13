package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.BarLeading
import dev.southstar21.vectra.core.designsystem.components.BottomBar
import dev.southstar21.vectra.core.designsystem.components.DeleteRow
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.ScreenColumn
import dev.southstar21.vectra.core.designsystem.components.ScrollBody
import dev.southstar21.vectra.core.designsystem.components.SectionTitle
import dev.southstar21.vectra.core.designsystem.components.SheetInputHeight
import dev.southstar21.vectra.core.designsystem.components.SurfaceGutter
import dev.southstar21.vectra.core.designsystem.components.TopBar
import dev.southstar21.vectra.core.designsystem.components.TopBarSubtitle
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VInput
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dev.southstar21.vectra.core.designsystem.theme.tappable

private val EditMapHeight = 208.dp

/**
 * The stop editor for one tracker: the route on a map (tap a dot to track or untrack it),
 * the tracked stops with their directions, a search to add more, and a two-tap delete
 * that can be undone from its toast. Pops itself if the tracker disappears.
 */
@Composable
fun EditTrackerScreen(repo: TransitRepository, trackerId: String, onBack: () -> Unit) {
    val toaster = LocalToaster.current
    val haptics = Vectra.haptics
    val scope = rememberCoroutineScope()
    val tracker = rememberTracker(repo, trackerId, onMissing = onBack)

    var scrolled by remember { mutableStateOf(false) }
    val trackerStops = remember(tracker) { tracker?.let(::getTrackerStops).orEmpty() }
    val draft = rememberTrackerDraft(trackerStops)
    val stops = draft.stops
    val routeStops = draft.routeStops.orEmpty()
    var stopSearch by remember { mutableStateOf("") }
    var routeMapReady by remember { mutableStateOf(false) }
    var loadingRoute by remember { mutableStateOf(true) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // Bumped by the retry button so the route-details effect re-fetches.
    var reloadNonce by remember { mutableIntStateOf(0) }

    // Fetch the route's stops, headsigns and polylines.
    val routeId = tracker?.routeId
    LaunchedEffect(routeId, reloadNonce) {
        if (routeId == null) return@LaunchedEffect
        try {
            draft.showRoute(repo.api.fetchParsedRouteDetails(routeId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            routeError = trackerErrorMessage(e, "Failed to load route stops")
        }
        loadingRoute = false
    }

    val trackedStopIds = remember(stops) { stops.map { it.stopId }.toSet() }
    val availableStops = remember(routeStops, trackedStopIds, stopSearch) {
        val q = normalizeSearchText(stopSearch.trim())
        routeStops.filter { s -> s.id !in trackedStopIds && (q.isEmpty() || normalizeSearchText(s.name).contains(q)) }
    }

    val previewVehicles = rememberPreviewVehicles(repo, routeId?.takeIf { it.isNotEmpty() }, stops)

    val c = Vectra.colors
    val scroll = rememberScrollState()
    if (tracker == null) {
        // Still loading the trackers: an empty screen with a way out.
        ScreenColumn { TopBar("", BarLeading.Back, onBack) }
        return
    }

    val displayName = formatRouteDisplayName(tracker.routeShortName, tracker.routeId)

    val handleRemoveStop: (String) -> Unit = draft::remove
    val handleAddStop: (FutarStop) -> Unit = { stop ->
        if (!draft.add(stop)) toaster.warning("Maximum 4 stops", "You can track up to $MaxTrackerStops stops on this line.")
    }
    val handleDirectionChange: (String, String) -> Unit = draft::setDirection

    val handleSave: () -> Unit = save@{
        val current = stops
        if (current.isEmpty()) return@save
        scope.launch {
            saving = true
            try {
                repo.updateTrackerStops(tracker.id, current)
                saving = false
                toaster.success("Tracker updated")
                haptics.confirm()
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                saving = false
                toaster.error("Failed to update tracker", trackerErrorMessage(e))
            }
        }
    }

    // The two-tap guard lives in DeleteRow; undo re-creates the tracker from this copy.
    val handleDeleteTracker: () -> Unit = {
        val deleted = tracker
        scope.launch {
            deleting = true
            try {
                removeTrackerWithUndo(repo, toaster, deleted)
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toaster.error("Failed to remove tracker", trackerErrorMessage(e))
            } finally {
                deleting = false
            }
        }
    }

    val isAtStopLimit = stops.size >= MaxTrackerStops

    ScreenColumn {
        TopBar(
            title = displayName,
            leading = BarLeading.Back,
            onLeading = onBack,
            titleContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RouteBadge(displayName, tracker.routeColor, tracker.routeTextColor, size = RouteBadgeSize.Sm)
                    FormattedRouteTitle(
                        tracker.routeLongName.ifEmpty { displayName },
                        Modifier.weight(1f, fill = false),
                        style = vtext(TypeScale.BodyLarge, Weight.Extrabold, Tracking.Tight),
                        color = c.textPrimary,
                    )
                }
            },
            subtitle = { TopBarSubtitle(routeTypeMeta(tracker.routeType).label) },
            scrolled = scrolled,
        )

        // No stretch overscroll: the map is a separate surface the stretch cannot distort.
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            ScrollBody(
                scrollState = scroll,
                onScrolledChange = { scrolled = it },
                fadeBottom = false,
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                // The route leads, edge to edge; tap a dot to track or untrack it.
                Box(Modifier.fillMaxWidth().height(EditMapHeight)) {
                    val previewStops = remember(routeStops, trackedStopIds) {
                        routeStops.map { s -> RoutePreviewStop(s.id, s.name, s.lat, s.lon, selected = s.id in trackedStopIds) }
                    }
                    RoutePreviewMap(
                        polylines = draft.routePolylines,
                        stops = previewStops,
                        vehicles = previewVehicles,
                        routeColor = tracker.routeColor.ifEmpty { "64748b" },
                        height = EditMapHeight,
                        onReady = { routeMapReady = true },
                        onSelectStop = { stopId ->
                            if (stopId in trackedStopIds) handleRemoveStop(stopId)
                            else routeStops.firstOrNull { it.id == stopId }?.let(handleAddStop)
                        },
                    )
                    MapLoadingOrb(show = loadingRoute || !routeMapReady, label = "Loading route map…")
                    MapBottomFade()
                }

                Column(
                    Modifier.padding(start = SurfaceGutter, end = SurfaceGutter, top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Tracked stops", action = { StopCountPill(stops.size) })
                        val rows = buildList<@Composable () -> Unit> {
                            stops.forEach { stop ->
                                add {
                                    TrackedStopRow(
                                        stop = stop,
                                        routeHeadsigns = draft.routeHeadsigns,
                                        onDirectionChange = { h -> handleDirectionChange(stop.stopId, h) },
                                        onRemove = { handleRemoveStop(stop.stopId) },
                                    )
                                }
                            }
                            if (stops.isEmpty()) {
                                add {
                                    SheetMutedText(
                                        "No stops tracked. Add one below, or delete the tracker.",
                                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                                    )
                                }
                            }
                        }
                        ClippedFormGroup(rows = rows)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Add a stop")
                        if (isAtStopLimit) {
                            SheetFootnote("That’s the most stops a line can track. Remove one above to add another.")
                        } else {
                            VInput(
                                value = stopSearch,
                                onValueChange = { stopSearch = it },
                                placeholder = "Search stops on $displayName…",
                                icon = RemixIcons.SearchLine,
                                height = SheetInputHeight,
                                textSize = TypeScale.BodyLarge,
                            )
                            when {
                                loadingRoute -> ListStatus(Modifier.edgeStatus()) { SheetListSpinner() }
                                routeError != null -> ListStatus(Modifier.edgeStatus()) {
                                    SheetMutedText(routeError!!, Modifier.padding(horizontal = 8.dp), c.tints.red.text)
                                    SheetRetryButton {
                                        routeError = null
                                        loadingRoute = true
                                        reloadNonce++
                                    }
                                }
                                availableStops.isNotEmpty() -> ClippedFormGroup(
                                    rows = availableStops.take(20).map { stop ->
                                        @Composable {
                                            ListRow(
                                                stop.name,
                                                onClick = { handleAddStop(stop) },
                                                trailing = { VIcon(RemixIcons.AddLine, tint = c.accentPrimary) },
                                            )
                                        }
                                    },
                                )
                                else -> ListStatus(Modifier.edgeStatus()) {
                                    SheetMutedText(
                                        if (stopSearch.trim().isNotEmpty()) "No stops match “${stopSearch.trim()}”"
                                        else "Every stop on this line is already tracked.",
                                    )
                                }
                            }
                        }
                    }

                    DeleteRow(
                        label = "Delete tracker",
                        onConfirm = handleDeleteTracker,
                        enabled = !saving,
                        busy = deleting,
                    )
                }
            }
        }

        BottomBar {
            SheetPrimaryButton(onClick = handleSave, Modifier.fillMaxWidth(), pressedScale = 0.98f, enabled = stops.isNotEmpty() && !saving && !deleting) {
                SheetPrimaryLabel(saving, if (stops.isEmpty()) "Keep at least one stop" else "Save changes")
            }
        }
    }
}

/** [ListStatus] pads 40dp; the add-a-stop states sit tighter, so pull 16dp back on each side. */
private fun Modifier.edgeStatus(): Modifier = negativeVertical(16.dp)

private fun Modifier.negativeVertical(amount: Dp): Modifier =
    layout { measurable, constraints ->
        val px = amount.roundToPx()
        val placeable = measurable.measure(constraints)
        val h = (placeable.height - px * 2).coerceAtLeast(0)
        layout(placeable.width, h) { placeable.place(0, -px) }
    }

@Composable
private fun TrackedStopRow(
    stop: TrackerStop,
    routeHeadsigns: List<String>,
    onDirectionChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val c = Vectra.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        VIcon(RemixIcons.MapPinLine, Modifier.padding(top = 8.dp), tint = c.tints.blue.text)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VText(stop.stopName, Modifier.padding(top = 6.dp), vtext(TypeScale.Body, Weight.Semibold), c.textPrimary, maxLines = 1)
            if (routeHeadsigns.isNotEmpty()) {
                DirectionSelect(stop.headsign.orEmpty(), routeHeadsigns, onDirectionChange, Modifier.fillMaxWidth())
            }
        }
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Box(
            Modifier
                .size(36.dp)
                .pressScale(interaction)
                .background(if (pressed) Palette.Rose500.copy(alpha = 0.1f) else Color.Transparent, CircleShape)
                .tappable(role = Role.Button, highlight = false, interactionSource = interaction, onClick = onRemove)
                .semantics { contentDescription = "Stop tracking ${stop.stopName}" },
            contentAlignment = Alignment.Center,
        ) {
            VIcon(RemixIcons.DeleteBinLine, tint = if (pressed) c.tints.red.text else c.textMuted)
        }
    }
}
