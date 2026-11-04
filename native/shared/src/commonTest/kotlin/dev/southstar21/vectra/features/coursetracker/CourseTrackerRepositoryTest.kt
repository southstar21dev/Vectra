package dev.southstar21.vectra.features.coursetracker

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.southstar21.vectra.core.data.KeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory DataStore so KeyValueStore runs without a file. */
internal class FakeDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val writer = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writer.withLock { transform(state.value).also { state.value = it } }
}

/** The `course_tracker_data` singleton row, with call counters like the vitest mocks. */
internal class FakeCloud : TrackerCloud {
    var row: String? = null
    var hasRow = false
    var fetchError: Throwable? = null
    var creates = 0
    var updates = 0
    val uploads = mutableListOf<String>()
    var fetches = 0

    override suspend fun fetch(userId: String): String? {
        fetches++
        fetchError?.let { throw it }
        return row
    }

    override suspend fun upsert(userId: String, encrypted: String) {
        fetchError?.let { throw it }
        if (hasRow) updates++ else creates++
        hasRow = true
        row = encrypted
        uploads += encrypted
    }
}

/** encryptField/decryptField mocked as pass-through. */
internal object PassThroughCipher : TrackerCipher {
    override suspend fun encrypt(plain: String) = plain
    override suspend fun decrypt(value: String) = value
}

private fun intensiveEvents() = listOf("08:00" to "09:30", "09:50" to "11:20", "11:40" to "13:10").map { (s, e) ->
    event("Business Communication Intensive", "X5VX18SUBM01M-G10", 1, s, e, "2026/27/1", "2026-09-07", "2026-09-18", "seminar")
}

private val IntensiveIdentity = CourseIdentity("X5VX18SUBM01M-G10", "Business Communication Intensive", "2026/27/1")

private class Harness(scope: CoroutineScope, userId: String?, val kv: KeyValueStore = KeyValueStore(FakeDataStore()), val cloud: FakeCloud = FakeCloud()) {
    val gate = CloudWriteGate()
    val repo = CourseTrackerRepository(cloud, PassThroughCipher, kv, scope, MutableStateFlow(userId), isOnline = { false }, gate = gate)

    suspend fun saveCache(data: CourseTrackerData, uid: String?) = kv.set(courseTrackerCacheKey(uid), serializeTrackerData(data))
    suspend fun readCache(uid: String?) = parseTrackerData(kv.get(courseTrackerCacheKey(uid)))
}

private fun TestScope.harness(userId: String?, kv: KeyValueStore = KeyValueStore(FakeDataStore()), cloud: FakeCloud = FakeCloud()) =
    Harness(backgroundScope, userId, kv, cloud)

class ScheduleReconcilerTest {
    @Test fun examOnlyScheduleCreatesNothing() = runTest {
        val h = harness("user-exams-only")
        val r = h.repo.syncFromScheduleEvents(listOf(event("Accounting", "ACC101", 4, "10:00", "12:00", "2026/27/1", "2026-10-01", "2026-10-01", "exam")))
        assertTrue(r.trackers.isEmpty())
    }

    @Test fun dropsTrackerSeededForAnExamStoredAsClass() = runTest {
        val h = harness("user-legacy-exam")
        val exam = event("Elements of Accounting Midterm Exam", "", 1, "09:50", "11:20", "2026/27/1", "2026-11-02", "2026-11-02", "class")
        val stale = createDefaultTracker("Elements of Accounting Midterm Exam", semester = "2026/27/1").let {
            it.copy(attendance = it.attendance.copy(sessions = listOf(
                session("s1", "2026-11-02", AttendanceStatus.Unmarked, "Lecture", startTime = "09:50"),
                session("s2", "2026-11-02", AttendanceStatus.Unmarked, "Lecture", startTime = "11:40"),
            )))
        }
        h.saveCache(CourseTrackerData(1, mapOf(stale.courseKey to stale)), "user-legacy-exam")
        assertEquals(1, h.readCache("user-legacy-exam")?.trackers?.size)
        val r = h.repo.syncFromScheduleEvents(listOf(exam))
        assertTrue(r.trackers.isEmpty())
        assertTrue(h.readCache("user-legacy-exam")!!.trackers.isEmpty())
    }

    @Test fun keepsExamNamedTrackerWithWork() = runTest {
        val h = harness("user-exam-skills")
        val t = createDefaultTracker("Exam Skills", semester = "2026/27/1").let {
            it.copy(attendance = it.attendance.copy(sessions = listOf(session("s1", "2026-10-05", AttendanceStatus.Present, "Week 1", SessionType.Seminar, "10:00"))))
        }
        h.saveCache(CourseTrackerData(1, mapOf(t.courseKey to t)), "user-exam-skills")
        val r = h.repo.syncFromScheduleEvents(emptyList())
        assertEquals(listOf(t.courseKey), r.trackers.keys.toList())
        assertNotNull(h.readCache("user-exam-skills")?.trackers?.get(t.courseKey))
    }

