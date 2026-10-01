package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.MapObjectT
import bayern.kickner.ruinborn.server.db.MarchT
import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.server.db.RallyT
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.dto.GatherReport
import bayern.kickner.ruinborn.shared.dto.IncomingDto
import bayern.kickner.ruinborn.shared.dto.MarchRequest
import bayern.kickner.ruinborn.shared.dto.ScoutReport
import bayern.kickner.ruinborn.shared.dto.ScoutResource
import bayern.kickner.ruinborn.shared.dto.SystemReport
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.model.AchievementKind
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.model.RallyState
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.normalized
import bayern.kickner.ruinborn.shared.rules.Bonuses
import bayern.kickner.ruinborn.shared.rules.floorSafe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Helpers

/** Checks hero and troops of a new march (without deducting them). */
fun Ctx.validateArmy(pid: Long, heroId: HeroId?, troops: List<TroopCount>): Res<List<TroopCount>> {
    val h = heroId ?: return fail(ErrorCode.VALIDATION, "Bitte einen Helden wählen.")
    val row = hero(pid, h) ?: return fail(ErrorCode.REQUIREMENT_NOT_MET, "Der Held ist noch nicht freigeschaltet.")
    ensure(row.marchId == null, ErrorCode.HERO_BUSY) { "Der Held ist bereits unterwegs." }?.let { return it }
    ensure(troops.all { it.count >= 0 && it.tier in 1..balance.tiers.maxTier }, ErrorCode.VALIDATION) { "Ungültige Truppenangaben." }?.let { return it }
    val army = troops.normalized()
    val total = army.sumOf { it.count }
    ensure(total >= balance.marches.minUnits, ErrorCode.MARCH_SIZE) { "Ein Marsch braucht mindestens eine Einheit." }?.let { return it }
    val max = marchSizeOf(pid)
    ensure(total <= max, ErrorCode.MARCH_SIZE) { "Die Marschgröße ist $max Einheiten." }?.let { return it }
    val home = homeTroops(pid)
    army.forEach { t ->
        val have = home.firstOrNull { it.type == t.type && it.tier == t.tier }?.count ?: 0
        ensure(have >= t.count, ErrorCode.NOT_ENOUGH_TROOPS) { "Nicht genug Truppen zu Hause." }?.let { return it }
    }
    return ok(army)
}

/** March speed bonus of a player (logistics). */
fun Ctx.marchSpeedBonus(pid: Long): Double = Bonuses.research(balance, researchLevels(pid))[BonusKind.MARCH_SPEED]

fun Ctx.travelMs(pid: Long, fromX: Double, fromY: Double, toX: Int, toY: Int, secPerTile: Double): Long {
    val dx = fromX - toX
    val dy = fromY - toY
    return rules.marchTimeMs(kotlin.math.sqrt(dx * dx + dy * dy), secPerTile, marchSpeedBonus(pid))
}

fun Ctx.secPerTileOf(m: MarchRow): Double =
    if (m.kind == MarchKind.SCOUT || m.troops.isEmpty()) balance.scouting.secPerTile else rules.slowestSecPerTile(m.troops)

/** Starts an own PvP action: peace shield and beginner protection end immediately. */
fun Ctx.endOwnProtection(pid: Long) {
    val p = player(pid)
    if (p.shieldUntil > now || p.protectionUntil > now) {
        PlayerT.update({ PlayerT.id eq pid }) {
            if (p.shieldUntil > now && p.shieldUntil != INACTIVITY_SHIELD_UNTIL) it[shieldUntil] = now
            if (p.protectionUntil > now) it[protectionUntil] = now
        }
        cancelEvents(EventType.SHIELD_END) { it.playerId == pid }
        changedObjects += listOfNotNull(baseOf(pid)?.id)
        dirty(pid)
    }
}

fun Ctx.isProtected(pid: Long): Boolean = player(pid).protectedAt(now)

