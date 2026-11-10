package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.network.AuthState
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.network.json
import dev.southstar21.vectra.core.network.string
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.platform.PlainSecretCipher
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val UserId = "user-1"
private const val ItemA = "11111111-1111-4111-8111-111111111111"
private const val ItemB = "22222222-2222-4222-8222-222222222222"
// A Monday, far enough ahead that the reference date never makes it historic.
private const val Monday = "2099-03-02"

private fun item(id: String = ItemA, title: String = "Work", start: String = "08:00", end: String = "09:00", acks: List<String> = emptyList()) =
    ScheduleItem(id, ScheduleItem.TYPE_SHIFT, title, Monday, start, end, acknowledgedConflictKeys = acks)

/** PocketBase, its crypto route and the schedule collections, in memory. */
private class FakeBackend {
    private val lock = Mutex()
    private var version = 0
    val itemRows = mutableListOf<JsonObject>()
    val classRows = mutableListOf<JsonObject>()
    /** Every item write, e.g. "create $ItemA", "update row-1", "delete row-1". */
    val writes = mutableListOf<String>()
    var itemsDown = false
    var classesDown = false
    /** Runs inside a create, before it lands: hold it here to act mid-flight. */
    var duringCreate: (suspend (clientId: String) -> Unit)? = null
    /** Runs after the pass's single-row re-read target was chosen, before it is answered. */
    var beforeRowLookup: (suspend () -> Unit)? = null
    /** Runs after a full item list was read, before it is answered: hold it to make it stale. */
    var afterListRead: (suspend () -> Unit)? = null
    @Volatile var listReads = 0
    /** Bodies of class edits (PATCH schedule_events). */
    val eventPatches = mutableListOf<JsonObject>()

