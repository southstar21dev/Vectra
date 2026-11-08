package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.auth.SessionListener
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.platform.DepartureQuery
import dev.southstar21.vectra.core.platform.Notifier
import dev.southstar21.vectra.core.platform.ScheduledNotification
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.coursetracker.CourseTrackerRepository
import dev.southstar21.vectra.features.schedule.ScheduleRepository
import dev.southstar21.vectra.features.transit.TransitRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the system's scheduled reminders in step with the schedule, the "No class" marks and
 * [prefs]: a week ahead, replanned whenever any of them changes. Everything is scheduled on the
 * device, so reminders fire offline and with the app closed.
 */
class ReminderScheduler(
    private val kv: KeyValueStore,
    private val schedule: ScheduleRepository,
    private val courseTracker: CourseTrackerRepository,
    private val transit: TransitRepository,
    private val notifier: Notifier,
    private val auth: AuthRepository,
    private val scope: CoroutineScope,
) : SessionListener {
    private val _prefs = MutableStateFlow(ReminderPrefs())
    val prefs: StateFlow<ReminderPrefs> = _prefs.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val replanRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val lock = Mutex()
    private val departures = DepartureLookup(transit)
    private var started = false

    /** What is scheduled right now (the test reminder rides along with it). */
    private var planned: List<ScheduledNotification> = emptyList()

    init {
        scope.launch {
            _prefs.value = kv.get(PrefsKey)?.let { runCatching { VectraJson.decodeFromString(ReminderPrefs.serializer(), it) }.getOrNull() } ?: ReminderPrefs()
            loaded.complete(Unit)
        }
    }

    /** Starts following the inputs. Safe to call more than once (app start, boot, background refresh). */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            loaded.await()
            val inputs = combine(
                prefs,
                schedule.events,
                schedule.items,
                courseTracker.data,
                combine(schedule.localReady, auth.session.map { it?.userId }) { ready, user -> ready to user },
            ) { _, _, _, _, _ -> }
            merge(inputs, replanRequests).debounce(1_500).collect { runSuspendCatching { reschedule() } }
        }
    }

    /** Replan soon, e.g. when the app comes to the foreground and departures can be refreshed. */
    fun requestReplan() {
        replanRequests.tryEmit(Unit)
    }

    fun update(transform: (ReminderPrefs) -> ReminderPrefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        scope.launch { kv.set(PrefsKey, VectraJson.encodeToString(ReminderPrefs.serializer(), next)) }
    }

    /**
     * For a cold start with no screen (boot, background refresh): waits for the cached schedule,
     * then replans once.
     */
    suspend fun rescheduleWhenReady(timeoutMs: Long = 20_000) {
        start()
        withTimeoutOrNull(timeoutMs) { schedule.localReady.first { it } }
        reschedule()
    }

    suspend fun reschedule() = lock.withLock {
        loaded.await()
        val prefs = _prefs.value
        if (auth.session.value == null || !prefs.anyOn) {
            apply(emptyList())
            return@withLock
        }
        // Before the cached schedule is in memory an empty list means "not read yet", not "no classes".
        if (!schedule.localReady.value) return@withLock
        val now = nowMs()
        val events = schedule.events.value
        val items = schedule.items.value.map { it.toItem() }
        val sessions = withContext(Dispatchers.Default) {
            expandSessions(events, items, prefs.personalItems, nowInBudapest().date, HorizonDays) { e, ymd ->
                courseTracker.isSessionHoliday(e.courseName, e.courseCode, e.semester, ymd, e.startTime)
            }
        }
        var plan = planReminders(sessions, prefs, now).take(MaxScheduled)
        if (!notifier.resolvesAtFireTime) plan = departures.bake(plan, now)
        apply(plan)
    }

    /** A sample reminder in a few seconds, so the user can see one without waiting for class. */
    suspend fun sendTest() {
        val prefs = _prefs.value
        val now = nowMs()
        val next = withContext(Dispatchers.Default) {
            expandSessions(schedule.events.value, emptyList(), false, nowInBudapest().date, HorizonDays).firstOrNull { it.startMs > now }
        }
        val test = ScheduledNotification(
            id = "rem:test",
            atMs = now + 5_000,
            title = next?.let { "${it.title} ${leadLabel(prefs.leadMinutes)}" } ?: "Class reminders are on",
            body = next?.let { listOfNotNull(it.location.ifEmpty { null }, it.typeLabel).joinToString(" · ") }?.ifEmpty { null }
                ?: "This is what a reminder looks like.",
            url = "/schedule",
            departures = prefs.toUni?.query?.takeIf { prefs.departures },
        )
        val ready = if (notifier.resolvesAtFireTime) test else departures.bake(listOf(test), now).first()
        lock.withLock { notifier.replaceScheduled(planned.filter { it.id != test.id } + ready) }
    }

    /** "Tram 6 from Blaha Lujza tér: 09:08 · 09:13 · …" with live times, for a reminder firing now. */
    suspend fun liveDepartures(query: DepartureQuery): LiveDepartures {
        withTimeoutOrNull(3_000) { auth.session.first { it != null } }
        val times = departures.times(query, nowMs())
        return if (times == null) LiveDepartures(departuresFallbackLine(query), null)
        else LiveDepartures(departuresLine(query, times), times.firstOrNull())
    }

    data class LiveDepartures(val line: String, val firstMs: Long?)

    private fun apply(plan: List<ScheduledNotification>) {
        if (plan == planned) return
        planned = plan
        notifier.replaceScheduled(plan)
    }

    override suspend fun beforeSignOut() = lock.withLock { apply(emptyList()) }

    override suspend fun onSessionCleared() = lock.withLock { apply(emptyList()) }

    companion object {
        const val PrefsKey = "vectra.reminders"
        const val HorizonDays = 7
        /** iOS keeps at most 64 pending notifications per app. */
        const val MaxScheduled = 60
    }
}
