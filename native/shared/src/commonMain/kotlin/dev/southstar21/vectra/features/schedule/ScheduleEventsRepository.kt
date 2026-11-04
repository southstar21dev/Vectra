package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.data.OfflineCache
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.int
import dev.southstar21.vectra.core.network.isCiphertext
import dev.southstar21.vectra.core.network.pbString
import dev.southstar21.vectra.core.network.string
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.core.auth.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** Who the schedule belongs to right now and whether the network is reachable. */
internal class ScheduleSession(private val auth: AuthRepository, private val connectivity: Connectivity) {
    val userId: String? get() = auth.session.value?.userId?.ifEmpty { null }
    val online: Boolean get() = connectivity.online.value
    fun requireUserId(): String = userId ?: error("Not authenticated")
}

/** The class editor's output. */
data class ScheduleFormData(
    val courseName: String,
    val attendanceType: String?,
    val courseCode: String,
    val instructor: String,
    val location: String,
    val dayOfWeek: Int,
    val startTime: String,
    val endTime: String,
    val color: String,
    val semester: String,
    val semesterStart: String? = null,
    val semesterEnd: String? = null,
    val frequency: ScheduleFrequency = ScheduleFrequency.Weekly,
)

/**
 * Every class: manual `schedule_events` plus `neptun_schedule_events`, decrypted and cached
 * per user. Other parts of the schedule read and publish through here so there is one list.
 */
