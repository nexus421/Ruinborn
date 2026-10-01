package bayern.kickner.ruinborn.server.api

import bayern.kickner.klogger.KLogger
import bayern.kickner.ruinborn.server.App
import bayern.kickner.ruinborn.server.admin.AdminCommand
import bayern.kickner.ruinborn.server.admin.adminCommand
import bayern.kickner.ruinborn.server.db.SessionT
import bayern.kickner.ruinborn.server.engine.Ctx
import bayern.kickner.ruinborn.server.engine.HttpOut
import bayern.kickner.ruinborn.server.engine.OK
import bayern.kickner.ruinborn.server.engine.Res
import bayern.kickner.ruinborn.server.engine.SESSION_VALID_MS
import bayern.kickner.ruinborn.server.engine.Tokens
import bayern.kickner.ruinborn.server.engine.accountById
import bayern.kickner.ruinborn.server.engine.accountByName
import bayern.kickner.ruinborn.server.engine.alliRallies
import bayern.kickner.ruinborn.server.engine.allianceDetail
import bayern.kickner.ruinborn.server.engine.allianceRequests
import bayern.kickner.ruinborn.server.engine.allianceSettings
import bayern.kickner.ruinborn.server.engine.answerRequest
import bayern.kickner.ruinborn.server.engine.banText
import bayern.kickner.ruinborn.server.engine.build
import bayern.kickner.ruinborn.server.engine.cancelRally
import bayern.kickner.ruinborn.server.engine.cancelTimer
import bayern.kickner.ruinborn.server.engine.changePassword
import bayern.kickner.ruinborn.server.engine.chatHistory
import bayern.kickner.ruinborn.server.engine.claimAchievement
import bayern.kickner.ruinborn.server.engine.claimDaily
import bayern.kickner.ruinborn.server.engine.claimGift
import bayern.kickner.ruinborn.server.engine.createAlliance
import bayern.kickner.ruinborn.server.engine.createRally
import bayern.kickner.ruinborn.server.engine.deleteChat
import bayern.kickner.ruinborn.server.engine.deleteReport
import bayern.kickner.ruinborn.server.engine.demolish
import bayern.kickner.ruinborn.server.engine.disbandAlliance
import bayern.kickner.ruinborn.server.engine.equipCosmetics
import bayern.kickner.ruinborn.server.engine.fail
import bayern.kickner.ruinborn.server.engine.giftList
import bayern.kickner.ruinborn.server.engine.heal
import bayern.kickner.ruinborn.server.engine.helpAll
import bayern.kickner.ruinborn.server.engine.helpRequests
import bayern.kickner.ruinborn.server.engine.joinAlliance
import bayern.kickner.ruinborn.server.engine.joinRally
import bayern.kickner.ruinborn.server.engine.leaveAlliance
import bayern.kickner.ruinborn.server.engine.login
import bayern.kickner.ruinborn.server.engine.logout
import bayern.kickner.ruinborn.server.engine.manageMember
import bayern.kickner.ruinborn.server.engine.mapSnapshot
import bayern.kickner.ruinborn.server.engine.markActive
import bayern.kickner.ruinborn.server.engine.markAllReportsRead
import bayern.kickner.ruinborn.server.engine.ok
import bayern.kickner.ruinborn.server.engine.openReport
import bayern.kickner.ruinborn.server.engine.orReturn
import bayern.kickner.ruinborn.server.engine.playerState
import bayern.kickner.ruinborn.server.engine.postChat
import bayern.kickner.ruinborn.server.engine.profile
import bayern.kickner.ruinborn.server.engine.ranking
import bayern.kickner.ruinborn.server.engine.recall
import bayern.kickner.ruinborn.server.engine.register
import bayern.kickner.ruinborn.server.engine.reportChat
import bayern.kickner.ruinborn.server.engine.reportList
import bayern.kickner.ruinborn.server.engine.requestHelp
import bayern.kickner.ruinborn.server.engine.research
import bayern.kickner.ruinborn.server.engine.searchAlliances
import bayern.kickner.ruinborn.server.engine.sendHome
import bayern.kickner.ruinborn.server.engine.speedup
import bayern.kickner.ruinborn.server.engine.startMarch
import bayern.kickner.ruinborn.server.engine.train
import bayern.kickner.ruinborn.server.engine.useItem
import bayern.kickner.ruinborn.server.respondError
import bayern.kickner.ruinborn.shared.ApiJson
import bayern.kickner.ruinborn.shared.Headers
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
import bayern.kickner.ruinborn.shared.model.Role
import bayern.kickner.ruinborn.shared.rules.Validation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotnexlib.ResultOf2
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.security.MessageDigest

