package dev.southstar21.vectra.features.transit.strip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.Banner
import dev.southstar21.vectra.core.designsystem.components.BannerChevron
import dev.southstar21.vectra.core.designsystem.components.BannerTone
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.transit.data.TransitAlert
import dev.southstar21.vectra.features.transit.data.TransitRepository
import dev.southstar21.vectra.features.transit.data.TransitTracker
import dev.southstar21.vectra.features.transit.isReplacementAlert
import dev.southstar21.vectra.features.transit.monitor
import kotlinx.coroutines.launch

/**
 * What sits under a stop's strip: its ends (or why there are none: the feed failed, nothing
 * is coming), a last-stop note, directions the strip can't draw, and the first alert.
 */
@Composable
internal fun StripStatus(repo: TransitRepository, tracker: TransitTracker, s: StopStrip) {
    val c = Vectra.colors
    val noun = vehicleNoun(tracker.routeType)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (s.error) FeedErrorRow(repo) else StripHeadline(s, noun)
        if (s.model.terminus && !s.noDepartures) {
            StripNote(RemixIcons.MapPinLine, "Last stop, ${noun ?: "vehicles"} only arrive here", c.textSecondary)
        }
        if (s.divertedTo != null) {
            Banner(BannerTone.Amber, RemixIcons.AlertLine, "Diverted, terminating at ${s.divertedTo}", Modifier.padding(top = 6.dp))
        }
        for (d in s.extra) {
            val a = d.arrival ?: continue
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                VText("Also", style = vtext(12.sp to 16.sp, Weight.Medium), color = c.textMuted)
                VIcon(RemixIcons.ArrowRightLine, size = 13.dp, tint = c.textMuted)
                VText(d.destination, Modifier.weight(1f), vtext(12.sp to 16.sp, Weight.Semibold), c.textSecondary, maxLines = 1)
                VText(stripTimeLabel(a.arrivalMs, s.now), style = vtext(13.sp to 16.sp, Weight.Extrabold, mono = true).tnum(), color = Vectra.monitor.live, maxLines = 1)
            }
        }
        AlertNote(s.alerts)
    }
}

/** Notes under a stop's strip when replaced, or a forked branch's destination/ETA. */
@Composable
private fun StripHeadline(s: StopStrip, noun: String?) {
    val c = Vectra.colors
    when {
        // Replaced by buses that stop elsewhere: the line isn't off, it just doesn't come here.
        s.noDepartures && s.replaced ->
            StripNote(RemixIcons.BusLine, "No ${noun ?: "vehicles"} stop here for now, replacement buses instead", c.textSecondary)
        s.noDepartures || s.loading -> Unit
        s.model.fork != null -> ForkEndRow(s.model.fork, s.model.forkTimes)
    }
}

/** The first alert: a replacement before anything else, in bus blue; any other in amber. */
@Composable
private fun AlertNote(alerts: List<TransitAlert>) {
    val alert = alerts.firstOrNull(::isReplacementAlert) ?: alerts.firstOrNull() ?: return
    val replacement = isReplacementAlert(alert)
    val text = if (replacement) alert.header else alert.header.ifEmpty { alert.description }
    val tone = if (replacement) BannerTone.Blue else BannerTone.Amber
    // The card around it opens the alert detail; the chevron says so.
    Banner(
        tone,
        if (replacement) RemixIcons.BusLine else RemixIcons.AlertLine,
        text.replaceFirstChar { it.uppercase() },
        Modifier.padding(top = 6.dp),
        trailing = { BannerChevron(tone) },
    )
}

/** One quiet line under the strip. */
@Composable
private fun StripNote(icon: ImageVector, text: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(icon, size = 15.dp, tint = color)
        VText(text, Modifier.weight(1f), vtext(12.5.sp to 17.sp, Weight.Semibold), color, maxLines = 2)
    }
}

/** The feed failed for this stop: say so, with a retry. */
@Composable
private fun FeedErrorRow(repo: TransitRepository) {
    val scope = rememberCoroutineScope()
    Banner(
        BannerTone.Red,
        RemixIcons.ErrorWarningLine,
        "Live feed unavailable",
        Modifier.padding(top = 4.dp),
        trailing = {
            VButton("Retry", { scope.launch { runSuspendCatching { repo.refresh() } } }, variant = ButtonVariant.Outline, size = ButtonSize.Sm, icon = RemixIcons.RefreshLine)
        },
    )
}
