package bayern.kickner.ruinborn.client.net

import bayern.kickner.ruinborn.shared.ApiJson
import bayern.kickner.ruinborn.shared.Headers
import bayern.kickner.ruinborn.shared.balance.Balance
import bayern.kickner.ruinborn.shared.balance.BalanceCodec
import bayern.kickner.ruinborn.shared.dto.AllianceCreateRequest
import bayern.kickner.ruinborn.shared.dto.AllianceDetailDto
import bayern.kickner.ruinborn.shared.dto.AllianceRequestDto
import bayern.kickner.ruinborn.shared.dto.AllianceSettingsRequest
import bayern.kickner.ruinborn.shared.dto.AllianceSummaryDto
import bayern.kickner.ruinborn.shared.dto.BuildRequest
import bayern.kickner.ruinborn.shared.dto.ChatMessageDto
import bayern.kickner.ruinborn.shared.dto.ChatPostRequest
import bayern.kickner.ruinborn.shared.dto.ChatPostResponse
import bayern.kickner.ruinborn.shared.dto.CosmeticEquipRequest
import bayern.kickner.ruinborn.shared.dto.DemolishRequest
import bayern.kickner.ruinborn.shared.dto.ErrorDto
import bayern.kickner.ruinborn.shared.dto.GiftDto
import bayern.kickner.ruinborn.shared.dto.HealRequest
import bayern.kickner.ruinborn.shared.dto.HelpRequestDto
import bayern.kickner.ruinborn.shared.dto.ItemUseRequest
import bayern.kickner.ruinborn.shared.dto.LoginRequest
import bayern.kickner.ruinborn.shared.dto.MapSnapshot
import bayern.kickner.ruinborn.shared.dto.MarchRequest
import bayern.kickner.ruinborn.shared.dto.OkResponse
import bayern.kickner.ruinborn.shared.dto.PasswordChangeRequest
import bayern.kickner.ruinborn.shared.dto.PlayerProfileDto
import bayern.kickner.ruinborn.shared.dto.PlayerState
import bayern.kickner.ruinborn.shared.dto.RallyDto
import bayern.kickner.ruinborn.shared.dto.RallyJoinRequest
import bayern.kickner.ruinborn.shared.dto.RallyRequest
import bayern.kickner.ruinborn.shared.dto.RankingDto
import bayern.kickner.ruinborn.shared.dto.RegisterRequest
import bayern.kickner.ruinborn.shared.dto.ReportDto
import bayern.kickner.ruinborn.shared.dto.ResearchRequest
import bayern.kickner.ruinborn.shared.dto.SpeedupRequest
import bayern.kickner.ruinborn.shared.dto.TokenResponse
import bayern.kickner.ruinborn.shared.dto.TrainRequest
import bayern.kickner.ruinborn.shared.dto.VersionInfo
import bayern.kickner.ruinborn.shared.model.AchievementId
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.ErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlin.random.Random

/** Result of an API call. [Fail.code] is `null` for network errors. */
sealed class ApiResult<out T> {
    data class Ok<out T>(val value: T) : ApiResult<T>()
    data class Fail(val code: ErrorCode?, val message: String, val status: Int = 0) : ApiResult<Nothing>()

    val valueOrNull: T? get() = (this as? Ok)?.value
}

/** Responses the app reacts to (concept section 12, error handling). */
interface ApiListener {
    /** 401: back to login. */
    fun onUnauthorized() {}

    /** 426: blocking update dialog. */
    fun onOutdated() {}

    /** Network error (timeout, no connection). */
    fun onNetworkError(message: String) {}
}

const val NETWORK_ERROR_TEXT = "Keine Verbindung zum Server."

/**
 * HTTP access to all endpoints (concept section 15). Every response updates the server time offset.
 * Write commands carry an `X-Request-Id`. After a timeout the request is retried exactly once with the same ID,
 * and the server does not execute the command twice.
 *
 * The HTTP engine is passed in by the app (CIO on desktop, OkHttp on Android).
 */
