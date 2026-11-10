package dev.southstar21.vectra.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.vtext

/**
 * The server deletes every passkey when the password is reset or changed, or the email changes
 * (the account's token key rotates), so each of those forms says so before the user commits.
 */
object PasskeyResetNotes {
    const val SIGNUP = "If you later reset or change your password or email, you'll need to set biometric sign-in up again."
    const val RESET = "Resetting your password turns off biometric sign-in on all your devices. You can set it up again in Settings."
    const val PASSWORD = "Changing your password turns off biometric sign-in on all your devices."
    const val EMAIL = "Changing your email turns off biometric sign-in on all your devices."
}

/** A small amber caption with a warning icon. */
@Composable
fun PasskeyResetNote(text: String, modifier: Modifier = Modifier) {
    val tint = Vectra.colors.tints.amber.text
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VIcon(RemixIcons.ErrorWarningLine, Modifier.padding(top = 1.dp), size = 14.dp, tint = tint)
        VText(text, style = vtext(TypeScale.Caption), color = tint)
    }
}
