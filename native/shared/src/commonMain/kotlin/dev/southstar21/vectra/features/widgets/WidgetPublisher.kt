package dev.southstar21.vectra.features.widgets

import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.auth.SessionListener
import dev.southstar21.vectra.core.platform.WidgetHost
import dev.southstar21.vectra.core.preferences.TimeFormat
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.coursetracker.CourseTrackerRepository
import dev.southstar21.vectra.features.reminders.budapestMs
import dev.southstar21.vectra.features.reminders.expandSessions
import dev.southstar21.vectra.features.schedule.ScheduleRepository
import dev.southstar21.vectra.features.settings.AppearanceRepository
import dev.southstar21.vectra.features.update.UpdateGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus

/** Days after today the snapshot covers: next week's view at the weekend and an exam heads-up a week out. */
internal const val SnapshotAheadDays = 14

/**
 * Keeps the home-screen widgets' snapshot current: rewrites it whenever the timetable, personal
 * items, "No class" marks or the signed-in account change. While the app is locked for an update
 * the widgets show only the update prompt.
 */
class WidgetPublisher internal constructor(
    private val schedule: ScheduleRepository,
    private val courseTracker: CourseTrackerRepository,
    private val auth: AuthRepository,
    private val host: WidgetHost,
    private val scope: CoroutineScope,
    private val updateGate: UpdateGate,
    private val appearance: AppearanceRepository,
) : SessionListener {
    private val lock = Mutex()
    private var started = false

    /** Starts following the inputs. Safe to call more than once. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val inputs = combine(
                schedule.events,
                schedule.items,
                courseTracker.data,
                schedule.localReady,
                auth.session.map { it?.userId },
            ) { _, _, _, _, _ -> }
            combine(inputs, updateGate.state, appearance.prefs.map { it.timeFormat }) { _, _, _ -> }
                .debounce(1_000)
                .collect { runSuspendCatching { publish() } }
        }
    }

    /** For a cold start with no screen (background refresh): waits for the cached schedule, then publishes. */
    suspend fun publishWhenReady(timeoutMs: Long = 20_000): Boolean {
        start()
        withTimeoutOrNull(timeoutMs) { schedule.localReady.first { it } }
        return publish()
    }

    /** Returns whether a snapshot was handed to the host (false when the cache wasn't ready). */
    suspend fun publish(): Boolean = lock.withLock {
        // Without a screen nothing has read the stored session yet: "no session" isn't "signed out".
        auth.restore()
        // Without a screen nothing has read a stored lock either.
        updateGate.loadStored()
        // Same for the stored time format: the default would put 24-hour times on a 12-hour phone.
        if (!appearance.loaded.value) appearance.loadStored()
        val hour12 = appearance.prefs.value.timeFormat == TimeFormat.H12
        if (updateGate.state.value != null) {
            host.publish(encodeWidgetSnapshot(updateRequiredSnapshot(nowMs(), hour12)))
            return@withLock true
        }
        val today = nowInBudapest().date
        // From Monday, so the week view has the days already gone.
        val monday = mondayOf(today)
        if (auth.session.value == null) {
            host.publish(encodeWidgetSnapshot(signedOutSnapshot(nowMs(), hour12)))
            return@withLock true
        }
        // Before the cached schedule is in memory an empty list means "not read yet", not "no classes".
        if (!schedule.localReady.value) return@withLock false
        val events = schedule.events.value
        val items = schedule.items.value.map { it.toItem() }
        val days = (today.toEpochDays() - monday.toEpochDays()).toInt() + SnapshotAheadDays + 1
        val snapshot = withContext(Dispatchers.Default) {
            val sessions = expandSessions(events, items, includeItems = true, from = monday, days = days) { e, ymd ->
                courseTracker.isSessionHoliday(e.courseName, e.courseCode, e.semester, ymd, e.startTime)
            }
            encodeWidgetSnapshot(buildWidgetSnapshot(sessions, nowMs(), hour12))
        }
        host.publish(snapshot)
        true
    }

    override suspend fun onSessionCleared() {
        host.publish(encodeWidgetSnapshot(signedOutSnapshot(nowMs(), appearance.prefs.value.timeFormat == TimeFormat.H12)))
    }
}

/** The next midnight in Budapest, for a redraw when the day changes. */
fun nextBudapestMidnightMs(): Long = budapestMs(nowInBudapest().date.plus(DatePeriod(days = 1)), "00:00")!!
