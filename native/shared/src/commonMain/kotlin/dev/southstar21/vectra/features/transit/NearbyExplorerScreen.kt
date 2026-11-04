package dev.southstar21.vectra.features.transit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.time.nowMs
import kotlinx.coroutines.delay
import dev.southstar21.vectra.core.designsystem.theme.consumeTouches
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Radius

/**
 * The full-screen nearby explorer: the interactive map, the selected route or
 * stop header, recenter/back buttons and the draggable departures sheet.
 */
@Composable
fun NearbyExplorerScreen(repo: TransitRepository, deviceLocation: DeviceLocation, auth: AuthRepository, onBack: () -> Unit) {
    ExplorerShell(repo, deviceLocation, auth, onBack)
}

@Composable
private fun ExplorerShell(repo: TransitRepository, deviceLocation: DeviceLocation, auth: AuthRepository, onBack: () -> Unit) {
    val c = Vectra.colors
    val explorer = rememberNearbyExplorer(repo, deviceLocation, auth)
    var cameraRequest by remember { mutableStateOf<ExplorerCameraRequest?>(null) }
    // Settled coverage goes through state (re-queries the map); per-frame coverage
    // is read only at layout time so a drag never recomposes the map or the feed.
    var sheetCovered by remember { mutableFloatStateOf(0f) }
    val sheetLive = remember { mutableFloatStateOf(0f) }
    var mapReady by remember { mutableStateOf(false) }

    // Never leave the cover stuck if readiness never arrives.
    LaunchedEffect(mapReady) {
        if (mapReady) return@LaunchedEffect
        delay(2500)
        mapReady = true
    }

    val user = if (explorer.geoState.status == WatchLocationStatus.Tracking)
        remember(explorer.geoState.lat, explorer.geoState.lon, explorer.geoState.accuracy) {
            ExplorerUser(explorer.geoState.lat, explorer.geoState.lon, explorer.geoState.accuracy)
        }
    else null

    val handleRecenter = {
        val target = explorer.anchor?.let { LatLon(it.lat, it.lon) } ?: explorer.initialCenter
        cameraRequest = ExplorerCameraRequest(target.lat, target.lon, 15.5, nowMs())
        if (explorer.geoState.status != WatchLocationStatus.Tracking) explorer.refreshLocation()
    }

    val handleSelectItem = { item: RadarDepartureItem ->
        explorer.handleSelectItem(item)
        val stop = explorer.stopsInView.firstOrNull { it.id == item.stopId }
        // Match the selection and marker: the departure's own physical stop first.
        val lat = item.platformLat ?: stop?.lat
        val lon = item.platformLon ?: stop?.lon
        if (lat != null && lon != null) cameraRequest = ExplorerCameraRequest(lat, lon, 15.5, nowMs())
    }

    val selection = explorer.selection
    val selectionKey = selection?.let { NearbySelectionKey(it.stopId, it.routeId, it.headsign, it.shortName, it.color, it.textColor) }
    val safeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().background(c.surfacePrimary)) {
        NearbyExplorerMap(
            initialCenter = explorer.initialCenter,
            stops = explorer.mapStops,
            focusedStopId = explorer.focusedStopId,
            selectedStop = explorer.selectedStop,
            polylines = explorer.polylines,
            routeStops = explorer.routeStops,
            routeColor = selection?.color ?: "F7C600",
            vehicles = explorer.vehicles,
            cameraRequest = cameraRequest,
            user = user,
            bottomInset = sheetCovered.dp,
            attributionBottom = { sheetLive.floatValue.dp },
            onReady = { mapReady = true },
            onViewportChange = explorer.handleViewportChange,
            onSelectStop = explorer.focusStop,
            modifier = Modifier.fillMaxSize(),
        )

        // Covers the map's blank init frame until the first idle paint.
        val coverAlpha by animateFloatAsState(if (mapReady) 0f else 1f, tween(300), label = "explorerCover")
        if (coverAlpha > 0.001f) {
            Column(
                Modifier.fillMaxSize().graphicsLayer { alpha = coverAlpha }.background(c.surfacePrimary),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NearbyBorderSpinner(32.dp, c.tints.blue.text, Color.Transparent, stroke = 2.dp, gapTop = true)
                VText("Loading map…", style = vtext(TypeScale.Caption, Weight.Medium), color = c.textSecondary)
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))),
        )

        // Recenter + back, riding 12dp above the sheet.
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp)
                .offset { IntOffset(0, -((sheetLive.floatValue + 12f).dp.roundToPx())) },
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MapChromeButton(RemixIcons.Focus3Line, "Recenter on your location", handleRecenter)
            MapChromeButton(RemixIcons.ArrowLeftLine, "Back", onBack)
        }

        val headerTop = safeTop + 12.dp
        if (selection != null) {
            SelectionHeader(selection, explorer.trackedCountdownMinutes ?: 0, Modifier.padding(top = headerTop, start = 12.dp, end = 12.dp))
        } else if (explorer.focusedStop != null) {
            FocusedStopHeader(explorer.focusedStop.name, explorer.clearFocus, Modifier.padding(top = headerTop, start = 12.dp, end = 12.dp))
        }

        val bannerTop = safeTop + if (selection != null || explorer.focusedStop != null) 128.dp else 64.dp
        if (explorer.anchor?.outsideHungary == true) {
            val shape = Radius.Control
            VText(
                "Outside Hungary — showing Budapest center.",
                Modifier
                    .padding(top = bannerTop, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
                    .background(Palette.Amber500.copy(alpha = 0.1f), shape)
                    .border(1.dp, c.tints.amber.text.copy(alpha = 0.3f), shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                vtext(11.sp to 16.5.sp, Weight.Medium),
                c.tints.amber.text,
            )
        }
        if (explorer.geoState.status == WatchLocationStatus.Error) {
            GeoErrorBanner(explorer.geoState.errorMessage.orEmpty(), explorer.refreshLocation, Modifier.padding(top = bannerTop, start = 12.dp, end = 12.dp))
        }

        NearbyDeparturesSheet(
            feed = explorer.feed,
            selection = selectionKey,
            onSelectItem = handleSelectItem,
            modeFilter = explorer.modeFilter,
            onToggleMode = explorer.handleToggleMode,
            arrivalsEnabled = explorer.arrivalsEnabled,
            isLoading = explorer.isArrivalsLoading,
            visibleStopCount = explorer.visibleStopCount,
            polledStopCount = explorer.polledStopCount,
            onCoverageChange = { covered, settled ->
                sheetLive.floatValue = covered
                if (settled) sheetCovered = covered
            },
        )
    }
}

