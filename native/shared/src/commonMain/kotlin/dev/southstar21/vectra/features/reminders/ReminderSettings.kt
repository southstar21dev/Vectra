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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.HairlineDivider
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

/** Turning a reminder on asks for the notification permission first; [enable] runs once it's granted. */
@Composable
internal fun rememberEnabler(notifier: Notifier): (() -> Unit) -> Unit {
    val requester = rememberPermissionRequester(PermissionKind.Notifications)
    val toaster = LocalToaster.current
    return remember(requester, notifier) {
        { enable ->
            if (notifier.permissionGranted()) enable()
            else requester.request { ok ->
                if (ok) enable() else toaster.error("Notifications are blocked. Allow them for Vectra in system settings.")
            }
        }
    }
}

@Composable
private fun BlockLabel(text: String) =
    VText(text, Modifier.padding(bottom = 8.dp), vtext(11.sp to 16.sp, Weight.Semibold), Vectra.colors.textMuted)

/** The "Class reminders" settings group. */
fun classReminderRows(scheduler: ReminderScheduler, notifier: Notifier): List<@Composable () -> Unit> = listOf(
    { BeforeClassRows(scheduler, notifier) },
    { NextClassRow(scheduler, notifier) },
    { TypeFilter(scheduler) },
    { PersonalItemsRow(scheduler) },
    { TestReminderRow(scheduler, notifier) },
)

@Composable
private fun BeforeClassRows(scheduler: ReminderScheduler, notifier: Notifier) {
    val c = Vectra.colors
    val prefs by scheduler.prefs.collectAsStateWithLifecycle()
    val enable = rememberEnabler(notifier)
    Column {
        PermissionLostRow(prefs, notifier)
        SettingsRow(RemixIcons.TimeLine, c.tints.sky.bg, c.tints.sky.text, "Before class", "Which class and room, ahead of time") {
            VToggle(prefs.beforeClass, { on ->
                if (on) enable { scheduler.update { it.copy(beforeClass = true) } } else scheduler.update { it.copy(beforeClass = false) }
            }, label = "Before class")
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            BlockLabel("Remind me")
            VTabs(
                prefs.leadMinutes,
                ReminderPrefs.LeadOptions,
                { m -> scheduler.update { it.copy(leadMinutes = m) } },
                Modifier.fillMaxWidth(),
                triggerHeight = 36.dp,
                pillSpec = VectraSprings.snappy(),
                pillHonorsReduceMotion = false,
            ) { m, _ ->
                VText(if (m == 60) "1 hour" else "$m min", style = vtext(TypeScale.Caption, Weight.Medium))
            }
        }
    }
}

/**
 * Settings that came back with the account (a reinstall, a new phone) arrive without the system
 * permission, so they would stay silent: offer to allow notifications again.
 */
@Composable
private fun PermissionLostRow(prefs: ReminderPrefs, notifier: Notifier) {
    val c = Vectra.colors
    var granted by remember { mutableStateOf(notifier.permissionGranted()) }
    LifecycleResumeEffect(notifier) {
        granted = notifier.permissionGranted()
        onPauseOrDispose { }
    }
    val enable = rememberEnabler(notifier)
    if (granted || !prefs.anyOn) return
    SettingsRow(RemixIcons.NotificationOffLine, c.tints.red.bg, c.tints.red.text, "Notifications are off", "Your reminders are set but can't appear") {
        VButton(onClick = { enable { granted = true } }, variant = ButtonVariant.Outline, size = ButtonSize.Sm) {
            VText("Allow", style = vtext(TypeScale.Caption, Weight.Bold))
        }
    }
    HairlineDivider()
}

@Composable
private fun NextClassRow(scheduler: ReminderScheduler, notifier: Notifier) {
    val c = Vectra.colors
    val prefs by scheduler.prefs.collectAsStateWithLifecycle()
    val enable = rememberEnabler(notifier)
    SettingsRow(RemixIcons.ArrowRightLine, c.tints.purple.bg, c.tints.purple.text, "Next class", "Five minutes before a class ends: what's next and where") {
        VToggle(prefs.nextClass, { on ->
            if (on) enable { scheduler.update { it.copy(nextClass = true) } } else scheduler.update { it.copy(nextClass = false) }
        }, label = "Next class")
    }
}

@Composable
private fun TypeFilter(scheduler: ReminderScheduler) {
    val prefs by scheduler.prefs.collectAsStateWithLifecycle()
    val chips = listOf(
        Triple("Lectures", prefs.lectures) { v: Boolean -> scheduler.update { it.copy(lectures = v) } },
        Triple("Seminars & practice", prefs.seminars) { v: Boolean -> scheduler.update { it.copy(seminars = v) } },
        Triple("Labs", prefs.labs) { v: Boolean -> scheduler.update { it.copy(labs = v) } },
        Triple("Exams", prefs.exams) { v: Boolean -> scheduler.update { it.copy(exams = v) } },
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        BlockLabel("Remind me for")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, on, set) -> TypeChip(label, on, { set(!on) }, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PersonalItemsRow(scheduler: ReminderScheduler) {
    val c = Vectra.colors
    val prefs by scheduler.prefs.collectAsStateWithLifecycle()
    SettingsRow(RemixIcons.Calendar2Line, c.tints.amber.bg, c.tints.amber.text, "Personal items too", "Shifts and tasks from your schedule") {
        VToggle(prefs.personalItems, { on -> scheduler.update { it.copy(personalItems = on) } }, label = "Personal items too")
    }
}

@Composable
private fun TestReminderRow(scheduler: ReminderScheduler, notifier: Notifier) {
    val c = Vectra.colors
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val enable = rememberEnabler(notifier)
    var sending by remember { mutableStateOf(false) }
    SettingsRow(RemixIcons.SendPlaneLine, c.surfaceOverlay, c.textSecondary, "Test reminder", "Arrives in 5 seconds, even if you close Vectra") {
        VButton(onClick = {
            enable {
                sending = true
                scope.launch {
                    runSuspendCatching { scheduler.sendTest() }.onFailure { toaster.error("Couldn't schedule the test reminder") }
                    sending = false
                }
            }
        }, variant = ButtonVariant.Outline, size = ButtonSize.Sm, enabled = !sending) {
            if (sending) InlineLoader(size = 14.dp) else VText("Send", style = vtext(TypeScale.Caption, Weight.Bold))
        }
    }
}

/** One class type in the 2×2 filter: tinted and ticked when its reminders are on. */
@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val accent = c.accentPrimary
    Row(
        modifier
            .height(36.dp)
            .background(if (selected) accent.copy(alpha = 0.15f) else c.surfaceOverlay, CircleShape)
            .border(1.dp, if (selected) accent.copy(alpha = 0.3f) else c.borderSubtle, CircleShape)
            .semantics { this.selected = selected }
            .tappable(CircleShape, role = Role.Checkbox, haptic = { tick() }, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) VIcon(RemixIcons.CheckLine, size = 14.dp, tint = accent)
        VText(
            label,
            style = vtext(TypeScale.Caption, if (selected) Weight.Bold else Weight.Medium),
            color = if (selected) accent else c.textSecondary,
            maxLines = 1,
        )
    }
}