fun Ctx.insertMarch(
    pid: Long, kind: MarchKind, hero: HeroId?, troops: List<TroopCount>, fromX: Int, fromY: Int, toX: Int, toY: Int,
    targetId: Long?, state: MarchState, arriveAt: Long, rallyId: Long? = null, hostId: Long? = null,
): Long {
    val id = MarchT.insert {
        it[playerId] = pid
        it[MarchT.kind] = kind
        it[MarchT.hero] = hero
        it[MarchT.troops] = troopsToJson(troops.normalized())
        it[MarchT.fromX] = fromX
        it[MarchT.fromY] = fromY
        it[MarchT.toX] = toX
        it[MarchT.toY] = toY
        it[MarchT.targetId] = targetId
        it[MarchT.state] = state
        it[departAt] = now
        it[MarchT.arriveAt] = arriveAt
        it[gatherStart] = 0
        it[gatherRate] = 0.0
        it[cargo] = costToJson(Cost.ZERO)
        it[MarchT.rallyId] = rallyId
        it[MarchT.hostId] = hostId
    }[MarchT.id]
    troops.normalized().forEach { changeTroops(pid, it.type, it.tier, -it.count, 0) }
    if (hero != null) setHeroMarch(pid, hero, id)
    changedMarches += id
    dirty(pid)
    return id
}

// ---------------------------------------------------------------- Starting a march

fun Ctx.startMarch(pid: Long, req: MarchRequest): Res<Long> {
    settle(pid)
    ensure(rules.inBounds(req.x, req.y), ErrorCode.VALIDATION) { "Das Feld liegt außerhalb der Karte." }?.let { return it }
    val base = baseOf(pid) ?: return fail(ErrorCode.INTERNAL, "Basis fehlt.")
    val target = mapObjectAt(req.x, req.y) ?: return fail(ErrorCode.TARGET_INVALID, "Auf diesem Feld ist nichts.")
    var hostId: Long? = null
    var pvp = false

    when (req.kind) {
        MarchKind.SCOUT -> {
            ensure(target.kind == MapObjectKind.BASE && target.playerId != pid, ErrorCode.TARGET_INVALID) { "Aufklären geht nur bei fremden Basen." }?.let { return it }
            ensure(sameAlliance(pid, target.playerId!!).not(), ErrorCode.TARGET_INVALID) { "Allianzmitglieder können nicht aufgeklärt werden." }?.let { return it }
            ensure(isProtected(target.playerId).not(), ErrorCode.TARGET_SHIELDED) { "Die Basis ist geschützt." }?.let { return it }
            val scouts = marchesOf(pid).count { it.kind == MarchKind.SCOUT }
            ensure(scouts < balance.marches.maxScouts, ErrorCode.MARCH_LIMIT) { "Es läuft bereits eine Aufklärung." }?.let { return it }
            val cost = Cost(food = balance.scouting.foodCost)
            ensure(player(pid).stock.canAfford(cost), ErrorCode.NOT_ENOUGH_RESOURCES) { "Aufklärung kostet ${balance.scouting.foodCost} Nahrung." }?.let { return it }
            saveStock(pid, player(pid).stock - cost)
            pvp = true
        }
        MarchKind.ATTACK -> when (target.kind) {
            MapObjectKind.ZOMBIE -> {
                val allowed = player(pid).maxZombieLevel + 1
                ensure(target.level <= allowed, ErrorCode.ZOMBIE_LEVEL_LOCKED) { "Du kannst Zombies bis Stufe $allowed angreifen." }?.let { return it }
            }
            MapObjectKind.BASE -> {
                ensure(target.playerId != pid, ErrorCode.TARGET_INVALID) { "Die eigene Basis kann nicht angegriffen werden." }?.let { return it }
                ensure(sameAlliance(pid, target.playerId!!).not(), ErrorCode.TARGET_INVALID) { "Allianzmitglieder können nicht angegriffen werden." }?.let { return it }
                ensure(isProtected(target.playerId).not(), ErrorCode.TARGET_SHIELDED) { "Die Basis ist geschützt." }?.let { return it }
                pvp = true
            }
            MapObjectKind.FIELD -> {
                val g = target.occupiedBy?.let { march(it) }
                ensure(g != null && g.playerId != pid && sameAlliance(pid, g.playerId).not(), ErrorCode.TARGET_INVALID) {
                    "Hier sammelt kein fremder Marsch."
                }?.let { return it }
                pvp = true
            }
            MapObjectKind.NEST -> return fail(ErrorCode.TARGET_INVALID, "Zombie-Nester sind nur per Sammelangriff angreifbar.")
        }
        MarchKind.GATHER -> {
            ensure(target.kind == MapObjectKind.FIELD, ErrorCode.TARGET_INVALID) { "Sammeln geht nur auf Ressourcenfeldern." }?.let { return it }
            ensure(target.occupiedBy == null, ErrorCode.TARGET_INVALID) { "Das Feld wird bereits besammelt." }?.let { return it }
        }
        MarchKind.REINFORCE -> {
            val host = target.playerId
            ensure(target.kind == MapObjectKind.BASE && host != null && host != pid && sameAlliance(pid, host), ErrorCode.TARGET_INVALID) {
                "Verstärken geht nur bei Allianzmitgliedern."
            }?.let { return it }
            ensure(marchesOf(pid).none { it.kind == MarchKind.REINFORCE && it.hostId == host }, ErrorCode.VALIDATION) {
                "Du verstärkst diese Basis bereits."
            }?.let { return it }
            hostId = host
        }
        MarchKind.RALLY -> return fail(ErrorCode.VALIDATION, "Sammelangriffe werden über das Allianzmenü gestartet.")
    }

    val army = if (req.kind == MarchKind.SCOUT) emptyList() else validateArmy(pid, req.heroId, req.troops).orReturn { return it }
    if (req.kind == MarchKind.REINFORCE) {
        val cap = rules.reinforceCapacity(buildings(hostId!!).levelOf(BuildingType.ALLIANCE_CENTER))
        ensure(cap > 0, ErrorCode.REQUIREMENT_NOT_MET) { "Die Basis hat kein Allianzzentrum." }?.let { return it }
        ensure(stationedUnitsAt(hostId) + army.sumOf { it.count } <= cap, ErrorCode.REQUIREMENT_NOT_MET) { "Der Verstärkungsplatz reicht nicht." }?.let { return it }
    }
    if (pvp) endOwnProtection(pid)

    val spt = if (req.kind == MarchKind.SCOUT) balance.scouting.secPerTile else rules.slowestSecPerTile(army)
    val arrive = now + travelMs(pid, base.x.toDouble(), base.y.toDouble(), target.x, target.y, spt)
    val hero = if (req.kind == MarchKind.SCOUT) null else req.heroId
    val id = insertMarch(pid, req.kind, hero, army, base.x, base.y, target.x, target.y, target.id, MarchState.OUTBOUND, arrive, hostId = hostId)
    schedule(EventType.MARCH_ARRIVE, arrive, EventPayload(marchId = id))
    announceIncoming(id)
    return ok(id)
}

