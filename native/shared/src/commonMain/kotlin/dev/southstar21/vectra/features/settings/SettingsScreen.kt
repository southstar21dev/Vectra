package dev.southstar21.vectra.features.settings

import androidx.compose.ui.graphics.Color
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

/** Settings rows read as a narrow column, narrower than `ContentMaxWidth`. */
private val SettingsColumnWidth = 448.dp

/** Published by .github/workflows/release.yml from .github/pages/privacy.html. */
const val PrivacyPolicyUrl = "https://southstar21dev.github.io/Vectra/privacy.html"

/**
 * The settings page. The Neptun card and the location row belong to the schedule
 * and weather features; they are passed in as slots.
 */
@Composable
fun SettingsScreen(
    graph: AppGraph,
    neptunSection: (@Composable () -> Unit)? = null,
    locationRow: (@Composable () -> Unit)? = null,
) {
    val c = Vectra.colors
    val scope = rememberCoroutineScope()
    var loggingOut by remember { mutableStateOf(false) }
    VectraPage(spacing = 28) {
        Column(Modifier.widthIn(max = SettingsColumnWidth).fillMaxWidth().align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Reveal(0) { AppearanceSettings(graph.appearanceRepository) }
            Reveal(1) {
                SettingsSection("Account", tilt = StampTilt.Right, rows = accountRows(graph.auth) + passkeyRows(graph.passkeyRepository))
            }
            if (neptunSection != null) Reveal(2) { neptunSection() }
            Reveal(3) {
                SettingsSection("Class reminders", tilt = StampTilt.Left, rows = classReminderRows(graph.reminders, graph.notifier))
            }
            Reveal(4) {
                SettingsSection("Commute", tilt = StampTilt.Right, rows = commuteRows(graph.reminders, graph.transitRepository, graph.notifier))
            }
            Reveal(5) {
                SettingsSection("Permissions", tilt = StampTilt.Left, rows = listOfNotNull(locationRow) + notificationRows(graph.notificationsRepository))
            }
            Reveal(6) {
                SettingsSection("About", tilt = StampTilt.Right, rows = listOf(
                    {
                        SettingsRow(RemixIcons.InformationLine, c.tints.sky.bg, c.tints.sky.text, "Version") {
                            VText("${graph.config.appVersion}-$platformName", style = vtext(TypeScale.Body), color = c.textSecondary)
                        }
                    },
                    {
                        val uri = LocalUriHandler.current
                        SettingsRow(RemixIcons.ExternalLinkLine, c.tints.purple.bg, c.tints.purple.text, "Privacy policy", "What Vectra stores and how to remove it") {
                            VButton(onClick = { runCatching { uri.openUri(PrivacyPolicyUrl) } }, variant = ButtonVariant.Outline, size = ButtonSize.Sm) {
                                VText("Open", style = vtext(TypeScale.Caption, Weight.Bold))
                            }
                        }
                    },
                    {
                        SettingsRow(RemixIcons.LogoutBoxRLine, c.surfaceOverlay, c.textSecondary, "Sign out", "End your session on this device") {
                            VButton(onClick = { loggingOut = true; scope.launch { try { graph.auth.signOut() } finally { loggingOut = false } } }, variant = ButtonVariant.Outline, size = ButtonSize.Sm, enabled = !loggingOut) {
                                if (loggingOut) InlineLoader(size = 14.dp) else VText("Sign out", style = vtext(TypeScale.Caption, Weight.Bold))
                            }
                        }
                    },
                    {
                        SettingsRow(RemixIcons.DeleteBinLine, c.tints.red.bg, c.tints.red.text, "Delete account", "Permanently delete your account and data", danger = true) {
                            DeleteAccountButton(graph.auth)
                        }
                    },
                ))
            }
        }
    }
}

