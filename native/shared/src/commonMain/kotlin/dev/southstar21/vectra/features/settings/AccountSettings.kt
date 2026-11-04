package dev.southstar21.vectra.features.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.DialogDescription
import dev.southstar21.vectra.core.designsystem.components.DialogFooter
import dev.southstar21.vectra.core.designsystem.components.DialogHeader
import dev.southstar21.vectra.core.designsystem.components.DialogTitle
import dev.southstar21.vectra.core.designsystem.components.IconChip
import dev.southstar21.vectra.core.designsystem.components.InlineLoader
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.SettingsRow
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VDialog
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VInput
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.launchCatching
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.features.auth.AccountLogic
import kotlinx.serialization.json.JsonPrimitive
import dev.southstar21.vectra.core.designsystem.theme.Radius

@Composable
private fun FieldError(message: String?) {
    if (message == null) return
    val c = Vectra.colors
    val shape = Radius.Control
    Row(
        Modifier.fillMaxWidth().background(Palette.Red500.copy(alpha = 0.1f), shape).border(1.dp, c.tints.red.border, shape).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(RemixIcons.ErrorWarningLine, tint = c.tints.red.text)
        VText(message, style = vtext(TypeScale.Caption), color = c.tints.red.text)
    }
}

@Composable
private fun FormLabel(text: String) = VText(text, style = vtext(TypeScale.Caption, Weight.Bold), color = Vectra.colors.textSecondary)

@Composable
private fun EditButton(label: String, onClick: () -> Unit) =
    VButton(label, onClick, variant = ButtonVariant.Outline, size = ButtonSize.Sm, modifier = Modifier)

@Composable
private fun EditActions(saving: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        VButton("Cancel", onCancel, variant = ButtonVariant.Ghost, size = ButtonSize.Sm, enabled = !saving)
        VButton(onClick = onSave, variant = ButtonVariant.Primary, size = ButtonSize.Sm, enabled = !saving) {
            if (saving) InlineLoader(size = 14.dp) else VText("Save")
        }
    }
}

private fun messageOf(e: Throwable, fallback: String) = AccountLogic.fieldErrorMessage(e) ?: e.message?.takeIf { it.isNotBlank() } ?: fallback

/** The editable name, email and password rows. */
fun accountRows(auth: AuthRepository): List<@Composable () -> Unit> = listOf({ NameRow(auth) }, { EmailRow(auth) }, { PasswordRow(auth) })

@Composable
private fun NameRow(auth: AuthRepository) {
    val c = Vectra.colors
    val profile by auth.profile.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    if (editing) {
        Column(SettingsBlockPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormLabel("Full name")
            VInput(value, { value = it.take(AccountLogic.FULL_NAME_MAX_LENGTH) }, placeholder = "Your name", icon = RemixIcons.UserLine)
            FieldError(error)
            EditActions(saving, { editing = false }) {
                val name = value.trim()
                if (name.isEmpty()) error = "Name cannot be empty" else {
                    error = null; saving = true
                    scope.launchCatching({
                        auth.updateProfile(mapOf("full_name" to JsonPrimitive(name)))
                        saving = false; editing = false; toaster.show("Name updated")
                    }) { saving = false; error = messageOf(it, "Could not update name") }
                }
            }
        }
    } else {
        SettingsRow(RemixIcons.UserLine, c.tints.blue.bg, c.tints.blue.text, profile?.fullName ?: "Not set", "Full name") {
            EditButton("Edit") { value = profile?.fullName.orEmpty(); error = null; editing = true }
        }
    }
}

