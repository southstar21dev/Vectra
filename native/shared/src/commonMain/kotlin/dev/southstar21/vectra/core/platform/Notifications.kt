package dev.southstar21.vectra.core.platform

import dev.southstar21.vectra.core.network.OneOrManySerializer
import kotlinx.serialization.Serializable

/** System notifications. */
interface Notifier {
    fun permissionGranted(): Boolean
    /** Returns false when the system refused to show it. */
    fun show(title: String, body: String, url: String? = null, tag: String? = null): Boolean

    /**
     * Whether the app gets to run when a scheduled notification fires (Android), so a
     * [ScheduledNotification.departures] query can be answered with live times then. Where it
     * can't (iOS), the body must be final when it is scheduled.
     */
    val resolvesAtFireTime: Boolean get() = false

    /** Replaces everything scheduled through this before with [notifications]; empty cancels them all. */
    fun replaceScheduled(notifications: List<ScheduledNotification>) {}
}

/** The two kinds of reminder; Android shows them on separate channels the user can mute apart. */
@Serializable
enum class ReminderChannel { Classes, Commute }

/** A notification set to appear at [atMs], whether or not the app is running then. */
@Serializable
data class ScheduledNotification(
    val id: String,
    val atMs: Long,
    val title: String,
    val body: String,
    val url: String? = null,
    val channel: ReminderChannel = ReminderChannel.Classes,
    /** Departures to add below [body] when it fires, a line each; empty when the body is already complete. */
    @Serializable(with = DepartureQueryList::class)
    val departures: List<DepartureQuery> = emptyList(),
    /** When it stops being useful (its class has started); Android removes it then. */
    val expiresAtMs: Long? = null,
)

/** One saved tracker stop, enough to fetch and describe its next departures without the tracker list. */
@Serializable
data class DepartureQuery(
    val stopId: String,
    val stopName: String,
    val routeId: String,
    val headsign: String? = null,
    /** "Tram 6", "M4". */
    val lineLabel: String,
)

/** Alarms set before several lines were allowed carry a single query. */
object DepartureQueryList : OneOrManySerializer<DepartureQuery>(DepartureQuery.serializer())

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