    @Test fun noSessionsFromExamWithManualFallback() {
        val exam = event("Accounting", "ACC101", 4, "10:00", "12:00", eventType = "exam")
        assertEquals(emptyList(), generateSessionsFromScheduleEvents("Accounting", "ACC101", dayOfWeek = 4, startTime = "10:00", endTime = "12:00", scheduleEvents = listOf(exam)))
    }

    @Test fun noSessionsFromExamStoredAsClass() {
        val exam = event("Elements of Accounting Midterm Exam (Félévközi részvizsga)", "", 1, "09:50", "11:20", semesterStart = "2026-11-02", semesterEnd = "2026-11-02", eventType = "class")
        assertEquals(emptyList(), generateSessionsFromScheduleEvents(exam.courseName, "", "2026/27/1", scheduleEvents = listOf(exam)))
    }

    @Test fun populatesAllCourses() = runTest {
        val h = harness("user-auto")
        val r = h.repo.syncFromScheduleEvents(listOf(
            event("Calculus I", "MATH101-E", 1, "08:15", "09:45", "2025/26/1", "2025-09-01", "2025-12-15", "lecture"),
            event("Calculus I", "MATH101-G", 3, "10:00", "11:30", "2025/26/1", "2025-09-01", "2025-12-15", "seminar"),
            event("Algorithms", "CS201", 2, "14:00", "15:30", "2025/26/1", "2025-09-01", "2025-12-15", "lecture"),
        ))
        assertEquals(28, r.trackers.getValue("math101_2025-26-1").attendance.sessions.size)
        assertEquals(14, r.trackers.getValue("cs201_2025-26-1").attendance.sessions.size)
    }

    @Test fun preservesMarksAcrossSyncs() = runTest {
        val h = harness("user-preserve")
        val events = listOf(event("Database Systems", "CS301", 1, "10:00", "11:30", "2025/26/1", "2025-09-01", "2025-12-15", "lecture"))
        val first = h.repo.syncFromScheduleEvents(events)
        val key = "cs301_2025-26-1"
        val t = first.trackers.getValue(key)
        val marked = t.copy(
            attendance = t.attendance.copy(sessions = t.attendance.sessions.mapIndexed { i, s -> if (i == 0) s.copy(status = AttendanceStatus.Present) else s }),
            points = t.points.copy(assessments = listOf(AssessmentItem("test-quiz", "Quiz 1", 18.0, 20.0))),
        )
        h.saveCache(first.copy(trackers = first.trackers + (key to marked)), "user-preserve")
        val second = h.repo.syncFromScheduleEvents(events).trackers.getValue(key)
        assertEquals(AttendanceStatus.Present, second.attendance.sessions[0].status)
        assertEquals(listOf("Quiz 1"), second.points.assessments.map { it.name })
    }

    @Test fun deletedSlotNotRegenerated() = runTest {
        val h = harness("user-intensive")
        val synced = h.repo.syncFromScheduleEvents(intensiveEvents())
        val key = resolveCourseKey(IntensiveIdentity)
        val t = synced.trackers.getValue(key)
        val first = t.attendance.sessions[0]
        val slot = sessionSlotKey(first)
        h.saveCache(applyUpsertTracker(synced, t.copy(attendance = t.attendance.copy(sessions = t.attendance.sessions.drop(1), removedSessionKeys = listOf(slot)))), "user-intensive")
        val sessions = h.repo.syncFromScheduleEvents(intensiveEvents()).trackers.getValue(key).attendance.sessions
        assertEquals(t.attendance.sessions.size - 1, sessions.size)
        assertFalse(sessions.any { it.id == first.id || sessionSlotKey(it) == slot })
    }

    @Test fun checkInUpdatesTheSyncedTracker() = runTest {
        val h = harness("user-intensive")
        val synced = h.repo.syncFromScheduleEvents(intensiveEvents())
        val key = resolveCourseKey(IntensiveIdentity)
        val updated = applySetAttendanceStatus(synced, IntensiveIdentity, "2026-09-07", AttendanceStatus.Present, startTime = "08:00", sessionType = SessionType.Seminar)
        assertEquals(AttendanceStatus.Present, updated.trackers.getValue(key).attendance.sessions.first { it.date == "2026-09-07" && it.startTime == "08:00" }.status)
        assertEquals(1, updated.trackers.size)
    }

