package dev.southstar21.vectra.features.settings

import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.SettingsRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.designsystem.components.ConfirmDialog
import dev.southstar21.vectra.core.designsystem.components.InlineLoader
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import kotlinx.coroutines.launch

/**
 * Sign-out: uploads what only this device holds first (bounded), and asks before signing out
 * anyway when some of it could not be sent, since signing out deletes it from the device.
 */
@Composable
fun SignOutRow(auth: AuthRepository) {
    val c = Vectra.colors
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var unsynced by remember { mutableStateOf(false) }
    val signOut = {
        busy = true
        scope.launch {
            try {
                if (auth.flushPendingWrites()) auth.signOut() else unsynced = true
            } finally {
                busy = false
            }
        }
        Unit
    }
    SettingsRow(RemixIcons.LogoutBoxRLine, "Sign out", "End your session on this device", enabled = !busy, onClick = signOut) {
        if (busy) InlineLoader(size = 14.dp) else VIcon(RemixIcons.ArrowRightSLine, size = 18.dp, tint = c.textMuted)
    }
    ConfirmDialog(
        open = unsynced,
        onClose = { unsynced = false },
        onConfirm = { auth.signOut() },
        title = "Sign out with unsynced changes?",
        description = "Some schedule or course tracker changes on this device haven't reached the server yet. " +
            "Signing out deletes them. Stay signed in and go online to keep them.",
        confirmText = "Sign out anyway",
        cancelText = "Stay signed in",
    )
}