    fun row(item: ScheduleItem, updatedAt: String, rowId: String = "row-${item.id.take(1)}"): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(rowId),
            "user" to JsonPrimitive(UserId),
            "client_id" to JsonPrimitive(item.id),
            "payload" to JsonPrimitive("vc3:" + scheduleItemPayload(item)),
            "updated_at" to JsonPrimitive(updatedAt),
        ),
    )

    fun seed(item: ScheduleItem, updatedAt: String) { itemRows += row(item, updatedAt) }

    fun bump(clientId: String, updatedAt: String) {
        val i = itemRows.indexOfFirst { it.string("client_id") == clientId }
        itemRows[i] = JsonObject(itemRows[i] + ("updated_at" to JsonPrimitive(updatedAt)))
    }

    fun title(clientId: String): String? = itemRows.firstOrNull { it.string("client_id") == clientId }
        ?.string("payload")?.removePrefix("vc3:")?.let { VectraJson.parseToJsonElement(it).jsonObject["title"]?.jsonPrimitive?.content }

    private fun body(req: HttpRequestData): JsonObject =
        VectraJson.parseToJsonElement((req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    private fun list(rows: List<JsonObject>) = JsonObject(mapOf("items" to JsonArray(rows))).toString()

    suspend fun handle(scope: MockRequestHandleScope, req: HttpRequestData): HttpResponseData = with(scope) {
        val path = req.url.encodedPath
        when {
            path == "/api/vectra/crypto" -> {
                val b = body(req)
                val values = b.getValue("values").jsonArray.map { it.jsonPrimitive.content }
                val out = if (b.getValue("op").jsonPrimitive.content == "encrypt") values.map { "vc3:$it" } else values.map { it.removePrefix("vc3:") }
                json(JsonObject(mapOf("values" to JsonArray(out.map(::JsonPrimitive)))).toString())
            }
            path.startsWith("/api/collections/schedule_events/") && req.method == HttpMethod.Patch -> {
                val b = body(req)
                eventPatches += b
                json(JsonObject(b + mapOf("id" to JsonPrimitive(path.substringAfterLast('/')), "updated_at" to JsonPrimitive("e2"))).toString())
            }
            path.startsWith("/api/collections/schedule_events/") ->
                if (classesDown) json("{}", HttpStatusCode.InternalServerError) else json(lock.withLock { list(classRows.toList()) })
            path.startsWith("/api/collections/neptun_schedule_events/") ->
                if (classesDown) json("{}", HttpStatusCode.InternalServerError) else json(list(emptyList()))
            path.startsWith("/api/collections/schedule_items/") -> items(req, path.substringAfter("/records").trim('/'))
            else -> error("unexpected $path")
        }
    }

    private suspend fun MockRequestHandleScope.items(req: HttpRequestData, rowId: String): HttpResponseData {
        if (itemsDown) return json("{}", HttpStatusCode.InternalServerError)
        return when (req.method) {
            HttpMethod.Get -> {
                val clientId = Regex("client_id = '([^']+)'").find(req.url.parameters["filter"].orEmpty())?.groupValues?.get(1)
                if (clientId != null) beforeRowLookup?.invoke()
                val snapshot = lock.withLock { list(itemRows.filter { clientId == null || it.string("client_id") == clientId }) }
                if (clientId == null) {
                    listReads++
                    afterListRead?.invoke()
                }
                json(snapshot)
            }
            HttpMethod.Post -> {
                val b = body(req)
                val clientId = b.string("client_id")!!
                duringCreate?.invoke(clientId)
                json(lock.withLock {
                    writes += "create $clientId"
                    JsonObject(b + mapOf("id" to JsonPrimitive("row-${++version}"), "updated_at" to JsonPrimitive("saved-$version"))).also { itemRows += it }
                }.toString())
            }
            HttpMethod.Patch -> json(lock.withLock {
                writes += "update $rowId"
                val i = itemRows.indexOfFirst { it.string("id") == rowId }
                val next = JsonObject(itemRows[i] + body(req) + ("updated_at" to JsonPrimitive("saved-${++version}")))
                itemRows[i] = next
                next
            }.toString())
            HttpMethod.Delete -> {
                lock.withLock {
                    writes += "delete $rowId"
                    itemRows.removeAll { it.string("id") == rowId }
                }
                json("")
            }
            else -> error("unexpected ${req.method}")
        }
    }
}

private class Online(value: Boolean) : Connectivity {
    override val online = MutableStateFlow(value)
}

private class Harness(val backend: FakeBackend, online: Boolean = true) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val kv = KeyValueStore(FakeDataStore())
    val connectivity = Online(online)
    val http = HttpClient(MockEngine { req -> backend.handle(this, req) })
    val store = AuthStore(kv, PlainSecretCipher)
    val pb = PocketBaseClient(createHttpClient(http), "https://pb.test", store)
    val auth = AuthRepository(pb, store, kv, scope, lazyOf(emptyList()))
    lateinit var repo: ScheduleRepository

    /** Signs in with [local] already on the device, as after a restart with a queue pending. */
    suspend fun start(local: ScheduleItemsState = ScheduleItemsState()) {
        kv.set(getScheduleItemsStorageKey(UserId), encodeScheduleItemsState(local))
        store.save(AuthState("tok", JsonObject(mapOf("id" to JsonPrimitive(UserId)))))
        repo = ScheduleRepository(pb, CryptoClient(pb, scope), kv, connectivity, auth, http, scope, NeptunSyncListener { })
        until { repo.localReady.value }
    }

    val queue get() = repo.itemsState.value.queue
    fun stored(id: String = ItemA) = repo.itemsState.value.items.firstOrNull { it.id == id }
    suspend fun persisted() = parseScheduleItemsState(kv.get(getScheduleItemsStorageKey(UserId)))

    /** The stored queue is empty and the visible state has caught up, which lags the store by a flow hop. */
    suspend fun drained() = until {
        val saved = persisted()
        saved != null && saved.queue.isEmpty() && repo.itemsState.value == saved
    }
}

private suspend fun until(condition: suspend () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(2) }

/** Queued writes, as [saveItem]/[deleteItem] would have left them. */
private fun queuedCreate(item: ScheduleItem) = enqueueScheduleItemUpsert(ScheduleItemsState(), item.stored())