@Composable
private fun MapChromeButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Vectra.colors
    val shape = Radius.Control
    Box(
        Modifier
            .size(44.dp)
            .boxShadow(shape, NearbyShadows.lg())
            .background(c.surfaceSecondary.copy(alpha = c.surfaceSecondary.alpha * 0.95f), shape)
            .border(1.dp, c.borderSubtle, shape)
            .tappable(shape, onClickLabel = label, pressedScale = 0.95f, haptic = { tick() }, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        VIcon(icon, size = 20.dp, tint = c.textSecondary)
    }
}

@Composable
private fun BoxScope.SelectionHeader(selection: ExplorerSelection, countdown: Int, modifier: Modifier) {
    val c = Vectra.colors
    val shape = Radius.Panel
    Row(
        modifier
            .fillMaxWidth()
            .boxShadow(shape, listOf(BoxShadow(y = 5f, blur = 18f, color = nearbyHexAlpha(selection.color, 0x28))))
            .background(c.surfaceSecondary, shape)
            .border(1.dp, nearbyHexAlpha(selection.color, 0x80), shape)
            .consumeTouches(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp).height(68.dp))
        RouteBadge(selection.shortName, selection.color, selection.textColor, size = RouteBadgeSize.Lg)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                    VIcon(RemixIcons.ArrowRightLine, size = 12.dp, tint = c.textSecondary)
                }
                VText(selection.headsign.ifEmpty { "Any direction" }, style = vtext(11.sp to 16.5.sp, Weight.Bold), color = c.textPrimary, maxLines = 1)
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(6.dp).background(c.textSecondary.copy(alpha = 0.6f), CircleShape))
                }
                VText(selection.stopName, style = vtext(TypeScale.Micro, Weight.Medium), color = c.textMuted, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        NearbyEtaRow(selection.isPredicted, formatNearbyCountdown(countdown))
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun FocusedStopHeader(name: String, onClear: () -> Unit, modifier: Modifier) {
    val c = Vectra.colors
    val shape = Radius.Panel
    Row(
        modifier
            .fillMaxWidth()
            .boxShadow(shape, NearbyShadows.lg())
            .background(c.surfaceSecondary.copy(alpha = c.surfaceSecondary.alpha * 0.95f), shape)
            .border(1.dp, c.borderSubtle, shape)
            .consumeTouches()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(c.tints.blue.text.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
            VIcon(RemixIcons.MapPinLine, size = 16.dp, tint = c.tints.blue.text)
        }
        Column(Modifier.weight(1f)) {
            VText(name, style = vtext(11.sp to 16.5.sp, Weight.Bold), color = c.textPrimary, maxLines = 1)
            VText("Selected stop", style = vtext(TypeScale.Micro, Weight.Medium), color = c.textMuted, maxLines = 1)
        }
        Box(
            Modifier
                .size(32.dp)
                .tappable(CircleShape, role = null, onClick = onClear)
                .semantics { contentDescription = "Clear selected stop" },
            contentAlignment = Alignment.Center,
        ) {
            VIcon(RemixIcons.CloseLine, size = 16.dp, tint = c.textSecondary)
        }
    }
}

@Composable
private fun GeoErrorBanner(message: String, onRetry: () -> Unit, modifier: Modifier) {
    val shape = Radius.Control
    val red400 = Palette.Red400
    val red500 = Palette.Red500
    Row(
        modifier
            .fillMaxWidth()
            .background(red500.copy(alpha = 0.1f), shape)
            .border(1.dp, red500.copy(alpha = 0.3f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(message, Modifier.weight(1f), vtext(11.sp to 16.5.sp, Weight.Medium), red400)
        VText(
            "Retry",
            Modifier
                .border(1.dp, red400.copy(alpha = 0.4f), CircleShape)
                .tappable(CircleShape, role = null, onClick = onRetry)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            vtext(TypeScale.Micro, Weight.Semibold),
            red400,
            maxLines = 1,
        )
    }
}
