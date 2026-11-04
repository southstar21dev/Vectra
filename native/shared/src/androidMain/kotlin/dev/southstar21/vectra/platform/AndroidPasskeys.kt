package dev.southstar21.vectra.platform

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import dev.southstar21.vectra.core.platform.PasskeyAuthenticator
import dev.southstar21.vectra.core.platform.PasskeyCancelledException
import java.lang.ref.WeakReference

/** Credential Manager passkeys. The ceremony needs the foreground Activity. */
class AndroidPasskeyAuthenticator(context: Context) : PasskeyAuthenticator {
    private val manager = CredentialManager.create(context)
    private var activity = WeakReference<Activity>(null)

    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
    }

    // Credential Manager passkeys work from Android 9 (via Play Services).
    override val isSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    private fun host(): Activity = activity.get() ?: error("not_supported")

    override suspend fun create(optionsJson: String): String = try {
        val res = manager.createCredential(host(), CreatePublicKeyCredentialRequest(optionsJson)) as CreatePublicKeyCredentialResponse
        res.registrationResponseJson
    } catch (_: CreateCredentialCancellationException) {
        throw PasskeyCancelledException()
    }

    override suspend fun get(optionsJson: String): String = try {
        val res = manager.getCredential(host(), GetCredentialRequest(listOf(GetPublicKeyCredentialOption(optionsJson))))
        (res.credential as PublicKeyCredential).authenticationResponseJson
    } catch (_: GetCredentialCancellationException) {
        throw PasskeyCancelledException()
    } catch (_: NoCredentialException) {
        error("webauthn_credential_not_found")
    }
}