/** Authenticated caller. */
data class Principal(val accountId: Long, val username: String, val role: Role, val tokenHash: String)

private val stateSer = PlayerState.serializer()

/** All HTTP and WebSocket endpoints from concept section 15. */
fun Application.apiRoutes(app: App) {
    val engine = app.engine
    val config = app.config

    suspend fun ApplicationCall.respondOut(out: HttpOut) =
        respondText(out.body, ContentType.Application.Json, HttpStatusCode.fromValue(out.status))

    /** Reads the session from `Authorization: Bearer <token>`. Responds with 401/403/429 itself. */
    suspend fun ApplicationCall.principal(respond: Boolean = true): Principal? {
        val token = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrEmpty()) {
            if (respond) respondError(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "Bitte anmelden.")
            return null
        }
        val hash = Tokens.hashOf(token)
        val now = app.clock.now()
        val found = withContext(Dispatchers.IO) {
            transaction(app.db.read) {
                val s = SessionT.selectAll().where { SessionT.tokenHash eq hash }.firstOrNull() ?: return@transaction null
                if (s[SessionT.expiresAt] <= now) return@transaction null
                val acc = accountById(s[SessionT.accountId]) ?: return@transaction null
                Triple(acc, s[SessionT.expiresAt], hash)
            }
        }
        if (found == null) {
            if (respond) respondError(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "Sitzung abgelaufen, bitte neu anmelden.")
            return null
        }
        val (acc, expires, h) = found
        if (acc.bannedUntil > now) {
            if (respond) respondError(HttpStatusCode.Forbidden, ErrorCode.BANNED, banText(acc.bannedUntil, acc.banReason))
            return null
        }
        if (app.tokenLimiter.tryAcquire(h).not()) {
            if (respond) respondError(HttpStatusCode.TooManyRequests, ErrorCode.RATE_LIMITED, "Zu viele Anfragen.")
            return null
        }
        // Valid for 30 days after last use. Extended at most once per hour.
        if (expires - now < SESSION_VALID_MS - 3_600_000L) {
            app.scope.launch {
                engine.exec {
                    SessionT.update({ SessionT.tokenHash eq h }) { it[expiresAt] = now + SESSION_VALID_MS }
                    OK
                }
            }
        }
        return Principal(acc.id, acc.username, acc.role, h)
    }

    val requestId: ApplicationCall.() -> String? = { request.header(Headers.REQUEST_ID) }

    /** Write command through the engine, protected against duplicate execution. */
    suspend fun <T> RoutingContext.cmd(ser: KSerializer<T>, block: Ctx.(Principal) -> Res<T>) {
        val p = call.principal() ?: return
        call.respondOut(engine.http(p.accountId, call.requestId(), ser) { block(p) })
    }

    /** Command that responds with the new game state. */
    suspend fun RoutingContext.stateCmd(block: Ctx.(Principal) -> Res<*>) = cmd(stateSer) { p ->
        when (val r = block(p)) {
            is ResultOf2.Failure -> r
            is ResultOf2.Success -> ok(playerState(p.accountId))
        }
    }

    /** Pure read through the read-only connection on Dispatchers.IO. */
    suspend fun <T> RoutingContext.read(ser: KSerializer<T>, block: Ctx.(Principal) -> Res<T>) {
        val p = call.principal() ?: return
        val r = withContext(Dispatchers.IO) { transaction(app.db.read) { Ctx(app.game, app.clock.now()).block(p) } }
        when (r) {
            is ResultOf2.Success -> call.respondText(ApiJson.encodeToString(ser, r.value), ContentType.Application.Json)
            is ResultOf2.Failure -> call.respondError(HttpStatusCode.fromValue(r.value.code.httpStatus), r.value.code, r.value.message)
        }
    }

    fun ApplicationCall.longParam(name: String): Long? = parameters[name]?.toLongOrNull()

    routing {
        // ------------------------------------------------ Without login
        get("/api/health") { call.respondText("ok") }
        get("/api/version") {
            call.respondText(
                ApiJson.encodeToString(VersionInfo.serializer(), VersionInfo(config.minClientVersion, config.latestClientVersion, config.apkUrl)),
                ContentType.Application.Json,
            )
        }
        get("/download/ruinborn.apk") {
            val f = File(config.apkPath)
            if (f.isFile) {
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"ruinborn.apk\"")
                call.respondFile(f)
            } else call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Noch keine App hinterlegt.")
        }
        post("/api/auth/register") {
            if (app.registerLimiter.tryAcquire("ip:" + call.clientIp()).not()) {
                return@post call.respondError(HttpStatusCode.TooManyRequests, ErrorCode.RATE_LIMITED, "Zu viele Versuche, bitte warten.")
            }
            val req = call.receive<RegisterRequest>()
            Validation.username(req.username)?.let { return@post call.respondError(HttpStatusCode.BadRequest, ErrorCode.VALIDATION, it) }
            Validation.password(req.password)?.let { return@post call.respondError(HttpStatusCode.BadRequest, ErrorCode.VALIDATION, it) }
            val codeOk = MessageDigest.isEqual(req.inviteCode.toByteArray(), config.inviteCode.toByteArray())
            if (codeOk.not()) return@post call.respondError(HttpStatusCode.BadRequest, ErrorCode.INVALID_INVITE, "Der Einladungscode ist falsch.")
            val hash = withContext(Dispatchers.Default) { app.hasher.hash(req.password) }
            call.respondOut(engine.http(null, null, TokenResponse.serializer()) { register(req.username, hash).let { r ->
                if (r is ResultOf2.Success) ok(TokenResponse(r.value)) else r as ResultOf2.Failure
            } })
        }
        post("/api/auth/login") {
            if (app.loginLimiter.tryAcquire("ip:" + call.clientIp()).not()) {
                return@post call.respondError(HttpStatusCode.TooManyRequests, ErrorCode.RATE_LIMITED, "Zu viele Versuche, bitte warten.")
            }
            val req = call.receive<LoginRequest>()
            val acc = withContext(Dispatchers.IO) { transaction(app.db.read) { accountByName(req.username) } }
            val valid = acc != null && withContext(Dispatchers.Default) { app.hasher.verify(req.password, acc.pwHash) }
            if (valid.not()) return@post call.respondError(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "Name oder Passwort falsch.")
            call.respondOut(engine.http(null, null, TokenResponse.serializer()) { login(acc.id).let { r ->
                if (r is ResultOf2.Success) ok(TokenResponse(r.value)) else r as ResultOf2.Failure
            } })
        }

        // ------------------------------------------------ Account
        post("/api/auth/logout") { cmd(OkResponse.serializer()) { p -> logout(p.tokenHash).orReturn { return@cmd it }; ok(OkResponse()) } }
        post("/api/auth/password") {
            val p = call.principal() ?: return@post
            val req = call.receive<PasswordChangeRequest>()
            Validation.password(req.newPassword)?.let { return@post call.respondError(HttpStatusCode.BadRequest, ErrorCode.VALIDATION, it) }
            val acc = withContext(Dispatchers.IO) { transaction(app.db.read) { accountById(p.accountId) } }!!
            val okOld = withContext(Dispatchers.Default) { app.hasher.verify(req.oldPassword, acc.pwHash) }
            if (okOld.not()) return@post call.respondError(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "Das alte Passwort ist falsch.")
            val hash = withContext(Dispatchers.Default) { app.hasher.hash(req.newPassword) }
            call.respondOut(engine.http(p.accountId, call.requestId(), OkResponse.serializer()) {
                changePassword(p.accountId, hash, p.tokenHash).orReturn { return@http it }
                ok(OkResponse())
            })
        }

        // ------------------------------------------------ Game state and base
        get("/api/balance") {
            call.principal() ?: return@get
            call.response.header(HttpHeaders.ETag, app.balanceEtag)
            if (call.request.header(HttpHeaders.IfNoneMatch) == app.balanceEtag) call.respond(HttpStatusCode.NotModified)
            else call.respondText(app.balanceJson, ContentType.Application.Json)
        }
        get("/api/state") {
            val p = call.principal() ?: return@get
            call.respondOut(engine.http(p.accountId, null, stateSer) { markActive(p.accountId); ok(playerState(p.accountId)) })
        }
        post("/api/build") { val req = call.receive<BuildRequest>(); stateCmd { build(it.accountId, req) } }
        post("/api/demolish") { val req = call.receive<DemolishRequest>(); stateCmd { demolish(it.accountId, req.plot) } }
        post("/api/research") { val req = call.receive<ResearchRequest>(); stateCmd { research(it.accountId, req.tech) } }
        post("/api/train") { val req = call.receive<TrainRequest>(); stateCmd { train(it.accountId, req) } }
        post("/api/heal") { val req = call.receive<HealRequest>(); stateCmd { heal(it.accountId, req) } }
        post("/api/timers/{id}/speedup") {
            val id = call.longParam("id") ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Auftrag.")
            val req = call.receive<SpeedupRequest>()
            stateCmd { speedup(it.accountId, id, req.item, req.count) }
        }
        post("/api/timers/{id}/cancel") {
            val id = call.longParam("id") ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Auftrag.")
            stateCmd { cancelTimer(it.accountId, id) }
        }
        post("/api/timers/{id}/help") {
            val id = call.longParam("id") ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Auftrag.")
            stateCmd { requestHelp(it.accountId, id) }
        }
        post("/api/items/use") { val req = call.receive<ItemUseRequest>(); stateCmd { useItem(it.accountId, req) } }

        // ------------------------------------------------ Map and marches
        get("/api/map") { read(MapSnapshot.serializer()) { p -> ok(mapSnapshot(p.accountId)) } }
        post("/api/marches") { val req = call.receive<MarchRequest>(); stateCmd { startMarch(it.accountId, req) } }
        post("/api/marches/{id}/recall") {
            val id = call.longParam("id") ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Marsch.")
            stateCmd { recall(it.accountId, id) }
        }
        post("/api/marches/{id}/send-home") {
            val id = call.longParam("id") ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Marsch.")
            stateCmd { sendHome(it.accountId, id) }
        }
        post("/api/rallies") { val req = call.receive<RallyRequest>(); stateCmd { createRally(it.accountId, req) } }
        post("/api/rallies/{id}/join") {
            val id = call.longParam("id") ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Sammelangriff.")
            val req = call.receive<RallyJoinRequest>()
            stateCmd { joinRally(it.accountId, id, req) }
        }
        post("/api/rallies/{id}/cancel") {
            val id = call.longParam("id") ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Sammelangriff.")
            stateCmd { cancelRally(it.accountId, id) }
        }

        // ------------------------------------------------ Alliances
        get("/api/alliances") {
            val q = call.request.queryParameters["query"]
            read(ListSerializer(AllianceSummaryDto.serializer())) { p -> ok(searchAlliances(p.accountId, q)) }
        }
        get("/api/alliances/{id}") {
            val id = call.longParam("id") ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannte Allianz.")
            read(AllianceDetailDto.serializer()) { p -> allianceDetail(p.accountId, id) }
        }
        post("/api/alliances") { val req = call.receive<AllianceCreateRequest>(); stateCmd { createAlliance(it.accountId, req) } }
        post("/api/alliances/{id}/join") {
            val id = call.longParam("id") ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannte Allianz.")
            stateCmd { joinAlliance(it.accountId, id) }
        }
        post("/api/alliance/leave") { stateCmd { leaveAlliance(it.accountId) } }
        post("/api/alliance/settings") { val req = call.receive<AllianceSettingsRequest>(); stateCmd { allianceSettings(it.accountId, req) } }
        get("/api/alliance/requests") { read(ListSerializer(AllianceRequestDto.serializer())) { p -> allianceRequests(p.accountId) } }
        post("/api/alliance/requests/{playerId}/{action}") {
            val pid = call.longParam("playerId") ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Spieler.")
            val accept = when (call.parameters["action"]) {
                "accept" -> true
                "reject" -> false
                else -> return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannte Aktion.")
            }
            stateCmd { answerRequest(it.accountId, pid, accept) }
        }
        post("/api/alliance/members/{playerId}/{action}") {
            val pid = call.longParam("playerId") ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Spieler.")
            val action = call.parameters["action"].orEmpty()
            stateCmd { manageMember(it.accountId, pid, action) }
        }
        post("/api/alliance/disband") { stateCmd { disbandAlliance(it.accountId) } }
        get("/api/alliance/help") {
            val p = call.principal() ?: return@get
            call.respondOut(engine.http(p.accountId, null, ListSerializer(HelpRequestDto.serializer())) { helpRequests(p.accountId) })
        }
        post("/api/alliance/help-all") { stateCmd { helpAll(it.accountId) } }
        get("/api/alliance/rallies") { read(ListSerializer(RallyDto.serializer())) { p -> ok(alliRallies(p.accountId)) } }
        get("/api/alliance/gifts") { read(ListSerializer(GiftDto.serializer())) { p -> ok(giftList(p.accountId)) } }
        post("/api/alliance/gifts/{id}/claim") {
            val id = call.longParam("id") ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekanntes Geschenk.")
            stateCmd { claimGift(it.accountId, id) }
        }

        // ------------------------------------------------ Chat
        get("/api/chat/{channel}") {
            val channel = call.parameters["channel"].orEmpty()
            val before = call.request.queryParameters["before"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            read(ListSerializer(ChatMessageDto.serializer())) { p -> chatHistory(p.accountId, channel, before, limit) }
        }
        post("/api/chat/{channel}") {
            val p = call.principal() ?: return@post
            val channel = call.parameters["channel"].orEmpty()
            val req = call.receive<ChatPostRequest>()
            val admin = AdminCommand.parse(req.text)
            if (admin == null) {
                call.respondOut(engine.http(p.accountId, call.requestId(), ChatPostResponse.serializer()) {
                    postChat(p.accountId, channel, req.text).let { r -> if (r is ResultOf2.Success) ok(ChatPostResponse(message = r.value)) else r as ResultOf2.Failure }
                })
                return@post
            }
            if (p.role != Role.ADMIN) return@post call.respondError(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "Befehle mit / sind Admins vorbehalten.")
            // Password hashing never runs in the engine.
            val hash = if (admin.name == "password") {
                val pw = admin.args.getOrNull(1) ?: ""
                Validation.password(pw)?.let { return@post call.respondError(HttpStatusCode.BadRequest, ErrorCode.VALIDATION, it) }
                withContext(Dispatchers.Default) { app.hasher.hash(pw) }
            } else null
            call.respondOut(engine.http(p.accountId, call.requestId(), ChatPostResponse.serializer()) {
                adminCommand(p.accountId, admin, hash).let { r -> if (r is ResultOf2.Success) ok(ChatPostResponse(system = r.value)) else r as ResultOf2.Failure }
            })
        }
        post("/api/chat/messages/{id}/report") {
            val id = call.longParam("id") ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannte Nachricht.")
            cmd(OkResponse.serializer()) { p -> reportChat(p.accountId, id).orReturn { return@cmd it }; ok(OkResponse()) }
        }
        delete("/api/chat/messages/{id}") {
            val id = call.longParam("id") ?: return@delete call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannte Nachricht.")
            cmd(OkResponse.serializer()) { p ->
                if (p.role != Role.ADMIN) return@cmd fail(ErrorCode.FORBIDDEN, "Nur Admins dürfen löschen.")
                deleteChat(id).orReturn { return@cmd it }
                ok(OkResponse())
            }
        }

        // ------------------------------------------------ Reports, tasks, rankings, profile
        get("/api/reports") {
            val before = call.request.queryParameters["before"]?.toLongOrNull()
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, app.balance.reports.pageSize)
            read(ListSerializer(ReportDto.serializer())) { p -> ok(reportList(p.accountId, before, limit)) }
        }
        get("/api/reports/{id}") {
            val p = call.principal() ?: return@get
            val id = call.longParam("id") ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Bericht.")
            call.respondOut(engine.http(p.accountId, null, ReportDto.serializer()) { openReport(p.accountId, id) })
        }
        delete("/api/reports/{id}") {
            val id = call.longParam("id") ?: return@delete call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Bericht.")
            cmd(OkResponse.serializer()) { p -> deleteReport(p.accountId, id).orReturn { return@cmd it }; ok(OkResponse()) }
        }
        post("/api/reports/read-all") { cmd(OkResponse.serializer()) { p -> markAllReportsRead(p.accountId).orReturn { return@cmd it }; ok(OkResponse()) } }
        post("/api/daily/{task}/claim") {
            val task = call.parameters["task"]?.let { runCatching { DailyTask.valueOf(it.uppercase()) }.getOrNull() }
                ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannte Aufgabe.")
            stateCmd { claimDaily(it.accountId, task) }
        }
        post("/api/achievements/{id}/claim") {
            val id = call.parameters["id"]?.let { runCatching { AchievementId.valueOf(it.uppercase()) }.getOrNull() }
                ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Erfolg.")
            stateCmd { claimAchievement(it.accountId, id) }
        }
        get("/api/rankings/{kind}") {
            val kind = call.parameters["kind"].orEmpty()
            read(RankingDto.serializer()) { p -> ranking(p.accountId, kind) }
        }
        get("/api/players/{id}") {
            val id = call.longParam("id") ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Unbekannter Spieler.")
            read(PlayerProfileDto.serializer()) { profile(id) }
        }
        post("/api/cosmetics/equip") { val req = call.receive<CosmeticEquipRequest>(); stateCmd { equipCosmetics(it.accountId, req.skin, req.frame) } }

        // ------------------------------------------------ WebSocket (server notifications only)
        webSocket("/ws") {
            val p = call.principal(respond = false)
            if (p == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Nicht angemeldet"))
                return@webSocket
            }
            val conn = WsHub.Conn(p.accountId, this)
            app.hub.register(conn)
            val pump = app.hub.pump(this, conn)
            runCatching { for (frame in incoming) { /* The client sends nothing. Ktor handles pings. */ } }
                .onFailure { KLogger.debug("Ws") { "Verbindung ${p.username} beendet: ${it.message}" } }
            app.hub.unregister(conn)
            pump.cancel()
        }
    }
}

/**
 * Caller IP for rate limits. `X-Forwarded-For` only counts if the connection comes from a proxy on the
 * local network (Caddy on the host). Otherwise anyone could bypass the login limit with a made-up header.
 */
private fun ApplicationCall.clientIp(): String {
    val peer = request.local.remoteAddress
    val trusted = runCatching { java.net.InetAddress.getByName(peer) }.getOrNull()
        ?.let { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress } ?: false
    val forwarded = request.header("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
    return if (trusted && forwarded != null) forwarded else peer
}
