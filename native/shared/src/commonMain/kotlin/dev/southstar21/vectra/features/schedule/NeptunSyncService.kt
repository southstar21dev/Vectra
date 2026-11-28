package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.pbString
import dev.southstar21.vectra.core.network.string
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.auth.SessionEndedException
import dev.southstar21.vectra.core.util.BackoffPolicy
import dev.southstar21.vectra.core.util.retrying
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

internal enum class NeptunSyncStatus { Idle, Syncing, Success, Error }

/** One clean, DB-shaped Neptun event (sensitive fields still plaintext). */
internal data class NeptunSyncEvent(
    val courseName: String,
    val courseCode: String,
    val instructor: String,
    val location: String,
    val dayOfWeek: Int,
    val startTime: String,
    val endTime: String,
    val color: String,
    val semester: String,
    val semesterStart: String?,
    val semesterEnd: String?,
    val eventType: String,
)

internal fun ParsedIcsEvent.toSyncEvent() = NeptunSyncEvent(
    courseName, courseCode, instructor, location, dayOfWeek, startTime, endTime,
    color.ifEmpty { "#3B82F6" }, semester, semesterStart?.ifEmpty { null }, semesterEnd?.ifEmpty { null }, eventType.ifEmpty { "class" },
)

/** As the class list will show it once refetched (it has no row id yet). */
internal fun NeptunSyncEvent.toScheduleEvent(owner: String) = ScheduleEvent(
    id = "", userId = owner, courseName = courseName, courseCode = courseCode, instructor = instructor, location = location,
    dayOfWeek = dayOfWeek, startTime = startTime, endTime = endTime, color = color, semester = semester,
    semesterStart = semesterStart, semesterEnd = semesterEnd, eventType = eventType, source = "neptun",
)

/**
 * Told after a Neptun sync or import persisted its events, with every class as it now stands
 * (the new Neptun rows and the manual ones). Failures never fail the sync.
 */
internal fun interface NeptunSyncListener {
    suspend fun onNeptunEventsPersisted(events: List<ScheduleEvent>)
}

/** Another account signed in while a Neptun sync or import ran; it stops before touching that account. */
internal class AccountChangedException : IllegalStateException("You switched accounts during the Neptun sync. Please try again.")

/** The link was removed or the classes were cleared while a sync ran; it stops without writing. */
internal class SyncSupersededException : IllegalStateException("The Neptun sync was cancelled.")

/**
 * Neptun calendar sync: fetch the ICS feed through the proxy, parse it, replace the stored
 * classes. Everything downstream hears about it through [onScheduleReplaced].
 */

