package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.AccountT
import bayern.kickner.ruinborn.server.db.AllianceMemberT
import bayern.kickner.ruinborn.server.db.AllianceT
import bayern.kickner.ruinborn.server.db.BuildingT
import bayern.kickner.ruinborn.server.db.HeroT
import bayern.kickner.ruinborn.server.db.ItemT
import bayern.kickner.ruinborn.server.db.MapObjectT
import bayern.kickner.ruinborn.server.db.MarchT
import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.server.db.ResearchT
import bayern.kickner.ruinborn.server.db.TimerT
import bayern.kickner.ruinborn.server.db.TroopT
import bayern.kickner.ruinborn.shared.ApiJson
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.model.AllianceRank
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.Cosmetic
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.Role
import bayern.kickner.ruinborn.shared.model.Tech
import bayern.kickner.ruinborn.shared.model.TimerKind
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.UnitType
import bayern.kickner.ruinborn.shared.model.normalized
import bayern.kickner.ruinborn.shared.rules.Stock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

// Data access within a running transaction. All functions are deliberately small and direct.

// ---------------------------------------------------------------- JSON helpers

private val troopListSer = ListSerializer(TroopCount.serializer())

fun troopsToJson(t: List<TroopCount>): String = ApiJson.encodeToString(troopListSer, t)
fun troopsFromJson(s: String): List<TroopCount> = ApiJson.decodeFromString(troopListSer, s)
fun costToJson(c: Cost): String = ApiJson.encodeToString(Cost.serializer(), c)
fun costFromJson(s: String): Cost = ApiJson.decodeFromString(Cost.serializer(), s)

// ---------------------------------------------------------------- Account

data class AccountRow(
    val id: Long,
    val username: String,
    val pwHash: String,
    val role: Role,
    val createdAt: Long,
    val lastLoginAt: Long,
    val bannedUntil: Long,
    val banReason: String?,
    val mutedUntil: Long,
)

fun ResultRow.toAccount() = AccountRow(
    this[AccountT.id], this[AccountT.username], this[AccountT.pwHash], this[AccountT.role], this[AccountT.createdAt],
    this[AccountT.lastLoginAt], this[AccountT.bannedUntil], this[AccountT.banReason], this[AccountT.mutedUntil],
)

fun accountById(id: Long): AccountRow? = AccountT.selectAll().where { AccountT.id eq id }.firstOrNull()?.toAccount()

/** Case-insensitive search (the column has COLLATE NOCASE). */
fun accountByName(name: String): AccountRow? = AccountT.selectAll().where { AccountT.username eq name }.firstOrNull()?.toAccount()

fun accountName(id: Long): String = AccountT.selectAll().where { AccountT.id eq id }.firstOrNull()?.get(AccountT.username) ?: "?"

// ---------------------------------------------------------------- Players

data class PlayerRow(
    val id: Long,
    val stock: Stock,
    val protectionUntil: Long,
    val shieldUntil: Long,
    val catchupUntil: Long,
    val lastActiveAt: Long,
    val maxZombieLevel: Int,
    val zombiesDefeated: Long,
    val troopsTrained: Long,
    val skin: Cosmetic,
    val frame: Cosmetic,
    val allianceBlockUntil: Long,
) {
    fun protectedAt(now: Long): Boolean = maxOf(protectionUntil, shieldUntil) > now
}

fun ResultRow.toPlayer() = PlayerRow(
    this[PlayerT.id],
    Stock(this[PlayerT.food], this[PlayerT.wood], this[PlayerT.steel], this[PlayerT.resAt]),
    this[PlayerT.protectionUntil], this[PlayerT.shieldUntil], this[PlayerT.catchupUntil], this[PlayerT.lastActiveAt],
    this[PlayerT.maxZombieLevel], this[PlayerT.zombiesDefeated], this[PlayerT.troopsTrained], this[PlayerT.skin],
    this[PlayerT.frame], this[PlayerT.allianceBlockUntil],
)

fun playerOrNull(id: Long): PlayerRow? = PlayerT.selectAll().where { PlayerT.id eq id }.firstOrNull()?.toPlayer()
fun player(id: Long): PlayerRow = playerOrNull(id) ?: error("Spieler $id fehlt")
fun allPlayerIds(): List<Long> = PlayerT.selectAll().map { it[PlayerT.id] }