class ApiClient(
    engine: HttpClientEngine,
    var baseUrl: String,
    private val versionCode: Int,
    var listener: ApiListener = object : ApiListener {},
    private val clock: () -> Long,
) {
    val http = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 10_000
        }
        install(ContentNegotiation) { json(ApiJson) }
        install(WebSockets) { pingIntervalMillis = 30_000 }
    }

    @kotlin.concurrent.Volatile
    var token: String? = null

    val serverTime = ServerTime(clock)
    private val random = Random.Default

    fun newRequestId(): String = buildString { repeat(32) { append("0123456789abcdef"[random.nextInt(16)]) } }

    private fun HttpRequestBuilder.common(requestId: String?) {
        header(Headers.CLIENT_VERSION, versionCode.toString())
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        requestId?.let { header(Headers.REQUEST_ID, it) }
    }

    private suspend fun <T> call(
        method: HttpMethod,
        path: String,
        ser: KSerializer<T>?,
        body: Any? = null,
        idempotent: Boolean = method != HttpMethod.Get,
        extra: HttpRequestBuilder.() -> Unit = {},
    ): ApiResult<T> {
        val requestId = if (idempotent) newRequestId() else null
        // Retry only when it is safe: reads or commands with X-Request-Id (not login/registration).
        val retryable = method == HttpMethod.Get || requestId != null
        var attempt = 0
        while (true) {
            attempt++
            val response: HttpResponse = try {
                http.request(baseUrl.trimEnd('/') + path) {
                    this.method = method
                    common(requestId)
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    extra()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpRequestTimeoutException) {
                if (attempt < 2 && retryable) continue
                listener.onNetworkError(NETWORK_ERROR_TEXT)
                return ApiResult.Fail(null, NETWORK_ERROR_TEXT)
            } catch (e: Throwable) {
                if (attempt < 2 && retryable && e.isTimeout()) continue
                listener.onNetworkError(NETWORK_ERROR_TEXT)
                return ApiResult.Fail(null, NETWORK_ERROR_TEXT)
            }
            response.headers[Headers.SERVER_TIME]?.toLongOrNull()?.let { serverTime.update(it) }
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            val status = response.status.value
            if (status in 200..299) {
                if (ser == null) @Suppress("UNCHECKED_CAST") return ApiResult.Ok(text as T)
                return runCatching { ApiResult.Ok(ApiJson.decodeFromString(ser, text)) }
                    .getOrElse { ApiResult.Fail(ErrorCode.INTERNAL, "Unerwartete Antwort vom Server.", status) }
            }
            if (status == 304) return ApiResult.Fail(null, "", 304)
            val err = runCatching { ApiJson.decodeFromString(ErrorDto.serializer(), text) }.getOrNull()
            when (status) {
                401 -> listener.onUnauthorized()
                426 -> listener.onOutdated()
            }
            return ApiResult.Fail(err?.code ?: ErrorCode.INTERNAL, err?.message?.ifBlank { null } ?: "Serverfehler ($status).", status)
        }
    }

    private fun Throwable.isTimeout(): Boolean = this::class.simpleName?.contains("Timeout") == true

    private suspend fun <T> get(path: String, ser: KSerializer<T>) = call(HttpMethod.Get, path, ser)
    private suspend fun <T> post(path: String, ser: KSerializer<T>, body: Any? = null) = call(HttpMethod.Post, path, ser, body)
    private suspend fun state(path: String, body: Any? = null) = post(path, PlayerState.serializer(), body)

    // ---------------------------------------------------------------- Without login
    suspend fun version() = get("/api/version", VersionInfo.serializer())
    suspend fun health() = call(HttpMethod.Get, "/api/health", null as KSerializer<String>?)
    suspend fun register(r: RegisterRequest) = call(HttpMethod.Post, "/api/auth/register", TokenResponse.serializer(), r, idempotent = false)
    suspend fun login(r: LoginRequest) = call(HttpMethod.Post, "/api/auth/login", TokenResponse.serializer(), r, idempotent = false)

    // ---------------------------------------------------------------- Account and state
    suspend fun logout() = post("/api/auth/logout", OkResponse.serializer())
    suspend fun changePassword(r: PasswordChangeRequest) = post("/api/auth/password", OkResponse.serializer(), r)

    /** Balance with ETag: `Ok(null)` if unchanged, otherwise the balance and the new ETag. */
    suspend fun balance(etag: String?): ApiResult<Pair<Balance, String?>?> {
        val response = try {
            http.request(baseUrl.trimEnd('/') + "/api/balance") {
                method = HttpMethod.Get
                common(null)
                etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            listener.onNetworkError(NETWORK_ERROR_TEXT)
            return ApiResult.Fail(null, NETWORK_ERROR_TEXT)
        }
        response.headers[Headers.SERVER_TIME]?.toLongOrNull()?.let { serverTime.update(it) }
        return when (val status = response.status.value) {
            304 -> ApiResult.Ok(null)
            200 -> runCatching { ApiResult.Ok(BalanceCodec.decodeLenient(response.bodyAsText()) to response.headers[HttpHeaders.ETag]) }
                .getOrElse { ApiResult.Fail(ErrorCode.INTERNAL, "Balance-Daten unlesbar.", status) }
            else -> {
                if (status == 401) listener.onUnauthorized()
                if (status == 426) listener.onOutdated()
                ApiResult.Fail(ErrorCode.INTERNAL, "Balance-Daten nicht verfügbar ($status).", status)
            }
        }
    }

    suspend fun state() = get("/api/state", PlayerState.serializer())
    suspend fun build(r: BuildRequest) = state("/api/build", r)
    suspend fun demolish(r: DemolishRequest) = state("/api/demolish", r)
    suspend fun research(r: ResearchRequest) = state("/api/research", r)
    suspend fun train(r: TrainRequest) = state("/api/train", r)
    suspend fun heal(r: HealRequest) = state("/api/heal", r)
    suspend fun speedup(timerId: Long, r: SpeedupRequest) = state("/api/timers/$timerId/speedup", r)
    suspend fun cancelTimer(timerId: Long) = state("/api/timers/$timerId/cancel")
    suspend fun requestHelp(timerId: Long) = state("/api/timers/$timerId/help")
    suspend fun useItem(r: ItemUseRequest) = state("/api/items/use", r)

    // ---------------------------------------------------------------- Map
    suspend fun map() = get("/api/map", MapSnapshot.serializer())
    suspend fun march(r: MarchRequest) = state("/api/marches", r)
    suspend fun recall(marchId: Long) = state("/api/marches/$marchId/recall")
    suspend fun sendHome(marchId: Long) = state("/api/marches/$marchId/send-home")
    suspend fun rally(r: RallyRequest) = state("/api/rallies", r)
    suspend fun joinRally(rallyId: Long, r: RallyJoinRequest) = state("/api/rallies/$rallyId/join", r)
    suspend fun cancelRally(rallyId: Long) = state("/api/rallies/$rallyId/cancel")

    // ---------------------------------------------------------------- Alliance
    suspend fun alliances(query: String) = get("/api/alliances?query=" + encode(query), ListSerializer(AllianceSummaryDto.serializer()))
    suspend fun alliance(id: Long) = get("/api/alliances/$id", AllianceDetailDto.serializer())
    suspend fun createAlliance(r: AllianceCreateRequest) = state("/api/alliances", r)
    suspend fun joinAlliance(id: Long) = state("/api/alliances/$id/join")
    suspend fun leaveAlliance() = state("/api/alliance/leave")
    suspend fun allianceSettings(r: AllianceSettingsRequest) = state("/api/alliance/settings", r)
    suspend fun allianceRequests() = get("/api/alliance/requests", ListSerializer(AllianceRequestDto.serializer()))
    suspend fun answerRequest(playerId: Long, accept: Boolean) = state("/api/alliance/requests/$playerId/${if (accept) "accept" else "reject"}")
    suspend fun manageMember(playerId: Long, action: String) = state("/api/alliance/members/$playerId/$action")
    suspend fun disband() = state("/api/alliance/disband")
    suspend fun helpList() = get("/api/alliance/help", ListSerializer(HelpRequestDto.serializer()))
    suspend fun helpAll() = state("/api/alliance/help-all")
    suspend fun rallies() = get("/api/alliance/rallies", ListSerializer(RallyDto.serializer()))
    suspend fun gifts() = get("/api/alliance/gifts", ListSerializer(GiftDto.serializer()))
    suspend fun claimGift(id: Long) = state("/api/alliance/gifts/$id/claim")

    // ---------------------------------------------------------------- Chat
    suspend fun chat(channel: String, before: Long? = null) =
        get("/api/chat/$channel?limit=50" + (before?.let { "&before=$it" } ?: ""), ListSerializer(ChatMessageDto.serializer()))
    suspend fun postChat(channel: String, text: String) = post("/api/chat/$channel", ChatPostResponse.serializer(), ChatPostRequest(text))
    suspend fun reportMessage(id: Long) = post("/api/chat/messages/$id/report", OkResponse.serializer())
    suspend fun deleteMessage(id: Long) = call(HttpMethod.Delete, "/api/chat/messages/$id", OkResponse.serializer())

    // ---------------------------------------------------------------- Reports, tasks, rankings, profile
    suspend fun reports(before: Long? = null) = get("/api/reports?limit=50" + (before?.let { "&before=$it" } ?: ""), ListSerializer(ReportDto.serializer()))
    suspend fun report(id: Long) = get("/api/reports/$id", ReportDto.serializer())
    suspend fun deleteReport(id: Long) = call(HttpMethod.Delete, "/api/reports/$id", OkResponse.serializer())
    suspend fun readAllReports() = post("/api/reports/read-all", OkResponse.serializer())
    suspend fun claimDaily(task: DailyTask) = state("/api/daily/${task.name}/claim")
    suspend fun claimAchievement(id: AchievementId) = state("/api/achievements/${id.name}/claim")
    suspend fun ranking(kind: String) = get("/api/rankings/$kind", RankingDto.serializer())
    suspend fun profile(id: Long) = get("/api/players/$id", PlayerProfileDto.serializer())
    suspend fun equip(r: CosmeticEquipRequest) = state("/api/cosmetics/equip", r)

    fun close() = http.close()

    companion object {
        /** Simple URL encoding for search terms (letters and digits stay, everything else becomes %XX in UTF-8). */
        fun encode(s: String): String = buildString {
            s.encodeToByteArray().forEach { b ->
                val c = b.toInt() and 0xFF
                if (c.toChar().isLetterOrDigit() && c < 128) append(c.toChar())
                else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
    }
}
