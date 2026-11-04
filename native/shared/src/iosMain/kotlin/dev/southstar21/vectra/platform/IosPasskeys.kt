package dev.southstar21.vectra.platform

import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.platform.PasskeyAuthenticator
import dev.southstar21.vectra.core.platform.PasskeyCancelledException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationErrorCanceled
import platform.AuthenticationServices.ASAuthorizationErrorDomain
import platform.AuthenticationServices.ASAuthorizationPlatformPublicKeyCredentialAssertion
import platform.AuthenticationServices.ASAuthorizationPlatformPublicKeyCredentialDescriptor
import platform.AuthenticationServices.ASAuthorizationPlatformPublicKeyCredentialProvider
import platform.AuthenticationServices.ASAuthorizationPlatformPublicKeyCredentialRegistration
import platform.AuthenticationServices.ASAuthorizationRequest
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64

/**
 * AuthenticationServices platform passkeys. Takes the same PublicKeyCredential{Creation,Request}
 * OptionsJSON the server hands Android and answers in the same `toJSON()` shape. iOS only offers
 * passkeys for a relying party whose domain lists this app (`webcredentials:` + apple-app-site-association).
 */
class IosPasskeyAuthenticator : PasskeyAuthenticator {
    override val isSupported: Boolean = true

    // ASAuthorizationController keeps its delegate weakly; the ceremony in flight holds it here.
    private var inFlight: Ceremony? = null

    override suspend fun create(optionsJson: String): String {
        val o = options(optionsJson)
        val rp = (o["rp"] as JsonObject).string("id") ?: error("webauthn_invalid_options")
        val user = o["user"] as JsonObject
        val request = ASAuthorizationPlatformPublicKeyCredentialProvider(rp).createCredentialRegistrationRequestWithChallenge(
            challenge = o.bytes("challenge"),
            name = user.string("name").orEmpty(),
            userID = user.bytes("id"),
        )
        (o["authenticatorSelection"] as? JsonObject)?.string("userVerification")?.let { request.userVerificationPreference = it }
        o.string("attestation")?.let { request.attestationPreference = it }
        val reg = perform(request) as? ASAuthorizationPlatformPublicKeyCredentialRegistration ?: error("webauthn_verification_failed")
        val id = reg.credentialID.toBase64Url()
        return buildJsonObject {
            put("id", id)
            put("rawId", id)
            put("type", "public-key")
            put("authenticatorAttachment", "platform")
            put(
                "response",
                buildJsonObject {
                    put("clientDataJSON", reg.rawClientDataJSON.toBase64Url())
                    put("attestationObject", reg.rawAttestationObject?.toBase64Url() ?: error("webauthn_verification_failed"))
                    put("transports", buildJsonArray { add(JsonPrimitive("internal")); add(JsonPrimitive("hybrid")) })
                },
            )
            put("clientExtensionResults", JsonObject(emptyMap()))
        }.toString()
    }

    override suspend fun get(optionsJson: String): String {
        val o = options(optionsJson)
        val rp = o.string("rpId") ?: error("webauthn_invalid_options")
        val request = ASAuthorizationPlatformPublicKeyCredentialProvider(rp).createCredentialAssertionRequestWithChallenge(o.bytes("challenge"))
        (o["allowCredentials"] as? JsonArray)?.let { allowed ->
            request.allowedCredentials = allowed.map {
                ASAuthorizationPlatformPublicKeyCredentialDescriptor(credentialID = (it as JsonObject).bytes("id"))
            }
        }
        o.string("userVerification")?.let { request.userVerificationPreference = it }
        val assertion = perform(request) as? ASAuthorizationPlatformPublicKeyCredentialAssertion ?: error("webauthn_verification_failed")
        val id = assertion.credentialID.toBase64Url()
        return buildJsonObject {
            put("id", id)
            put("rawId", id)
            put("type", "public-key")
            put("authenticatorAttachment", "platform")
            put(
                "response",
                buildJsonObject {
                    put("clientDataJSON", assertion.rawClientDataJSON.toBase64Url())
                    put("authenticatorData", assertion.rawAuthenticatorData?.toBase64Url() ?: error("webauthn_verification_failed"))
                    put("signature", assertion.signature?.toBase64Url() ?: error("webauthn_verification_failed"))
                    assertion.userID?.let { put("userHandle", it.toBase64Url()) }
                },
            )
            put("clientExtensionResults", JsonObject(emptyMap()))
        }.toString()
    }

    private suspend fun perform(request: ASAuthorizationRequest): Any? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val ceremony = Ceremony(
                onDone = { result -> inFlight = null; if (cont.isActive) cont.resume(result) },
                onError = { e -> inFlight = null; if (cont.isActive) cont.resumeWithException(e) },
            )
            inFlight = ceremony
            ASAuthorizationController(listOf(request)).apply {
                delegate = ceremony
                presentationContextProvider = ceremony
                ceremony.controller = this
                performRequests()
            }
            cont.invokeOnCancellation { ceremony.controller?.cancel() }
        }
    }

    /** The server sends either the bare options or the WebAuthn `{ publicKey: … }` wrapper. */
    private fun options(json: String): JsonObject {
        val root = VectraJson.parseToJsonElement(json).jsonObject
        return root["publicKey"] as? JsonObject ?: root
    }
}

private class Ceremony(
    val onDone: (Any?) -> Unit,
    val onError: (Throwable) -> Unit,
) : NSObject(), ASAuthorizationControllerDelegateProtocol, ASAuthorizationControllerPresentationContextProvidingProtocol {
    var controller: ASAuthorizationController? = null

    override fun authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization: ASAuthorization) =
        onDone(didCompleteWithAuthorization.credential)

    override fun authorizationController(controller: ASAuthorizationController, didCompleteWithError: NSError) = onError(
        if (didCompleteWithError.domain == ASAuthorizationErrorDomain && didCompleteWithError.code == ASAuthorizationErrorCanceled) {
            PasskeyCancelledException()
        } else {
            IllegalStateException(didCompleteWithError.localizedDescription)
        },
    )

    override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): UIWindow =
        keyWindow() ?: UIWindow()
}

/** The foreground scene's key window. */
internal fun keyWindow(): UIWindow? = UIApplication.sharedApplication.connectedScenes
    .filterIsInstance<UIWindowScene>()
    .flatMap { it.windows.filterIsInstance<UIWindow>() }
    .let { windows -> windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private val Base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

private fun JsonObject.bytes(key: String): NSData =
    Base64Url.decode(string(key) ?: error("webauthn_invalid_options")).toNSData()

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toBase64Url(): String {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
}
