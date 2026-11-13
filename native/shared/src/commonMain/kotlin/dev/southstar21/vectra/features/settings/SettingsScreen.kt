package dev.southstar21.vectra.features.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.AppGraph
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.InlineLoader
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.Reveal
import dev.southstar21.vectra.core.designsystem.components.SettingsRow
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.VToggle
import dev.southstar21.vectra.core.designsystem.components.launchCatching
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.platform.PermissionKind
import dev.southstar21.vectra.core.platform.platformName
import dev.southstar21.vectra.core.platform.rememberPermissionRequester
import dev.southstar21.vectra.core.time.formatDDMMYYYY
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.auth.PasskeyRepository
import dev.southstar21.vectra.features.notifications.NotificationsRepository
import dev.southstar21.vectra.features.reminders.classReminderRows
import dev.southstar21.vectra.features.reminders.commuteRows
import dev.southstar21.vectra.features.shell.VectraPage
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import dev.southstar21.vectra.features.reminders.TestReminderLink
import dev.southstar21.vectra.core.designsystem.components.SettingsFooterLink
import dev.southstar21.vectra.core.designsystem.components.SettingsLink
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester

/** Settings rows read as a narrow column, narrower than `ContentMaxWidth`. */
private val SettingsColumnWidth = 448.dp

/** Published by .github/workflows/release.yml from .github/pages/privacy.html. */
const val PrivacyPolicyUrl = "https://southstar21dev.github.io/Vectra/privacy.html"

/** "1" once the setup card was dismissed on this device. */
private const val SetupDismissedKey = "settings_setup_dismissed"

/** Sections the setup card can bring into view, for features set up in place. */
enum class SettingsAnchor { Account, Commute }

/**
 * The settings page. The Neptun card, the location row and the schedule's Danger-zone rows belong
 * to the schedule and weather features, and the setup card's items span every feature: all are
 * passed in as slots.
 */
@Composable
fun SettingsScreen(
    graph: AppGraph,
    neptunSection: (@Composable () -> Unit)? = null,
    locationRow: (@Composable () -> Unit)? = null,
    neptunDangerRows: List<@Composable () -> Unit> = emptyList(),
    setupItems: @Composable (scrollTo: (SettingsAnchor) -> Unit) -> List<SetupItem> = { emptyList() },
) {
    val scope = rememberCoroutineScope()
    val anchors = remember { SettingsAnchor.entries.associateWith { BringIntoViewRequester() } }
    val items = setupItems { anchor -> scope.launch { anchors.getValue(anchor).bringIntoView() } }
    val missing = items.count { it.done == false }
    val dismissed by remember(graph.kv) { graph.kv.observe(SetupDismissedKey).map { it == "1" } }
        .collectAsStateWithLifecycle(initialValue = null)
    val setDismissed: (Boolean) -> Unit = { d -> scope.launch { graph.kv.set(SetupDismissedKey, if (d) "1" else null) } }
    VectraPage(spacing = 28) {
        Column(
            Modifier.widthIn(max = SettingsColumnWidth).fillMaxWidth().align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            if (dismissed == false && missing > 0) Reveal(0) { SetupCard(items, onDismiss = { setDismissed(true) }) }
            Reveal(1) { AppearanceSettings(graph.appearanceRepository) }
            Reveal(2, Modifier.bringIntoViewRequester(anchors.getValue(SettingsAnchor.Account))) {
                SettingsSection("Account", tilt = StampTilt.Right, rows = accountRows(graph.auth) + passkeyRows(graph.passkeyRepository))
            }
            neptunSection?.let { section -> Reveal(3) { section() } }
            Reveal(4) {
                SettingsSection(
                    "Class reminders",
                    tilt = StampTilt.Left,
                    rows = classReminderRows(graph.reminders, graph.notifier),
                    footer = { TestReminderLink(graph.reminders, graph.notifier) },
                )
            }
            Reveal(5, Modifier.bringIntoViewRequester(anchors.getValue(SettingsAnchor.Commute))) {
                SettingsSection("Commute", tilt = StampTilt.Right, rows = commuteRows(graph.reminders, graph.transitRepository, graph.notifier))
            }
            Reveal(6) {
                SettingsSection(
                    "Permissions",
                    tilt = StampTilt.Left,
                    rows = listOfNotNull(locationRow) + pushRow(graph.notificationsRepository),
                    footer = { TestNotificationLink(graph.notificationsRepository) },
                )
            }
            Reveal(7) {
                val tips = if (dismissed == true && missing > 0) missing else 0
                SettingsSection("About", tilt = StampTilt.Right, rows = aboutRows(graph, tips) { setDismissed(false) })
            }
            Reveal(8) {
                SettingsSection("Danger zone", tilt = StampTilt.Left, danger = true, rows = neptunDangerRows + { DeleteAccountRow(graph.auth) })
            }
        }
    }
}

