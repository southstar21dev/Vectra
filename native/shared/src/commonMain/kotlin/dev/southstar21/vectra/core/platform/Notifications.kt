package dev.southstar21.vectra.core.platform

/** System notifications. */
interface Notifier {
    fun permissionGranted(): Boolean
    /** Returns false when the system refused to show it. */
    fun show(title: String, body: String, url: String? = null, tag: String? = null): Boolean
}

/**
 * Native push registration (FCM on Android). The backend's push route sends Web
 * Push only today; a native transport needs the `fcm` changes described in
 * native/docs/BACKEND_CHANGES.md and a Firebase config, so this stays off until then.
 */
interface PushRegistrar {
    val isConfigured: Boolean
    suspend fun token(): String?
}

object NoPush : PushRegistrar {
    override val isConfigured = false
    override suspend fun token(): String? = null
}
