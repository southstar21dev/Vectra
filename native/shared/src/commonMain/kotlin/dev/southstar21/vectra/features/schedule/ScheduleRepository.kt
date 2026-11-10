package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.core.auth.AuthRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The schedule as screens see it. Classes ([ScheduleEventsRepository]), personal items
 * ([ScheduleItemsSync]) and Neptun ([NeptunSyncService]) each live in their own class; this
 * ties them to the session and the connection, and works out what needs all of them.
 */
class ScheduleRepository(
    pb: PocketBaseClient,
    crypto: CryptoClient,
    kv: KeyValueStore,
    private val connectivity: Connectivity,
    private val auth: AuthRepository,
    http: HttpClient,
    private val scope: CoroutineScope,
    neptunSync: NeptunSyncListener,
) {
    private val session = ScheduleSession(auth, connectivity)
    private val classes = ScheduleEventsRepository(pb, crypto, kv, session, scope)
    private val itemsSync = ScheduleItemsSync(pb, crypto, kv, session, classes, scope)
    private val neptun = NeptunSyncService(pb, crypto, kv, auth, http, session, classes, scope, neptunSync, ::onScheduleReplaced)

    val isOnline: StateFlow<Boolean> get() = connectivity.online

    /** Every decrypted class: manual schedule_events + neptun_schedule_events, tagged by source. */
    val events: StateFlow<List<ScheduleEvent>> get() = classes.events
    val eventsLoading: StateFlow<Boolean> get() = classes.eventsLoading
    val eventsError: StateFlow<String?> get() = classes.eventsError
    val isCreatingEvent: StateFlow<Boolean> get() = classes.isCreatingEvent

    private val _localReady = MutableStateFlow(false)
    /** The signed-in user's cached classes and items are in memory (whether or not any exist). */
    val localReady: StateFlow<Boolean> = _localReady.asStateFlow()

    val itemsState: StateFlow<ScheduleItemsState> get() = itemsSync.itemsState
    val items: StateFlow<List<StoredScheduleItem>> get() = itemsSync.items
    val itemsLoading: StateFlow<Boolean> get() = itemsSync.itemsLoading
    val itemSyncError: StateFlow<String?> get() = itemsSync.itemSyncError
    val isSavingItem: StateFlow<Boolean> get() = itemsSync.isSavingItem

    /** Active clashes between personal items and classes, worked out once here and off Main for every screen. */
    val conflicts: StateFlow<List<ScheduleConflict>> = combine(items, events) { items, events -> items to events }
        .conflate().map { (items, events) ->
            getScheduleConflicts(items.map { it.toItem() }, events)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val neptunSyncStatus: StateFlow<NeptunSyncStatus> get() = neptun.neptunSyncStatus
    val neptunSyncError: StateFlow<String?> get() = neptun.neptunSyncError

    init {
        scope.launch {
            auth.session.map { it?.userId?.ifEmpty { null } }.distinctUntilChanged().collect { uid -> onUserChanged(uid) }
        }
        scope.launch {
            connectivity.online.drop(1).collect { isOnline ->
                if (isOnline && session.userId != null) {
                    // Refetch on reconnect, and replay whatever was queued while offline.
                    launch { runSuspendCatching { classes.refreshEvents(force = true) } }
                    launch { itemsSync.refreshItems(force = true); itemsSync.executeSync() }
                }
            }
        }
        scope.launch {
            auth.profile.collect { neptun.maybeAutoSync() }
        }
    }

    private suspend fun onUserChanged(uid: String?) {
        neptun.onUserChanged()
        classes.onUserChanged(uid)
        itemsSync.onUserChanged(uid)
        if (uid == null) {
            _localReady.value = false
            return
        }
        _localReady.value = true
        neptun.restoreStatus()
        scope.launch { runSuspendCatching { classes.refreshEvents(force = true) } }
        scope.launch { itemsSync.refreshItems(force = true) }
        neptun.maybeAutoSync()
    }

    /** Refresh when stale (15 min for classes, 1 min for items). Screens call this on entry. */
    fun ensureFresh() {
        classes.ensureFresh()
        itemsSync.ensureFresh()
    }

    /** Classes were replaced wholesale, which also changes what the items clash with. */
    private fun onScheduleReplaced() {
        scope.launch { classes.invalidateEvents() }
        scope.launch {
            itemsSync.refreshItems(force = true)
            if (session.online) itemsSync.executeSync()
        }
    }

    // ---- classes ---------------------------------------------------------------

    suspend fun refreshEvents(force: Boolean = false) = classes.refreshEvents(force)
    suspend fun fetchEventsFromCloud(uid: String): List<ScheduleEvent> = classes.fetchEventsFromCloud(uid)
    suspend fun createEvent(data: ScheduleFormData): ScheduleEvent = classes.createEvent(data)
    suspend fun updateEvent(id: String, data: ScheduleFormData): ScheduleEvent = classes.updateEvent(id, data)
    suspend fun deleteEvent(id: String) = classes.deleteEvent(id)

    /** Delete every manual and Neptun class (items stay). */
    suspend fun clearAllEvents() {
        classes.deleteAll()
        onScheduleReplaced()
    }

    // ---- personal items ----------------------------------------------------------

    suspend fun refreshItems(force: Boolean = false) = itemsSync.refreshItems(force)
    suspend fun executeSync() = itemsSync.executeSync()

    /** Before an explicit sign-out: true when no personal-item change is left only on this device. */
    suspend fun flushPendingWrites(): Boolean = itemsSync.flushPending()
    suspend fun saveItem(candidate: ScheduleItem): StoredScheduleItem = itemsSync.saveItem(candidate)
    suspend fun deleteItem(id: String) = itemsSync.deleteItem(id)
    suspend fun toggleTaskCompleted(item: ScheduleItem): ScheduleItem = itemsSync.toggleTaskCompleted(item)
    fun resolveSyncConflict(id: String, resolution: String) = itemsSync.resolveSyncConflict(id, resolution)

    // ---- Neptun -----------------------------------------------------------------

    suspend fun fetchNeptunIcs(url: String, maxRetries: Int = 3): String = neptun.fetchNeptunIcs(url, maxRetries)
    suspend fun parseIcs(text: String): ParseIcsResult = neptun.parseIcs(text)
    suspend fun pickNeptunSyncSemester(events: List<ParsedIcsEvent>, at: Instant = Clock.System.now()): String? =
        neptun.pickNeptunSyncSemester(events, at)
    suspend fun persistNeptunEvents(events: List<NeptunSyncEvent>) = neptun.persistNeptunEvents(events)
    suspend fun clearNeptunEvents() = neptun.clearNeptunEvents()
    suspend fun importNeptunEvents(events: List<NeptunSyncEvent>) = neptun.importNeptunEvents(events)
    suspend fun runSync(url: String, isAutoSync: Boolean = false): String? = neptun.runSync(url, isAutoSync)
    suspend fun savedNeptunUrl(): String = neptun.savedNeptunUrl()
    suspend fun saveNeptunUrl(url: String) = neptun.saveNeptunUrl(url)
    suspend fun checkSyncRateLimit(nowMs: Long = Clock.System.now().toEpochMilliseconds()): RateLimit = neptun.checkSyncRateLimit(nowMs)

    data class RateLimit(val allowed: Boolean, val remainingMinutes: Int)

    companion object {
        const val MAX_SYNCS_PER_HOUR = NeptunSyncService.MAX_SYNCS_PER_HOUR
    }
}

/**
 * Today's classes that actually meet today
 * (odd/even weeks, date ranges, holidays). The dashboard's timeline input.
 */
fun scheduleTodayEvents(events: List<ScheduleEvent>, at: Instant = Clock.System.now()): List<ScheduleEvent> {
    val now = nowInBudapest(at)
    val index = ScheduleEventIndex(events)
    return events.filter { it.dayOfWeek == now.dayOfWeek }
        .filter { classOccursOnDate(it, now.ymd, index) }
}

/** Unique semesters, chronologically. */
fun scheduleSemesters(events: List<ScheduleEvent>): List<String> =
    events.map { it.semester }.filter { it.isNotEmpty() }.distinct().sorted()