/** Version, privacy policy, the setup tips when dismissed with [tipsLeft] still to do, and signing out. */
private fun aboutRows(graph: AppGraph, tipsLeft: Int, onShowTips: () -> Unit): List<@Composable () -> Unit> = listOfNotNull(
    {
        val c = Vectra.colors
        SettingsRow(RemixIcons.InformationLine, c.tints.sky.text, "Version") {
            VText("${graph.config.appVersion}-$platformName", style = vtext(TypeScale.Body), color = c.textSecondary)
        }
    },
    {
        val c = Vectra.colors
        val uri = LocalUriHandler.current
        SettingsRow(
            RemixIcons.ShieldCheckLine,
            c.tints.purple.text,
            "Privacy policy",
            "What Vectra stores and how to remove it",
            onClick = { runCatching { uri.openUri(PrivacyPolicyUrl) } },
        ) { VIcon(RemixIcons.ExternalLinkLine, size = 16.dp, tint = c.textMuted) }
    },
    if (tipsLeft > 0) {
        {
            val c = Vectra.colors
            SettingsRow(RemixIcons.CheckboxCircleLine, c.accentPrimary, "Setup tips", "$tipsLeft ${if (tipsLeft == 1) "thing" else "things"} left to set up") {
                SettingsLink("Show", onShowTips)
            }
        }
    } else null,
    { SignOutRow(graph.auth) },
)

/** Passkey rows for the Account group. */
private fun passkeyRows(repo: PasskeyRepository): List<@Composable () -> Unit> = listOf {
    val c = Vectra.colors
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val passkeys by repo.passkeys.collectAsStateWithLifecycle()
    var loadError by remember { mutableStateOf(false) }
    var registering by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(repo) { runSuspendCatching { repo.refresh() }.onFailure { loadError = true } }
    // The server re-checks the password before enrolling: a stolen session alone must not add a passkey.
    val register: () -> Unit = { registering = true }
    CurrentPasswordDialog(
        open = registering,
        title = "Add a passkey",
        description = "Confirm it's you with your current password, then follow your device's prompt.",
        confirmText = "Continue",
        onDismiss = { registering = false },
        onConfirm = { password -> repo.register(password); toaster.success("Passkey added") },
        errorMessage = repo::errorMessage,
    )
    val list = passkeys
    Column {
        when {
            !repo.isSupported -> SettingsRow(RemixIcons.FingerprintLine, c.tints.violet.text, "Passkey sign-in", "Not supported on this device (needs Android 9+ or a modern browser on HTTPS)")
            loadError -> SettingsRow(RemixIcons.FingerprintLine, c.tints.violet.text, "Passkey sign-in", "Couldn't load your passkeys") {
                SettingsLink("Retry", { loadError = false; scope.launch { runSuspendCatching { repo.refresh() }.onFailure { loadError = true } } })
            }
            list == null -> SettingsRow(RemixIcons.FingerprintLine, c.tints.violet.text, "Passkey sign-in", "Checking your passkeys…")
            list.isNotEmpty() -> {
                list.forEach { p ->
                    SettingsRow(RemixIcons.FingerprintLine, c.tints.violet.text, p.friendlyName ?: "Passkey", "Added ${formatDDMMYYYY(p.createdAt.take(10))}") {
                        VButton(onClick = { deleting = true; scope.launchCatching({ repo.delete(p.id); deleting = false }) { deleting = false } }, variant = ButtonVariant.Ghost, size = ButtonSize.Icon, enabled = !deleting, contentDescription = "Delete passkey ${p.friendlyName ?: ""}".trim()) {
                            if (deleting) InlineLoader(tint = c.textMuted) else VIcon(RemixIcons.DeleteBinLine, tint = c.textMuted)
                        }
                    }
                }
                SettingsRow(RemixIcons.AddLine, c.textSecondary, "Add another passkey", "Register this device too", enabled = !registering, onClick = register)
            }
            else -> SettingsRow(RemixIcons.FingerprintLine, c.tints.violet.text, "Passkey sign-in", "Use your fingerprint or face instead of a password") {
                SettingsLink("Set up", register, enabled = !registering)
            }
        }
    }
}

