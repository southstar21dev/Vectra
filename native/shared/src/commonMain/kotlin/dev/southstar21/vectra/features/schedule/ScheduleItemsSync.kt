package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.PocketBaseException
import dev.southstar21.vectra.core.network.isCiphertext
import dev.southstar21.vectra.core.network.pbString
import dev.southstar21.vectra.core.network.string
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * Personal items and their offline queue. The device copy is the source of truth for
 * pending changes; a sync pass replays them against the server, checking each row's version.
 */
internal class ScheduleItemsSync(
    private val pb: PocketBaseClient,
    private val crypto: CryptoClient,
    private val kv: KeyValueStore,
    private val session: ScheduleSession,
    private val classes: ScheduleEventsRepository,
    private val scope: CoroutineScope,
) {
    private val userId: String? get() = session.userId
    private val online: Boolean get() = session.online

    private val _itemsState = MutableStateFlow(createEmptyScheduleItemsState())
    private val completionOverrides = MutableStateFlow<Map<String, CompletionOverride>>(emptyMap())

    /** The items as shown: the stored state plus any task toggle still being validated. */
    val itemsState: StateFlow<ScheduleItemsState> = combine(_itemsState, completionOverrides, ::applyCompletionOverrides)
        .stateIn(scope, SharingStarted.Eagerly, _itemsState.value)
    val items: StateFlow<List<StoredScheduleItem>> = itemsState.map { it.items }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _itemsLoading = MutableStateFlow(true)
    val itemsLoading: StateFlow<Boolean> = _itemsLoading.asStateFlow()

    private val _itemSyncError = MutableStateFlow<String?>(null)
    val itemSyncError: StateFlow<String?> = _itemSyncError.asStateFlow()

    private val _isSavingItem = MutableStateFlow(false)
    val isSavingItem: StateFlow<Boolean> = _isSavingItem.asStateFlow()

    @Volatile private var itemsFetchedAt = 0L
    @Volatile private var syncRetryCount = 0
    private val syncJobLock = Mutex()
    private var syncJob: Deferred<Unit>? = null
    private var syncRequested = false
    @Volatile private var retryJob: Job? = null
    private val stateLock = Mutex()
    @Volatile private var validatedCloud: CloudSnapshot? = null
    @Volatile private var committedWrites = 0

    /** Forget the previous account; a new one loads its items and queue from the device. */
    suspend fun onUserChanged(uid: String?) {
        itemsFetchedAt = 0L
        syncRetryCount = 0
        retryJob?.cancel()
        // The previous account's pass must not run on, or be joined by, the next one.
        syncJobLock.withLock { syncJob?.cancel() }
        _itemSyncError.value = null
        if (uid == null) {
            _itemsState.value = createEmptyScheduleItemsState()
            _itemsLoading.value = true
            validatedCloud = null
            return
        }
        _itemsState.value = readLocalItems(uid)
        _itemsLoading.value = false
    }

    /** Refresh when stale. */
    fun ensureFresh() {
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - itemsFetchedAt > ITEMS_STALE_MS) scope.launch { refreshItems() }
    }

    private suspend fun readLocalItems(uid: String): ScheduleItemsState =
        runSuspendCatching { parseScheduleItemsState(kv.get(getScheduleItemsStorageKey(uid))) }.getOrNull() ?: createEmptyScheduleItemsState()

    private fun currentLocal(): ScheduleItemsState = _itemsState.value

    /** Persist durably first; the in-memory state only moves once the write succeeded. */
    private suspend fun saveLocal(uid: String, next: ScheduleItemsState) {
        if (!isValidScheduleItemsState(next)) error(SAVE_FAILED)
        // A pass outliving its session must not bring back what sign-out just wiped.
        if (userId != uid) return
        try {
            kv.set(getScheduleItemsStorageKey(uid), encodeScheduleItemsState(next))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            error(SAVE_FAILED)
        }
        if (userId == uid) _itemsState.value = next
    }

    private suspend fun persistLocalState(next: ScheduleItemsState) {
        val uid = userId ?: error("Sign in to save schedule items.")
        saveLocal(uid, next)
        _itemSyncError.value = null
        syncRetryCount = 0
    }

    private suspend fun fetchCloudItems(uid: String): List<StoredScheduleItem> {
        val rows = pb.collection("schedule_items").getFullList(filter = "user = ${pbString(uid)}")
        val clear = crypto.decryptAll(rows.map { it.string("payload") })
        return rows.mapIndexedNotNull { i, row -> decodeScheduleItemRow(row) { clear[i] } }
    }

    /** The items query: local cache offline; merged cloud + queue online (cache on failure). */
    suspend fun refreshItems(force: Boolean = false) {
        val uid = userId ?: return
        if (!force && Clock.System.now().toEpochMilliseconds() - itemsFetchedAt < ITEMS_STALE_MS) return
        try {
            if (!online) return
            try {
                val (cloud, latestEvents) = coroutineScope {
                    val c = async { fetchCloudItems(uid) }
                    val e = async { classes.fetchShared(uid) }
                    c.await() to e.await()
                }
                if (userId != uid) return
                classes.publish(latestEvents)
                stateLock.withLock {
                    val merged = mergeScheduleItemsState(currentLocal(), cloud)
                    val revalidated = reconcileQueuedItemConflicts(merged, latestEvents)
                    try {
                        saveLocal(uid, revalidated)
                    } catch (_: Throwable) {
                        // Preserve the in-memory result even when the cache refresh fails.
                        _itemsState.value = revalidated
                    }
                }
                itemsFetchedAt = Clock.System.now().toEpochMilliseconds()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Failed to load schedule items; the local cache stays on screen.
            }
        } finally {
            _itemsLoading.value = false
            val q = _itemsState.value.queue
            if (online && q.isNotEmpty() && _itemSyncError.value == null) scope.launch { executeSync() }
        }
    }

    private fun isNotFound(e: Throwable) = e is PocketBaseException && e.status == 404

    private suspend fun findItemRow(uid: String, clientId: String): JsonObject? = try {
        pb.collection("schedule_items").getFirstOrNull("user = ${pbString(uid)} && client_id = ${pbString(clientId)}")
    } catch (e: PocketBaseException) {
        if (isNotFound(e)) null else throw e
    }

    /**
     * Replay the offline queue against the server with optimistic version checks. Only one
     * sync runs at a time. A call made meanwhile joins it and earns one more pass: the pass
     * in progress took its copy of the queue before whatever that caller just enqueued.
     */
    suspend fun executeSync() {
        val uid = userId ?: return
        if (!online) return
        val job = syncJobLock.withLock {
            val running = syncJob?.takeIf { it.isActive }
            syncRequested = running != null
            running ?: scope.async { runSyncPasses(uid) }.also { syncJob = it }
        }
        job.await()
    }

    private suspend fun runSyncPasses(uid: String) {
        while (true) {
            val hasNewer = runSyncPass(uid)
            val again = syncJobLock.withLock {
                val rerun = (hasNewer || syncRequested) && userId == uid && online
                syncRequested = false
                // Under the lock, so nobody can join a sync that has already decided to stop.
                if (!rerun) syncJob = null
                rerun
            }
            if (!again) return
        }
    }

    /** One pass over the queue; true when it left work the next pass must pick up. */
    private suspend fun runSyncPass(uid: String): Boolean {
        var hasNewer = false
        if (currentLocal().queue.isEmpty()) {
            _itemSyncError.value = null
            syncRetryCount = 0
            return false
        }
        try {
            // A save that just validated against the cloud has already done these reads; every
            // write below still re-checks its row's version.
            val validated = validatedCloud?.takeIf {
                it.writesAtRead == committedWrites && Clock.System.now().toEpochMilliseconds() - it.at < VALIDATED_CLOUD_REUSE_MS
            }
            validatedCloud = null
            // Refresh classes first so newly imported ones cannot silently invalidate an offline acknowledgment.
            val latestEvents = validated?.events ?: classes.fetchShared(uid)
            if (userId != uid) return false
            classes.publish(latestEvents)

            val cloudItems = (validated?.items ?: fetchCloudItems(uid)).toMutableList()
            if (userId != uid) return false
            var current = stateLock.withLock {
                mergeScheduleItemsState(currentLocal(), cloudItems).also { saveLocal(uid, it) }
            }

            for (queued in current.queue.toList()) {
                val latest = currentLocal()
                val latestQueued = latest.queue.firstOrNull { it.id == queued.id }
                if (latestQueued != queued) continue
                val serverItem = cloudItems.firstOrNull { it.id == queued.id }

                // A create can land just before the app closed: identical content is an
                // already-completed idempotent create; different content is a real conflict.
                if (queued.isUpsert && queued.baseUpdatedAt == null && serverItem != null) {
                    stateLock.withLock {
                        val next = if (sameItemPayload(serverItem.toItem(), queued.item!!.toItem())) {
                            removeQueuedScheduleItemMutation(currentLocal(), queued.id, serverItem)
                        } else markScheduleItemSyncConflict(currentLocal(), queued.id, serverItem, serverItem.updatedAt)
                        saveLocal(uid, next)
                    }
                    continue
                }

                val versionMatches = if (queued.baseUpdatedAt == null) serverItem == null else serverItem?.updatedAt == queued.baseUpdatedAt
                if (!versionMatches && queued.isDelete && serverItem == null) {
                    stateLock.withLock { saveLocal(uid, removeQueuedScheduleItemMutation(currentLocal(), queued.id)) }
                    continue
                }
                if (!versionMatches) {
                    stateLock.withLock {
                        saveLocal(uid, markScheduleItemSyncConflict(currentLocal(), queued.id, serverItem, serverItem?.updatedAt))
                    }
                    continue
                }

                if (queued.isUpsert) {
                    val queuedItem = queued.item!!
                    val itemConflicts = getConflictsForItem(queuedItem.toItem(), latest.items.map { it.toItem() }, latestEvents)
                    if (itemConflicts.any { it.needsAcknowledgment }) continue

                    val encrypted = crypto.encrypt(scheduleItemPayload(queuedItem.toItem()).toString())
                    if (!isCiphertext(encrypted)) error("Schedule item encryption did not produce an encrypted payload.")

                    var savedRow: JsonObject? = null
                    if (queued.baseUpdatedAt == null) {
                        // The client UUID travels as client_id: PocketBase ids are 15 chars and reject a UUID.
                        savedRow = pb.collection("schedule_items").create(buildJsonObject {
                            put("user", uid)
                            put("payload", encrypted)
                            put("client_id", queued.id)
                        })
                    } else {
                        // PocketBase has no conditional write: read and compare the version ourselves.
                        val row = findItemRow(uid, queued.id)
                        if (row != null && row.string("updated_at") == queued.baseUpdatedAt) {
                            savedRow = pb.collection("schedule_items").update(row.string("id").orEmpty(), buildJsonObject { put("payload", encrypted) })
                        }
                    }

                    if (savedRow != null) committedWrites++
                    if (savedRow == null) {
                        val refreshed = fetchCloudItems(uid)
                        val latestServer = refreshed.firstOrNull { it.id == queued.id }
                        stateLock.withLock {
                            saveLocal(uid, markScheduleItemSyncConflict(currentLocal(), queued.id, latestServer, latestServer?.updatedAt))
                        }
                        continue
                    }

                    val savedUpdatedAt = savedRow.string("updated_at")
                    val savedItem = decodeScheduleItemRow(savedRow) { crypto.decrypt(it) }
                        ?: queuedItem.copy(updatedAt = savedUpdatedAt, pendingSync = false, syncConflict = null)
                    stateLock.withLock {
                        val newest = currentLocal()
                        val newestMutation = newest.queue.firstOrNull { it.id == queued.id }
                        when {
                            newestMutation == queued -> saveLocal(uid, removeQueuedScheduleItemMutation(newest, queued.id, savedItem))
                            newestMutation?.isUpsert == true -> {
                                // An edit made while this write was in flight now rebases on the committed version.
                                val rebasedItem = newestMutation.item!!.copy(updatedAt = savedUpdatedAt, pendingSync = true)
                                val rebased = newestMutation.copy(item = rebasedItem, baseUpdatedAt = savedUpdatedAt)
                                saveLocal(uid, newest.copy(
                                    items = newest.items.map { if (it.id == queued.id) rebasedItem else it },
                                    queue = newest.queue.map { if (it.id == queued.id) rebased else it },
                                ))
                                hasNewer = true
                            }
                            newestMutation?.isDelete == true -> {
                                val rebased = newestMutation.copy(baseUpdatedAt = savedUpdatedAt)
                                saveLocal(uid, newest.copy(queue = newest.queue.map { if (it.id == queued.id) rebased else it }))
                                hasNewer = true
                            }
                            newest.items.none { it.id == queued.id } -> {
                                // A queued create was cancelled locally mid-flight: delete the committed row next pass.
                                saveLocal(uid, enqueueScheduleItemDelete(newest, queued.id, savedItem.updatedAt))
                                hasNewer = true
                            }
                            else -> saveLocal(uid, removeQueuedScheduleItemMutation(newest, queued.id, savedItem))
                        }
                    }
                    val idx = cloudItems.indexOfFirst { it.id == queued.id }
                    if (idx >= 0) cloudItems[idx] = savedItem else cloudItems += savedItem
                } else {
                    // Conditional delete: only when the version still matches, so a concurrent edit surfaces.
                    var deleted = false
                    val row = findItemRow(uid, queued.id)
                    if (row != null && row.string("updated_at") == queued.baseUpdatedAt) {
                        pb.collection("schedule_items").delete(row.string("id").orEmpty())
                        committedWrites++
                        deleted = true
                    }
                    if (!deleted) {
                        val refreshed = fetchCloudItems(uid)
                        val latestServer = refreshed.firstOrNull { it.id == queued.id }
                        stateLock.withLock {
                            saveLocal(uid, markScheduleItemSyncConflict(currentLocal(), queued.id, latestServer, latestServer?.updatedAt))
                        }
                        continue
                    }
                    stateLock.withLock {
                        val newest = currentLocal()
                        if (newest.queue.firstOrNull { it.id == queued.id } == queued) {
                            saveLocal(uid, removeQueuedScheduleItemMutation(newest, queued.id))
                        }
                    }
                }
            }
            // An item paused for acknowledgment is not a failure; only transient errors retry.
            _itemSyncError.value = null
            syncRetryCount = 0
            retryJob?.cancel()
            return hasNewer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _itemSyncError.value = e.message ?: "Schedule changes are waiting to sync."
            syncRetryCount++
            scheduleRetry()
            return false
        }
    }

    /** Exponential backoff while the queue is non-empty and the last pass failed. */
    private fun scheduleRetry() {
        retryJob?.cancel()
        val delayMs = minOf(60_000L, 2_000L * (1L shl minOf(syncRetryCount, 5)))
        retryJob = scope.launch {
            delay(delayMs)
            if (online && currentLocal().queue.isNotEmpty() && _itemSyncError.value != null) executeSync()
        }
    }

    /** Create or update an item: validate, re-check clashes on fresh data, queue, sync. */
    suspend fun saveItem(candidate: ScheduleItem): StoredScheduleItem {
        _isSavingItem.value = true
        try {
            val uid = userId ?: error("Sign in to save schedule items.")
            val valid = validateScheduleItem(candidate)
                ?: error("Enter a title, valid date, and a start/end time. Tasks cannot run overnight.")
            var validationEvents = classes.events.value
            var current = currentLocal()
            if (online) {
                val writesAtRead = committedWrites
                try {
                    val (latestEvents, cloud) = coroutineScope {
                        val e = async { classes.fetchShared(uid) }
                        val c = async { fetchCloudItems(uid) }
                        e.await() to c.await()
                    }
                    validationEvents = latestEvents
                    classes.show(latestEvents)
                    validatedCloud = CloudSnapshot(Clock.System.now().toEpochMilliseconds(), writesAtRead, latestEvents, cloud)
                    current = stateLock.withLock { mergeScheduleItemsState(currentLocal(), cloud).also { saveLocal(uid, it) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    error("Could not check your latest schedule. Please try again or save while offline.")
                }
            }
            val currentItem = current.items.firstOrNull { it.id == valid.id }
            val itemConflicts = getConflictsForItem(valid, current.items.map { it.toItem() }, validationEvents)
            throwForUnacknowledgedConflicts(itemConflicts, valid.acknowledgedConflictKeys)
            val stored = valid.stored(updatedAt = currentItem?.updatedAt, pendingSync = true)
            stateLock.withLock { persistLocalState(enqueueScheduleItemUpsert(currentLocal(), stored, currentItem?.updatedAt)) }
            executeSync()
            return stored
        } finally {
            _isSavingItem.value = false
        }
    }

    suspend fun deleteItem(id: String) {
        _isSavingItem.value = true
        try {
            userId ?: error("Sign in to save schedule items.")
            stateLock.withLock {
                val current = currentLocal()
                val item = current.items.firstOrNull { it.id == id }
                persistLocalState(enqueueScheduleItemDelete(current, id, item?.updatedAt))
            }
            executeSync()
        } finally {
            _isSavingItem.value = false
        }
    }

    /**
     * Toggling a task back to not-done drops its acknowledgments (a fresh check is required).
     * The checkbox moves at once through [completionOverrides]; nothing is stored until
     * [saveItem] has passed its usual checks, and a failed save simply lets it fall back.
     */
    suspend fun toggleTaskCompleted(item: ScheduleItem): ScheduleItem {
        if (!item.isTask) return item
        val completed = item.completed != true
        val override = CompletionOverride(completed)
        completionOverrides.update { it + (item.id to override) }
        try {
            return saveItem(item.copy(completed = completed, acknowledgedConflictKeys = if (completed) item.acknowledgedConflictKeys else emptyList())).toItem()
        } finally {
            // A second tap may have replaced this one while it was saving.
            completionOverrides.update { if (it[item.id] === override) it - item.id else it }
        }
    }

    /** 'server' | 'local' */
    fun resolveSyncConflict(id: String, resolution: String) {
        scope.launch {
            runSuspendCatching {
                stateLock.withLock { persistLocalState(resolveScheduleItemSyncConflict(currentLocal(), id, resolution)) }
                if (online) executeSync()
            }
        }
    }

    private companion object {
        const val ITEMS_STALE_MS = 60_000L
        const val VALIDATED_CLOUD_REUSE_MS = 5_000L
        const val SAVE_FAILED = "Unable to save this change on this device. Check available storage and try again."
    }
}

/** A shown-but-unsaved task completion. Compared by identity, so a later tap can supersede it. */
internal class CompletionOverride(val completed: Boolean)

/**
 * What a save just read from the cloud, reusable by the sync pass right after it, unless
 * a pass has written since: the snapshot would then miss the row it just committed.
 */
private class CloudSnapshot(val at: Long, val writesAtRead: Int, val events: List<ScheduleEvent>, val items: List<StoredScheduleItem>)

internal fun applyCompletionOverrides(state: ScheduleItemsState, overrides: Map<String, CompletionOverride>): ScheduleItemsState {
    if (overrides.isEmpty()) return state
    return state.copy(items = state.items.map { item -> overrides[item.id]?.let { item.copy(completed = it.completed) } ?: item })
}