private fun queuedUpdate(item: ScheduleItem, base: String) = enqueueScheduleItemUpsert(
    ScheduleItemsState(items = listOf(item.stored(updatedAt = base))),
    item.stored(updatedAt = base),
    base,
)

private fun queuedDelete(item: ScheduleItem, base: String) =
    enqueueScheduleItemDelete(ScheduleItemsState(items = listOf(item.stored(updatedAt = base))), item.id, base)

class ScheduleSyncTest {
    private fun scenario(online: Boolean = true, test: suspend Harness.(FakeBackend) -> Unit) = runTest {
        val backend = FakeBackend()
        val h = Harness(backend, online)
        try {
            withContext(Dispatchers.Default) { withTimeout(20_000) { h.test(backend) } }
        } finally {
            h.scope.cancel()
        }
    }

    /** Lets every pass started so far finish, so "nothing was written" means something. */
    private suspend fun Harness.settle() {
        repo.executeSync()
        delay(20)
        repo.executeSync()
    }

    // ---- creates ---------------------------------------------------------------

    @Test fun aQueuedCreateReachesTheServer() = scenario { b ->
        start(queuedCreate(item()))
        drained()
        assertEquals(listOf("create $ItemA"), b.writes)
        assertEquals("Work", b.title(ItemA))
        val saved = assertNotNull(stored())
        assertEquals(false, saved.pendingSync)
        assertTrue(saved.updatedAt.orEmpty().startsWith("saved-"))
        assertEquals(emptyList(), persisted()?.queue)
    }

    @Test fun aCreateThatAlreadyLandedIsNotRepeated() = scenario { b ->
        b.seed(item(), "v1")
        start(queuedCreate(item()))
        drained()
        assertEquals(emptyList(), b.writes)
        assertEquals("v1", stored()?.updatedAt)
    }

    @Test fun aCreateCollidingWithDifferentContentIsAConflict() = scenario { b ->
        b.seed(item(title = "Theirs"), "v1")
        start(queuedCreate(item(title = "Mine")))
        until { stored()?.syncConflict != null }
        settle()
        assertEquals(emptyList(), b.writes)
        assertEquals("Theirs", stored()?.syncConflict?.serverItem?.title)
        assertEquals("v1", stored()?.syncConflict?.serverUpdatedAt)
        assertEquals("Mine", stored()?.title)
        assertEquals(1, queue.size)
    }

    // ---- stale reads -------------------------------------------------------------

    /** 04 #4: a refresh whose read predates a sync commit must not merge the old row back over it. */
    @Test fun aRefreshReadBeforeACommitDoesNotRevertIt() = scenario { b ->
        b.seed(item(title = "Old"), "v1")
        start(ScheduleItemsState(items = listOf(item(title = "Old").stored(updatedAt = "v1"))))
        // Let the sign-in refresh finish first, so the held read below is ours.
        until { b.listReads >= 1 }
        settle()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        b.afterListRead = { if (held.complete(Unit)) release.await() }
        // The refresh reads "Old"/v1 and is held before it can merge.
        val refresh = scope.async { repo.refreshItems(force = true) }
        held.await()
        // Meanwhile a save commits "New" and the queue drains.
        repo.saveItem(item(title = "New"))
        drained()
        val committed = assertNotNull(stored()).updatedAt
        assertEquals("New", b.title(ItemA))
        release.complete(Unit)
        refresh.await()
        assertEquals("New", stored()?.title)
        assertEquals(committed, stored()?.updatedAt)
        assertEquals(false, stored()?.pendingSync)
        assertEquals("New", persisted()?.items?.single()?.title)
    }

    // ---- updates ---------------------------------------------------------------

    @Test fun anUpdateOnTheSameVersionIsWritten() = scenario { b ->
        b.seed(item(title = "Old"), "v1")
        start(queuedUpdate(item(title = "New"), "v1"))
        drained()
        assertEquals(listOf("update row-1"), b.writes)
        assertEquals("New", b.title(ItemA))
    }

