package dev.southstar21.vectra.features.widgets

import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.auth.SessionListener
import dev.southstar21.vectra.core.platform.WidgetHost
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.coursetracker.CourseTrackerRepository
import dev.southstar21.vectra.features.reminders.budapestMs
import dev.southstar21.vectra.features.reminders.expandSessions
import dev.southstar21.vectra.features.schedule.ScheduleRepository
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
private const val AheadDays = 14

/**
 * Keeps the home-screen widgets' snapshot current: rewrites it whenever the timetable, personal
 * items, "No class" marks or the signed-in account change.
 */
class WidgetPublisher(
    private val schedule: ScheduleRepository,
    private val courseTracker: CourseTrackerRepository,
    private val auth: AuthRepository,
    private val host: WidgetHost,
    private val scope: CoroutineScope,
) : SessionListener {
    private val lock = Mutex()
    private var started = false

    /** Starts following the inputs. Safe to call more than once. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                schedule.events,
                schedule.items,
                courseTracker.data,
                schedule.localReady,
                auth.session.map { it?.userId },
            ) { _, _, _, _, _ -> }
                .debounce(1_000)
                .collect { runSuspendCatching { publish() } }
        }
    }

    /** For a cold start with no screen (background refresh): waits for the cached schedule, then publishes. */
    suspend fun publishWhenReady(timeoutMs: Long = 20_000) {
        start()
        withTimeoutOrNull(timeoutMs) { schedule.localReady.first { it } }
        publish()
    }

    suspend fun publish() = lock.withLock {
        // Without a screen nothing has read the stored session yet: "no session" isn't "signed out".
        auth.restore()
        val today = nowInBudapest().date
        // From Monday, so the week view has the days already gone.
        val monday = mondayOf(today)
        if (auth.session.value == null) {
            host.publish(encodeWidgetSnapshot(signedOutSnapshot(nowMs())))
            return@withLock
        }
        // Before the cached schedule is in memory an empty list means "not read yet", not "no classes".
        if (!schedule.localReady.value) return@withLock
        val events = schedule.events.value
        val items = schedule.items.value.map { it.toItem() }
        val days = (today.toEpochDays() - monday.toEpochDays()).toInt() + AheadDays + 1
        val snapshot = withContext(Dispatchers.Default) {
            val sessions = expandSessions(events, items, includeItems = true, from = monday, days = days) { e, ymd ->
                courseTracker.isSessionHoliday(e.courseName, e.courseCode, e.semester, ymd, e.startTime)
            }
            encodeWidgetSnapshot(buildWidgetSnapshot(sessions, nowMs()))
        }
        host.publish(snapshot)
    }

    override suspend fun onSessionCleared() {
        host.publish(encodeWidgetSnapshot(signedOutSnapshot(nowMs())))
    }
}

/** The next midnight in Budapest, for a redraw when the day changes. */
fun nextBudapestMidnightMs(): Long = budapestMs(nowInBudapest().date.plus(DatePeriod(days = 1)), "00:00")!!