/** The push-notifications toggle for the Permissions group. */
private fun pushRow(repo: NotificationsRepository): @Composable () -> Unit = { PushNotificationsRow(repo) }

@Composable
private fun PushNotificationsRow(repo: NotificationsRepository) {
    val c = Vectra.colors
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val subscribed by repo.subscribed.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(repo.permissionGranted()) }
    LaunchedEffect(repo) { repo.check() }
    val requester = rememberPermissionRequester(PermissionKind.Notifications)
    val on = if (repo.pushAvailable) subscribed else granted
    SettingsRow(
        if (on) RemixIcons.Notification3Line else RemixIcons.NotificationOffLine,
        c.tints.purple.text,
        "Push notifications",
        when {
            !repo.pushAvailable && granted -> "System notifications allowed"
            !repo.pushAvailable -> "Allow notifications for departure and class alerts"
            subscribed -> "Enabled on this device"
            else -> "Get alerts even when Vectra is closed"
        },
    ) {
        VToggle(on, { checked ->
            busy = true
            if (checked) {
                requester.request { ok ->
                    granted = ok
                    if (!ok) { busy = false; toaster.error("Notifications are blocked. Allow them for Vectra in system settings.") }
                    else if (!repo.pushAvailable) busy = false
                    else scope.launchCatching({ repo.subscribe(); busy = false; toaster.success("Push notifications enabled and synced") }) { busy = false; toaster.error(it.message ?: "Failed to toggle push notifications") }
                }
            } else {
                scope.launchCatching({ repo.unsubscribe(); busy = false; toaster.show("Push notifications disabled") }) { busy = false }
            }
        }, loading = busy, label = "Push notifications")
    }
}

/** The Permissions footer: a test alert, through the server when subscribed, else a local one. */
@Composable
private fun TestNotificationLink(repo: NotificationsRepository) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val subscribed by repo.subscribed.collectAsStateWithLifecycle()
    var sending by remember { mutableStateOf(false) }
    SettingsFooterLink(RemixIcons.SendPlaneLine, "Send a test notification", "An alert to this device", busy = sending, onClick = {
        sending = true
        if (subscribed) {
            scope.launchCatching({ val n = repo.sendServerTest(); sending = false; toaster.success("Server test push delivered to $n device(s)!") }) { sending = false; toaster.error(it.message ?: "Test failed") }
        } else {
            val shown = repo.showLocal("Vectra Local Alert 🚀", "Local notifications are active. Turn on push notifications above for background alerts.")
            sending = false
            if (!shown) toaster.error("Notifications are blocked. Allow them for Vectra in system settings.")
        }
    })
}