    @Test fun anUpdateOnAnOlderVersionIsAConflict() = scenario { b ->
        b.seed(item(title = "Theirs"), "v2")
        start(queuedUpdate(item(title = "Mine"), "v1"))
        until { stored()?.syncConflict != null }
        settle()
        assertEquals(emptyList(), b.writes)
        assertEquals("Theirs", stored()?.syncConflict?.serverItem?.title)
        assertEquals("v2", stored()?.syncConflict?.serverUpdatedAt)
    }

    @Test fun anUpdateOfARowDeletedElsewhereIsAConflict() = scenario { b ->
        start(queuedUpdate(item(title = "Mine"), "v1"))
        until { stored()?.syncConflict != null }
        assertNull(stored()?.syncConflict?.serverItem)
        assertEquals(emptyList(), b.writes)
    }

    @Test fun aRowThatMovesAfterTheListReadIsNotOverwritten() = scenario { b ->
        b.seed(item(title = "Old"), "v1")
        b.beforeRowLookup = { b.bump(ItemA, "v2") }
        start(queuedUpdate(item(title = "Mine"), "v1"))
        until { stored()?.syncConflict != null }
        assertEquals(emptyList(), b.writes)
        assertEquals("v2", stored()?.syncConflict?.serverUpdatedAt)
    }

    // ---- deletes ---------------------------------------------------------------

    @Test fun aDeleteOnTheSameVersionIsApplied() = scenario { b ->
        b.seed(item(), "v1")
        start(queuedDelete(item(), "v1"))
        drained()
        assertEquals(listOf("delete row-1"), b.writes)
        assertTrue(b.itemRows.isEmpty())
        assertNull(stored())
    }

    @Test fun aDeleteOfAnAlreadyGoneRowJustLeavesTheQueue() = scenario { b ->
        start(queuedDelete(item(), "v1"))
        drained()
        assertEquals(emptyList(), b.writes)
    }

    @Test fun aDeleteOnAnOlderVersionIsAConflictAndBringsTheItemBack() = scenario { b ->
        b.seed(item(title = "Edited"), "v2")
        start(queuedDelete(item(), "v1"))
        until { stored()?.syncConflict != null }
        settle()
        assertEquals(emptyList(), b.writes)
        assertEquals("Edited", stored()?.syncConflict?.serverItem?.title)
    }

    @Test fun aRowThatMovesBeforeADeleteIsKept() = scenario { b ->
        b.seed(item(), "v1")
        b.beforeRowLookup = { b.bump(ItemA, "v2") }
        start(queuedDelete(item(), "v1"))
        until { stored()?.syncConflict != null }
        assertEquals(emptyList(), b.writes)
        assertEquals(1, b.itemRows.size)
    }

    // ---- conflict resolution -----------------------------------------------------

    @Test fun keepingLocalRebasesAndWrites() = scenario { b ->
        b.seed(item(title = "Theirs"), "v2")
        start(queuedUpdate(item(title = "Mine"), "v1"))
        until { stored()?.syncConflict != null }
        repo.resolveSyncConflict(ItemA, "local")
        drained()
        assertEquals("Mine", b.title(ItemA))
        assertEquals(1, b.writes.size)
    }

    @Test fun keepingServerDropsTheLocalChange() = scenario { b ->
        b.seed(item(title = "Theirs"), "v2")
        start(queuedUpdate(item(title = "Mine"), "v1"))
        until { stored()?.syncConflict != null }
        repo.resolveSyncConflict(ItemA, "server")
        drained()
        assertEquals(emptyList(), b.writes)
        assertEquals("Theirs", stored()?.title)
    }

    // ---- writes racing the pass --------------------------------------------------

    @Test fun anEditDuringACreateRebasesOntoTheCommittedVersion() = scenario { b ->
        val inCreate = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        b.duringCreate = { inCreate.complete(Unit); release.await() }
        start(queuedCreate(item(title = "First")))
        inCreate.await()
        val edit = scope.async { repo.saveItem(item(title = "Second")) }
        until { queue.singleOrNull()?.item?.title == "Second" }
        release.complete(Unit)
        edit.await()
        drained()
        assertEquals("Second", b.title(ItemA))
        assertEquals(1, b.itemRows.size)
        assertEquals("Second", stored()?.title)
    }