fun saveStock(id: Long, s: Stock) {
    PlayerT.update({ PlayerT.id eq id }) {
        it[food] = s.food
        it[wood] = s.wood
        it[steel] = s.steel
        it[resAt] = s.at
    }
}

// ---------------------------------------------------------------- Buildings

data class BuildingRow(val plot: Plot, val type: BuildingType, val level: Int)

fun buildings(pid: Long): Map<Plot, BuildingRow> =
    BuildingT.selectAll().where { BuildingT.playerId eq pid }
        .associate { it[BuildingT.plot] to BuildingRow(it[BuildingT.plot], it[BuildingT.type], it[BuildingT.level]) }

fun setBuilding(pid: Long, plot: Plot, type: BuildingType, level: Int) {
    BuildingT.upsert {
        it[playerId] = pid
        it[BuildingT.plot] = plot
        it[BuildingT.type] = type
        it[BuildingT.level] = level
    }
}

fun removeBuilding(pid: Long, plot: Plot) {
    BuildingT.deleteWhere { (playerId eq pid) and (BuildingT.plot eq plot) }
}

fun Map<Plot, BuildingRow>.levelOf(type: BuildingType): Int = values.filter { it.type == type }.maxOfOrNull { it.level } ?: 0
fun Map<Plot, BuildingRow>.hqLevel(): Int = this[Plot.HQ]?.level ?: 0

// ---------------------------------------------------------------- Research

fun researchLevels(pid: Long): Map<Tech, Int> =
    ResearchT.selectAll().where { ResearchT.playerId eq pid }.associate { it[ResearchT.tech] to it[ResearchT.level] }

fun setResearch(pid: Long, tech: Tech, level: Int) {
    ResearchT.upsert {
        it[playerId] = pid
        it[ResearchT.tech] = tech
        it[ResearchT.level] = level
    }
}

// ---------------------------------------------------------------- Troops

data class TroopRow(val type: UnitType, val tier: Int, val home: Int, val wounded: Int)

fun troops(pid: Long): List<TroopRow> =
    TroopT.selectAll().where { TroopT.playerId eq pid }
        .map { TroopRow(it[TroopT.type], it[TroopT.tier], it[TroopT.home], it[TroopT.wounded]) }
        .sortedWith(compareBy({ it.type.ordinal }, { it.tier }))

private fun troopRow(pid: Long, type: UnitType, tier: Int): TroopRow? =
    TroopT.selectAll().where { (TroopT.playerId eq pid) and (TroopT.type eq type) and (TroopT.tier eq tier) }
        .firstOrNull()?.let { TroopRow(it[TroopT.type], it[TroopT.tier], it[TroopT.home], it[TroopT.wounded]) }

/** Changes troops at home and wounded by the deltas (result never negative, otherwise a programming error). */
fun changeTroops(pid: Long, type: UnitType, tier: Int, homeDelta: Int, woundedDelta: Int) {
    if (homeDelta == 0 && woundedDelta == 0) return
    val cur = troopRow(pid, type, tier) ?: TroopRow(type, tier, 0, 0)
    val home = cur.home + homeDelta
    val wounded = cur.wounded + woundedDelta
    check(home >= 0 && wounded >= 0) { "Truppenbestand würde negativ: $pid $type T$tier" }
    TroopT.upsert {
        it[playerId] = pid
        it[TroopT.type] = type
        it[TroopT.tier] = tier
        it[TroopT.home] = home
        it[TroopT.wounded] = wounded
    }
}

fun homeTroops(pid: Long): List<TroopCount> = troops(pid).filter { it.home > 0 }.map { TroopCount(it.type, it.tier, it.home) }
fun woundedTotal(pid: Long): Long = troops(pid).sumOf { it.wounded.toLong() }

// ---------------------------------------------------------------- Heroes

data class HeroRow(val hero: HeroId, val level: Int, val xp: Long, val marchId: Long?)

fun heroes(pid: Long): List<HeroRow> =
    HeroT.selectAll().where { HeroT.playerId eq pid }
        .map { HeroRow(it[HeroT.hero], it[HeroT.level], it[HeroT.xp], it[HeroT.marchId]) }
        .sortedBy { it.hero.ordinal }

fun hero(pid: Long, h: HeroId): HeroRow? = heroes(pid).firstOrNull { it.hero == h }

fun setHeroMarch(pid: Long, h: HeroId, marchId: Long?) {
    HeroT.update({ (HeroT.playerId eq pid) and (HeroT.hero eq h) }) { it[HeroT.marchId] = marchId }
}

