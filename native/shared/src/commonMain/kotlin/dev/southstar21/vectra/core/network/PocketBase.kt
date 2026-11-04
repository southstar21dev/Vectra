package dev.southstar21.vectra.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val VectraJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

/**
 * A failed PocketBase call (ClientResponseError). `status == 0` means the
 * request never got an HTTP answer (offline, DNS, timeout).
 */
class PocketBaseException(
    val status: Int,
    override val message: String,
    val data: JsonObject = JsonObject(emptyMap()),
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** PocketBase field-level validation errors: field → message. */
    val fieldErrors: Map<String, String>
        get() = (data["data"] as? JsonObject)?.mapNotNull { (field, v) ->
            ((v as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull?.let { field to it }
        }?.toMap().orEmpty()

    val isNetwork get() = status == 0
    val isAuthRejection get() = status == 401 || status == 403
}

/** One page of a list query. */
data class ListResult(val page: Int, val perPage: Int, val totalItems: Int, val items: List<JsonObject>)

/** A last resort only: well above the 40 s Futár and 30 s ICS proxy budgets, and a slow catalog download. */
private const val RequestTimeoutMs = 90_000L
private const val ConnectTimeoutMs = 15_000L
private const val SocketTimeoutMs = 60_000L

fun createHttpClient(engine: HttpClient = HttpClient()): HttpClient = engine.config {
    install(HttpTimeout) {
        requestTimeoutMillis = RequestTimeoutMs
        connectTimeoutMillis = ConnectTimeoutMs
        socketTimeoutMillis = SocketTimeoutMs
    }
    expectSuccess = false
}

/**
 * Minimal typed client for the PocketBase REST API — the subset the app uses (auth,
 * record CRUD, `send` for custom routes). Records stay JSON so repositories map
 * them explicitly.
 */
class PocketBaseClient(
    private val http: HttpClient,
    val baseUrl: String,
    val auth: AuthStore,
) {
    private val base = baseUrl.trimEnd('/')

    suspend fun send(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        body: JsonElement? = null,
        query: Map<String, String?> = emptyMap(),
    ): JsonElement {
        val text = sendText(path, method, body, query)
        return if (text.isBlank()) JsonObject(emptyMap()) else parseLoosely(text)
    }

    private fun parseLoosely(text: String): JsonElement =
        runCatching { VectraJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }

    /** The raw success body, for a hot path that decodes it directly instead of through a [JsonElement] tree. */
    suspend fun sendText(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        body: JsonElement? = null,
        query: Map<String, String?> = emptyMap(),
    ): String {
        val response: HttpResponse = try {
            http.request("$base$path") {
                this.method = method
                applyAuth()
                query.forEach { (k, v) -> if (v != null) parameter(k, v) }
                if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw PocketBaseException(0, e.message ?: "Network request failed", cause = e)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val obj = (if (text.isBlank()) null else parseLoosely(text)) as? JsonObject ?: JsonObject(emptyMap())
            val message = (obj["message"] as? JsonPrimitive)?.contentOrNull ?: "Something went wrong while processing your request."
            throw PocketBaseException(response.status.value, message, obj)
        }
        return text
    }

    private fun HttpRequestBuilder.applyAuth() {
        auth.token?.let { header("Authorization", it) }
    }

    fun collection(name: String) = RecordService(this, name)

    // ---- auth ---------------------------------------------------------------

    suspend fun authWithPassword(identity: String, password: String): AuthState {
        val res = send(
            "/api/collections/users/auth-with-password",
            HttpMethod.Post,
            JsonObject(mapOf("identity" to JsonPrimitive(identity), "password" to JsonPrimitive(password))),
        ).jsonObject
        return auth.saveFrom(res)
    }

    /**
     * Refresh the session. Only a real token is saved (see SessionWatchdog: a 200
     * with an unreadable body must never wipe the session).
     */
    suspend fun authRefresh(): AuthState? {
        val res = send("/api/collections/users/auth-refresh", HttpMethod.Post).jsonObject
        return if (res["token"] is JsonPrimitive && res["record"] is JsonObject) auth.saveFrom(res) else null
    }
}

class RecordService(private val pb: PocketBaseClient, private val name: String) {
    private val path = "/api/collections/$name/records"

    suspend fun getList(page: Int = 1, perPage: Int = 30, filter: String? = null, sort: String? = null, skipTotal: Boolean = true): ListResult {
        val res = pb.send(
            path,
            query = mapOf(
                "page" to page.toString(),
                "perPage" to perPage.toString(),
                "filter" to filter,
                "sort" to sort,
                "skipTotal" to if (skipTotal) "1" else null,
            ),
        ).jsonObject
        return ListResult(
            page = res["page"]?.jsonPrimitive?.intOrNull ?: page,
            perPage = res["perPage"]?.jsonPrimitive?.intOrNull ?: perPage,
            totalItems = res["totalItems"]?.jsonPrimitive?.intOrNull ?: -1,
            items = (res["items"] as? JsonArray)?.map { it.jsonObject }.orEmpty(),
        )
    }

    /** All records, paging with the SDK's default batch size. */
    suspend fun getFullList(filter: String? = null, sort: String? = null, batch: Int = 500): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var page = 1
        while (true) {
            val r = getList(page, batch, filter, sort)
            out += r.items
            if (r.items.size < batch) break
            page++
        }
        return out
    }

    suspend fun getOne(id: String): JsonObject = pb.send("$path/$id").jsonObject

    /** First match or null (the SDK throws 404; callers here want null). */
    suspend fun getFirstOrNull(filter: String): JsonObject? = getList(1, 1, filter).items.firstOrNull()

    suspend fun create(body: JsonObject): JsonObject = pb.send(path, HttpMethod.Post, body).jsonObject

    suspend fun update(id: String, body: JsonObject): JsonObject = pb.send("$path/$id", HttpMethod.Patch, body).jsonObject

    suspend fun delete(id: String) {
        pb.send("$path/$id", HttpMethod.Delete)
    }
}

/** PocketBase filter literal escaping (pb.filter's {:param} binding). */
fun pbString(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