    @Test fun aDeleteDuringACreateRemovesTheCommittedRow() = scenario { b ->
        val inCreate = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        b.duringCreate = { inCreate.complete(Unit); release.await() }
        start(queuedCreate(item()))
        inCreate.await()
        val delete = scope.async { repo.deleteItem(ItemA) }
        drained()
        release.complete(Unit)
        delete.await()
        until { b.itemRows.isEmpty() && b.writes.size == 2 }
        drained()
        assertEquals("delete", b.writes.last().substringBefore(' '))
    }

    @Test fun simultaneousSyncsRunOnePass() = scenario { b ->
        val release = CompletableDeferred<Unit>()
        b.duringCreate = { release.await() }
        start(queuedCreate(item()))
        coroutineScope {
            val calls = List(20) { async { repo.executeSync() } }
            until { queue.isNotEmpty() }
            delay(30)
            release.complete(Unit)
            calls.awaitAll()
        }
        drained()
        assertEquals(listOf("create $ItemA"), b.writes)
    }

    @Test fun anItemSavedDuringAPassIsNotLeftWaiting() = scenario { b ->
        val inCreate = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        b.duringCreate = { if (it == ItemA) { inCreate.complete(Unit); release.await() } }
        start(queuedCreate(item(ItemA)))
        inCreate.await()
        val save = scope.async { repo.saveItem(item(ItemB, start = "12:00", end = "13:00")) }
        until { queue.any { it.id == ItemB } }
        release.complete(Unit)
        save.await()
        drained()
        assertEquals(setOf("create $ItemA", "create $ItemB"), b.writes.toSet())
    }

    // ---- failure, offline, sign-out ------------------------------------------------

    @Test fun aServerFailureKeepsTheQueueAndAReplaySucceeds() = scenario { b ->
        b.itemsDown = true
        start(queuedCreate(item()))
        repo.executeSync()
        until { repo.itemSyncError.value != null }
        assertEquals(1, queue.size)
        b.itemsDown = false
        repo.executeSync()
        drained()
        assertNull(repo.itemSyncError.value)
        assertEquals(1, b.writes.size)
    }

    @Test fun aSaveOfflineQueuesAndAReconnectReplays() = scenario(online = false) { b ->
        start()
        repo.saveItem(item())
        until { queue.size == 1 }
        assertEquals(emptyList(), b.writes)
        assertEquals(1, persisted()?.queue?.size)
        connectivity.online.value = true
        drained()
        assertEquals(listOf("create $ItemA"), b.writes)
    }

    @Test fun aSaveOnlineSyncsBeforeReturning() = scenario { b ->
        start()
        repo.saveItem(item())
        assertEquals(listOf("create $ItemA"), b.writes)
        assertEquals(emptyList(), persisted()?.queue)
    }

    @Test fun deletingANeverSyncedItemNeedsNoRequest() = scenario(online = false) { b ->
        start()
        repo.saveItem(item())
        repo.deleteItem(ItemA)
        assertEquals(emptyList(), persisted()?.queue)
        assertEquals(emptyList(), persisted()?.items)
        assertEquals(emptyList(), b.writes)
    }

    @Test fun signingOutMidPassDoesNotBringTheQueueBack() = scenario { b ->
        val inCreate = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        b.duringCreate = { inCreate.complete(Unit); release.await() }
        start(queuedCreate(item()))
        inCreate.await()
        auth.signOut()
        until { repo.itemsState.value.items.isEmpty() }
        release.complete(Unit)
        delay(50)
        assertNull(kv.get(getScheduleItemsStorageKey(UserId)))
    }

    /** 02 #2: an involuntary sign-out keeps the queue, and the same account replays it on its return. */
    @Test fun aQueueKeptThroughAnExpiryReplaysWhenTheAccountReturns() = scenario(online = false) { b ->
        start(queuedCreate(item()))
        auth.expire()
        until { repo.itemsState.value.items.isEmpty() }
        assertEquals(1, persisted()?.queue?.size)
        connectivity.online.value = true
        auth.adoptSession("tok", JsonObject(mapOf("id" to JsonPrimitive(UserId))))
        drained()
        assertEquals(listOf("create $ItemA"), b.writes)
    }