    @Test fun unpaddedTimesMarkOnlyTheirSession() = runTest {
        val h = harness("user-unpadded")
        val events = listOf("8:00" to "9:30", "9:50" to "11:20", "11:40" to "13:10").map { (s, e) ->
            event("Business Communication Intensive", "X5VX18SUBM01M-G10", 1, s, e, "2026/27/1", "2026-09-07", "2026-09-18", "seminar")
        }
        val synced = h.repo.syncFromScheduleEvents(events)
        val key = resolveCourseKey(IntensiveIdentity)
        var data = applySetAttendanceStatus(synced, IntensiveIdentity, "2026-09-07", AttendanceStatus.Present, startTime = "8:00", sessionType = SessionType.Seminar)
        assertEquals("08:00", data.trackers.getValue(key).attendance.sessions.first { it.date == "2026-09-07" }.startTime)
        val t = data.trackers.getValue(key)
        val regenerated = generateSessionsFromScheduleEvents(IntensiveIdentity.courseName!!, IntensiveIdentity.courseCode, IntensiveIdentity.semester, scheduleEvents = events, existingSessions = t.attendance.sessions)
        data = applyUpsertTracker(data, t.copy(attendance = t.attendance.copy(sessions = regenerated)))
        val sep7 = data.trackers.getValue(key).attendance.sessions.filter { it.date == "2026-09-07" }
        val marked = sep7.filter { it.status == AttendanceStatus.Present }
        assertEquals(1, marked.size)
        assertEquals("08:00", marked[0].startTime)
        assertEquals(3, sep7.size)
    }

    @Test fun autoPopulateDoesNotCopyMarksToSiblings() = runTest {
        val h = harness("user-intensive-siblings")
        val synced = h.repo.syncFromScheduleEvents(intensiveEvents())
        val key = resolveCourseKey(IntensiveIdentity)
        var data = applySetAttendanceStatus(synced, IntensiveIdentity, "2026-09-07", AttendanceStatus.Present, startTime = "08:00", sessionType = SessionType.Seminar)
        val t = data.trackers.getValue(key)
        val regenerated = generateSessionsFromScheduleEvents(IntensiveIdentity.courseName!!, IntensiveIdentity.courseCode, IntensiveIdentity.semester, scheduleEvents = intensiveEvents(), existingSessions = t.attendance.sessions)
        data = applyUpsertTracker(data, t.copy(attendance = t.attendance.copy(sessions = regenerated)))
        val sep7 = data.trackers.getValue(key).attendance.sessions.filter { it.date == "2026-09-07" }
        assertEquals(3, sep7.size)
        assertEquals(listOf("08:00"), sep7.filter { it.status == AttendanceStatus.Present }.map { it.startTime })
        assertEquals(3, sep7.map { it.id }.toSet().size)
    }

    @Test fun sameDaySessionsIsolated() = runTest {
        val h = harness("user-same-day")
        val r = h.repo.syncFromScheduleEvents(listOf(
            event("Operating Systems", "CS401-E", 1, "08:15", "09:45", "2025/26/1", "2025-09-01", "2025-09-15", "lecture"),
            event("Operating Systems", "CS401-L", 1, "10:15", "11:45", "2025/26/1", "2025-09-01", "2025-09-15", "lab"),
        ))
        val sep1 = r.trackers.getValue("cs401_2025-26-1").attendance.sessions.filter { it.date == "2025-09-01" }
        assertEquals(setOf("08:15", "10:15"), sep1.map { it.startTime }.toSet())
    }

    @Test fun createsTheSingletonRow() = runTest {
        val h = harness("user-cloud-create")
        h.repo.syncFromScheduleEvents(intensiveEvents())
        assertEquals(1, h.cloud.creates)
        assertEquals(0, h.cloud.updates)
    }

    @Test fun updatesTheExistingRow() = runTest {
        val cloud = FakeCloud().apply { hasRow = true }
        val h = harness("user-cloud-update", cloud = cloud)
        h.repo.syncFromScheduleEvents(intensiveEvents())
        assertEquals(1, cloud.updates)
        assertEquals(0, cloud.creates)
    }

    @Test fun keepsOfflineCacheWhenCloudReadFails() = runTest {
        val cloud = FakeCloud().apply { fetchError = IllegalStateException("network down") }
        val h = harness("user-offline", cloud = cloud)
        h.saveCache(createEmptyTrackerData(), "user-offline")
        val r = h.repo.syncFromScheduleEvents(intensiveEvents())
        assertTrue(cloud.fetches > 0)
        val key = resolveCourseKey(IntensiveIdentity)
        assertNotNull(r.trackers[key])
        assertNotNull(h.readCache("user-offline")?.trackers?.get(key))
    }

