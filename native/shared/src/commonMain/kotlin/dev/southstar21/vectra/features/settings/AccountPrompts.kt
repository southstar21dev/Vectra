package dev.southstar21.vectra.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.ConfirmDialog
import dev.southstar21.vectra.core.designsystem.components.DialogDescription
import dev.southstar21.vectra.core.designsystem.components.DialogFooter
import dev.southstar21.vectra.core.designsystem.components.DialogHeader
import dev.southstar21.vectra.core.designsystem.components.DialogTitle
import dev.southstar21.vectra.core.designsystem.components.InlineLoader
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VDialog
import dev.southstar21.vectra.core.designsystem.components.VInput
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.launchCatching
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlinx.coroutines.launch

/**
 * Asks for the current password before an action the server re-checks it for. [onConfirm] does
 * the work; a failure keeps the dialog open with [errorMessage]'s text (null closes it quietly).
 */
@Composable
fun CurrentPasswordDialog(
    open: Boolean,
    title: String,
    description: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (password: String) -> Unit,
    errorMessage: (Throwable) -> String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val close = { if (!busy) { password = ""; error = null; onDismiss() } }
    val submit = {
        if (!busy && password.isNotEmpty()) {
            busy = true; error = null
            scope.launchCatching({ onConfirm(password); busy = false; password = ""; onDismiss() }) {
                busy = false
                val message = errorMessage(it)
                if (message == null) close() else error = message
            }
        }
    }
    VDialog(open, close, modifier, showClose = false) {
        DialogHeader(Modifier.padding(top = 4.dp)) {
            DialogTitle(title)
            DialogDescription(description, Modifier.padding(top = 2.dp))
        }
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormLabel("Current password")
            VInput(
                password, { password = it }, placeholder = "Your current password", icon = RemixIcons.LockLine, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            FieldError(error)
        }
        DialogFooter(Modifier.padding(top = 16.dp)) {
            VButton(onClick = { submit() }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Primary, size = ButtonSize.Sm, enabled = !busy && password.isNotEmpty()) {
                if (busy) InlineLoader(size = 14.dp)
                VText(confirmText)
            }
            VButton("Cancel", close, Modifier.fillMaxWidth(), ButtonVariant.Ghost, ButtonSize.Sm, enabled = !busy)
        }
    }
}

/**
 * Sign-out: uploads what only this device holds first (bounded), and asks before signing out
 * anyway when some of it could not be sent, since signing out deletes it from the device.
 */
@Composable
fun SignOutButton(auth: AuthRepository, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var unsynced by remember { mutableStateOf(false) }
    VButton(
        onClick = {
            busy = true
            scope.launch {
                try {
                    if (auth.flushPendingWrites()) auth.signOut() else unsynced = true
                } finally {
                    busy = false
                }
            }
        },
        modifier = modifier,
        variant = ButtonVariant.Outline,
        size = ButtonSize.Sm,
        enabled = !busy,
    ) {
        if (busy) InlineLoader(size = 14.dp) else VText("Sign out", style = vtext(TypeScale.Caption, Weight.Bold))
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
