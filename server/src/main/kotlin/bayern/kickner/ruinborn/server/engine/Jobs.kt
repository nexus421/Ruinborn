package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.AllianceGiftT
import bayern.kickner.ruinborn.server.db.AllianceRequestT
import bayern.kickner.ruinborn.server.db.ChatMessageT
import bayern.kickner.ruinborn.server.db.DailyProgressT
import bayern.kickner.ruinborn.server.db.DefenseLossT
import bayern.kickner.ruinborn.server.db.MapObjectT
import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.server.db.ProcessedRequestT
import bayern.kickner.ruinborn.server.db.RallyT
import bayern.kickner.ruinborn.server.db.ReportT
import bayern.kickner.ruinborn.server.db.ServerMetaT
import bayern.kickner.ruinborn.server.db.SessionT
import bayern.kickner.ruinborn.shared.balance.SpawnRange
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.RallyState
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.rules.MS_PER_HOUR
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.LocalTime

// ---------------------------------------------------------------- Event dispatch

fun Ctx.handleEvent(e: ScheduledEvent) {
    when (e.type) {
        EventType.MARCH_ARRIVE -> onMarchArrive(e.payload.marchId!!, e.payload.rallyId)
        EventType.MARCH_HOME -> onMarchHome(e.payload.marchId!!)
        EventType.GATHER_END -> onGatherEnd(e.payload.marchId!!)
        EventType.RALLY_LAUNCH -> onRallyLaunch(e.payload.rallyId!!)
        EventType.SHIELD_END -> onShieldEnd(e.payload.playerId!!)
        EventType.SPAWN -> onSpawn()
        EventType.DAILY_RESET -> onDailyReset()
        EventType.BACKUP -> game.backupTrigger(now)
        EventType.CLEANUP -> onCleanup()
    }
    if (e.type.recurring) scheduleNextRecurring(e.type, e.dueAt)
}

private fun Ctx.localTimeOf(type: EventType): LocalTime = when (type) {
    EventType.DAILY_RESET -> config.resetTime
    EventType.BACKUP -> LocalTime.parse(config.backupTime)
    EventType.CLEANUP -> LocalTime.parse(config.cleanupTime)
    else -> error("$type ist kein täglicher Job")
}

/** First scheduling of a recurring event. The spawn runs immediately so the map is populated. */
fun Ctx.scheduleFirstRecurring(type: EventType) {
    val due = if (type == EventType.SPAWN) now else GameTime.nextOccurrence(now, config.zone, localTimeOf(type))
    schedule(type, due)
}

/**
 * Recurring events reschedule themselves. Spawn every 5 min real time (after downtime not once for every
 * missed round). Daily jobs at the next local time after the due time.
 */
fun Ctx.scheduleNextRecurring(type: EventType, dueAt: Long) {
    val next = if (type == EventType.SPAWN) maxOf(dueAt + balance.spawn.intervalSec * 1000, game.clock.now())
    else GameTime.nextOccurrence(dueAt, config.zone, localTimeOf(type))
    schedule(type, next)
}

// ---------------------------------------------------------------- Spawn

