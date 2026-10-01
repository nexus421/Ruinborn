package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.AccountT
import bayern.kickner.ruinborn.server.db.AllianceMemberT
import bayern.kickner.ruinborn.server.db.AllianceT
import bayern.kickner.ruinborn.server.db.MapObjectT
import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.server.db.ReportT
import bayern.kickner.ruinborn.shared.ApiJson
import bayern.kickner.ruinborn.shared.dto.MapObjectDto
import bayern.kickner.ruinborn.shared.dto.MapSnapshot
import bayern.kickner.ruinborn.shared.dto.MarchDto
import bayern.kickner.ruinborn.shared.dto.ReportPayload
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.Cosmetic
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.model.ReportKind
import bayern.kickner.ruinborn.shared.dto.BattleReport
import bayern.kickner.ruinborn.shared.dto.GatherReport
import bayern.kickner.ruinborn.shared.dto.ScoutReport
import bayern.kickner.ruinborn.shared.dto.SystemReport
import bayern.kickner.ruinborn.shared.rules.Bonuses
import bayern.kickner.ruinborn.shared.rules.MS_PER_HOUR
import bayern.kickner.ruinborn.shared.rules.floorSafe
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Public player data for map and marches, loaded once per request. */
class PlayerDirectory(now: Long) {
    data class Info(val name: String, val allianceId: Long?, val allianceTag: String?, val skin: Cosmetic, val shielded: Boolean)

    private val tags = AllianceT.selectAll().associate { it[AllianceT.id] to it[AllianceT.tag] }
    private val members = AllianceMemberT.selectAll().associate { it[AllianceMemberT.playerId] to it[AllianceMemberT.allianceId] }
    private val names = AccountT.selectAll().associate { it[AccountT.id] to it[AccountT.username] }
    private val infos: Map<Long, Info> = PlayerT.selectAll().associate { row ->
        val id = row[PlayerT.id]
        val aid = members[id]
        id to Info(
            names[id] ?: "?", aid, aid?.let { tags[it] }, row[PlayerT.skin],
            maxOf(row[PlayerT.shieldUntil], row[PlayerT.protectionUntil]) > now,
        )
    }

    operator fun get(pid: Long?): Info? = pid?.let { infos[it] }
}

fun mapObjectDto(o: MapObjectRow, dir: PlayerDirectory): MapObjectDto {
    val info = dir[o.playerId]
    return MapObjectDto(
        id = o.id, kind = o.kind, x = o.x, y = o.y, level = o.level, resType = o.resType,
        amount = if (o.kind == bayern.kickner.ruinborn.shared.model.MapObjectKind.FIELD) o.amount else null,
        playerId = o.playerId, playerName = info?.name, allianceId = info?.allianceId, allianceTag = info?.allianceTag,
        skin = info?.skin, shielded = info?.shielded ?: false, occupiedByMarchId = o.occupiedBy,
    )
}

/** March DTO. For other players' marches hero and troop details are omitted, `troopCount` is the total. */
fun Ctx.marchDto(m: MarchRow, own: Boolean, dir: PlayerDirectory? = null): MarchDto {
    val info = dir?.get(m.playerId)
    val load = if (own && m.kind != bayern.kickner.ruinborn.shared.model.MarchKind.SCOUT) loadOf(m) else null
    val gatherEnd = if (own && m.state == MarchState.GATHERING) gatherEndOf(m) else null
    return MarchDto(
        id = m.id, playerId = m.playerId, kind = m.kind, state = m.state, fromX = m.fromX, fromY = m.fromY, toX = m.toX, toY = m.toY,
        departAt = m.departAt, arriveAt = m.arriveAt, troopCount = m.units,
        heroId = if (own) m.hero else null,
        playerName = info?.name ?: if (dir == null) accountName(m.playerId) else null,
        allianceId = info?.allianceId, allianceTag = info?.allianceTag, targetId = m.targetId, rallyId = m.rallyId,
        troops = if (own) m.troops else null, cargo = if (own) m.cargo else null,
        gatherEndAt = gatherEnd, gatherRatePerHour = if (own && m.state == MarchState.GATHERING) m.gatherRate else null,
        gatherStartAt = if (own && m.state == MarchState.GATHERING) m.gatherStart else null,
        load = load, hostId = m.hostId,
    )
}

/** Load of a march: troops × load × (1 + research + hero). */
fun Ctx.loadOf(m: MarchRow): Long {
    val bon = Bonuses.research(balance, researchLevels(m.playerId)) + heroBonus(m.playerId, m.hero)
    return rules.load(m.troops, bon[BonusKind.LOAD])
}