internal class NeptunSyncService(
    private val pb: PocketBaseClient,
    private val crypto: CryptoClient,
    private val kv: KeyValueStore,
    private val auth: AuthRepository,
    private val http: HttpClient,
    private val session: ScheduleSession,
    private val classes: ScheduleEventsRepository,
    private val listener: NeptunSyncListener,
    private val onScheduleReplaced: () -> Unit,
    /** Wall clock in epoch milliseconds; a seam so the rate limit and the 24 h auto-sync gate can be tested. */
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val userId: String? get() = session.userId

    /** Syncs, imports and the auto-sync check run in the signed-in account's session and end with it. */
    private val sessions get() = session.sessions

    private val _neptunStatus = MutableStateFlow(NeptunSyncStatus.Idle)
    val neptunSyncStatus: StateFlow<NeptunSyncStatus> = _neptunStatus.asStateFlow()

    private val _neptunError = MutableStateFlow<String?>(null)
    val neptunSyncError: StateFlow<String?> = _neptunError.asStateFlow()

    @Volatile private var autoSyncAttempted = false

    /** Bumped when the link is removed or all classes are cleared, so a sync already running gives up. */
    @Volatile private var syncEpoch = 0

    /** Makes any sync that is already past its fetch abort before it writes. */
    fun invalidateRunningSyncs() {
        syncEpoch++
    }

    private fun requireEpoch(epoch: Int) {
        if (syncEpoch != epoch) throw SyncSupersededException()
    }
    private val autoSyncCheckRunning = MutableStateFlow(false)

    fun onUserChanged() {
        autoSyncAttempted = false
    }

    /** Shows the last sync's outcome again after sign-in. */
    suspend fun restoreStatus() {
        val storedError = kv.get(SYNC_ERROR_KEY)
        _neptunError.value = storedError
        _neptunStatus.value = if (storedError != null) NeptunSyncStatus.Error else NeptunSyncStatus.Idle
    }

    /** Fetch the Neptun ICS feed, always through the Apps Script proxy (Neptun blocks datacenter IPs). */
    suspend fun fetchNeptunIcs(url: String, maxRetries: Int = 3): String {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            error("Invalid URL format. Please provide a valid http:// or https:// URL.")
        }
        val policy = BackoffPolicy.linear(maxRetries, stepMs = RETRY_STEP_MS, attemptTimeoutMs = ICS_FETCH_TIMEOUT_MS)
        if (maxRetries > 0) {
            runSuspendCatching {
                retrying(policy) {
                    val res = http.get(NEPTUN_ICS_PROXY_URL) {
                        parameter("url", clean)
                        // A sync must see the live feed, never the HTTP cache copy, and its URL carries the Neptun link: store nothing.
                        header(HttpHeaders.CacheControl, "no-cache, no-store")
                    }
                    val text = if (res.status.isSuccess()) res.bodyAsText() else null
                    if (text == null || !text.contains("BEGIN:VCALENDAR")) error("no calendar in the answer")
                    text
                }
            }.getOrNull()?.let { return it }
        }
        error(NEPTUN_FETCH_FAILED)
    }

    /**
     * Whether [url] serves a calendar with at least one class, checked before it is saved: a link that
     * only looks like a URL would otherwise be stored as "connected" and fail every sync. Throws when
     * nothing could be downloaded from it.
     */
    suspend fun verifyNeptunLink(url: String): Boolean =
        parseIcs(fetchNeptunIcs(url, maxRetries = VERIFY_FETCH_ATTEMPTS)).valid.isNotEmpty()

    /** Parse off the main thread. */
    suspend fun parseIcs(text: String): ParseIcsResult = withContext(Dispatchers.Default) { parseIcsContent(text) }

    /** pickNeptunSyncSemester: never swaps a newer stored semester for an older one. */
    suspend fun pickNeptunSyncSemester(events: List<ParsedIcsEvent>, at: Instant = Clock.System.now()): String? {
        val stored = newestStoredSemester(userId?.let { classes.cached(it) }.orEmpty())
        return selectSyncSemester(events.map { SemesterTaggedEvent(it.semester, it.semesterEnd) }, at, stored)
    }

    /** Throws when [owner] is no longer the signed-in account. */
    private fun requireOwner(owner: String) {
        if (userId != owner) throw AccountChangedException()
    }

    /**
     * persistNeptunEvents: encrypt, then the atomic server-side replace. [owner] is the account the
     * events belong to; the replace goes out with whoever is signed in, so it is checked again just before.
     */
    suspend fun persistNeptunEvents(
        events: List<NeptunSyncEvent>,
        owner: String = session.requireUserId(),
        epoch: Int = syncEpoch,
    ) = sessions.withSession(owner) {
        requireEpoch(epoch)
        val encrypted = coroutineScope {
            events.map { e -> async { neptunRecord(e) { v -> crypto.encrypt(v) } } }.awaitAll()
        }
        requireEpoch(epoch)
        // Kept: the replace is authenticated as whoever is signed in when it leaves, so the owner is
        // checked at the last moment before it is sent, not just through the session scope.
        requireOwner(owner)
        pb.send("/api/vectra/neptun/replace", HttpMethod.Post, buildJsonObject { put("events", JsonArray(encrypted)) })
    }

    /** Remove the Neptun-synced rows (URL cleared). */
    suspend fun clearNeptunEvents() {
        val uid = userId ?: return
        coroutineScope {
            pb.collection("neptun_schedule_events").getFullList(filter = "user = ${pbString(uid)}")
                .map { row -> async { pb.collection("neptun_schedule_events").delete(row.string("id").orEmpty()) } }.awaitAll()
        }
    }

    /**
     * Hands the listener the classes as they are after the replace: refetched (and shown), or
     * when that fails the manual classes on screen plus the rows just persisted. The list on
     * screen is still the pre-sync one, which on a first import is empty.
     */
    private suspend fun notifyListener(owner: String, persisted: List<NeptunSyncEvent>) {
        try {
            val fresh = runSuspendCatching { classes.fetchEventsFromCloud(owner) }.getOrNull()
            currentCoroutineContext().ensureActive()
            if (fresh != null) classes.publish(fresh)
            listener.onNeptunEventsPersisted(fresh ?: (classes.events.value.filter { it.source != "neptun" } + persisted.map { it.toScheduleEvent(owner) }))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Auto-populating course trackers is best-effort.
        }
    }

    private suspend fun stampLastSync(nowMs: Long) {
        try {
            auth.updateProfile(mapOf("neptun_last_sync" to JsonPrimitive(Instant.fromEpochMilliseconds(nowMs).toString())))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Failing to stamp the sync time must not fail the sync.
        }
    }

    /** Import the import wizard's confirmed preview. */
    suspend fun importNeptunEvents(events: List<NeptunSyncEvent>) {
        val owner = session.requireUserId()
        sessions.withSession(owner) {
            persistNeptunEvents(events, owner)
            notifyListener(owner, events)
            stampLastSync(clockMs())
            onScheduleReplaced()
        }
    }

    private suspend fun writeStoredSyncError(message: String?) {
        runSuspendCatching { kv.set(SYNC_ERROR_KEY, message) }
    }

    /**
     * runSync: fetch → parse → pick one semester → replace → stamp. Returns the
     * imported semester on success (the caller toasts a manual sync), null otherwise.
     */
    suspend fun runSync(url: String, isAutoSync: Boolean = false): String? {
        if (url.isEmpty()) return null
        _neptunStatus.value = NeptunSyncStatus.Syncing
        _neptunError.value = null
        writeStoredSyncError(null)
        return try {
            // The fetch can take a minute and a half: pin the account it was started for.
            val owner = session.requireUserId()
            val epoch = syncEpoch
            sessions.withSession(owner) {
                val text = fetchNeptunIcs(url)
                val result = parseIcs(text)
                if (result.valid.isEmpty()) error("No valid class events found in calendar feed.")
                val semester = pickNeptunSyncSemester(result.valid) ?: error("No class events found for the current or upcoming semester.")
                val clean = result.valid.filter { it.semester == semester }.map { it.toSyncEvent() }
                persistNeptunEvents(clean, owner, epoch)
                notifyListener(owner, clean)
                requireEpoch(epoch)
                val nowMs = clockMs()
                // Only successful manual syncs consume the rate-limit quota.
                if (!isAutoSync) recordManualSync(nowMs)
                stampLastSync(nowMs)
                _neptunStatus.value = NeptunSyncStatus.Success
                _neptunError.value = null
                writeStoredSyncError(null)
                onScheduleReplaced()
                sessions.launchFor(owner) {
                    delay(3000)
                    if (_neptunStatus.value == NeptunSyncStatus.Success) _neptunStatus.value = NeptunSyncStatus.Idle
                }
                semester
            }
        } catch (_: SessionEndedException) {
            // The account signed in now never asked for this sync; it reports nothing.
            _neptunStatus.value = NeptunSyncStatus.Idle
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: SyncSupersededException) {
            // The user removed the link or cleared the classes meanwhile; nothing was written.
            _neptunStatus.value = NeptunSyncStatus.Idle
            null
        } catch (_: AccountChangedException) {
            // Raised just before the replace was sent: the same case as a session that ended.
            _neptunStatus.value = NeptunSyncStatus.Idle
            null
        } catch (e: Throwable) {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: "Failed to sync with Neptun calendar."
            _neptunStatus.value = NeptunSyncStatus.Error
            _neptunError.value = msg
            writeStoredSyncError(msg)
            null
        }
    }

    private suspend fun readTimestamps(): List<Long> = runSuspendCatching {
        kv.get(RATE_LIMIT_STORAGE_KEY)?.let { VectraJson.decodeFromString(ListSerializer(Long.serializer()), it) }
    }.getOrNull().orEmpty()

    /** checkSyncRateLimit: at most 3 manual syncs per rolling hour. */
    suspend fun checkSyncRateLimit(nowMs: Long = clockMs()): ScheduleRepository.RateLimit {
        val valid = readTimestamps().filter { nowMs - it < ONE_HOUR_MS }
        if (valid.size >= MAX_SYNCS_PER_HOUR) {
            val oldest = valid.min()
            val remaining = ((ONE_HOUR_MS - (nowMs - oldest)) + 59_999) / 60_000
            return ScheduleRepository.RateLimit(false, remaining.toInt())
        }
        return ScheduleRepository.RateLimit(true, 0)
    }

    private suspend fun recordManualSync(nowMs: Long) {
        val valid = readTimestamps().filter { nowMs - it < ONE_HOUR_MS } + nowMs
        runSuspendCatching { kv.set(RATE_LIMIT_STORAGE_KEY, VectraJson.encodeToString(ListSerializer(Long.serializer()), valid)) }
    }

    /** The saved subscription URL, decrypted ("" when none). */
    suspend fun savedNeptunUrl(): String {
        val raw = auth.profile.value?.neptunIcsUrl?.trim()?.ifEmpty { null } ?: return ""
        return crypto.decryptUrl(raw).orEmpty()
    }

    /** Save (encrypted) or clear the subscription link; clearing also removes the synced rows. */
    suspend fun saveNeptunUrl(url: String, claimFirstSync: Boolean = false) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            invalidateRunningSyncs()
            clearNeptunEvents()
            auth.updateProfile(mapOf("neptun_ics_url" to JsonNull, "neptun_last_sync" to JsonNull))
            onScheduleReplaced()
            return
        }
        val encrypted = crypto.encrypt(trimmed)
        // The import wizard imports on confirm; the profile update must not start a second, unconfirmed sync.
        if (claimFirstSync) autoSyncAttempted = true
        auth.updateProfile(mapOf("neptun_ics_url" to JsonPrimitive(encrypted)))
    }

    /** Sync on launch when never synced or the last sync is over 24h old. */
    fun maybeAutoSync() {
        if (autoSyncAttempted) return
        val uid = userId ?: return
        val profile = auth.profile.value ?: return
        val raw = profile.neptunIcsUrl?.trim()?.ifEmpty { null } ?: return
        // Reached from both the user and the profile collectors: one check at a time.
        if (!autoSyncCheckRunning.compareAndSet(expect = false, update = true)) return
        // Released on completion, not in a finally: a scope that is already cancelled never runs its body.
        sessions.launchFor(uid) {
            try {
                val url = crypto.decryptUrl(raw)
                if (url.isNullOrEmpty() || autoSyncAttempted) return@launchFor
                val last = parseLastSyncMs(profile.neptunLastSync)
                val now = clockMs()
                if (last == null || now - last > 24 * ONE_HOUR_MS) {
                    autoSyncAttempted = true
                    runSync(url, isAutoSync = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The auto-sync check is best-effort.
            }
        }.invokeOnCompletion { autoSyncCheckRunning.value = false }
    }

    /** The profile's last-sync stamp as epoch ms; PocketBase writes it with a space, the app with a T. */
    private fun parseLastSyncMs(stamp: String?): Long? {
        stamp ?: return null
        return runCatching { Instant.parse(stamp).toEpochMilliseconds() }.getOrNull()
            ?: runCatching { Instant.parse(stamp.replace(' ', 'T')).toEpochMilliseconds() }.getOrNull()
    }

    companion object {
        const val SYNC_ERROR_KEY = "vectra_neptun_sync_error"
        const val RATE_LIMIT_STORAGE_KEY = "vectra_neptun_sync_timestamps"
        const val MAX_SYNCS_PER_HOUR = 3
        const val ONE_HOUR_MS = 60L * 60 * 1000
        private const val ICS_FETCH_TIMEOUT_MS = 30_000L
        private const val VERIFY_FETCH_ATTEMPTS = 2
        /** Wait after the first failed download; each further one waits this much longer. */
        private const val RETRY_STEP_MS = 1500L
    }
}
