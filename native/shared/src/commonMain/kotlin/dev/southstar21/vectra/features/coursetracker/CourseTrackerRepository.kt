package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.core.data.UserKeys
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.auth.SessionScope
import dev.southstar21.vectra.core.network.isCiphertext
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.schedule.ScheduleEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** Offline cache base key: `<base>_<userId>`, the bare base when signed out. */
internal const val CourseTrackerCacheKey = UserKeys.COURSE_TRACKER

internal fun courseTrackerCacheKey(userId: String?): String = if (userId.isNullOrEmpty()) CourseTrackerCacheKey else "${CourseTrackerCacheKey}_$userId"

internal enum class TrackerUploadResult { Uploaded, HeldBack }

/** Attendance gets ticked off in runs; only the last state of a run goes to the cloud. */
internal const val TrackerUploadDebounceMs = 1500L

/**
 * The course tracker's data, cloud sync and schedule reconciliation. One encrypted
 * `data` blob per user in `course_tracker_data`, cached per user offline.
 * [data] is the single source of truth every view renders from; each write
 * lands there and in the offline cache synchronously, then uploads through
 * the [CloudWriteGate] (merged with the latest cloud copy at upload time)
 * once writes pause for [TrackerUploadDebounceMs].
 */
internal class CourseTrackerRepository(
    private val cloud: TrackerCloud,
    private val cipher: TrackerCipher,
    private val kv: KeyValueStore,
    private val scope: CoroutineScope,
    private val userIdFlow: StateFlow<String?>,
    private val isOnline: () -> Boolean,
    private val gate: CloudWriteGate = CloudWriteGate(),
    /** Connectivity changes: every false to true transition retries what never reached the cloud. */
    onlineChanges: Flow<Boolean>? = null,
) {
    private val _data = MutableStateFlow(createEmptyTrackerData())
    val data: StateFlow<CourseTrackerData> = _data.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    /** True until this user's cache and first cloud read have resolved. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val refreshLock = Mutex()
    private var lastFetchMs = 0L
    private val uploadLock = Mutex()
    private var pendingUploadUser: String? = null
    private var uploadTimer: Job? = null

    private val userId: String? get() = userIdFlow.value

    /** Per-user async work (refreshes, uploads, cache writes) runs here and dies with the signed-in user. */
    private val sessions = SessionScope(scope, { userIdFlow.value }, userIdFlow)
    private var uploadedSnapshot: CourseTrackerData? = null

    // Tracker keys a schedule sync removed (stale exams, legacy keys): cloud copies of them are
    // ignored in later merges, or a collapsed or later upload would put them back.
    @Volatile private var droppedKeys: Set<String> = emptySet()

    private fun withoutDropped(remote: CourseTrackerData): CourseTrackerData {
        val keys = droppedKeys
        return if (keys.isEmpty()) remote else remote.copy(trackers = remote.trackers - keys)
    }

    // Signed-in edits made, and how many of them the cloud has: unequal means some exist only here.
    @Volatile private var editsMade = 0
    @Volatile private var editsUploaded = 0

    init {
        onlineChanges?.let { flow ->
            scope.launch {
                var wasOnline = isOnline()
                flow.collect { online ->
                    if (online && !wasOnline) retryUnsynced()
                    wasOnline = online
                }
            }
        }
        scope.launch {
            userIdFlow.collect { uid ->
                editsMade = 0
                editsUploaded = 0
                droppedKeys = emptySet()
                _isLoading.value = true
                _data.value = readCache(uid) ?: readCache(null) ?: createEmptyTrackerData()
                refresh()
                _isLoading.value = false
            }
        }
    }

    // ---- offline cache ---------------------------------------------------------

    private suspend fun readCache(uid: String?): CourseTrackerData? =
        runSuspendCatching { parseTrackerData(kv.get(courseTrackerCacheKey(uid))) }.getOrNull()

    private suspend fun writeCache(next: CourseTrackerData, uid: String?) {
        runSuspendCatching { kv.set(courseTrackerCacheKey(uid), serializeTrackerData(next)) }
    }

    // ---- cloud ---------------------------------------------------------------------

    /**
     * readCloudTrackerData: null when the user has no row; throws when the row
     * cannot be decrypted/parsed (e.g. written by a newer client) and rethrows
     * network errors, so a failed read is never mistaken for an empty document.
     */
    private suspend fun readCloud(uid: String): CourseTrackerData? {
        val encrypted = cloud.fetch(uid) ?: return null
        val decrypted = cipher.decrypt(encrypted)
        return (if (isCiphertext(decrypted)) null else parseTrackerData(decrypted)) ?: throw UnreadableCloudTrackerException()
    }

    /**
     * The single cloud write path: held back until the cloud copy has been read
     * on this device; otherwise re-reads and merges the cloud copy inside the
     * serialised queue, so another device's newer trackers survive.
     */
    suspend fun upload(uid: String, local: CourseTrackerData, dropKeys: List<String> = emptyList()): TrackerUploadResult {
        // Remembered before queueing: a refresh merge, or an upload this one collapses into, must
        // already leave the removed trackers out, even when this run never executes itself.
        if (dropKeys.isNotEmpty()) droppedKeys += dropKeys
        if (!gate.isReadable(uid)) {
            gate.noteHeldBack(uid)
            return TrackerUploadResult.HeldBack
        }
        gate.enqueue {
            val remote = readCloud(uid)
            var merged = if (remote != null) mergeTrackerData(local, withoutDropped(remote)) else local
            if (dropKeys.isNotEmpty()) merged = merged.copy(trackers = merged.trackers - dropKeys.toSet())
            cloud.upsert(uid, cipher.encrypt(serializeTrackerData(merged)))
            uploadedSnapshot = merged
        }
        return TrackerUploadResult.Uploaded
    }

    /** On resume, when the data is over 30 s old. */
    fun refreshIfStale() {
        if (Clock.System.now().toEpochMilliseconds() - lastFetchMs < StaleMs) return
        val uid = userId ?: return
        sessions.launchFor(uid) { refresh() }
    }

    /** The query function: merge the cloud copy into the live data (newest tracker wins). */
    suspend fun refresh() = refreshLock.withLock {
        val uid = userId
        if (uid == null || !isOnline()) return@withLock
        lastFetchMs = Clock.System.now().toEpochMilliseconds()
        // A sign-out ends the refresh quietly; the next user's own refresh follows it.
        sessions.withSessionOrNull(uid) { refreshFor(uid) }
        Unit
    }

    private suspend fun refreshFor(uid: String) {
        // A failure keeps the offline cache. The write gate hands every waiter the outcome of the run that
        // replaced its own, so a cancelled one is a failure here too (runSuspendCatching).
        runSuspendCatching {
            val encrypted = cloud.fetch(uid)
            if (encrypted == null) {
                // No cloud record yet: seed it from local data if there is any.
                gate.markReadable(uid)
                gate.takeHeldBack(uid)
                seedCloud(uid, _data.value)
                return@runSuspendCatching
            }
            val decrypted = cipher.decrypt(encrypted)
            // Unreadable (e.g. a newer client): keep the gate closed so the local copy never replaces it.
            val parsed = (if (isCiphertext(decrypted)) null else parseTrackerData(decrypted)) ?: return@runSuspendCatching
            gate.markReadable(uid)
            // The fetch was awaited in the session, so a sign-out ended it; this only guards the first write.
            currentCoroutineContext().ensureActive()
            // Merge against the live data at return time so an edit that landed
            // during the fetch is never clobbered by the cloud snapshot.
            var reconciledNext: CourseTrackerData? = null
            _data.update { live -> mergeTrackerData(live, withoutDropped(parsed)).also { reconciledNext = it } }
            val reconciled = reconciledNext ?: return@runSuspendCatching
            writeCache(reconciled, uid)
            // Local edits made while uploads were blocked, or kept from a session that expired
            // before they uploaded (the cache holds trackers newer than the cloud's): push the merged copy.
            if (gate.takeHeldBack(uid) || reconciled.trackers != parsed.trackers) pushMerged(uid, reconciled)
        }
    }

    private suspend fun seedCloud(uid: String, cached: CourseTrackerData) {
        if (cached.trackers.isEmpty()) return
        runSuspendCatching {
            val enc = cipher.encrypt(serializeTrackerData(cached))
            gate.enqueue { cloud.upsert(uid, enc) }
        }
    }

    private suspend fun pushMerged(uid: String, reconciled: CourseTrackerData) {
        runSuspendCatching {
            val enc = cipher.encrypt(serializeTrackerData(reconciled))
            gate.enqueue { cloud.upsert(uid, enc) }
        }.onFailure { gate.noteHeldBack(uid) }
    }

    // ---- writes ----------------------------------------------------------------------

    /** Restart the quiet period; the upload sends whatever [data] is by then. */
    private suspend fun scheduleUpload(uid: String) = uploadLock.withLock {
        pendingUploadUser = uid
        uploadTimer?.cancel()
        uploadTimer = sessions.launchFor(uid) {
            delay(TrackerUploadDebounceMs)
            uploadPending()
        }
    }

    private suspend fun uploadPending() {
        val uid = uploadLock.withLock {
            uploadTimer = null
            pendingUploadUser.also { pendingUploadUser = null }
        } ?: return
        // Signed out meanwhile: that user's edits stay out of the next user's upload, and the
        // upload below stops with the session.
        sessions.withSessionOrNull(uid) { uploadFor(uid) }
    }

    private suspend fun uploadFor(uid: String) {
        _isSaving.value = true
        val edits = editsMade
        try {
            // A failure stays in the offline cache; the next save or refresh carries it.
            runSuspendCatching {
                if (upload(uid, _data.value) == TrackerUploadResult.HeldBack) refresh() else adoptUploaded(uid, edits)
            }
        } finally {
            _isSaving.value = false
        }
    }

    /** After an upload that went through: counts it and takes in the cloud copy it merged. */
    private suspend fun adoptUploaded(uid: String, edits: Int) {
        // Kept: both writes below touch the next user's counters and data, with no suspension before them.
        if (userId != uid) return
        editsUploaded = maxOf(editsUploaded, edits)
        // The upload just merged the cloud copy, so another device's trackers are in hand.
        val merged = uploadedSnapshot ?: return
        uploadedSnapshot = null
        var next: CourseTrackerData? = null
        _data.update { live -> mergeTrackerData(live, merged).also { next = it } }
        next?.let { writeCache(it, uid) }
    }

    /**
     * Back online or back in the foreground: sends edits that exist only on this device (an upload
     * that failed offline or was held back because the cloud copy was never read).
     */
    fun retryUnsynced() {
        val uid = userId ?: return
        sessions.launchFor(uid) {
            if (!isOnline()) return@launchFor
            if (!gate.isReadable(uid)) refresh()
            if (editsMade != editsUploaded && gate.isReadable(uid)) {
                uploadLock.withLock {
                    uploadTimer?.cancel()
                    uploadTimer = null
                    pendingUploadUser = uid
                }
                uploadPending()
            }
        }
    }

    /** Sends a debounced upload now: the app is leaving the foreground and may not get another chance. */
    fun flushPendingUpload() {
        val uid = userId ?: return
        sessions.launchFor(uid) {
            uploadLock.withLock { uploadTimer?.cancel() }
            uploadPending()
        }
    }

    /**
     * Before an explicit sign-out: sends a debounced or failed upload now. True when the cloud
     * has every edit made while signed in.
     */
    suspend fun flushPendingWrites(): Boolean {
        val uid = userId
        if (uid != null && editsMade != editsUploaded) {
            uploadLock.withLock {
                uploadTimer?.cancel()
                uploadTimer = null
                pendingUploadUser = uid
            }
            uploadPending()
        }
        return editsMade == editsUploaded
    }

    /** Every mutation: instant local state + offline cache, then the (debounced) cloud upload. */
    fun update(mutator: (CourseTrackerData) -> CourseTrackerData) {
        var next: CourseTrackerData? = null
        _data.update { prev -> mutator(prev).also { next = it } }
        val snapshot = next ?: return
        val uid = userId
        if (uid != null) editsMade++
        val save: suspend CoroutineScope.() -> Unit = {
            writeCache(snapshot, uid)
            if (uid != null) scheduleUpload(uid)
        }
        // A signed-in edit must not outlive its account: it would write that account's cache after the wipe.
        if (uid != null) sessions.launchFor(uid, save) else scope.launch(block = save)
    }

    fun getTracker(identity: CourseIdentity): CourseTracker? = findTracker(_data.value, identity)

    fun getOrCreateTracker(identity: CourseIdentity): CourseTracker = findOrCreateTracker(_data.value, identity)

    fun setAttendanceStatus(
        identity: CourseIdentity,
        identifier: String,
        status: AttendanceStatus,
        startTime: String? = null,
        endTime: String? = null,
        title: String? = null,
        sessionType: SessionType? = null,
    ) = update { applySetAttendanceStatus(it, identity, identifier, status, startTime, endTime, title, sessionType) }

    fun addAttendanceSession(identity: CourseIdentity, session: AttendanceSession) = update { applyAddSession(it, identity, session) }
    fun removeAttendanceSession(identity: CourseIdentity, session: AttendanceSession) = update { applyRemoveSession(it, identity, session) }
    fun restoreAttendanceSession(identity: CourseIdentity, session: AttendanceSession) = update { applyRestoreSession(it, identity, session) }

    /** The Course Hub's sessions regenerated from the timetable. */
    fun replaceSessions(identity: CourseIdentity, sessions: List<AttendanceSession>) = update { applySessions(it, identity, sessions) }

    fun addAssessment(identity: CourseIdentity, item: AssessmentItem) = update { d -> applyAssessments(d, identity) { it + item } }

    fun updateAssessment(identity: CourseIdentity, itemId: String, transform: (AssessmentItem) -> AssessmentItem) =
        update { d -> applyAssessments(d, identity) { list -> list.map { if (it.id == itemId) transform(it) else it } } }

    fun removeAssessment(identity: CourseIdentity, itemId: String) = update { d -> applyAssessments(d, identity) { list -> list.filter { it.id != itemId } } }

    fun updateGradingScale(identity: CourseIdentity, scale: List<GradingScaleTier>) = update { applyGradingScale(it, identity, scale) }
    fun updateLimits(identity: CourseIdentity, limits: TrackerLimits) = update { applyLimits(it, identity, limits) }
    fun confirmTrackerSettings(identity: CourseIdentity) = update { applyConfirmSettings(it, identity) }

    /** Whether the class at [dateStr]/[startTime] was marked "No class" (dashboard hides it). */
    fun isSessionHoliday(courseName: String, courseCode: String?, semester: String?, dateStr: String?, startTime: String?): Boolean =
        isSessionHoliday(_data.value, courseName, courseCode, semester, dateStr, startTime)

    // ---- schedule sync ----------------------------------------------------------------

    /**
     * syncCourseTrackersFromScheduleEvents: after a Neptun/CSV sync, populate
     * every course's sessions. Starts from this account's cache (never the
     * anonymous one) merged with the cloud copy, plus whatever was edited on
     * screen while the cloud was read; persists and uploads only what changed.
     */
    suspend fun syncFromScheduleEvents(events: List<ScheduleEvent>): CourseTrackerData {
        val uid = userId
        val before = _data.value
        val existing = readForScheduleSync(uid)
        var base = existing
        var result = reconcileTrackersWithSchedule(existing, events)
        if (userId == uid) {
            // Reconciled again against the live data at assignment, so a mark made during the cloud read is kept.
            _data.update { live ->
                base = withEditsSince(before, live, existing)
                result = if (base === existing) result else reconcileTrackersWithSchedule(base, events)
                if (result.data === base) live else result.data
            }
        }
        // Signed out (or switched) while the cloud was read: this account's data must not be written back.
        if (result.data !== base && userId == uid) {
            writeCache(result.data, uid)
            if (uid != null && result.changed) {
                // The upload shares the write gate's outcome; a failure stays in the cache for the next one.
                runSuspendCatching { upload(uid, result.data, result.dropKeys) }
            }
        }
        return result.data
    }

    /** This account's cache merged with the cloud copy; a failed read leaves uploads gated. */
    private suspend fun readForScheduleSync(uid: String?): CourseTrackerData {
        val local = readCache(uid) ?: createEmptyTrackerData()
        if (uid == null) return local
        return try {
            val cloudData = readCloud(uid)
            gate.markReadable(uid)
            if (cloudData != null) mergeTrackerData(local, cloudData) else local
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            local
        }
    }

    companion object {
        const val StaleMs = 30_000L
    }
}