/** Affected players see attacker, march type and arrival time immediately (without troop details). */
fun Ctx.announceIncoming(marchId: Long) {
    val m = march(marchId) ?: return
    val victim = victimOf(m) ?: return
    notify(victim, WsEvent.Incoming(m.id, accountName(m.playerId), m.kind, m.arriveAt))
    dirty(victim)
}

/** Player a march is directed at (base or gathering march), otherwise null. */
fun Ctx.victimOf(m: MarchRow): Long? {
    if (m.state != MarchState.OUTBOUND) return null
    if (m.kind != MarchKind.ATTACK && m.kind != MarchKind.SCOUT && m.kind != MarchKind.RALLY) return null
    if (m.kind == MarchKind.RALLY && m.hostId != null) return null
    val t = m.targetId?.let { mapObject(it) } ?: return null
    return when (t.kind) {
        MapObjectKind.BASE -> t.playerId?.takeIf { it != m.playerId }
        MapObjectKind.FIELD -> t.occupiedBy?.let { march(it) }?.playerId?.takeIf { it != m.playerId }
        else -> null
    }
}

/** Incoming hostile marches and rallies against a player. */
fun Ctx.incomingFor(pid: Long): List<IncomingDto> {
    val result = mutableListOf<IncomingDto>()
    allMarches().filter { it.playerId != pid && it.state == MarchState.OUTBOUND }.forEach { m ->
        if (m.kind == MarchKind.RALLY && m.rallyId != null) {
            // Running rally: only the starter's march carries the event.
            val r = RallyT.selectAll().where { RallyT.id eq m.rallyId }.firstOrNull() ?: return@forEach
            if (r[RallyT.leaderId] != m.playerId || m.hostId != null) return@forEach
        }
        if (victimOf(m) == pid) result += IncomingDto(m.id, accountName(m.playerId), m.kind, m.arriveAt, rallyId = m.rallyId)
    }
    val myBase = baseOf(pid)?.id
    if (myBase != null) {
        RallyT.selectAll().where { RallyT.state eq RallyState.WAITING }.filter { it[RallyT.targetId] == myBase }.forEach { r ->
            val leader = r[RallyT.leaderId]
            val leaderMarch = marchesOf(leader).firstOrNull { it.rallyId == r[RallyT.id] && it.state == MarchState.WAITING }
            result += IncomingDto(leaderMarch?.id ?: 0, accountName(leader), MarchKind.RALLY, r[RallyT.launchAt], r[RallyT.launchAt], r[RallyT.id])
        }
    }
    return result.sortedBy { it.arriveAt }
}