fun Ctx.heroBonus(pid: Long, h: bayern.kickner.ruinborn.shared.model.HeroId?): Bonuses {
    h ?: return Bonuses.NONE
    val row = hero(pid, h) ?: return Bonuses.NONE
    return Bonuses.hero(balance, h, row.level)
}

/** Planned end of gathering: min(load, remaining supply) ÷ gathering rate. */
fun Ctx.gatherEndOf(m: MarchRow): Long {
    val field = m.targetId?.let { mapObject(it) } ?: return m.gatherStart
    val total = minOf(loadOf(m), field.amount)
    if (m.gatherRate <= 0.0) return m.gatherStart
    return m.gatherStart + kotlin.math.ceil(total / m.gatherRate * MS_PER_HOUR).toLong()
}

/** Amount gathered at a point in time = min(load, remaining supply, ⌊rate × elapsed time⌋). */
fun Ctx.gatheredAt(m: MarchRow, at: Long): Long {
    val field = m.targetId?.let { mapObject(it) } ?: return 0
    val hours = (at - m.gatherStart).coerceAtLeast(0).toDouble() / MS_PER_HOUR
    return minOf(loadOf(m), field.amount, floorSafe(m.gatherRate * hours))
}

/** Map in a single request (concept: about 150 KB JSON, gzip via Caddy). */
fun Ctx.mapSnapshot(viewer: Long): MapSnapshot {
    val dir = PlayerDirectory(now)
    val objects = MapObjectT.selectAll().map { mapObjectDto(it.toMapObject(), dir) }
    val marches = allMarches().filter { it.state != MarchState.WAITING }.map { marchDto(it, it.playerId == viewer, dir) }
    return MapSnapshot(balance.map.width, balance.map.height, objects, marches)
}

/** After the commit: changed and removed map objects and marches as `map_changed` (public view). */
fun buildMapChanged(game: Game, ctx: Ctx): WsEvent.MapChanged? {
    if (ctx.changedObjects.isEmpty() && ctx.removedObjects.isEmpty() && ctx.changedMarches.isEmpty() && ctx.removedMarches.isEmpty()) return null
    return transaction(game.db.write) {
        val c = Ctx(game, game.clock.now())
        val dir = PlayerDirectory(c.now)
        val objects = (ctx.changedObjects - ctx.removedObjects).mapNotNull { mapObject(it) }.map { mapObjectDto(it, dir) }
        val goneObjects = ctx.removedObjects + (ctx.changedObjects - ctx.removedObjects).filter { mapObject(it) == null }
        val marches = (ctx.changedMarches - ctx.removedMarches).mapNotNull { march(it) }
        val goneMarches = ctx.removedMarches + (ctx.changedMarches - ctx.removedMarches).filter { march(it) == null }
        WsEvent.MapChanged(
            objects = objects,
            marches = marches.filter { it.state != MarchState.WAITING }.map { c.marchDto(it, false, dir) },
            removedObjectIds = goneObjects.toList(),
            removedMarchIds = (goneMarches + marches.filter { it.state == MarchState.WAITING }.map { it.id }).toList(),
        )
    }
}

// ---------------------------------------------------------------- Reports

/** Stored form of a report in the `report.payload` column. */
@kotlinx.serialization.Serializable
data class StoredReport(val title: String, val data: ReportPayload)

private fun ReportPayload.kind(): ReportKind = when (this) {
    is BattleReport -> ReportKind.BATTLE
    is ScoutReport -> ReportKind.SCOUT
    is GatherReport -> ReportKind.GATHER
    is SystemReport -> ReportKind.SYSTEM
}

/** Creates a report and notifies the recipient. */
fun Ctx.addReport(pid: Long, payload: ReportPayload, title: String): Long {
    val kind = payload.kind()
    val json = ApiJson.encodeToString(StoredReport.serializer(), StoredReport(title, payload))
    val id = ReportT.insert {
        it[playerId] = pid
        it[ReportT.kind] = kind
        it[createdAt] = now
        it[read] = false
        it[ReportT.payload] = json
    }[ReportT.id]
    notify(pid, WsEvent.Report(id, kind))
    dirty(pid)
    return id
}

fun unreadReportCount(pid: Long): Int =
    ReportT.selectAll().where { (ReportT.playerId eq pid) and (ReportT.read eq false) }.count().toInt()
