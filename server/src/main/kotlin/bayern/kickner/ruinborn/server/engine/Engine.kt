package bayern.kickner.ruinborn.server.engine

import bayern.kickner.klogger.KLogger
import bayern.kickner.ruinborn.server.db.ProcessedRequestT
import bayern.kickner.ruinborn.server.db.ScheduledEventT
import bayern.kickner.ruinborn.shared.ApiJson
import bayern.kickner.ruinborn.shared.dto.ErrorDto
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.model.ErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotnexlib.ResultOf2
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.Executors

/** Response of an HTTP command: status and finished JSON text (also for replays via X-Request-Id). */
data class HttpOut(val status: Int, val body: String)

/**
 * Single-writer engine (concept section 13): exactly one coroutine on its own thread processes a
 * channel of commands and wake-ups. Each command runs in its own transaction. After every message
 * all due events are processed in order and the alarm is reset.
 */
class Engine(private val game: Game) {
    private sealed interface Msg
    private class Cmd(val run: () -> Unit) : Msg
    private data object Wakeup : Msg

    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loopJob: Job? = null
    private var wakeJob: Job? = null

    @Volatile
    var running = false
        private set

    /** Loads all scheduled events, creates missing recurring ones and starts the loop. */
    fun start() {
        check(running.not()) { "Engine läuft bereits" }
        running = true
        loadEvents()
        loopJob = scope.launch {
            processDue()
            rescheduleWakeup()
            for (msg in inbox) {
                // The loop must never die, otherwise no further command would get an answer.
                runCatching {
                    processDue()
                    if (msg is Cmd) msg.run()
                    processDue()
                    rescheduleWakeup()
                }.onFailure { KLogger.crash("Engine") { "Fehler in der Engine-Schleife: ${it.stackTraceToString()}" } }
            }
        }
    }

    /** Stops accepting commands, finishes the current message and halts. */
    suspend fun stop() {
        if (running.not()) return
        running = false
        inbox.close()
        loopJob?.join()
        wakeJob?.cancel()
        scope.cancel()
        executor.shutdown()
    }

    /** Runs a command in the engine and waits for the result. */
    suspend fun <T> exec(block: Ctx.() -> Res<T>): Res<T> {
        check(Thread.currentThread().name.startsWith(THREAD_NAME).not()) { "Engine-Befehl aus der Engine heraus (Deadlock)" }
        if (running.not()) return fail(ErrorCode.INTERNAL, "Server fährt herunter.")
        val reply = CompletableDeferred<Res<T>>()
        val sent = inbox.trySend(Cmd { reply.complete(runTx(game.clock.now(), block)) })
        if (sent.isFailure) return fail(ErrorCode.INTERNAL, "Server fährt herunter.")
        return reply.await()
    }

    /**
     * HTTP command protected against duplicate execution: if (player, [requestId]) was already processed, the
     * stored response is returned without running the command again.
     */
    suspend fun <T> http(playerId: Long?, requestId: String?, serializer: KSerializer<T>, block: Ctx.() -> Res<T>): HttpOut {
        if (running.not()) return errorOut(GameError(ErrorCode.INTERNAL, "Server fährt herunter."))
        val reply = CompletableDeferred<HttpOut>()
        val sent = inbox.trySend(Cmd { reply.complete(runHttp(playerId, requestId, serializer, block)) })
        if (sent.isFailure) return errorOut(GameError(ErrorCode.INTERNAL, "Server fährt herunter."))
        return reply.await()
    }

    /** Processes due events (e.g. after advancing a test clock) and waits for them. */
    suspend fun tick() {
        exec { OK }
    }

    private fun <T> runHttp(playerId: Long?, requestId: String?, serializer: KSerializer<T>, block: Ctx.() -> Res<T>): HttpOut {
        val key = if (playerId != null && requestId.isNullOrBlank().not()) playerId to requestId else null
        if (key != null) {
            val existing = transaction(game.db.write) {
                ProcessedRequestT.selectAll()
                    .where { (ProcessedRequestT.playerId eq key.first) and (ProcessedRequestT.requestId eq key.second) }
                    .firstOrNull()?.let { HttpOut(it[ProcessedRequestT.status], it[ProcessedRequestT.response]) }
            }
            if (existing != null) return existing
        }
        var out: HttpOut? = null
        val result = runTx(game.clock.now()) {
            val r = block()
            if (r is ResultOf2.Success) {
                val body = ApiJson.encodeToString(serializer, r.value)
                out = HttpOut(200, body)
                if (key != null) storeProcessed(key.first, key.second, now, 200, body)
            }
            r
        }
        return when (result) {
            is ResultOf2.Success -> out!!
            is ResultOf2.Failure -> {
                val err = errorOut(result.value)
                if (key != null && result.value.code != ErrorCode.INTERNAL) {
                    runCatching { transaction(game.db.write) { storeProcessed(key.first, key.second, game.clock.now(), err.status, err.body) } }
                }
                err
            }
        }
    }

