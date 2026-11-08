package dev.southstar21.vectra.features.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.InlineLoader
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.OptionSheet
import dev.southstar21.vectra.core.designsystem.components.SelectOption
import dev.southstar21.vectra.core.designsystem.components.SettingsRow
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VTabs
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.VToggle
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Radius
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.platform.Notifier
import dev.southstar21.vectra.core.platform.PermissionKind
import dev.southstar21.vectra.core.platform.rememberPermissionRequester
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.transit.RouteBadge
import dev.southstar21.vectra.features.transit.RouteBadgeSize
import dev.southstar21.vectra.features.transit.TransitRepository
import dev.southstar21.vectra.features.transit.TransitTracker
import dev.southstar21.vectra.features.transit.collect
import dev.southstar21.vectra.features.transit.getTrackerStops
import kotlinx.coroutines.launch

/** The "Commute" settings group: which saved tracker gets you to uni and back. */
fun commuteRows(scheduler: ReminderScheduler, transit: TransitRepository, notifier: Notifier): List<@Composable () -> Unit> = listOf(
    { CommutePicker(scheduler, transit, notifier, home = false) },
    { CommutePicker(scheduler, transit, notifier, home = true) },
    { DeparturesRow(scheduler, notifier) },
)

@Composable
private fun DeparturesRow(scheduler: ReminderScheduler, notifier: Notifier) {
    val c = Vectra.colors
    val prefs by scheduler.prefs.collectAsStateWithLifecycle()
    val enable = rememberEnabler(notifier)
    SettingsRow(RemixIcons.RouteLine, c.tints.emerald.bg, c.tints.emerald.text, "Departure times", "When you head in or leave, breaks of 2 h+ too") {
        VToggle(prefs.departures, { on ->
            if (on) enable { scheduler.update { it.copy(departures = true) } } else scheduler.update { it.copy(departures = false) }
        }, label = "Departure times")
    }
}

/** Every stop of every saved tracker, as a commute leg to pick. */
private fun commuteLegs(trackers: List<TransitTracker>): List<CommuteLeg> = trackers.flatMap { t ->
    val stops = getTrackerStops(t)
    stops.map { s ->
        CommuteLeg(
            trackerId = t.id,
            stopId = s.stopId,
            stopName = s.stopName,
            routeId = t.routeId,
            routeShortName = t.routeShortName,
            routeType = t.routeType,
            routeColor = t.routeColor,
            routeTextColor = t.routeTextColor,
            // A single-stop tracker may keep its direction on the tracker itself (legacy rows).
            headsign = s.headsign?.ifEmpty { null } ?: t.headsign.ifEmpty { null }?.takeIf { stops.size == 1 },
        )
    }
}

private fun CommuteLeg?.key() = this?.let { "${it.trackerId}|${it.stopId}" } ?: ""

private fun CommuteLeg.describe() = stopName + (headsign?.let { " → $it" } ?: "")

@Composable
private fun CommutePicker(scheduler: ReminderScheduler, transit: TransitRepository, notifier: Notifier, home: Boolean) {
    val prefs by scheduler.prefs.collectAsStateWithLifecycle()
    val trackers = transit.trackersQuery.collect(Unit).data.orEmpty()
    val enable = rememberEnabler(notifier)
    var open by remember { mutableStateOf(false) }
    val current = if (home) prefs.backHome else prefs.toUni
    val legs = remember(trackers) { commuteLegs(trackers) }
    val title = if (home) "Back home" else "To uni"
    val subtitle = when {
        current != null -> current.describe()
        legs.isEmpty() -> "Save a line on the Transit tab first"
        else -> "Pick one of your saved lines"
    }
    CommuteRow(current, home, title, subtitle) { open = true }

    val options = listOf(SelectOption("", "None")) + legs.map { SelectOption(it.key(), "${it.lineLabel} · ${it.describe()}") }
    OptionSheet(open, title, options, current.key(), onDismiss = { open = false }) { key ->
        open = false
        val leg = legs.firstOrNull { it.key() == key }
        val set = { scheduler.update { if (home) it.copy(backHome = leg) else it.copy(toUni = leg) } }
        if (leg != null) enable(set) else set()
    }
}

/** A settings row led by the picked line's badge, opening the picker. */
@Composable
private fun CommuteRow(current: CommuteLeg?, home: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    val c = Vectra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .tappable(role = Role.DropdownList, onClickLabel = "Choose the line", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current != null) {
            RouteBadge(current.routeShortName, current.routeColor, current.routeTextColor, size = RouteBadgeSize.Xs)
        } else {
            Box(Modifier.size(28.dp).background(c.surfaceOverlay, Radius.Chip), contentAlignment = Alignment.Center) {
                VIcon(if (home) RemixIcons.Home5Line else RemixIcons.WalkLine, size = 14.dp, tint = c.textSecondary)
            }
        }
        Column(Modifier.weight(1f)) {
            VText(title, style = vtext(TypeScale.Body, Weight.Semibold, lineHeight = 17.5.sp), color = c.textPrimary)
            VText(subtitle, Modifier.padding(top = 2.dp), vtext(TypeScale.Caption), c.textSecondary, maxLines = 1)
        }
        VIcon(RemixIcons.ArrowRightSLine, size = 16.dp, tint = c.textMuted)
    }
}