fun setHeroLevel(pid: Long, h: HeroId, level: Int, xp: Long) {
    HeroT.update({ (HeroT.playerId eq pid) and (HeroT.hero eq h) }) {
        it[HeroT.level] = level
        it[HeroT.xp] = xp
    }
}

fun insertHero(pid: Long, h: HeroId) {
    HeroT.insert {
        it[playerId] = pid
        it[hero] = h
        it[level] = 1
        it[xp] = 0
        it[marchId] = null
    }
}

// ---------------------------------------------------------------- Items

fun items(pid: Long): Map<ItemId, Int> =
    ItemT.selectAll().where { (ItemT.playerId eq pid) and (ItemT.count greater 0) }.associate { it[ItemT.item] to it[ItemT.count] }

fun itemCount(pid: Long, item: ItemId): Int = items(pid)[item] ?: 0

fun addItem(pid: Long, item: ItemId, n: Int) {
    if (n == 0) return
    val cur = itemCount(pid, item)
    val next = cur + n
    check(next >= 0) { "Itemzahl würde negativ" }
    if (next == 0) ItemT.deleteWhere { (playerId eq pid) and (ItemT.item eq item) }
    else ItemT.upsert {
        it[playerId] = pid
        it[ItemT.item] = item
        it[count] = next
    }
}

// ---------------------------------------------------------------- Timers

@Serializable
data class TimerPayload(
    val plot: Plot? = null,
    val type: BuildingType? = null,
    val level: Int? = null,
    val tech: Tech? = null,
    val unitType: UnitType? = null,
    val tier: Int? = null,
    val count: Int? = null,
    val units: List<TroopCount> = emptyList(),
    val helpRequested: Boolean = false,
)

data class TimerRow(
    val id: Long,
    val playerId: Long,
    val kind: TimerKind,
    val target: String,
    val payload: TimerPayload,
    val cost: Cost,
    val startedAt: Long,
    val endsAt: Long,
    val totalMs: Long,
    val helpMax: Int,
    val helpCount: Int,
)

fun ResultRow.toTimer() = TimerRow(
    this[TimerT.id], this[TimerT.playerId], this[TimerT.kind], this[TimerT.target],
    ApiJson.decodeFromString(TimerPayload.serializer(), this[TimerT.payload]), costFromJson(this[TimerT.cost]),
    this[TimerT.startedAt], this[TimerT.endsAt], this[TimerT.totalMs], this[TimerT.helpMax], this[TimerT.helpCount],
)

fun timers(pid: Long): List<TimerRow> =
    TimerT.selectAll().where { TimerT.playerId eq pid }.map { it.toTimer() }.sortedWith(compareBy({ it.endsAt }, { it.id }))

fun timerById(id: Long): TimerRow? = TimerT.selectAll().where { TimerT.id eq id }.firstOrNull()?.toTimer()

fun insertTimer(pid: Long, kind: TimerKind, target: String, payload: TimerPayload, cost: Cost, now: Long, durationMs: Long): Long =
    TimerT.insert {
        it[playerId] = pid
        it[TimerT.kind] = kind
        it[TimerT.target] = target
        it[TimerT.payload] = ApiJson.encodeToString(TimerPayload.serializer(), payload)
        it[TimerT.cost] = costToJson(cost)
        it[startedAt] = now
        it[endsAt] = now + durationMs
        it[totalMs] = durationMs
        it[helpMax] = 0
        it[helpCount] = 0
    }[TimerT.id]

fun setTimerEnd(id: Long, endsAt: Long) {
    TimerT.update({ TimerT.id eq id }) { it[TimerT.endsAt] = endsAt }
}

fun deleteTimer(id: Long) {
    TimerT.deleteWhere { TimerT.id eq id }
}

// ---------------------------------------------------------------- Map and marches

data class MapObjectRow(
    val id: Long,
    val kind: MapObjectKind,
    val x: Int,
    val y: Int,
    val zone: Int,
    val level: Int,
    val resType: Resource?,
    val amount: Long,
    val playerId: Long?,
    val occupiedBy: Long?,
)

fun ResultRow.toMapObject() = MapObjectRow(
    this[MapObjectT.id], this[MapObjectT.kind], this[MapObjectT.x], this[MapObjectT.y], this[MapObjectT.zone],
    this[MapObjectT.level], this[MapObjectT.resType], this[MapObjectT.amount], this[MapObjectT.playerId], this[MapObjectT.occupiedBy],
)

