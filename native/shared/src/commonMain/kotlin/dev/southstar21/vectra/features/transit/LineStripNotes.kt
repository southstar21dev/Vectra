package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.util.runSuspendCatching
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
            StripNote(RemixIcons.MapPinLine, "Last stop · ${noun ?: "vehicles"} only arrive here", c.textSecondary)
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

/** The strip's ends, or why there are none: nothing coming, or buses running in the line's place. */
@Composable
private fun StripHeadline(s: StopStrip, noun: String?) {
    val c = Vectra.colors
    when {
        // Replaced by buses that stop elsewhere: the line isn't off, it just doesn't come here.
        s.noDepartures && s.replaced ->
            StripNote(RemixIcons.BusLine, "No ${noun ?: "vehicles"} stop here for now · replacement buses instead", c.textSecondary)
        s.noDepartures -> StripNote(RemixIcons.MoonFill, noun?.let { "No $it coming right now" } ?: "Nothing coming right now", c.textSecondary)
        s.loading -> Unit
        else -> StripEnds(s.model)
    }
}

/** The first alert: a replacement before anything else, in bus blue; any other in amber. */
@Composable
private fun AlertNote(alerts: List<TransitAlert>) {
    val c = Vectra.colors
    val alert = alerts.firstOrNull(::isReplacementAlert) ?: alerts.firstOrNull() ?: return
    if (isReplacementAlert(alert)) {
        val sky = c.tints.sky
        StripNote(RemixIcons.BusLine, alert.header.replaceFirstChar { it.uppercase() }, sky.text, background = sky.bg, chevron = true)
    } else {
        val amber = c.tints.amber
        StripNote(RemixIcons.AlertLine, alert.header.ifEmpty { alert.description }, amber.text, background = amber.bg, chevron = true)
    }
}

/** One line under the strip, at most two lines of text; tinted and with a chevron when it opens something. */
@Composable
private fun StripNote(icon: ImageVector, text: String, color: Color, background: Color? = null, chevron: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .then(if (background != null) Modifier.background(background, shape).padding(horizontal = 10.dp, vertical = 9.dp) else Modifier.padding(top = 2.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(icon, size = 15.dp, tint = color)
        VText(text, Modifier.weight(1f), vtext(12.5.sp to 17.sp, Weight.Semibold), color, maxLines = 2)
        if (chevron) VIcon(RemixIcons.ArrowRightSLine, size = 14.dp, tint = color)
    }
}

/** The feed failed for this stop: say so, with a retry. */
@Composable
private fun FeedErrorRow(repo: TransitRepository) {
    val c = Vectra.colors
    val scope = rememberCoroutineScope()
    val pill = RoundedCornerShape(999.dp)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        VText("Live feed unavailable", Modifier.weight(1f), vtext(13.sp to 18.sp, Weight.Semibold), c.tints.red.text, maxLines = 1)
        Row(
            Modifier
                .clip(pill)
                .background(c.surfaceOverlay, pill)
                .tappable(pill) { scope.launch { runSuspendCatching { repo.refresh() } } }
                .defaultMinSize(minHeight = 34.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VIcon(RemixIcons.RefreshLine, size = 14.dp, tint = c.textSecondary)
            VText("Retry", style = vtext(13.sp to 18.sp, Weight.Semibold), color = c.textSecondary)
        }
    }
}