    private fun storeProcessed(playerId: Long, requestId: String, now: Long, status: Int, body: String) {
        ProcessedRequestT.insert {
            it[ProcessedRequestT.playerId] = playerId
            it[ProcessedRequestT.requestId] = requestId
            it[ProcessedRequestT.createdAt] = now
            it[ProcessedRequestT.status] = status
            it[ProcessedRequestT.response] = body
        }
    }

    /** One transaction. Rolls back on errors, otherwise runs side effects after the commit. */
    private fun <T> runTx(now: Long, block: Ctx.() -> Res<T>): Res<T> {
        val ctx = Ctx(game, now)
        val result = runCatching {
            transaction(game.db.write) {
                val r = ctx.block()
                if (r is ResultOf2.Failure) rollback()
                r
            }
        }.getOrElse { e ->
            KLogger.error("Engine") { "Befehl fehlgeschlagen: ${e.stackTraceToString()}" }
            return fail(ErrorCode.INTERNAL, "Interner Fehler.")
        }
        if (result is ResultOf2.Success) afterCommit(ctx)
        return result
    }

    private fun afterCommit(ctx: Ctx) {
        ctx.removedEvents.forEach { game.events.remove(it) }
        ctx.newEvents.forEach { game.events.add(it) }
        val n = game.notifier
        runCatching {
            buildMapChanged(game, ctx)?.let { n.broadcast(it) }
            ctx.dirtyPlayers.forEach { n.send(it, WsEvent.StateChanged) }
            ctx.messages.forEach { (pid, e) -> if (pid == null) n.broadcast(e) else n.send(pid, e) }
            ctx.sessionsToEnd.forEach { (pid, reason) -> n.endSessions(pid, reason) }
            ctx.afterCommit.forEach { it() }
        }.onFailure { KLogger.error("Engine") { "Nachbearbeitung fehlgeschlagen: ${it.stackTraceToString()}" } }
    }

    private fun processDue() {
        while (true) {
            val e = game.events.peek() ?: return
            if (e.dueAt > game.clock.now()) return
            game.events.poll()
            val r = runTx(e.dueAt) {
                ScheduledEventT.deleteWhere { ScheduledEventT.id eq e.id }
                handleEvent(e)
                OK
            }
            if (r is ResultOf2.Failure) {
                KLogger.error("Engine") { "Ereignis ${e.type} (${e.id}) fehlgeschlagen, wird verworfen" }
                runCatching {
                    transaction(game.db.write) { ScheduledEventT.deleteWhere { ScheduledEventT.id eq e.id } }
                }
                // Recurring events must never be lost.
                if (e.type.recurring) runTx(e.dueAt) { scheduleNextRecurring(e.type, e.dueAt); OK }
            }
        }
    }

    private fun rescheduleWakeup() {
        wakeJob?.cancel()
        val next = game.events.peek() ?: return
        val wait = (next.dueAt - game.clock.now()).coerceAtLeast(0)
        wakeJob = scope.launch {
            delay(wait)
            inbox.trySend(Wakeup)
        }
    }

    private fun loadEvents() {
        game.events.clear()
        transaction(game.db.write) {
            ScheduledEventT.selectAll().forEach { row ->
                val type = runCatching { EventType.valueOf(row[ScheduledEventT.type]) }.getOrNull() ?: return@forEach
                val payload = ApiJson.decodeFromString(EventPayload.serializer(), row[ScheduledEventT.payload])
                game.events.add(ScheduledEvent(row[ScheduledEventT.id], row[ScheduledEventT.dueAt], type, payload))
            }
        }
        val missing = EventType.entries.filter { t -> t.recurring && game.events.all().none { it.type == t } }
        if (missing.isNotEmpty()) {
            runTx(game.clock.now()) {
                missing.forEach { t -> scheduleFirstRecurring(t) }
                OK
            }
        }
    }

    private companion object {
        const val THREAD_NAME = "ruinborn-engine"
    }

    private fun errorOut(e: GameError) = HttpOut(e.code.httpStatus, ApiJson.encodeToString(ErrorDto.serializer(), ErrorDto(e.code, e.message)))
}