    @Test fun neverSeedsFromAnonymousCache() = runTest {
        val h = harness("user-anon")
        h.saveCache(CourseTrackerData(1, mapOf("stranger" to createDefaultTracker("Someone Else", semester = "2026/27/1"))), null)
        assertNull(h.repo.syncFromScheduleEvents(intensiveEvents()).trackers["stranger"])
    }

    @Test fun mergesCloudCopyFirst() = runTest {
        val seeded = harness(null).repo.syncFromScheduleEvents(intensiveEvents())
        val key = resolveCourseKey(IntensiveIdentity)
        val remote = createDefaultTracker("Remote Course", semester = "2026/27/1").copy(updatedAt = "2020-01-01T00:00:00.000Z")
        val cloud = FakeCloud().apply { hasRow = true; row = serializeTrackerData(CourseTrackerData(1, seeded.trackers + ("remote" to remote))) }
        val r = harness("user-merge", cloud = cloud).repo.syncFromScheduleEvents(intensiveEvents())
        assertNotNull(r.trackers["remote"])
        assertEquals(seeded.trackers.getValue(key).updatedAt, r.trackers.getValue(key).updatedAt)
        assertEquals("2020-01-01T00:00:00.000Z", r.trackers.getValue("remote").updatedAt)
        assertEquals(0, cloud.updates)
        assertEquals(0, cloud.creates)
    }

    @Test fun holdsBackWhenCloudUnreachable() = runTest {
        val cloud = FakeCloud().apply { fetchError = IllegalStateException("network down") }
        harness("user-gated", cloud = cloud).repo.syncFromScheduleEvents(intensiveEvents())
        assertEquals(0, cloud.updates + cloud.creates)
    }

    @Test fun holdsBackWhenCloudUnreadable() = runTest {
        val cloud = FakeCloud().apply { hasRow = true; row = "vc2:garbage" }
        harness("user-unreadable", cloud = cloud).repo.syncFromScheduleEvents(intensiveEvents())
        assertEquals(0, cloud.updates)
    }
}

class CloudGateTest {
    @Test fun closedUntilRead() = runTest {
        val g = CloudWriteGate()
        assertFalse(g.isReadable("u1"))
        g.markReadable("u1")
        assertTrue(g.isReadable("u1"))
        assertFalse(g.isReadable("u2"))
    }

    @Test fun heldBackReportedOnce() = runTest {
        val g = CloudWriteGate()
        g.noteHeldBack("u1")
        assertTrue(g.takeHeldBack("u1"))
        assertFalse(g.takeHeldBack("u1"))
    }

    @Test fun serialisesAndCollapsesToNewest() = runTest {
        val g = CloudWriteGate()
        val ran = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val first = async { g.enqueue { ran += "first"; release.await() } }
        runCurrent()
        val second = async { g.enqueue { ran += "second" } }
        runCurrent()
        val third = async { g.enqueue { ran += "third" } }
        runCurrent()
        release.complete(Unit)
        first.await(); second.await(); third.await()
        assertEquals(listOf("first", "third"), ran)
    }

    @Test fun queueUsableAfterFailure() = runTest {
        val g = CloudWriteGate()
        assertFailsWith<IllegalStateException> { g.enqueue { error("boom") } }
        val ran = mutableListOf<String>()
        g.enqueue { ran += "ok" }
        assertEquals(listOf("ok"), ran)
    }
}

private fun tracker(name: String, updatedAt: String) = createDefaultTracker(name).copy(updatedAt = updatedAt)

class TrackerUploadTest {
    @Test fun heldBackUntilRead() = runTest {
        val h = harness("u1")
        assertEquals(TrackerUploadResult.HeldBack, h.repo.upload("u1", createEmptyTrackerData()))
        assertTrue(h.cloud.uploads.isEmpty())
        assertTrue(h.gate.takeHeldBack("u1"))
    }

    @Test fun mergesLatestCloudCopy() = runTest {
        val cloud = FakeCloud().apply {
            hasRow = true
            row = serializeTrackerData(CourseTrackerData(1, mapOf(
                "a" to tracker("A remote newer", "2026-10-02T00:00:00.000Z"),
                "b" to tracker("B remote only", "2026-10-01T00:00:00.000Z"),
                "gone" to tracker("Dropped", "2026-10-01T00:00:00.000Z"),
            )))
        }
        val h = harness("u1", cloud = cloud)
        h.gate.markReadable("u1")
        h.repo.upload("u1", CourseTrackerData(1, mapOf("a" to tracker("A local older", "2026-10-01T00:00:00.000Z"), "c" to tracker("C local only", "2026-10-01T00:00:00.000Z"))), listOf("gone"))
        val uploaded = parseTrackerData(cloud.uploads.single())!!
        assertEquals(listOf("a", "b", "c"), uploaded.trackers.keys.sorted())
        assertEquals("A remote newer", uploaded.trackers.getValue("a").courseName)
    }