    @Test fun aSignOutFlushSendsTheQueueOrSaysItCannot() = scenario(online = false) { b ->
        start(queuedCreate(item()))
        assertFalse(repo.flushPendingWrites(), "offline: the queued create cannot be sent")
        connectivity.online.value = true
        until { b.writes.isNotEmpty() }
        assertTrue(repo.flushPendingWrites())
        assertEquals(listOf("create $ItemA"), b.writes)
    }

    /** 04 #5: the editor has no date fields, so an edit must not clear the dates a class has. */
    @Test fun editingAClassKeepsItsDates() = scenario { b ->
        start()
        repo.updateEvent("class-1", ScheduleFormData("Algebra", "seminar", "A1", "", "", 1, "10:00", "11:00", "blue", "2098/99/2"))
        val patch = b.eventPatches.single()
        assertFalse("semester_start" in patch, patch.toString())
        assertFalse("semester_end" in patch, patch.toString())
    }

    // ---- clashes with classes ------------------------------------------------------

    private fun FakeBackend.seedClass() {
        classRows += JsonObject(
            mapOf(
                "id" to JsonPrimitive("class-1"),
                "user" to JsonPrimitive(UserId),
                "course_name" to JsonPrimitive("Algebra"),
                "course_code" to JsonPrimitive("A1"),
                "day_of_week" to JsonPrimitive(1),
                "start_time" to JsonPrimitive("10:00"),
                "end_time" to JsonPrimitive("11:00"),
                "semester" to JsonPrimitive("2098/99/2"),
                "semester_start" to JsonPrimitive("2099-02-02"),
                "semester_end" to JsonPrimitive("2099-05-25"),
                "event_type" to JsonPrimitive("class"),
            ),
        )
    }

    @Test fun anUnacknowledgedClashIsHeldBackWithoutAnError() = scenario { b ->
        b.seedClass()
        start(queuedCreate(item(start = "10:00", end = "11:00")))
        settle()
        assertEquals(emptyList(), b.writes)
        assertEquals(1, queue.size)
        assertNull(repo.itemSyncError.value)
    }

    @Test fun savingOverAClassNeedsAnAcknowledgment() = scenario { b ->
        b.seedClass()
        start()
        val e = assertFailsWith<ScheduleItemConflictException> { repo.saveItem(item(start = "10:00", end = "11:00")) }
        assertEquals(emptyList(), persisted()?.queue)
        assertEquals(emptyList(), b.writes)
        repo.saveItem(item(start = "10:00", end = "11:00", acks = e.conflicts.map { it.key }))
        assertEquals(listOf("create $ItemA"), b.writes)
    }

    // ---- classes ---------------------------------------------------------------------

    @Test fun aLoadFailureWithNothingCachedIsReported() = scenario { b ->
        b.classesDown = true
        start()
        until { repo.eventsError.value != null }
        assertFalse(repo.eventsLoading.value)
        b.classesDown = false
        repo.refreshEvents(force = true)
        assertNull(repo.eventsError.value)
    }

    @Test fun aLoadFailureWithACacheStaysQuiet() = scenario { b ->
        b.seedClass()
        start()
        until { repo.events.value.isNotEmpty() }
        b.classesDown = true
        repo.refreshEvents(force = true)
        assertNull(repo.eventsError.value)
        assertEquals("Algebra", repo.events.value.single().courseName)
    }

    @Test fun simultaneousClassRefreshesShareOneLoad() = scenario { b ->
        b.seedClass()
        start()
        until { repo.events.value.isNotEmpty() }
        coroutineScope { List(10) { launch { repo.refreshEvents(force = true) } } }
        assertEquals("Algebra", repo.events.value.single().courseName)
    }

    @Test fun signingOutClearsTheClassError() = scenario { b ->
        b.classesDown = true
        start()
        until { repo.eventsError.value != null }
        auth.signOut()
        until { repo.eventsError.value == null }
        assertTrue(repo.events.value.isEmpty())
    }
}
