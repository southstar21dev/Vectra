package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.isCiphertext
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.schedule.ScheduleEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** Offline cache base key: `<base>_<userId>`, the bare base when signed out. */
const val CourseTrackerCacheKey = "vectra_course_tracker_cache"

fun courseTrackerCacheKey(userId: String?): String = if (userId.isNullOrEmpty()) CourseTrackerCacheKey else "${CourseTrackerCacheKey}_$userId"

enum class TrackerUploadResult { Uploaded, HeldBack }

/** Attendance gets ticked off in runs; only the last state of a run goes to the cloud. */
const val TrackerUploadDebounceMs = 1500L

/**
 * The course tracker's data, cloud sync and schedule reconciliation. One encrypted
 * `data` blob per user in `course_tracker_data`, cached per user offline.
 * [data] is the single source of truth every view renders from; each write
 * lands there and in the offline cache synchronously, then uploads through
 * the [CloudWriteGate] (merged with the latest cloud copy at upload time)
 * once writes pause for [TrackerUploadDebounceMs].
 */
class CourseTrackerRepository(
    private val cloud: TrackerCloud,
    private val cipher: TrackerCipher,
    private val kv: KeyValueStore,
    private val scope: CoroutineScope,
    private val userIdFlow: StateFlow<String?>,
    private val isOnline: () -> Boolean,
    private val gate: CloudWriteGate = CloudWriteGate(),
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
    private var uploadedSnapshot: CourseTrackerData? = null

    // Signed-in edits made, and how many of them the cloud has: unequal means some exist only here.
    @Volatile private var editsMade = 0
    @Volatile private var editsUploaded = 0

    init {
        scope.launch {
            userIdFlow.collect { uid ->
                editsMade = 0
                editsUploaded = 0
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
        if (!gate.isReadable(uid)) {
            gate.noteHeldBack(uid)
            return TrackerUploadResult.HeldBack
        }
        gate.enqueue {
            val remote = readCloud(uid)
            var merged = if (remote != null) mergeTrackerData(local, remote) else local
            if (dropKeys.isNotEmpty()) merged = merged.copy(trackers = merged.trackers - dropKeys.toSet())
            cloud.upsert(uid, cipher.encrypt(serializeTrackerData(merged)))
            uploadedSnapshot = merged
        }
        return TrackerUploadResult.Uploaded
    }

    /** On resume, when the data is over 30 s old. */
    fun refreshIfStale() {
        if (Clock.System.now().toEpochMilliseconds() - lastFetchMs < StaleMs) return
        scope.launch { refresh() }
    }

    /** The query function: merge the cloud copy into the live data (newest tracker wins). */
    suspend fun refresh() = refreshLock.withLock {
        val uid = userId
        lastFetchMs = Clock.System.now().toEpochMilliseconds()
        if (uid == null || !isOnline()) return@withLock
        try {
            val encrypted = cloud.fetch(uid)
            if (encrypted == null) {
                // No cloud record yet: seed it from local data if there is any.
                gate.markReadable(uid)
                gate.takeHeldBack(uid)
                val cached = _data.value
                if (cached.trackers.isNotEmpty()) {
                    runSuspendCatching {
                        val enc = cipher.encrypt(serializeTrackerData(cached))
                        gate.enqueue { cloud.upsert(uid, enc) }
                    }
                }
                return@withLock
            }
            val decrypted = cipher.decrypt(encrypted)
            // Unreadable (e.g. a newer client): keep the gate closed so the local copy never replaces it.
            val parsed = (if (isCiphertext(decrypted)) null else parseTrackerData(decrypted)) ?: return@withLock
            gate.markReadable(uid)
            if (userId != uid) return@withLock
            // Merge against the live data at return time so an edit that landed
            // during the fetch is never clobbered by the cloud snapshot.
            val reconciled = mergeTrackerData(_data.value, parsed)
            _data.value = reconciled
            writeCache(reconciled, uid)
            // Local edits made while uploads were blocked, or kept from a session that expired
            // before they uploaded (the cache holds trackers newer than the cloud's): push the merged copy.
            if (gate.takeHeldBack(uid) || reconciled.trackers != parsed.trackers) {
                try {
                    val enc = cipher.encrypt(serializeTrackerData(reconciled))
                    gate.enqueue { cloud.upsert(uid, enc) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    gate.noteHeldBack(uid)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Keep the offline cache.
        }
    }

    // ---- writes ----------------------------------------------------------------------

    /** Restart the quiet period; the upload sends whatever [data] is by then. */
    private suspend fun scheduleUpload(uid: String) = uploadLock.withLock {
        pendingUploadUser = uid
        uploadTimer?.cancel()
        uploadTimer = scope.launch {
            delay(TrackerUploadDebounceMs)
            uploadPending()
        }
    }

    private suspend fun uploadPending() {
        val uid = uploadLock.withLock {
            uploadTimer = null
            pendingUploadUser.also { pendingUploadUser = null }
        } ?: return
        // Signed out meanwhile: that user's edits stay out of the next user's upload.
        if (userId != uid) return
        _isSaving.value = true
        val edits = editsMade
        try {
            if (upload(uid, _data.value) == TrackerUploadResult.HeldBack) {
                refresh()
            } else {
                if (userId == uid) editsUploaded = maxOf(editsUploaded, edits)
                // The upload just merged the cloud copy, so another device's trackers are in hand.
                uploadedSnapshot?.let { merged ->
                    uploadedSnapshot = null
                    if (userId == uid) {
                        var next: CourseTrackerData? = null
                        _data.update { live -> mergeTrackerData(live, merged).also { next = it } }
                        next?.let { writeCache(it, uid) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Stays in the offline cache; the next save or refresh carries it.
        } finally {
            _isSaving.value = false
        }
    }

    /** Sends a debounced upload now: the app is leaving the foreground and may not get another chance. */
    fun flushPendingUpload() {
        scope.launch {
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
        scope.launch {
            writeCache(snapshot, uid)
            if (uid != null) scheduleUpload(uid)
        }
    }

    fun getTracker(identity: CourseIdentity): CourseTracker? = findTracker(_data.value, identity)

    fun getOrCreateTracker(identity: CourseIdentity): CourseTracker = findOrCreateTracker(_data.value, identity)

    fun upsertTracker(tracker: CourseTracker) = update { applyUpsertTracker(it, tracker) }

    fun setAttendanceStatus(
        identity: CourseIdentity,
        identifier: String,
        status: AttendanceStatus,
        startTime: String? = null,
        endTime: String? = null,
        title: String? = null,
        sessionType: SessionType? = null,
    ) = update { applySetAttendanceStatus(it, identity, identifier, status, startTime, endTime, title, sessionType) }

    fun addAttendanceSession(courseKey: String, session: AttendanceSession) = update { applyAddSession(it, courseKey, session) }
    fun removeAttendanceSession(courseKey: String, session: AttendanceSession) = update { applyRemoveSession(it, courseKey, session) }
    fun restoreAttendanceSession(courseKey: String, session: AttendanceSession) = update { applyRestoreSession(it, courseKey, session) }

    fun addAssessment(identity: CourseIdentity, item: AssessmentItem) = update { d -> applyAssessments(d, identity) { it + item } }

    fun updateAssessment(identity: CourseIdentity, itemId: String, transform: (AssessmentItem) -> AssessmentItem) =
        update { d -> applyAssessments(d, identity) { list -> list.map { if (it.id == itemId) transform(it) else it } } }

    fun removeAssessment(identity: CourseIdentity, itemId: String) = update { d -> applyAssessments(d, identity) { list -> list.filter { it.id != itemId } } }

    fun updateGradingScale(courseKey: String, scale: List<GradingScaleTier>) = update { applyGradingScale(it, courseKey, scale) }
    fun updateLimits(courseKey: String, limits: TrackerLimits) = update { applyLimits(it, courseKey, limits) }
    fun confirmTrackerSettings(courseKey: String) = update { applyConfirmSettings(it, courseKey) }

    /** Whether the class at [dateStr]/[startTime] was marked "No class" (dashboard hides it). */
    fun isSessionHoliday(courseName: String, courseCode: String?, semester: String?, dateStr: String?, startTime: String?): Boolean =
        isSessionHoliday(_data.value, courseName, courseCode, semester, dateStr, startTime)

    // ---- schedule sync ----------------------------------------------------------------

    /**
     * syncCourseTrackersFromScheduleEvents: after a Neptun/CSV sync, populate
     * every course's sessions. Starts from this account's cache (never the
     * anonymous one) merged with the cloud copy; persists and uploads only
     * what changed.
     */
    suspend fun syncFromScheduleEvents(events: List<ScheduleEvent>): CourseTrackerData {
        val uid = userId
        val local = readCache(uid) ?: createEmptyTrackerData()
        var existing = local
        if (uid != null) {
            try {
                val cloudData = readCloud(uid)
                gate.markReadable(uid)
                if (cloudData != null) existing = mergeTrackerData(local, cloudData)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Upload stays gated; local data is still reconciled.
            }
        }
        val result = reconcileTrackersWithSchedule(existing, events)
        if (result.data === existing) return existing
        writeCache(result.data, uid)
        if (uid != null && result.changed) {
            try {
                upload(uid, result.data, result.dropKeys)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
        if (userId == uid) _data.value = result.data
        return result.data
    }

    companion object {
        const val StaleMs = 30_000L
    }
}