/** Fills each zone and object type up to the target count. Skips after [maxAttempts] failed attempts. */
fun Ctx.onSpawn() {
    val occupied = MapObjectT.selectAll().map { it[MapObjectT.x] to it[MapObjectT.y] }.toHashSet()
    val counts = MapObjectT.selectAll().groupBy { it[MapObjectT.kind] to it[MapObjectT.zone] }.mapValues { it.value.size }
    val zoneTiles = (1..3).associateWith { z ->
        buildList { for (x in 0 until balance.map.width) for (y in 0 until balance.map.height) if (rules.zoneOf(x, y) == z) add(x to y) }
    }
    balance.spawn.zones.forEach { zs ->
        val tiles = zoneTiles.getValue(zs.zone)
        fun fill(kind: MapObjectKind, range: SpawnRange) {
            val need = range.count - (counts[kind to zs.zone] ?: 0)
            repeat(need.coerceAtLeast(0)) {
                var placed = false
                var attempts = 0
                while (placed.not() && attempts < balance.spawn.maxAttempts) {
                    attempts++
                    val (x, y) = tiles[random.nextInt(tiles.size)]
                    if ((x to y) in occupied) continue
                    val level = range.minLevel + random.nextInt(range.maxLevel - range.minLevel + 1)
                    val res = if (kind == MapObjectKind.FIELD) Resource.entries[random.nextInt(Resource.entries.size)] else null
                    val id = MapObjectT.insert {
                        it[MapObjectT.kind] = kind
                        it[MapObjectT.x] = x
                        it[MapObjectT.y] = y
                        it[zone] = zs.zone
                        it[MapObjectT.level] = level
                        it[resType] = res
                        it[amount] = if (res != null) rules.fieldStock(level, res) else 0
                        it[playerId] = null
                        it[occupiedBy] = null
                    }[MapObjectT.id]
                    occupied += x to y
                    changedObjects += id
                    placed = true
                }
            }
        }
        fill(MapObjectKind.ZOMBIE, zs.zombies)
        fill(MapObjectKind.NEST, zs.nests)
        fill(MapObjectKind.FIELD, zs.fields)
    }
}

// ---------------------------------------------------------------- Daily reset

/** Median HQ level of all active players (login within the last 7 days), rounded down for an even count. */
fun Ctx.computeHqMedian(): Int? {
    val limit = now - rules.daysMs(balance.catchup.activeDays)
    val active = PlayerT.selectAll().filter { it[PlayerT.lastActiveAt] >= limit }.map { hqOf(it[PlayerT.id]) }.sorted()
    if (active.isEmpty()) return null
    return if (active.size % 2 == 1) active[active.size / 2] else (active[active.size / 2 - 1] + active[active.size / 2]) / 2
}

fun Ctx.onDailyReset() {
    val ids = allPlayerIds()
    ids.forEach { settle(it) }
    val median = computeHqMedian()
    ServerMetaT.upsert {
        it[key] = "hq_median"
        it[value] = median?.toString() ?: ""
    }
    ids.forEach { applyCatchupFor(it, median) }
    // Inactivity shield after 7 days without login, until the next login.
    val inactiveLimit = now - rules.daysMs(balance.protection.inactivityDays)
    PlayerT.update({ PlayerT.lastActiveAt less inactiveLimit }) { it[shieldUntil] = INACTIVITY_SHIELD_UNTIL }
    transferInactiveLeaders()
    ids.forEach { dirty(it) }
    changedObjects += MapObjectT.selectAll().where { MapObjectT.kind eq MapObjectKind.BASE }.map { it[MapObjectT.id] }
}

// ---------------------------------------------------------------- Cleanup

fun Ctx.onCleanup() {
    val reportLimit = now - rules.daysMs(balance.reports.retentionDays)
    ReportT.deleteWhere { createdAt less reportLimit }
    val chatLimit = now - rules.daysMs(balance.chat.historyDays)
    ChatMessageT.deleteWhere { createdAt less chatLimit }
    SessionT.deleteWhere { expiresAt lessEq now }
    val requestLimit = now - rules.daysMs(balance.alliance.requestExpiryDays)
    AllianceRequestT.deleteWhere { createdAt less requestLimit }
    AllianceGiftT.deleteWhere { expiresAt lessEq now }
    ProcessedRequestT.deleteWhere { createdAt less (now - 24 * MS_PER_HOUR) }
    DefenseLossT.deleteWhere { at less (now - rules.hoursMs(balance.protection.recoveryWindowHours)) }
    val today = dayKey()
    DailyProgressT.deleteWhere { day neq today }
    RallyT.deleteWhere { state inList listOf(RallyState.DONE, RallyState.CANCELLED) }
    game.lastChatAt.clear()
}

