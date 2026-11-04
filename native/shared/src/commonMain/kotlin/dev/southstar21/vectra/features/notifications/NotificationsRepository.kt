package dev.southstar21.vectra.features.notifications

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.int
import dev.southstar21.vectra.core.platform.Notifier
import dev.southstar21.vectra.core.platform.PushRegistrar
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.auth.SessionListener
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The device's push
 * registration lives in `push_subscriptions` with `transport = 'fcm'`.
 */
class NotificationsRepository(
    private val pb: PocketBaseClient,
    private val auth: AuthRepository,
    private val kv: KeyValueStore,
    private val notifier: Notifier,
    private val push: PushRegistrar,
) : SessionListener {
    private val _subscribed = MutableStateFlow(false)
    val subscribed: StateFlow<Boolean> = _subscribed.asStateFlow()

    val pushAvailable: Boolean get() = push.isConfigured

    fun permissionGranted() = notifier.permissionGranted()

    suspend fun check() {
        val id = kv.get(ROW_KEY)
        _subscribed.value = id != null && notifier.permissionGranted()
    }

    suspend fun subscribe() {
        val token = push.token() ?: error("Push notifications are not available on this build")
        val user = auth.session.value?.userId ?: error("Not authenticated")
        val col = pb.collection("push_subscriptions")
        // Same token → one row: delete existing rows for this token before creating it.
        col.getFullList(filter = "user = '$user' && transport = 'fcm'").forEach { row ->
            if ((row["subscription"] as? JsonObject)?.get("token") == JsonPrimitive(token)) col.delete(row["id"]!!.toString().trim('"'))
        }
        val row = col.create(
            buildJsonObject {
                put("user", JsonPrimitive(user))
                put("subscription", buildJsonObject { put("token", JsonPrimitive(token)) })
                put("transport", JsonPrimitive("fcm"))
                put("device_name", JsonPrimitive("Mobile"))
            },
        )
        kv.set(ROW_KEY, row["id"].toString().trim('"'))
        _subscribed.value = true
    }

    suspend fun unsubscribe() {
        kv.get(ROW_KEY)?.let { runSuspendCatching { pb.collection("push_subscriptions").delete(it) } }
        kv.set(ROW_KEY, null)
        _subscribed.value = false
    }

    /** sendServerTestNotification: returns the number of devices reached. */
    suspend fun sendServerTest(): Int {
        val user = auth.session.value?.userId ?: error("Not authenticated")
        val res = pb.send(
            "/api/vectra/push/send",
            HttpMethod.Post,
            buildJsonObject {
                put("user_id", JsonPrimitive(user))
                put("title", JsonPrimitive("Vectra Test 🚀"))
                put("body", JsonPrimitive("Push notifications are working on this device."))
                put("url", JsonPrimitive("/settings"))
                put("tag", JsonPrimitive("vectra-test"))
            },
        ).jsonObject
        return res.int("sent") ?: 0
    }

    fun showLocal(title: String, body: String) = notifier.show(title, body)

    /** Sign-out: drop this device's row while the session can still delete it. */
    override suspend fun beforeSignOut() = unsubscribe()

    private companion object {
        const val ROW_KEY = "vectra.push.row"
    }
}