fun mapObject(id: Long): MapObjectRow? = MapObjectT.selectAll().where { MapObjectT.id eq id }.firstOrNull()?.toMapObject()

fun mapObjectAt(x: Int, y: Int): MapObjectRow? =
    MapObjectT.selectAll().where { (MapObjectT.x eq x) and (MapObjectT.y eq y) }.firstOrNull()?.toMapObject()

fun baseOf(pid: Long): MapObjectRow? =
    MapObjectT.selectAll().where { (MapObjectT.kind eq MapObjectKind.BASE) and (MapObjectT.playerId eq pid) }.firstOrNull()?.toMapObject()

data class MarchRow(
    val id: Long,
    val playerId: Long,
    val kind: MarchKind,
    val hero: HeroId?,
    val troops: List<TroopCount>,
    val fromX: Int,
    val fromY: Int,
    val toX: Int,
    val toY: Int,
    val targetId: Long?,
    val state: MarchState,
    val departAt: Long,
    val arriveAt: Long,
    val gatherStart: Long,
    val gatherRate: Double,
    val cargo: Cost,
    val rallyId: Long?,
    val hostId: Long?,
) {
    val units: Int get() = troops.sumOf { it.count }

    /** Current position, linear from start and arrival time (as a decimal number). */
    fun positionAt(now: Long): Pair<Double, Double> {
        if (state != MarchState.OUTBOUND && state != MarchState.RETURNING) return toX.toDouble() to toY.toDouble()
        val total = (arriveAt - departAt).coerceAtLeast(1)
        val f = ((now - departAt).toDouble() / total).coerceIn(0.0, 1.0)
        return (fromX + (toX - fromX) * f) to (fromY + (toY - fromY) * f)
    }
}

fun ResultRow.toMarch() = MarchRow(
    this[MarchT.id], this[MarchT.playerId], this[MarchT.kind], this[MarchT.hero], troopsFromJson(this[MarchT.troops]),
    this[MarchT.fromX], this[MarchT.fromY], this[MarchT.toX], this[MarchT.toY], this[MarchT.targetId], this[MarchT.state],
    this[MarchT.departAt], this[MarchT.arriveAt], this[MarchT.gatherStart], this[MarchT.gatherRate], costFromJson(this[MarchT.cargo]),
    this[MarchT.rallyId], this[MarchT.hostId],
)

fun march(id: Long): MarchRow? = MarchT.selectAll().where { MarchT.id eq id }.firstOrNull()?.toMarch()
fun marchesOf(pid: Long): List<MarchRow> = MarchT.selectAll().where { MarchT.playerId eq pid }.map { it.toMarch() }.sortedBy { it.id }
fun allMarches(): List<MarchRow> = MarchT.selectAll().map { it.toMarch() }.sortedBy { it.id }

fun setMarchTroops(id: Long, t: List<TroopCount>) {
    MarchT.update({ MarchT.id eq id }) { it[troops] = troopsToJson(t.normalized()) }
}

// ---------------------------------------------------------------- Alliance

data class MembershipRow(val playerId: Long, val allianceId: Long, val rank: AllianceRank, val joinedAt: Long)

fun membership(pid: Long): MembershipRow? =
    AllianceMemberT.selectAll().where { AllianceMemberT.playerId eq pid }.firstOrNull()?.let {
        MembershipRow(it[AllianceMemberT.playerId], it[AllianceMemberT.allianceId], it[AllianceMemberT.rank], it[AllianceMemberT.joinedAt])
    }

fun allianceIdOf(pid: Long): Long? = membership(pid)?.allianceId

fun sameAlliance(a: Long, b: Long): Boolean {
    val x = allianceIdOf(a) ?: return false
    return x == allianceIdOf(b)
}

fun allianceMembers(allianceId: Long): List<MembershipRow> =
    AllianceMemberT.selectAll().where { AllianceMemberT.allianceId eq allianceId }.map {
        MembershipRow(it[AllianceMemberT.playerId], it[AllianceMemberT.allianceId], it[AllianceMemberT.rank], it[AllianceMemberT.joinedAt])
    }

fun allianceTag(allianceId: Long): String? = AllianceT.selectAll().where { AllianceT.id eq allianceId }.firstOrNull()?.get(AllianceT.tag)
