package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.pbString
import dev.southstar21.vectra.core.network.string
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.core.auth.AuthRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

enum class NeptunSyncStatus { Idle, Syncing, Success, Error }

/** One clean, DB-shaped Neptun event (sensitive fields still plaintext). */
data class NeptunSyncEvent(
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

fun ParsedIcsEvent.toSyncEvent() = NeptunSyncEvent(
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
fun interface NeptunSyncListener {
    suspend fun onNeptunEventsPersisted(events: List<ScheduleEvent>)
}

/** Another account signed in while a Neptun sync or import ran; it stops before touching that account. */
internal class AccountChangedException : IllegalStateException("You switched accounts during the Neptun sync. Please try again.")

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
    private val scope: CoroutineScope,
    private val listener: NeptunSyncListener,
    private val onScheduleReplaced: () -> Unit,
) {
    private val userId: String? get() = session.userId

    private val _neptunStatus = MutableStateFlow(NeptunSyncStatus.Idle)
    val neptunSyncStatus: StateFlow<NeptunSyncStatus> = _neptunStatus.asStateFlow()

    private val _neptunError = MutableStateFlow<String?>(null)
    val neptunSyncError: StateFlow<String?> = _neptunError.asStateFlow()

    @Volatile private var autoSyncAttempted = false
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
        for (attempt in 1..maxRetries) {
            try {
                val text = withTimeout(ICS_FETCH_TIMEOUT_MS) {
                    val res = http.get(NEPTUN_ICS_PROXY_URL) {
                        parameter("url", clean)
                        // A sync must see the live feed, never the HTTP cache's copy.
                        header(HttpHeaders.CacheControl, "no-cache")
                    }
                    if (res.status.isSuccess()) res.bodyAsText() else null
                }
                if (text != null && text.contains("BEGIN:VCALENDAR")) return text
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
            } catch (_: Throwable) {
                // retried below
            }
            if (attempt < maxRetries) delay(1500L * attempt)
        }
        error(NEPTUN_FETCH_FAILED)
    }

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
    suspend fun persistNeptunEvents(events: List<NeptunSyncEvent>, owner: String = session.requireUserId()) {
        requireOwner(owner)
        val encrypted = coroutineScope {
            events.map { e ->
                async {
                    val enc = listOf(
                        e.courseName, e.courseCode, e.instructor, e.location, e.dayOfWeek.toString(), e.startTime, e.endTime,
                        e.semester, e.semesterStart, e.semesterEnd, e.eventType,
                    ).map { v -> async { crypto.encrypt(v) } }.awaitAll()
                    buildJsonObject {
                        put("course_name", enc[0]); put("course_code", enc[1]); put("instructor", enc[2]); put("location", enc[3])
                        put("day_of_week", enc[4]); put("start_time", enc[5]); put("end_time", enc[6])
                        put("color", e.color)
                        put("semester", enc[7]); put("semester_start", enc[8]); put("semester_end", enc[9]); put("event_type", enc[10])
                    }
                }
            }.awaitAll()
        }
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
            requireOwner(owner)
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
        persistNeptunEvents(events, owner)
        notifyListener(owner, events)
        requireOwner(owner)
        stampLastSync(Clock.System.now().toEpochMilliseconds())
        onScheduleReplaced()
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
            val text = fetchNeptunIcs(url)
            val result = parseIcs(text)
            if (result.valid.isEmpty()) error("No valid class events found in calendar feed.")
            val semester = pickNeptunSyncSemester(result.valid) ?: error("No class events found for the current or upcoming semester.")
            val clean = result.valid.filter { it.semester == semester }.map { it.toSyncEvent() }
            persistNeptunEvents(clean, owner)
            notifyListener(owner, clean)
            requireOwner(owner)
            val nowMs = Clock.System.now().toEpochMilliseconds()
            // Only successful manual syncs consume the rate-limit quota.
            if (!isAutoSync) recordManualSync(nowMs)
            stampLastSync(nowMs)
            _neptunStatus.value = NeptunSyncStatus.Success
            _neptunError.value = null
            writeStoredSyncError(null)
            onScheduleReplaced()
            scope.launch {
                delay(3000)
                if (_neptunStatus.value == NeptunSyncStatus.Success) _neptunStatus.value = NeptunSyncStatus.Idle
            }
            semester
        } catch (e: CancellationException) {
            throw e
        } catch (_: AccountChangedException) {
            // The account signed in now never asked for this sync; it reports nothing.
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
    suspend fun checkSyncRateLimit(nowMs: Long = Clock.System.now().toEpochMilliseconds()): ScheduleRepository.RateLimit {
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
    suspend fun saveNeptunUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            clearNeptunEvents()
            auth.updateProfile(mapOf("neptun_ics_url" to JsonNull, "neptun_last_sync" to JsonNull))
            onScheduleReplaced()
            return
        }
        val encrypted = crypto.encrypt(trimmed)
        auth.updateProfile(mapOf("neptun_ics_url" to JsonPrimitive(encrypted)))
    }

    /** Sync on launch when never synced or the last sync is over 24h old. */
    fun maybeAutoSync() {
        if (autoSyncAttempted) return
        val profile = auth.profile.value ?: return
        val raw = profile.neptunIcsUrl?.trim()?.ifEmpty { null } ?: return
        // Reached from both the user and the profile collectors: one check at a time.
        if (!autoSyncCheckRunning.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                val url = crypto.decryptUrl(raw)
                if (url.isNullOrEmpty() || autoSyncAttempted) return@launch
                val last = profile.neptunLastSync?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                    ?: profile.neptunLastSync?.let { runCatching { Instant.parse(it.replace(' ', 'T')).toEpochMilliseconds() }.getOrNull() }
                val now = Clock.System.now().toEpochMilliseconds()
                if (last == null || now - last > 24 * ONE_HOUR_MS) {
                    autoSyncAttempted = true
                    runSync(url, isAutoSync = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The auto-sync check is best-effort.
            } finally {
                autoSyncCheckRunning.value = false
            }
        }
    }

    companion object {
        const val SYNC_ERROR_KEY = "vectra_neptun_sync_error"
        const val RATE_LIMIT_STORAGE_KEY = "vectra_neptun_sync_timestamps"
        const val MAX_SYNCS_PER_HOUR = 3
        const val ONE_HOUR_MS = 60L * 60 * 1000
        private const val ICS_FETCH_TIMEOUT_MS = 30_000L
    }
}
