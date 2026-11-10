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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
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

    // A count, not a flag: the first of two overlapping saves to finish must not re-enable Save.
    private val savesInFlight = MutableStateFlow(0)
    val isSavingItem: StateFlow<Boolean> = savesInFlight.map { it > 0 }
        .stateIn(scope, SharingStarted.Eagerly, false)
    /** Saves and deletes of one item run in the order they were made, not the order their reads return. */
    private val itemLocks = KeyedMutex()

    @Volatile private var itemsFetchedAt = 0L
    @Volatile private var syncRetryCount = 0
    private val syncJobLock = Mutex()
    private var syncJob: Deferred<Unit>? = null
    private var syncRequested = false
    @Volatile private var retryJob: Job? = null
    private val stateLock = Mutex()
    @Volatile private var validatedCloud: CloudSnapshot? = null
    @Volatile private var committedWrites = 0
    /**
     * The account whose items and queue [_itemsState] holds. The session switches before
     * [onUserChanged] has read the account's queue back from disk; until then the in-memory state
     * is empty, and a refresh or sync pass merging against it would save that over the kept queue.
     */
    @Volatile private var loadedFor: String? = null

    /** Forget the previous account; a new one loads its items and queue from the device. */
    suspend fun onUserChanged(uid: String?) {
        loadedFor = null
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
        loadedFor = uid
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
        // A pass outliving its session must not bring back what sign-out just wiped, nor save over a
        // queue it has not read back yet.
        if (userId != uid || loadedFor != uid) return
        try {
            kv.set(getScheduleItemsStorageKey(uid), encodeScheduleItemsState(next))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            error(SAVE_FAILED)
        }
        if (userId == uid) _itemsState.value = next
    }

    /** A user's own change, for [uid]: refused when another account signed in since it began. */
    private suspend fun persistLocalState(uid: String, next: ScheduleItemsState) {
        if (userId != uid) error(ACCOUNT_CHANGED)
        if (loadedFor != uid) error(SAVE_FAILED)
        saveLocal(uid, next)
        _itemSyncError.value = null
        syncRetryCount = 0
    }

    private suspend fun fetchCloudItems(uid: String): List<StoredScheduleItem> {
        val rows = pb.collection("schedule_items").getFullList(filter = "user = ${pbString(uid)}")
        val clear = crypto.decryptAll(rows.map { it.string("payload") })
        return rows.mapIndexedNotNull { i, row -> decodeScheduleItemRow(row) { clear[i] } }
    }

    /** Fresh enough, or [uid]'s queue not read back yet: its own load (onUserChanged) refreshes once it has. */
    private fun skipsRefresh(uid: String, force: Boolean): Boolean =
        loadedFor != uid || (!force && Clock.System.now().toEpochMilliseconds() - itemsFetchedAt < ITEMS_STALE_MS)

    /** The items query: local cache offline; merged cloud + queue online (cache on failure). */
    suspend fun refreshItems(force: Boolean = false) {
        val uid = userId ?: return
        if (skipsRefresh(uid, force)) return
        try {
            if (!online) return
            try {
                val merged = readAndMerge(uid, classes::publish) { local, read ->
                    val revalidated = reconcileQueuedItemConflicts(mergeScheduleItemsState(local, read.items), read.events)
                    try {
                        saveLocal(uid, revalidated)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Preserve the in-memory result even when the cache refresh fails.
                        if (userId == uid && loadedFor == uid) _itemsState.value = revalidated
                    }
                }
                if (merged != null) itemsFetchedAt = Clock.System.now().toEpochMilliseconds()
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

    /**
     * Reads classes and items, then merges them into the local state under [stateLock]. A sync
     * pass that committed a write after the read began makes the read stale: merging it would
     * bring back the row's previous version (and `updatedAt`) over the commit, so the read is
     * retried instead. Null when the account changed or every read raced a commit; the local
     * state, which already holds those commits, then stays as it is.
     */
    private suspend fun readAndMerge(
        uid: String,
        show: (List<ScheduleEvent>) -> Unit,
        merge: suspend (local: ScheduleItemsState, read: CloudSnapshot) -> Unit,
    ): CloudSnapshot? {
        var merged: CloudSnapshot? = null
        var attempts = 0
        while (merged == null && attempts < STALE_READ_ATTEMPTS && userId == uid) {
            attempts++
            val writesAtRead = committedWrites
            val (latestEvents, cloud) = coroutineScope {
                val e = async { classes.fetchShared(uid) }
                val c = async { fetchCloudItems(uid) }
                e.await() to c.await()
            }
            if (userId == uid) {
                show(latestEvents)
                val read = CloudSnapshot(Clock.System.now().toEpochMilliseconds(), writesAtRead, latestEvents, cloud)
                val fresh = stateLock.withLock {
                    (committedWrites == writesAtRead).also { if (it) merge(currentLocal(), read) }
                }
                if (fresh) merged = read
            }
        }
        return merged
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
        if (!online || loadedFor != uid) return
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
                val versionMatches = when {
                    queued.isCreate -> serverItem == null
                    // The delete of an unconfirmed create has no version to check: the delete branch settles it.
                    queued.baseUpdatedAt == UnconfirmedCreateBase -> true
                    else -> serverItem?.updatedAt == queued.baseUpdatedAt
                }
                if (!versionMatches) {
                    if (settleVersionMismatch(uid, queued, serverItem)) hasNewer = true
                    continue
                }

                if (queued.isUpsert) {
                    val queuedItem = queued.item!!
                    val itemConflicts = getConflictsForItem(queuedItem.toItem(), latest.items.map { it.toItem() }, latestEvents)
                    if (itemConflicts.any { it.needsAcknowledgment }) continue

                    val encrypted = crypto.encrypt(scheduleItemPayload(queuedItem.toItem()).toString())
                    if (!isCiphertext(encrypted)) error("Schedule item encryption did not produce an encrypted payload.")

                    var savedRow: JsonObject? = null
                    if (queued.isCreate) {
                        savedRow = createRow(uid, queued, encrypted)
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
                        if (settleVersionMismatch(uid, queued, refreshed.firstOrNull { it.id == queued.id })) hasNewer = true
                        continue
                    }

                    val savedUpdatedAt = savedRow.string("updated_at")
                    val savedItem = decodeScheduleItemRow(savedRow) { crypto.decrypt(it) }
                        ?: queuedItem.copy(updatedAt = savedUpdatedAt, pendingSync = false, syncConflict = null)
                    if (settleCommitted(uid, queued, savedItem)) hasNewer = true
                    val idx = cloudItems.indexOfFirst { it.id == queued.id }
                    if (idx >= 0) cloudItems[idx] = savedItem else cloudItems += savedItem
                } else {
                    // Conditional delete: only when the version still matches, so a concurrent edit surfaces.
                    // A create never confirmed has no version: whatever landed of it is ours to delete.
                    var deleted = false
                    val row = findItemRow(uid, queued.id)
                    val ours = queued.baseUpdatedAt == UnconfirmedCreateBase || row?.string("updated_at") == queued.baseUpdatedAt
                    if (row != null && ours) {
                        pb.collection("schedule_items").delete(row.string("id").orEmpty())
                        committedWrites++
                        deleted = true
                    }
                    if (!deleted) {
                        val refreshed = fetchCloudItems(uid)
                        if (settleVersionMismatch(uid, queued, refreshed.firstOrNull { it.id == queued.id })) hasNewer = true
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

    /** Sends a queued create; one that gets no answer is marked unconfirmed before the pass fails. */
    private suspend fun createRow(uid: String, queued: ScheduleItemMutation, encrypted: String?): JsonObject = try {
        // The client UUID travels as client_id: PocketBase ids are 15 chars and reject a UUID.
        pb.collection("schedule_items").create(buildJsonObject {
            put("user", uid)
            put("payload", encrypted)
            put("client_id", queued.id)
        })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // A 4xx means nothing was created; no answer (or a 5xx) may hide a row that did land.
        if (e !is PocketBaseException || e.isNetwork || e.status >= 500) runSuspendCatching { markCreateUnconfirmed(uid, queued.id) }
        throw e
    }

    /**
     * The create of [id] may have landed with its reply lost. Its queued upsert keeps that in mind,
     * and if the item was deleted meanwhile a delete is queued for whatever landed (audit 04 #19).
     */
    private suspend fun markCreateUnconfirmed(uid: String, id: String) = stateLock.withLock {
        val newest = currentLocal()
        val queued = newest.queue.firstOrNull { it.id == id }
        val next = when {
            queued == null && newest.items.none { it.id == id } ->
                newest.copy(queue = newest.queue + ScheduleItemMutation(ScheduleItemMutation.DELETE, id, null, UnconfirmedCreateBase))
            queued != null && queued.isUpsert && queued.baseUpdatedAt == null ->
                newest.copy(queue = newest.queue.map { if (it.id == id) it.copy(baseUpdatedAt = UnconfirmedCreateBase) else it })
            else -> null
        }
        if (next != null) saveLocal(uid, next)
    }

    /**
     * The server row is not at the version [queued] was based on. Holding exactly what [queued]
     * would write, it is a write of ours whose reply never arrived (a create or an update), or
     * the same change made elsewhere: adopt it. A delete of a row already gone is done. Anything
     * else is a conflict for the user. True when a newer local change was rebased for the next pass.
     */
    private suspend fun settleVersionMismatch(uid: String, queued: ScheduleItemMutation, serverItem: StoredScheduleItem?): Boolean {
        val alreadyThere = if (queued.isDelete) serverItem == null
        else serverItem != null && sameItemPayload(serverItem.toItem(), queued.item!!.toItem())
        if (alreadyThere) return settleCommitted(uid, queued, serverItem)
        stateLock.withLock {
            saveLocal(uid, markScheduleItemSyncConflict(currentLocal(), queued.id, serverItem, serverItem?.updatedAt))
        }
        return false
    }

    /**
     * [queued] is on the server as [saved] (null: deleted). Drops it from the queue, or rebases a
     * change made since onto the committed version; true when that left work for the next pass.
     */
    private suspend fun settleCommitted(uid: String, queued: ScheduleItemMutation, saved: StoredScheduleItem?): Boolean = stateLock.withLock {
        val newest = currentLocal()
        val newestMutation = newest.queue.firstOrNull { it.id == queued.id }
        val savedUpdatedAt = saved?.updatedAt
        when {
            newestMutation == queued || newestMutation == null && saved == null -> {
                saveLocal(uid, removeQueuedScheduleItemMutation(newest, queued.id, saved))
                false
            }
            newestMutation?.isUpsert == true -> {
                // An edit made while this write was in flight now rebases on the committed version.
                val rebasedItem = newestMutation.item!!.copy(updatedAt = savedUpdatedAt, pendingSync = true)
                val rebased = newestMutation.copy(item = rebasedItem, baseUpdatedAt = savedUpdatedAt)
                saveLocal(uid, newest.copy(
                    items = newest.items.map { if (it.id == queued.id) rebasedItem else it },
                    queue = newest.queue.map { if (it.id == queued.id) rebased else it },
                ))
                true
            }
            newestMutation?.isDelete == true -> {
                val rebased = newestMutation.copy(baseUpdatedAt = savedUpdatedAt)
                saveLocal(uid, newest.copy(queue = newest.queue.map { if (it.id == queued.id) rebased else it }))
                true
            }
            newest.items.none { it.id == queued.id } -> {
                // A queued create was cancelled locally mid-flight: delete the committed row next pass.
                saveLocal(uid, enqueueScheduleItemDelete(newest, queued.id, savedUpdatedAt))
                true
            }
            else -> {
                saveLocal(uid, removeQueuedScheduleItemMutation(newest, queued.id, saved))
                false
            }
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

    /**
     * Before an explicit sign-out: one more sync pass when online. True when the queue is
     * empty afterwards; conflicts and items waiting for acknowledgment stay queued, so they count.
     */
    suspend fun flushPending(): Boolean {
        if (userId != null && online && currentLocal().queue.isNotEmpty()) executeSync()
        return currentLocal().queue.isEmpty()
    }

    /** Counts a save or delete as running for [isSavingItem]. */
    private suspend fun <T> saving(block: suspend () -> T): T {
        savesInFlight.update { it + 1 }
        try {
            return block()
        } finally {
            savesInFlight.update { it - 1 }
        }
    }

    /**
     * Create or update an item: validate, re-check clashes on fresh data, queue, sync. Two saves of
     * one item queue in the order they were made: the second waits for the first to be queued.
     */
    suspend fun saveItem(candidate: ScheduleItem): StoredScheduleItem = saving {
        // Pinned: the fresh read can take a while, and another account may sign in meanwhile.
        val uid = userId ?: error("Sign in to save schedule items.")
        val stored = itemLocks.withLock(candidate.id) { queueSave(uid, candidate) }
        executeSync()
        stored
    }

    private suspend fun queueSave(uid: String, candidate: ScheduleItem): StoredScheduleItem {
        val valid = validateScheduleItem(candidate)
            ?: error("Enter a title, valid date, and a start/end time. Tasks cannot run overnight.")
        var validationEvents = classes.events.value
        var current = currentLocal()
        if (online) {
            try {
                var merged: ScheduleItemsState? = null
                val read = readAndMerge(uid, classes::show) { local, read ->
                    merged = mergeScheduleItemsState(local, read.items).also { saveLocal(uid, it) }
                }
                if (read != null) {
                    validationEvents = read.events
                    validatedCloud = read
                    current = merged ?: current
                } else {
                    // Every read raced a commit: check against the newest local state, which has them.
                    current = currentLocal()
                }
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
        stateLock.withLock { persistLocalState(uid, enqueueScheduleItemUpsert(currentLocal(), stored, currentItem?.updatedAt)) }
        return stored
    }

    suspend fun deleteItem(id: String) = saving {
        val uid = userId ?: error("Sign in to save schedule items.")
        itemLocks.withLock(id) {
            stateLock.withLock {
                val current = currentLocal()
                val item = current.items.firstOrNull { it.id == id }
                persistLocalState(uid, enqueueScheduleItemDelete(current, id, item?.updatedAt))
            }
        }
        executeSync()
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
        val uid = userId ?: return
        scope.launch {
            runSuspendCatching {
                stateLock.withLock { persistLocalState(uid, resolveScheduleItemSyncConflict(currentLocal(), id, resolution)) }
                if (online) executeSync()
            }
        }
    }

    private companion object {
        const val ITEMS_STALE_MS = 60_000L
        const val VALIDATED_CLOUD_REUSE_MS = 5_000L
        /** Reads that raced a sync commit are retried this many times in all. */
        const val STALE_READ_ATTEMPTS = 3
        const val SAVE_FAILED = "Unable to save this change on this device. Check available storage and try again."
        const val ACCOUNT_CHANGED = "You switched accounts while this was saving. Nothing was saved."
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

/** One mutex per key, dropped once nobody holds or waits for it. Waiters get it in arrival order. */
internal class KeyedMutex {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val guard = Mutex()
    private val entries = HashMap<String, Entry>()

    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        val entry = guard.withLock { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            withContext(NonCancellable) {
                guard.withLock { if (--entry.users == 0) entries.remove(key) }
            }
        }
    }
}