@Composable
private fun EmailRow(auth: AuthRepository) {
    val c = Vectra.colors
    val profile by auth.profile.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    if (editing) {
        Column(SettingsBlockPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormLabel("New email address")
            VInput(email, { email = it }, placeholder = "name@example.com", icon = RemixIcons.MailLine, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            FormLabel("Current password")
            VInput(current, { current = it }, placeholder = "Your current password", icon = RemixIcons.LockLine, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            FieldError(error)
            EditActions(saving, { editing = false }) {
                val e = email.trim()
                when {
                    current.isEmpty() -> error = "Enter your current password"
                    !AccountLogic.isValidEmail(e) -> error = "Enter a valid email address"
                    else -> {
                        error = null; saving = true
                        scope.launchCatching({
                            auth.changeEmail(current, e)
                            saving = false; editing = false; toaster.show("Email address updated")
                        }) { saving = false; error = messageOf(it, "Could not update email") }
                    }
                }
            }
        }
    } else {
        SettingsRow(RemixIcons.MailLine, c.tints.purple.bg, c.tints.purple.text, profile?.email ?: "N/A", "Email address") {
            EditButton("Change") { email = profile?.email.orEmpty(); current = ""; error = null; editing = true }
        }
    }
}

@Composable
private fun PasswordRow(auth: AuthRepository) {
    val c = Vectra.colors
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    if (editing) {
        Column(SettingsBlockPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val pw = PasswordVisualTransformation()
            val ko = KeyboardOptions(keyboardType = KeyboardType.Password)
            FormLabel("Current password")
            VInput(current, { current = it }, placeholder = "Your current password", icon = RemixIcons.LockLine, visualTransformation = pw, keyboardOptions = ko)
            FormLabel("New password")
            VInput(next, { next = it }, placeholder = "At least ${AccountLogic.MIN_PASSWORD_LENGTH} characters", icon = RemixIcons.LockLine, visualTransformation = pw, keyboardOptions = ko)
            FormLabel("Confirm new password")
            VInput(confirm, { confirm = it }, placeholder = "Repeat the new password", icon = RemixIcons.LockLine, visualTransformation = pw, keyboardOptions = ko)
            FieldError(error)
            EditActions(saving, { editing = false }) {
                val invalid = when {
                    current.isEmpty() -> "Enter your current password"
                    else -> AccountLogic.validatePassword(next) ?: AccountLogic.validateConfirmPassword(next, confirm)
                }
                if (invalid != null) error = invalid else {
                    error = null; saving = true
                    scope.launchCatching({
                        auth.changePassword(current, next)
                        saving = false; editing = false; toaster.show("Password updated")
                    }) { saving = false; error = messageOf(it, "Could not update password") }
                }
            }
        }
    } else {
        SettingsRow(RemixIcons.LockLine, c.tints.sky.bg, c.tints.sky.text, "Password", "Change your password") {
            EditButton("Change") { current = ""; next = ""; confirm = ""; error = null; editing = true }
        }
    }
}

/** Destructive delete-account control with a type-to-confirm dialog. */
@Composable
fun DeleteAccountButton(auth: AuthRepository) {
    val c = Vectra.colors
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    VButton(onClick = { text = ""; error = null; open = true }, variant = ButtonVariant.Outline, size = ButtonSize.Sm) {
        VIcon(RemixIcons.DeleteBinLine, size = 14.dp, tint = c.tints.red.text)
        VText("Delete", style = vtext(TypeScale.Caption, Weight.Bold), color = c.tints.red.text)
    }
    val close = { if (!deleting) { open = false; text = ""; error = null } }
    VDialog(open, close, showClose = false) {
        DialogHeader(Modifier.padding(top = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconChip(RemixIcons.DeleteBinLine, c.tints.red, size = 32, iconSize = 16)
                DialogTitle("Delete account?")
            }
            DialogDescription("This permanently deletes your account and all of your data — profile, schedule, transit and more. This cannot be undone.", Modifier.padding(top = 2.dp))
        }
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VText(
                buildAnnotatedString {
                    append("Type ")
                    withStyle(SpanStyle(color = c.tints.red.text)) { append(AccountLogic.DELETE_ACCOUNT_CONFIRM_TEXT) }
                    append(" to confirm")
                },
                style = vtext(TypeScale.Caption, Weight.Bold),
                color = c.textSecondary,
            )
            VInput(text, { text = it }, placeholder = AccountLogic.DELETE_ACCOUNT_CONFIRM_TEXT, enabled = !deleting)
            FieldError(error)
        }
        DialogFooter(Modifier.padding(top = 16.dp)) {
            VButton(
                onClick = {
                    deleting = true; error = null
                    scope.launchCatching({ auth.deleteAccount(); toaster.show("Account deleted") }) { deleting = false; error = it.message ?: "Could not delete account" }
                },
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Destructive,
                size = ButtonSize.Sm,
                enabled = !deleting && AccountLogic.deleteConfirmationMatches(text),
            ) {
                if (deleting) InlineLoader(size = 14.dp) else VIcon(RemixIcons.DeleteBinLine, size = 14.dp)
                VText("Delete my account")
            }
            VButton("Cancel", close, Modifier.fillMaxWidth(), ButtonVariant.Ghost, ButtonSize.Sm, enabled = !deleting)
        }
    }
}