fun Ctx.stationedUnitsAt(hostId: Long): Long =
    allMarches().filter { it.kind == MarchKind.REINFORCE && it.hostId == hostId && it.state == MarchState.STATIONED }.sumOf { it.units.toLong() }

// ---------------------------------------------------------------- Return trip and arrival home

/** Starts the return trip from the current position. Without [durationMs] the travel time is calculated from the distance. */
fun Ctx.startReturn(marchId: Long, fromX: Double, fromY: Double, durationMs: Long? = null) {
    val m = march(marchId) ?: return
    val base = baseOf(m.playerId) ?: return
    cancelMarchEvents(m.id)
    val dur = durationMs ?: travelMs(m.playerId, fromX, fromY, base.x, base.y, secPerTileOf(m))
    val arrive = now + maxOf(1_000L, dur)
    MarchT.update({ MarchT.id eq marchId }) {
        it[state] = MarchState.RETURNING
        it[MarchT.fromX] = fromX.roundToInt()
        it[MarchT.fromY] = fromY.roundToInt()
        it[toX] = base.x
        it[toY] = base.y
        it[departAt] = now
        it[arriveAt] = arrive
        it[targetId] = null
        it[hostId] = null
        it[rallyId] = null
    }
    schedule(EventType.MARCH_HOME, arrive, EventPayload(marchId = marchId))
    changedMarches += marchId
    dirty(m.playerId)
}

/** Arrival home: troops and cargo go home, hero is free, march deleted. */
fun Ctx.onMarchHome(marchId: Long) {
    val m = march(marchId) ?: return
    val pid = m.playerId
    settle(pid)
    m.troops.forEach { changeTroops(pid, it.type, it.tier, it.count, 0) }
    if (m.hero != null) setHeroMarch(pid, m.hero, null)
    credit(pid, m.cargo)
    MarchT.deleteWhere { MarchT.id eq marchId }
    removedMarches += marchId
    if (m.kind == MarchKind.GATHER && m.cargo.total > 0) {
        val res = Resource.entries.first { m.cargo[it] > 0 }
        addReport(pid, GatherReport(now, m.fromX, m.fromY, res, m.cargo[res]), "Sammeln: ${m.cargo[res]} heimgebracht")
        addDaily(pid, DailyTask.GATHERER, m.cargo.total)
        achievementEvent(pid, AchievementKind.GATHER_HOME)
    }
    dirty(pid)
}

/** Deletes a march immediately (troops return), e.g. the starter's waiting march on cancellation. */
fun Ctx.dissolveMarch(marchId: Long) {
    val m = march(marchId) ?: return
    cancelMarchEvents(marchId)
    m.troops.forEach { changeTroops(m.playerId, it.type, it.tier, it.count, 0) }
    if (m.hero != null) setHeroMarch(m.playerId, m.hero, null)
    credit(m.playerId, m.cargo)
    MarchT.deleteWhere { MarchT.id eq marchId }
    removedMarches += marchId
    dirty(m.playerId)
}

/** Recall (concept section 7): from OUTBOUND back for as long as it has already traveled, otherwise immediately from the current position. */
fun Ctx.recall(pid: Long, marchId: Long): Res<Unit> {
    settle(pid)
    val m = march(marchId)
    ensure(m != null && m.playerId == pid, ErrorCode.NOT_FOUND) { "Marsch nicht gefunden." }?.let { return it }
    m!!
    when (m.state) {
        MarchState.OUTBOUND -> {
            if (m.kind == MarchKind.RALLY && m.hostId == null) return fail(ErrorCode.VALIDATION, "Nach dem Start eines Sammelangriffs ist kein Rückruf möglich.")
            val (x, y) = m.positionAt(now)
            startReturn(m.id, x, y, now - m.departAt)
        }
        MarchState.GATHERING -> {
            finishGathering(m)
            startReturn(m.id, m.toX.toDouble(), m.toY.toDouble())
        }
        MarchState.STATIONED -> startReturn(m.id, m.toX.toDouble(), m.toY.toDouble())
        MarchState.WAITING -> {
            val rally = m.rallyId?.let { rallyRow(it) }
            if (rally != null && rally.leaderId == pid) return cancelRally(pid, rally.id)
            startReturn(m.id, m.toX.toDouble(), m.toY.toDouble())
            m.rallyId?.let { notifyAlliance(rally?.allianceId) }
        }
        MarchState.RETURNING -> return fail(ErrorCode.VALIDATION, "Der Marsch ist bereits auf dem Rückweg.")
    }
    return OK
}

