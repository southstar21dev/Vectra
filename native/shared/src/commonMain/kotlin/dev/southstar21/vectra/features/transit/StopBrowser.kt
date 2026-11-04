package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.SectionTitle
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.formatClock
import dev.southstar21.vectra.core.preferences.TimeFormat

private val QuickPickGroups = listOf(MetroView.M1, MetroView.M2, MetroView.M3, MetroView.M4, MetroView.POPULAR)

@Composable
private fun GroupDot(group: MetroView) {
    val c = Vectra.colors
    val m = Modifier.size(36.dp)
    if (group == MetroView.POPULAR) {
        Box(
            m.background(parseHexColor(GroupLineColors.getValue("POPULAR")) ?: Color.Unspecified, CircleShape)
                .border(1.dp, c.borderPrimary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { VText("🚏", style = vtext(TypeScale.BodyLarge), color = Color(0xFF1A1A1A)) }
        return
    }
    val colors = MetroFallbackColorsByLine.getValue(group.key)
    Box(
        m.background(parseHexColor(colors.color) ?: Color.Unspecified, CircleShape).border(1.dp, c.borderPrimary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        VText(group.key, style = vtext(TypeScale.Body, Weight.Black, mono = true), color = parseHexColor(colors.textColor) ?: Color.White)
    }
}

@Composable
private fun StopPin() = VIcon(RemixIcons.MapPinLine, tint = Vectra.colors.tints.blue.text)

/** Root of By stop: search results when typing, otherwise the curated groups. */
@Composable
fun StopBrowser(
    searchQuery: String,
    filteredStops: List<CatalogStop>,
    catalogLoaded: Boolean,
    catalogError: Boolean,
    onRetryCatalog: () -> Unit,
    onSelectStop: (String) -> Unit,
    onOpenGroup: (MetroView) -> Unit,
) {
    val query = searchQuery.trim()
    if (query.isNotEmpty()) {
        when {
            filteredStops.isNotEmpty() -> ClippedFormGroup(
                rows = filteredStops.map { stop ->
                    @Composable {
                        ListRow(stop.name, onClick = { onSelectStop(stop.id) }, leading = { StopPin() }, navigates = true)
                    }
                },
            )
            catalogLoaded -> ListStatus { SheetMutedText("No stops match “$query”") }
            catalogError -> ListStatus {
                SheetMutedText("Couldn’t load the stop catalog")
                SheetRetryButton(onRetryCatalog)
            }
            else -> ListStatus { SheetListSpinner() }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Quick picks")
        ClippedFormGroup(
            rows = QuickPickGroups.map { group ->
                @Composable {
                    val count = CuratedStops.count { it.group == group.key }
                    ListRow(
                        metroViewLabel(group),
                        onClick = { onOpenGroup(group) },
                        leading = { GroupDot(group) },
                        subtitle = "$count stops",
                        navigates = true,
                    )
                }
            },
        )
        SheetFootnote("Or search any stop in Budapest by name.")
    }
}

/** The stops of one curated group (a metro line or the popular set). */
@Composable
fun CuratedStopList(group: MetroView, onSelectStop: (String) -> Unit) {
    ClippedFormGroup(
        rows = CuratedStops.filter { it.group == group.key }.map { stop ->
            @Composable { ListRow(stop.name, onClick = { onSelectStop(stop.id) }, leading = { StopPin() }, navigates = true) }
        },
    )
}

/** One direction at a stop with its upcoming departures. */
data class StopDirection(val headSign: String, val times: List<Arrival>)

/** After a stop is picked: the lines serving it, then the direction. */
@Composable
fun StopRoutePicker(
    loadingRoutes: Boolean,
    routesError: String?,
    onRetry: () -> Unit,
    routes: List<FutarRoute>?,
    selectedRouteId: String?,
    onSelectRoute: (String) -> Unit,
    existing: List<TransitTracker>,
    selectedRoute: FutarRoute?,
    selectedHeadsign: String,
    onSelectHeadsign: (String) -> Unit,
    allDeparturesCount: Int,
    directions: List<StopDirection>,
    timeFormat: TimeFormat,
) {
    val c = Vectra.colors
    if (loadingRoutes) {
        ListStatus {
            SheetListSpinner()
            SheetMutedText("Loading departures…")
        }
        return
    }
    if (routesError != null) {
        ListStatus {
            SheetMutedText("Could not load departures: $routesError", Modifier.padding(horizontal = 8.dp), c.tints.red.text)
            SheetRetryButton(onRetry)
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Lines at this stop")
            ClippedFormGroup(
                rows = routes.orEmpty().map { route ->
                    @Composable {
                        val meta = routeTypeMeta(route.type)
                        val isTracked = existing.any { isRouteMatch(it.routeId, route.id) }
                        ListRow(
                            meta.label,
                            onClick = { onSelectRoute(route.id) },
                            selected = selectedRouteId == route.id,
                            leading = { RouteBadge(route.shortName, route.color, route.textColor) },
                            titleContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    VIcon(meta.icon, size = 14.dp, tint = c.textSecondary)
                                    VText(
                                        meta.label,
                                        Modifier.weight(1f, fill = false),
                                        vtext(TypeScale.Body, Weight.Semibold),
                                        c.textPrimary,
                                        maxLines = 1,
                                    )
                                    if (isTracked) TrackedChip()
                                }
                                if (route.description.isNotEmpty()) {
                                    FormattedRouteTitle(
                                        route.description,
                                        Modifier.padding(top = 2.dp),
                                        style = vtext(TypeScale.Caption),
                                        color = c.textSecondary,
                                        iconSize = 10.dp,
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }

        if (selectedRoute != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Direction")
                val rows = buildList<@Composable () -> Unit> {
                    add {
                        ListRow(
                            "All directions",
                            onClick = { onSelectHeadsign("") },
                            selected = selectedHeadsign == "",
                            leading = { VIcon(RemixIcons.TrainLine, tint = c.textSecondary) },
                            subtitle = if (allDeparturesCount > 0) "$allDeparturesCount departures" else null,
                        )
                    }
                    directions.forEach { dir ->
                        add {
                            ListRow(
                                dir.headSign,
                                onClick = { onSelectHeadsign(dir.headSign) },
                                selected = selectedHeadsign == dir.headSign,
                                leading = { Box(Modifier.width(16.dp)) },
                                trailing = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        dir.times.take(2).forEach { t -> DepartureChip(t, timeFormat) }
                                    }
                                },
                            )
                        }
                    }
                }
                ClippedFormGroup(rows = rows)
                SheetFootnote("All directions shows every departure from this stop.")
            }
        }
    }
}

@Composable
private fun DepartureChip(t: Arrival, timeFormat: TimeFormat) {
    val c = Vectra.colors
    Row(
        Modifier.background(c.surfaceOverlay, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t.isPredicted) LiveSignalIcon(size = 10.dp, tint = c.tints.emerald.text)
        else VIcon(RemixIcons.TimeLine, size = 10.dp, tint = c.textMuted)
        VText(
            formatClock(t.arrivalMs, timeFormat),
            style = vtext(11.sp to 16.sp, Weight.Semibold).copy(fontFeatureSettings = "tnum"),
            color = c.textSecondary,
            maxLines = 1,
        )
    }
}
