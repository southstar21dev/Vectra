package dev.southstar21.vectra.features.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.designsystem.components.MultiOptionSheet
import dev.southstar21.vectra.core.designsystem.components.SelectOption
import dev.southstar21.vectra.core.designsystem.components.SettingsRow
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.VToggle
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.platform.Notifier
import dev.southstar21.vectra.features.transit.RouteBadge
import dev.southstar21.vectra.features.transit.RouteBadgeSize
import dev.southstar21.vectra.features.transit.TransitRepository
import dev.southstar21.vectra.features.transit.TransitTracker
import dev.southstar21.vectra.features.transit.collect
import dev.southstar21.vectra.features.transit.getTrackerStops

/** The "Commute" settings group: which saved trackers get you to uni and back. */
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
    SettingsRow(RemixIcons.RouteLine, c.tints.emerald.text, "Departure times", "When you head in or leave, breaks of 2 h+ too") {
        VToggle(prefs.departures, { on ->
            if (on) enable { scheduler.update { it.copy(departures = true) } } else scheduler.update { it.copy(departures = false) }
        }, label = "Departure times")
    }
}

/** Every stop of every saved tracker, as a commute leg to pick; paired lines (28 / 28A) at one stop are one leg. */
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
}.distinctBy { it.key() }

private fun CommuteLeg.key() = pickKey

private fun CommuteLeg.describe() = stopName + (headsign?.let { " → $it" } ?: "")

/** One leg: its stop and direction; several: "28 / 28A, 37 / 37A from Teleki László tér". */
private fun describe(legs: List<CommuteLeg>): String = legs.singleOrNull()?.describe()
    ?: (legs.map { it.displayName }.distinct().joinToString(", ") + " from " + legs.map { it.stopName }.distinct().joinToString(", "))

@Composable
private fun CommutePicker(scheduler: ReminderScheduler, transit: TransitRepository, notifier: Notifier, home: Boolean) {
    val prefs by scheduler.prefs.collectAsStateWithLifecycle()
    val trackers = transit.trackersQuery.collect(Unit).data.orEmpty()
    val enable = rememberEnabler(notifier)
    var open by remember { mutableStateOf(false) }
    val current = (if (home) prefs.backHome else prefs.toUni).distinctBy { it.key() }
    val saved = remember(trackers) { commuteLegs(trackers) }
    // A picked line whose tracker is gone stays listed, so it can still be unticked.
    val legs = saved + current.filter { c -> saved.none { it.key() == c.key() } }
    val title = if (home) "Back home" else "To uni"
    val subtitle = when {
        current.isNotEmpty() -> describe(current)
        legs.isEmpty() -> "Save a line on the Transit tab first"
        else -> "Pick the lines you take"
    }
    CommuteRow(current, home, title, subtitle) { open = true }

    val options = legs.map { SelectOption(it.key(), "${it.lineLabel} · ${it.describe()}") }
    MultiOptionSheet(open, title, options, current.map { it.key() }.toSet(), onDismiss = { open = false }) { key ->
        val picked = current.any { it.key() == key }
        val leg = legs.firstOrNull { it.key() == key } ?: return@MultiOptionSheet
        fun set(transform: (List<CommuteLeg>) -> List<CommuteLeg>) =
            scheduler.update { if (home) it.copy(backHome = transform(it.backHome)) else it.copy(toUni = transform(it.toUni)) }
        if (picked) set { list -> list.filterNot { it.key() == key } }
        else enable { set { list -> list.filterNot { it.key() == key } + leg } }
    }
}

/** A settings row led by the picked lines' badges, opening the picker. */
@Composable
private fun CommuteRow(current: List<CommuteLeg>, home: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    val c = Vectra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .tappable(role = Role.DropdownList, onClickLabel = "Choose the lines", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                current.take(MaxBadges).forEach { RouteBadge(it.displayName, it.routeColor, it.routeTextColor, size = RouteBadgeSize.Xs) }
                if (current.size > MaxBadges) {
                    VText("+${current.size - MaxBadges}", style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textSecondary)
                }
            }
        } else {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                VIcon(if (home) RemixIcons.Home5Line else RemixIcons.WalkLine, size = 18.dp, tint = c.textSecondary)
            }
        }
        Column(Modifier.weight(1f)) {
            VText(title, style = vtext(TypeScale.Body, Weight.Semibold, lineHeight = 17.5.sp), color = c.textPrimary)
            VText(subtitle, Modifier.padding(top = 2.dp), vtext(TypeScale.Caption), c.textSecondary, maxLines = 1)
        }
        VIcon(RemixIcons.ArrowRightSLine, size = 16.dp, tint = c.textMuted)
    }
}

/** Badges shown in a row before the rest fold into "+n". */
private const val MaxBadges = 3