/** Host sends a reinforcement home. */
fun Ctx.sendHome(hostPid: Long, marchId: Long): Res<Unit> {
    val m = march(marchId)
    ensure(m != null && m.kind == MarchKind.REINFORCE && m.hostId == hostPid && m.state == MarchState.STATIONED, ErrorCode.NOT_FOUND) {
        "Keine Verstärkung in deiner Basis."
    }?.let { return it }
    startReturn(m!!.id, m.toX.toDouble(), m.toY.toDouble())
    return OK
}

// ---------------------------------------------------------------- Arrival

fun Ctx.onMarchArrive(marchId: Long, rallyId: Long?) {
    val m = march(marchId) ?: return
    if (m.state != MarchState.OUTBOUND) return
    settle(m.playerId)
    if (m.kind == MarchKind.RALLY) {
        if (rallyId != null) onRallyArrive(rallyId) else onRallyJoinArrive(m)
        return
    }
    val target = m.targetId?.let { mapObject(it) }?.takeIf { it.x == m.toX && it.y == m.toY }
    val done = when (m.kind) {
        MarchKind.GATHER -> target != null && target.kind == MapObjectKind.FIELD && target.occupiedBy == null && startGathering(m, target)
        MarchKind.SCOUT -> target != null && validPlayerTarget(m.playerId, target) && scout(m, target)
        MarchKind.ATTACK -> target != null && when (target.kind) {
            MapObjectKind.ZOMBIE -> battleZombie(m, target)
            MapObjectKind.BASE -> validPlayerTarget(m.playerId, target) && battleBase(listOf(m), target, MarchKind.ATTACK)
            MapObjectKind.FIELD -> battleGatherer(m, target)
            MapObjectKind.NEST -> false
        }
        MarchKind.REINFORCE -> target != null && station(m, target)
        MarchKind.RALLY -> false
    }
    if (done.not()) targetUnavailable(m)
}

fun Ctx.validPlayerTarget(attacker: Long, target: MapObjectRow): Boolean {
    val owner = target.playerId ?: return false
    if (target.kind != MapObjectKind.BASE || owner == attacker) return false
    settle(owner)
    return sameAlliance(attacker, owner).not() && isProtected(owner).not()
}

/** Report "target not available" and turn back. */
fun Ctx.targetUnavailable(m: MarchRow) {
    addReport(m.playerId, SystemReport("Ziel nicht verfügbar", "Dein Marsch fand bei (${m.toX}, ${m.toY}) kein gültiges Ziel und kehrt um."), "Ziel nicht verfügbar")
    startReturn(m.id, m.toX.toDouble(), m.toY.toDouble())
}

private fun Ctx.station(m: MarchRow, target: MapObjectRow): Boolean {
    val host = target.playerId ?: return false
    if (host != m.hostId || sameAlliance(m.playerId, host).not()) return false
    val cap = rules.reinforceCapacity(buildings(host).levelOf(BuildingType.ALLIANCE_CENTER))
    if (stationedUnitsAt(host) + m.units > cap) return false
    MarchT.update({ MarchT.id eq m.id }) { it[state] = MarchState.STATIONED }
    changedMarches += m.id
    dirty(m.playerId, host)
    return true
}

// ---------------------------------------------------------------- Gathering

private fun Ctx.startGathering(m: MarchRow, field: MapObjectRow): Boolean {
    val bon = Bonuses.research(balance, researchLevels(m.playerId)) + heroBonus(m.playerId, m.hero)
    val rate = rules.gatherRatePerHour(field.level, field.resType!!, bon[BonusKind.GATHER_SPEED])
    MarchT.update({ MarchT.id eq m.id }) {
        it[state] = MarchState.GATHERING
        it[gatherStart] = now
        it[gatherRate] = rate
    }
    MapObjectT.update({ MapObjectT.id eq field.id }) { it[occupiedBy] = m.id }
    val updated = march(m.id)!!
    schedule(EventType.GATHER_END, gatherEndOf(updated), EventPayload(marchId = m.id))
    changedMarches += m.id
    changedObjects += field.id
    dirty(m.playerId)
    return true
}