    @Test fun refusesUnreadableCloud() = runTest {
        val cloud = FakeCloud().apply { hasRow = true; row = "vc2:undecryptable" }
        val h = harness("u1", cloud = cloud)
        h.gate.markReadable("u1")
        assertFailsWith<UnreadableCloudTrackerException> { h.repo.upload("u1", createEmptyTrackerData()) }
        assertTrue(cloud.uploads.isEmpty())
    }

    @Test fun writesLandLocallyAndInTheCache() = runTest {
        val h = harness("u9")
        runCurrent()
        h.repo.addAssessment(IntensiveIdentity, AssessmentItem("q", "Quiz", 5.0, 10.0))
        runCurrent()
        val key = resolveCourseKey(IntensiveIdentity)
        assertEquals(listOf("Quiz"), h.repo.data.value.trackers.getValue(key).points.assessments.map { it.name })
        assertEquals(listOf("Quiz"), h.readCache("u9")?.trackers?.get(key)?.points?.assessments?.map { it.name })
        launch { }.join()
    }

    private fun TestScope.signedIn(cloud: FakeCloud = FakeCloud()) = harness("u9", cloud = cloud).also { runCurrent() }

    @Test fun aRunOfEditsUploadsOnce() = runTest {
        val cloud = FakeCloud()
        val h = signedIn(cloud)
        h.gate.markReadable("u9")
        for (name in listOf("One", "Two", "Three")) {
            h.repo.addAssessment(IntensiveIdentity, AssessmentItem(name, name, 5.0, 10.0))
            runCurrent()
            advanceTimeBy(TrackerUploadDebounceMs / 2)
        }
        assertTrue(cloud.uploads.isEmpty())
        advanceTimeBy(TrackerUploadDebounceMs)
        runCurrent()
        val key = resolveCourseKey(IntensiveIdentity)
        assertEquals(listOf("One", "Two", "Three"), parseTrackerData(cloud.uploads.single())!!.trackers.getValue(key).points.assessments.map { it.name })
    }

    @Test fun leavingTheAppSendsAPendingUploadAtOnce() = runTest {
        val cloud = FakeCloud()
        val h = signedIn(cloud)
        h.gate.markReadable("u9")
        h.repo.addAssessment(IntensiveIdentity, AssessmentItem("q", "Quiz", 5.0, 10.0))
        runCurrent()
        h.repo.flushPendingUpload()
        runCurrent()
        assertEquals(1, cloud.uploads.size)
        advanceTimeBy(TrackerUploadDebounceMs * 2)
        runCurrent()
        assertEquals(1, cloud.uploads.size)
    }

    @Test fun anUploadBringsInWhatAnotherDeviceAdded() = runTest {
        val cloud = FakeCloud()
        val h = signedIn(cloud)
        h.gate.markReadable("u9")
        cloud.hasRow = true
        cloud.row = serializeTrackerData(CourseTrackerData(1, mapOf("other" to tracker("Other device", "2026-10-01T00:00:00.000Z"))))
        val fetchesBefore = cloud.fetches
        h.repo.addAssessment(IntensiveIdentity, AssessmentItem("q", "Quiz", 5.0, 10.0))
        runCurrent()
        advanceTimeBy(TrackerUploadDebounceMs + 1)
        runCurrent()
        assertTrue("other" in h.repo.data.value.trackers)
        assertEquals(fetchesBefore + 1, cloud.fetches)
        assertTrue(h.readCache("u9")?.trackers?.containsKey("other") == true)
    }

    @Test fun aHeldBackEditStillGoesThroughARefresh() = runTest {
        val cloud = FakeCloud()
        val h = signedIn(cloud)
        h.repo.addAssessment(IntensiveIdentity, AssessmentItem("q", "Quiz", 5.0, 10.0))
        runCurrent()
        advanceTimeBy(TrackerUploadDebounceMs + 1)
        runCurrent()
        // isOnline is false here, so the refresh stops at the gate: the edit stays local and held back.
        assertTrue(cloud.uploads.isEmpty())
        assertTrue(h.gate.takeHeldBack("u9"))
    }
}