internal class ScheduleEventsRepository(
    private val pb: PocketBaseClient,
    private val crypto: CryptoClient,
    kv: KeyValueStore,
    private val session: ScheduleSession,
    private val scope: CoroutineScope,
) {
    private val eventsCache = OfflineCache(kv, "vectra_schedule_cache_events", ListSerializer(ScheduleEvent.serializer()))

    private val userId: String? get() = session.userId
    private val online: Boolean get() = session.online

    private val _events = MutableStateFlow<List<ScheduleEvent>>(emptyList())
    val events: StateFlow<List<ScheduleEvent>> = _events.asStateFlow()

    private val _eventsLoading = MutableStateFlow(true)
    val eventsLoading: StateFlow<Boolean> = _eventsLoading.asStateFlow()

    private val _eventsError = MutableStateFlow<String?>(null)
    val eventsError: StateFlow<String?> = _eventsError.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreatingEvent: StateFlow<Boolean> = _isCreating.asStateFlow()

    // Written from whichever Default thread a coroutine lands on.
    @Volatile private var eventsFetchedAt = 0L
    private val eventsJobLock = Mutex()
    private var eventsJob: Deferred<Unit>? = null
    private val eventsFetchLock = Mutex()
    private var eventsFetch: Pair<String, Deferred<List<ScheduleEvent>>>? = null

    /** Forget the previous account; a new one paints its cached classes first. */
    suspend fun onUserChanged(uid: String?) {
        eventsFetchedAt = 0L
        // The previous account's load must not run on, or be joined by, the next one.
        eventsJobLock.withLock { eventsJob?.cancel() }
        _eventsError.value = null
        if (uid == null) {
            _events.value = emptyList()
            _eventsLoading.value = true
            return
        }
        val cached = eventsCache.read(uid)
        _events.value = cached.orEmpty()
        _eventsLoading.value = cached == null && online
    }

    /** Refresh when stale (15 min). */
    fun ensureFresh() {
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - eventsFetchedAt > EVENTS_STALE_MS) scope.launch { runSuspendCatching { refreshEvents() } }
    }

    suspend fun cached(uid: String): List<ScheduleEvent>? = eventsCache.read(uid)

    /** Classes just read from the cloud by someone else. */
    fun show(latest: List<ScheduleEvent>) {
        _events.value = latest
    }

    /** As [show], and they count as a fresh load. */
    fun publish(latest: List<ScheduleEvent>) {
        show(latest)
        eventsFetchedAt = Clock.System.now().toEpochMilliseconds()
    }

    /** The fetch: cache offline; cloud otherwise, falling back to a non-empty cache. */
    suspend fun refreshEvents(force: Boolean = false) {
        val job = eventsJobLock.withLock {
            val running = eventsJob?.takeIf { it.isActive }
            if (running == null && !force && Clock.System.now().toEpochMilliseconds() - eventsFetchedAt < EVENTS_STALE_MS) return
            running ?: scope.async { loadEvents() }.also { eventsJob = it }
        }
        job.await()
    }

    private suspend fun loadEvents() {
        val uid = userId ?: return
        try {
            if (!online) {
                _events.value = eventsCache.read(uid).orEmpty()
                return
            }
            try {
                val fresh = fetchShared(uid)
                if (userId != uid) return
                _events.value = fresh
                _eventsError.value = null
                eventsFetchedAt = Clock.System.now().toEpochMilliseconds()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val cached = eventsCache.read(uid)
                if (!cached.isNullOrEmpty()) _events.value = cached
                else _eventsError.value = EVENTS_LOAD_FAILED
            }
        } finally {
            _eventsLoading.value = false
        }
    }

    /**
     * The cloud fetch, joined by anyone asking while one is in flight: cold start, pull to
     * refresh and a save all want the same data, and each would otherwise pay for it.
     */
    internal suspend fun fetchShared(uid: String): List<ScheduleEvent> {
        val job = eventsFetchLock.withLock {
            eventsFetch?.takeIf { (owner, job) -> owner == uid && job.isActive }?.second
                ?: scope.async { fetchEventsFromCloud(uid) }.also { eventsFetch = uid to it }
        }
        return job.await()
    }

    /** fetchScheduleEventsFromCloud: decrypt both collections, sort by day then start, cache. */
    suspend fun fetchEventsFromCloud(uid: String): List<ScheduleEvent> {
        val owner = "user = ${pbString(uid)}"
        val (manualRows, neptunRows) = coroutineScope {
            val manual = async { pb.collection("schedule_events").getFullList(filter = owner, sort = "day_of_week,start_time") }
            val neptun = async { pb.collection("neptun_schedule_events").getFullList(filter = owner) }
            manual.await() to neptun.await()
        }
        val clear = crypto.decryptAll(
            manualRows.flatMap { row -> ManualEncryptedFields.map { row.string(it) } } +
                neptunRows.flatMap { row -> NeptunEncryptedFields.map { row.string(it) } },
        )
        val manualFieldCount = manualRows.size * ManualEncryptedFields.size
        val manual = manualRows.mapIndexed { i, row ->
            val (name, instructor, location) = clear.subList(i * ManualEncryptedFields.size, (i + 1) * ManualEncryptedFields.size)
            if (listOf(name, instructor, location).any(::isCiphertext)) error("Could not decrypt the latest class schedule.")
            adaptManualRow(row, name, instructor, location)
        }
        val neptun = neptunRows.mapIndexed { i, row ->
            val start = manualFieldCount + i * NeptunEncryptedFields.size
            val v = clear.subList(start, start + NeptunEncryptedFields.size)
            if (v.any(::isCiphertext)) error("Could not decrypt the latest Neptun schedule.")
            val day = v[4]
            ScheduleEvent(
                id = row.string("id").orEmpty(),
                userId = row.string("user").orEmpty(),
                courseName = v[0].orEmpty(),
                courseCode = v[1].orEmpty(),
                instructor = v[2].orEmpty(),
                location = v[3].orEmpty(),
                // A missing weekday must not become 0 (Sunday); -1 lists it on no day.
                dayOfWeek = day?.trim()?.toIntOrNull() ?: -1,
                startTime = v[5].orEmpty(),
                endTime = v[6].orEmpty(),
                color = row.string("color").orEmpty(),
                semester = v[7].orEmpty(),
                semesterStart = v[8]?.ifEmpty { null },
                semesterEnd = v[9]?.ifEmpty { null },
                eventType = v[10]?.ifEmpty { null } ?: "class",
                attendanceType = null,
                csvMetadata = null,
                createdAt = row.string("created_at").orEmpty(),
                updatedAt = row.string("updated_at").orEmpty(),
                source = "neptun",
            )
        }
        val all = (manual + neptun).sortedWith(compareBy<ScheduleEvent> { it.dayOfWeek }.thenBy { it.startTime })
        eventsCache.write(uid, all)
        return all
    }

    private fun adaptManualRow(row: JsonObject, name: String?, instructor: String?, location: String?) = ScheduleEvent(
        id = row.string("id").orEmpty(),
        userId = row.string("user").orEmpty(),
        courseName = name.orEmpty(),
        courseCode = row.string("course_code").orEmpty(),
        instructor = instructor.orEmpty(),
        location = location.orEmpty(),
        dayOfWeek = row.int("day_of_week") ?: 0,
        startTime = row.string("start_time").orEmpty(),
        endTime = row.string("end_time").orEmpty(),
        color = row.string("color").orEmpty(),
        semester = row.string("semester").orEmpty(),
        semesterStart = row.string("semester_start"),
        semesterEnd = row.string("semester_end"),
        eventType = row.string("event_type").orEmpty(),
        attendanceType = row.string("attendance_type"),
        csvMetadata = row["csv_metadata"]?.takeIf { it !is JsonNull },
        createdAt = row.string("created_at").orEmpty(),
        updatedAt = row.string("updated_at").orEmpty(),
        source = "manual",
    )

    suspend fun createEvent(data: ScheduleFormData): ScheduleEvent {
        _isCreating.value = true
        try {
            val uid = session.requireUserId()
            val body = buildJsonObject {
                put("course_name", crypto.encrypt(data.courseName))
                put("course_code", data.courseCode)
                put("instructor", crypto.encrypt(data.instructor))
                put("location", crypto.encrypt(data.location))
                put("day_of_week", data.dayOfWeek)
                put("start_time", data.startTime)
                put("end_time", data.endTime)
                put("color", data.color)
                put("semester", data.semester)
                put("semester_start", data.semesterStart)
                put("semester_end", data.semesterEnd)
                put("attendance_type", data.attendanceType ?: "unknown")
                // Frequency rides along in csv_metadata — there is no column for it.
                put("csv_metadata", setFrequencyInMetadata(null, data.frequency))
                put("user", uid)
            }
            val record = pb.collection("schedule_events").create(body)
            return adaptManualRow(record, data.courseName, data.instructor, data.location)
        } finally {
            _isCreating.value = false
            invalidateEvents()
        }
    }

    suspend fun updateEvent(id: String, data: ScheduleFormData): ScheduleEvent {
        session.requireUserId()
        val body = buildJsonObject {
            put("course_name", crypto.encrypt(data.courseName))
            put("course_code", data.courseCode)
            put("instructor", crypto.encrypt(data.instructor))
            put("location", crypto.encrypt(data.location))
            put("day_of_week", data.dayOfWeek)
            put("start_time", data.startTime)
            put("end_time", data.endTime)
            put("color", data.color)
            put("semester", data.semester)
            put("semester_start", data.semesterStart)
            put("semester_end", data.semesterEnd)
            put("attendance_type", data.attendanceType)
            // An edit replaces csv_metadata outright, so only the frequency survives.
            put("csv_metadata", setFrequencyInMetadata(null, data.frequency))
        }
        try {
            val record = pb.collection("schedule_events").update(id, body)
            return adaptManualRow(record, data.courseName, data.instructor, data.location)
        } finally {
            invalidateEvents()
        }
    }

    suspend fun deleteEvent(id: String) {
        session.requireUserId()
        try {
            pb.collection("schedule_events").delete(id)
        } finally {
            invalidateEvents()
        }
    }

    /** Delete every manual and Neptun class. */
    suspend fun deleteAll() {
        val uid = session.requireUserId()
        val owner = "user = ${pbString(uid)}"
        coroutineScope {
            pb.collection("schedule_events").getFullList(filter = owner)
                .map { row -> async { pb.collection("schedule_events").delete(row.string("id").orEmpty()) } }.awaitAll()
            pb.collection("neptun_schedule_events").getFullList(filter = owner)
                .map { row -> async { pb.collection("neptun_schedule_events").delete(row.string("id").orEmpty()) } }.awaitAll()
        }
    }

    suspend fun invalidateEvents() {
        eventsFetchedAt = 0L
        runSuspendCatching { refreshEvents(force = true) }
    }

    private companion object {
        const val EVENTS_STALE_MS = 15L * 60 * 1000
        const val EVENTS_LOAD_FAILED = "Could not load your classes. Pull down to try again."
        val ManualEncryptedFields = listOf("course_name", "instructor", "location")
        val NeptunEncryptedFields = listOf(
            "course_name", "course_code", "instructor", "location", "day_of_week", "start_time", "end_time",
            "semester", "semester_start", "semester_end", "event_type",
        )
    }
}
