package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.ServerConfig
import bayern.kickner.ruinborn.server.db.Db
import bayern.kickner.ruinborn.server.db.ScheduledEventT
import bayern.kickner.ruinborn.shared.ApiJson
import bayern.kickner.ruinborn.shared.balance.Balance
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.rules.Rules
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.random.Random

/** Receiver for WebSocket messages. The engine only calls it after the commit. */
interface Notifier {
    fun send(playerId: Long, event: WsEvent)
    fun broadcast(event: WsEvent)
    fun endSessions(playerId: Long, reason: String)

    object None : Notifier {
        override fun send(playerId: Long, event: WsEvent) {}
        override fun broadcast(event: WsEvent) {}
        override fun endSessions(playerId: Long, reason: String) {}
    }
}

/**
 * Everything the game logic needs: configuration, balance, rules, database, clock, randomness and notification.
 * In-memory state exists only in the event queue (can be rebuilt from the database at any time) and
 * in short-lived rate limits (chat).
 */
class Game(
    val config: ServerConfig,
    val balance: Balance,
    val db: Db,
    val clock: Clock,
    val random: Random,
    var notifier: Notifier = Notifier.None,
) {
    val rules = Rules(balance, config.gameSpeed)
    val events = EventQueue()

    /** Last chat message per player (engine thread only). */
    val lastChatAt = HashMap<Long, Long>()

    /** Starts a backup outside the engine. Set by [bayern.kickner.ruinborn.server.App]. */
    var backupTrigger: (dueAt: Long) -> Unit = {}
}

/**
 * Context of a single command or event inside an Exposed transaction of the engine.
 * [now] is the server time for commands and the due time for scheduled events.
 *
 * Side effects outside the database (WebSocket, queue) are collected and only executed after the commit.
 * They are discarded on rollback.
 */
class Ctx(val game: Game, val now: Long) {
    val rules: Rules get() = game.rules
    val balance: Balance get() = game.balance
    val config: ServerConfig get() = game.config
    val random: Random get() = game.random

    internal val newEvents = mutableListOf<ScheduledEvent>()
    internal val removedEvents = mutableSetOf<Long>()
    internal val messages = mutableListOf<Pair<Long?, WsEvent>>()
    internal val sessionsToEnd = mutableListOf<Pair<Long, String>>()
    internal val afterCommit = mutableListOf<() -> Unit>()

    /** Players whose state has changed (they receive `state_changed`). */
    val dirtyPlayers = mutableSetOf<Long>()
    val changedObjects = mutableSetOf<Long>()
    val removedObjects = mutableSetOf<Long>()
    val changedMarches = mutableSetOf<Long>()
    val removedMarches = mutableSetOf<Long>()

    /** Players already settled up to [now]. */
    val settled = mutableSetOf<Long>()

    fun notify(playerId: Long, event: WsEvent) {
        messages += playerId to event
    }

    fun broadcast(event: WsEvent) {
        messages += null to event
    }

    fun dirty(vararg playerIds: Long) {
        playerIds.forEach { dirtyPlayers += it }
    }

    fun endSessions(playerId: Long, reason: String) {
        sessionsToEnd += playerId to reason
    }

    fun onCommit(action: () -> Unit) {
        afterCommit += action
    }

    fun schedule(type: EventType, dueAt: Long, payload: EventPayload = EventPayload()): Long {
        val json = ApiJson.encodeToString(EventPayload.serializer(), payload)
        val id = ScheduledEventT.insert {
            it[ScheduledEventT.dueAt] = dueAt
            it[ScheduledEventT.type] = type.name
            it[ScheduledEventT.payload] = json
        }[ScheduledEventT.id]
        newEvents += ScheduledEvent(id, dueAt, type, payload)
        return id
    }

    /** Removes all scheduled events of a type whose payload satisfies [match]. */
    fun cancelEvents(type: EventType, match: (EventPayload) -> Boolean) {
        ScheduledEventT.selectAll().where { ScheduledEventT.type eq type.name }.forEach { row ->
            val p = ApiJson.decodeFromString(EventPayload.serializer(), row[ScheduledEventT.payload])
            if (match(p)) {
                val id = row[ScheduledEventT.id]
                ScheduledEventT.deleteWhere { ScheduledEventT.id eq id }
                removedEvents += id
            }
        }
        newEvents.removeAll { it.type == type && match(it.payload) }
    }

    fun cancelMarchEvents(marchId: Long) {
        listOf(EventType.MARCH_ARRIVE, EventType.MARCH_HOME, EventType.GATHER_END).forEach { t -> cancelEvents(t) { it.marchId == marchId } }
    }
}