private val CyanBg = Color(0xFF06B6D4).copy(alpha = 0.15f)
private val CyanText = Color(0xFF22D3EE)

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
    val register: () -> Unit = {
        registering = true
        scope.launchCatching({ repo.register(); registering = false; toaster.success("Passkey added") }) {
            registering = false
            repo.errorMessage(it)?.let(toaster::error)
        }
    }
    val list = passkeys
    Column {
        when {
            !repo.isSupported -> SettingsRow(RemixIcons.FingerprintLine, CyanBg, CyanText, "Passkey sign-in", "Not supported on this device (needs Android 9+ or a modern browser on HTTPS)")
            loadError -> SettingsRow(RemixIcons.FingerprintLine, CyanBg, CyanText, "Passkey sign-in", "Couldn't load your passkeys") {
                VButton("Retry", { loadError = false; scope.launch { runSuspendCatching { repo.refresh() }.onFailure { loadError = true } } }, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
            }
            list == null -> SettingsRow(RemixIcons.FingerprintLine, CyanBg, CyanText, "Passkey sign-in", "Checking your passkeys…")
            list.isNotEmpty() -> {
                list.forEach { p ->
                    SettingsRow(RemixIcons.FingerprintLine, CyanBg, CyanText, p.friendlyName ?: "Passkey", "Added ${formatDDMMYYYY(p.createdAt.take(10))}") {
                        VButton(onClick = { deleting = true; scope.launchCatching({ repo.delete(p.id); deleting = false }) { deleting = false } }, variant = ButtonVariant.Ghost, size = ButtonSize.Icon, enabled = !deleting) {
                            if (deleting) InlineLoader(tint = c.textMuted) else VIcon(RemixIcons.DeleteBinLine, tint = c.textMuted)
                        }
                    }
                }
                SettingsRow(RemixIcons.AddLine, c.surfaceOverlay, c.textSecondary, "Add another passkey", "Register this device too") {
                    VButton(onClick = register, size = ButtonSize.Sm, enabled = !registering) {
                        if (registering) InlineLoader(size = 14.dp) else VIcon(RemixIcons.AddLine, size = 14.dp)
                        VText("Add")
                    }
                }
            }
            else -> SettingsRow(RemixIcons.FingerprintLine, CyanBg, CyanText, "Passkey sign-in", "Use your fingerprint or face instead of a password") {
                VButton(onClick = register, size = ButtonSize.Sm, enabled = !registering) {
                    if (registering) InlineLoader(size = 14.dp) else VIcon(RemixIcons.FingerprintLine, size = 14.dp)
                    VText("Set up")
                }
            }
        }
    }
}

/** Notification rows for the Permissions group. */
private fun notificationRows(repo: NotificationsRepository): List<@Composable () -> Unit> = listOf(
    {
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
            c.tints.purple.bg,
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
    },
    {
        val c = Vectra.colors
        val toaster = LocalToaster.current
        val scope = rememberCoroutineScope()
        val subscribed by repo.subscribed.collectAsStateWithLifecycle()
        var sending by remember { mutableStateOf(false) }
        SettingsRow(RemixIcons.SendPlaneLine, c.tints.emerald.bg, c.tints.emerald.text, "Test notification", "Send a test alert to this device") {
            VButton(onClick = {
                sending = true
                if (subscribed) {
                    scope.launchCatching({ val n = repo.sendServerTest(); sending = false; toaster.success("Server test push delivered to $n device(s)!") }) { sending = false; toaster.error(it.message ?: "Test failed") }
                } else {
                    val shown = repo.showLocal("Vectra Local Alert 🚀", "Local notifications are active. Enable Push Notifications below for background alerts.")
                    sending = false
                    if (!shown) toaster.error("Notifications are blocked. Allow them for Vectra in system settings.")
                }
            }, variant = ButtonVariant.Outline, size = ButtonSize.Sm, enabled = !sending) {
                if (sending) InlineLoader(size = 14.dp) else VText("Test", style = vtext(TypeScale.Caption, Weight.Bold))
            }
        }
    },
)
