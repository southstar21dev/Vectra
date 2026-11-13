package dev.southstar21.vectra.features.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.designsystem.components.ConfirmDialog
import dev.southstar21.vectra.core.designsystem.components.InlineLoader
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.SettingsRow
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlinx.serialization.json.JsonNull

/**
 * The schedule's rows for the Settings Danger zone: unlinking Neptun and clearing every class.
 * Each asks first.
 */
fun neptunDangerRows(repo: ScheduleRepository, auth: AuthRepository): List<@Composable () -> Unit> = listOf(
    { RemoveNeptunLinkRow(repo, auth) },
    { ClearClassesRow(repo, auth) },
)

@Composable
private fun RemoveNeptunLinkRow(repo: ScheduleRepository, auth: AuthRepository) {
    val c = Vectra.colors
    val toaster = LocalToaster.current
    val profile by auth.profile.collectAsStateWithLifecycle()
    val linked = !profile?.neptunIcsUrl.isNullOrBlank()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    SettingsRow(
        RemixIcons.LinkUnlinkM,
        c.tints.red.text,
        "Remove Neptun link",
        if (linked) "Stops syncing and removes the classes it imported" else "Nothing linked yet",
        danger = true,
        enabled = linked && !busy,
        onClick = { confirm = true },
        control = if (busy) ({ InlineLoader(tint = c.tints.red.text) }) else null,
    )
    ConfirmDialog(
        open = confirm,
        onClose = { confirm = false },
        onConfirm = {
            busy = true
            runSuspendCatching { repo.saveNeptunUrl("") }
                .onSuccess { toaster.success("Neptun link removed") }
                .onFailure { toaster.error("Could not remove the Neptun link", it.message) }
            busy = false
        },
        title = "Remove the Neptun link?",
        description = "Vectra stops syncing your timetable and removes the classes it imported. Classes you added yourself stay. You can link Neptun again any time.",
        confirmText = "Remove",
        cancelText = "Cancel",
    )
}

@Composable
private fun ClearClassesRow(repo: ScheduleRepository, auth: AuthRepository) {
    val c = Vectra.colors
    val toaster = LocalToaster.current
    var confirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    SettingsRow(
        RemixIcons.DeleteBinLine,
        c.tints.red.text,
        "Clear all classes",
        "Synced and manual classes; work shifts and tasks stay",
        danger = true,
        enabled = !clearing,
        onClick = { confirm = true },
        control = if (clearing) ({ InlineLoader(tint = c.tints.red.text) }) else null,
    )
    ConfirmDialog(
        open = confirm,
        onClose = { confirm = false },
        onConfirm = {
            clearing = true
            runSuspendCatching {
                repo.clearAllEvents()
                auth.updateProfile(mapOf("neptun_ics_url" to JsonNull, "neptun_last_sync" to JsonNull))
            }
                .onSuccess { toaster.success("All classes cleared") }
                .onFailure { toaster.error("Could not clear your classes", it.message) }
            clearing = false
        },
        title = "Clear all classes?",
        description = "This deletes every class in your schedule, including the ones synced from Neptun, and disconnects the Neptun link. Work shifts and tasks stay. This can't be undone.",
        confirmText = "Clear all",
        cancelText = "Cancel",
    )
}