/** Ends gathering: reduce the field's supply by the amount gathered, cargo into the march. */
fun Ctx.finishGathering(m: MarchRow, keepCargo: Boolean = true): Long {
    val field = m.targetId?.let { mapObject(it) }
    val amount = gatheredAt(m, now)
    cancelEvents(EventType.GATHER_END) { it.marchId == m.id }
    if (field != null) {
        val left = field.amount - amount
        if (left <= 0) {
            MapObjectT.deleteWhere { MapObjectT.id eq field.id }
            removedObjects += field.id
        } else {
            MapObjectT.update({ MapObjectT.id eq field.id }) {
                it[MapObjectT.amount] = left
                it[occupiedBy] = null
            }
            changedObjects += field.id
        }
        if (keepCargo) MarchT.update({ MarchT.id eq m.id }) { it[cargo] = costToJson(Cost.of(field.resType!!, amount)) }
    }
    return amount
}

fun Ctx.onGatherEnd(marchId: Long) {
    val m = march(marchId) ?: return
    if (m.state != MarchState.GATHERING) return
    finishGathering(m)
    startReturn(m.id, m.toX.toDouble(), m.toY.toDouble())
}

// ---------------------------------------------------------------- Scouting

private fun Ctx.scout(m: MarchRow, target: MapObjectRow): Boolean {
    val owner = target.playerId!!
    settle(owner)
    val econ = economyOf(owner)
    val stock = player(owner).stock
    val h = defenseHero(owner)
    val report = ScoutReport(
        at = now, targetId = owner, targetName = accountName(owner), x = target.x, y = target.y,
        resources = Resource.entries.map { ScoutResource(it, stock.whole(it), (stock.whole(it) - econ.protectedAmount).coerceAtLeast(0)) },
        troopsHome = homeTroops(owner), wallLevel = buildings(owner).levelOf(BuildingType.WALL),
        reinforcementsTotal = stationedUnitsAt(owner).toInt(), defenseHero = h?.hero, defenseHeroLevel = h?.level ?: 0,
    )
    addReport(m.playerId, report, "Aufklärung: ${accountName(owner)}")
    addReport(owner, SystemReport("Aufgeklärt", "Du wurdest von ${accountName(m.playerId)} aufgeklärt."), "Du wurdest von ${accountName(m.playerId)} aufgeklärt")
    achievementEvent(m.playerId, AchievementKind.SCOUT_PLAYER)
    startReturn(m.id, target.x.toDouble(), target.y.toDouble())
    return true
}

// ---------------------------------------------------------------- Relocation

fun Ctx.relocate(pid: Long, x: Int, y: Int): Res<Unit> {
    ensure(rules.inBounds(x, y), ErrorCode.VALIDATION) { "Das Feld liegt außerhalb der Karte." }?.let { return it }
    ensure(mapObjectAt(x, y) == null, ErrorCode.TARGET_INVALID) { "Das Feld ist nicht frei." }?.let { return it }
    val zone = rules.zoneOf(x, y)
    val need = balance.relocation.minHqByZone.getValue(zone)
    ensure(hqOf(pid) >= need, ErrorCode.REQUIREMENT_NOT_MET) { "Zone $zone erfordert HQ $need." }?.let { return it }
    ensure(marchesOf(pid).isEmpty(), ErrorCode.VALIDATION) { "Umzug nur, wenn kein eigener Marsch unterwegs ist." }?.let { return it }
    val base = baseOf(pid)!!
    val foreignIncoming = allMarches().any {
        it.playerId != pid && it.state == MarchState.OUTBOUND && (it.targetId == base.id || it.hostId == pid)
    }
    ensure(foreignIncoming.not(), ErrorCode.VALIDATION) { "Umzug nicht möglich, solange ein fremder Marsch auf dem Weg zu dir ist." }?.let { return it }
    allMarches().filter { it.hostId == pid && it.state == MarchState.STATIONED }.forEach { startReturn(it.id, base.x.toDouble(), base.y.toDouble()) }
    MapObjectT.update({ MapObjectT.id eq base.id }) {
        it[MapObjectT.x] = x
        it[MapObjectT.y] = y
        it[MapObjectT.zone] = zone
    }
    changedObjects += base.id
    dirty(pid)
    return OK
}

