package bayern.kickner.ruinborn.server

import bayern.kickner.klogger.KLogger
import bayern.kickner.ruinborn.server.api.RateLimiter
import bayern.kickner.ruinborn.server.api.WsHub
import bayern.kickner.ruinborn.server.api.apiRoutes
import bayern.kickner.ruinborn.server.db.Db
import bayern.kickner.ruinborn.server.db.Migrations
import bayern.kickner.ruinborn.server.engine.Clock
import bayern.kickner.ruinborn.server.engine.Engine
import bayern.kickner.ruinborn.server.engine.Game
import bayern.kickner.ruinborn.server.engine.PasswordHasher
import bayern.kickner.ruinborn.server.jobs.Backup
import bayern.kickner.ruinborn.shared.ApiJson
import bayern.kickner.ruinborn.shared.Headers
import bayern.kickner.ruinborn.shared.balance.Balance
import bayern.kickner.ruinborn.shared.balance.BalanceCodec
import bayern.kickner.ruinborn.shared.dto.ErrorDto
import bayern.kickner.ruinborn.shared.model.ErrorCode
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.SerializationException
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Assembles the server: database, migrations, engine, WebSocket hub and Ktor module. Used by [main]
 * and by the tests (with a controllable clock and fixed randomness).
 */
class App(
    val config: ServerConfig,
    val balance: Balance,
    val clock: Clock = Clock.SYSTEM,
    random: Random = Random.Default,
) {
    val db = Db(config.dbPath)
    val game = Game(config, balance, db, clock, random)
    val engine = Engine(game)
    val hub = WsHub()
    val hasher = PasswordHasher(config.passwordIterations)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val backup = Backup(this)

    /** 20 requests per second per token, 5 login attempts per minute per IP. */
    val tokenLimiter = RateLimiter(20, 1_000, clock::now)
    val loginLimiter = RateLimiter(5, 60_000, clock::now)

    /** Registrations are limited per IP so the invite code cannot be brute-forced. */
    val registerLimiter = RateLimiter(10, 60_000, clock::now)

    val balanceJson: String = BalanceCodec.encode(balance)
    val balanceEtag: String = "\"" + MessageDigest.getInstance("SHA-256").digest(balanceJson.toByteArray()).joinToString("") { "%02x".format(it) }.take(32) + "\""

    fun start() {
        db.enableWal()
        val version = Migrations.migrate(db)
        KLogger.info("App") { "Datenbank ${config.dbPath} auf Schemaversion $version" }
        game.notifier = hub
        game.backupTrigger = { backup.runAsync(it) }
        engine.start()
    }

    suspend fun stop() {
        engine.stop()
        scope.cancel()
    }

    fun module(app: Application) = with(app) {
        install(ContentNegotiation) { json(ApiJson) }
        install(WebSockets) {
            pingPeriod = 30.seconds
            timeout = 60.seconds
        }
        install(StatusPages) {
            exception<BadRequestException> { call, _ -> call.respondError(HttpStatusCode.BadRequest, ErrorCode.VALIDATION, "Ungültige Anfrage.") }
            exception<SerializationException> { call, _ -> call.respondError(HttpStatusCode.BadRequest, ErrorCode.VALIDATION, "Ungültige Anfrage.") }
            exception<Throwable> { call, e ->
                KLogger.error("Http") { "Unerwarteter Fehler: ${e.stackTraceToString()}" }
                call.respondError(HttpStatusCode.InternalServerError, ErrorCode.INTERNAL, "Interner Fehler.")
            }
        }
        install(ServerTimeAndVersionPlugin(this@App))
        apiRoutes(this@App)
    }
}

suspend fun io.ktor.server.application.ApplicationCall.respondError(status: HttpStatusCode, code: ErrorCode, message: String) {
    respondText(ApiJson.encodeToString(ErrorDto.serializer(), ErrorDto(code, message)), ContentType.Application.Json, status)
}

/** Every response carries `X-Server-Time`. Outdated clients receive 426 (concept section 15, versioning). */
private fun ServerTimeAndVersionPlugin(app: App) = createApplicationPlugin("ServerTimeAndVersion") {
    onCall { call ->
        call.response.header(Headers.SERVER_TIME, app.clock.now().toString())
        val path = call.request.local.uri
        val exempt = path.startsWith("/api/health") || path.startsWith("/api/version") || path.startsWith("/download")
        val version = call.request.headers[Headers.CLIENT_VERSION]?.toIntOrNull()
        if (exempt.not() && version != null && version < app.config.minClientVersion) {
            call.respondError(HttpStatusCode.UpgradeRequired, ErrorCode.CLIENT_OUTDATED, "Bitte aktualisiere die App.")
        }
    }
}
