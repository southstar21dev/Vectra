package dev.southstar21.vectra.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.southstar21.vectra.core.designsystem.components.Banner
import dev.southstar21.vectra.core.designsystem.components.BannerTone
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons

/**
 * The server deletes every passkey when the password is reset or changed, or the email changes
 * (the account's token key rotates), so each of those forms says so before the user commits.
 */
internal object PasskeyResetNotes {
    const val SIGNUP = "If you later reset or change your password or email, you'll need to set your passkey up again."
    const val RESET = "Resetting your password turns off your passkeys on all your devices. You can set one up again in Settings."
    const val PASSWORD = "Changing your password turns off your passkeys on all your devices."
    const val EMAIL = "Changing your email turns off your passkeys on all your devices."
}

/** An amber banner warning that the change turns passkeys off. */
@Composable
internal fun PasskeyResetNote(text: String, modifier: Modifier = Modifier) {
    Banner(BannerTone.Amber, RemixIcons.ErrorWarningLine, text, modifier)
}
