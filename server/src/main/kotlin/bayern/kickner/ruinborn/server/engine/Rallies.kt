package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.MapObjectT
import bayern.kickner.ruinborn.server.db.MarchT
import bayern.kickner.ruinborn.server.db.RallyT
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.dto.BattleReport
import bayern.kickner.ruinborn.shared.dto.ItemCount
import bayern.kickner.ruinborn.shared.dto.RallyDto
import bayern.kickner.ruinborn.shared.dto.RallyJoinRequest
import bayern.kickner.ruinborn.shared.dto.RallyParticipantDto
import bayern.kickner.ruinborn.shared.dto.RallyRequest
import bayern.kickner.ruinborn.shared.dto.SystemReport
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.model.AchievementKind
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.model.RallyState
import bayern.kickner.ruinborn.shared.rules.Bonuses
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

data class RallyRow(val id: Long, val allianceId: Long, val leaderId: Long, val targetId: Long, val launchAt: Long, val state: RallyState)

fun rallyRow(id: Long): RallyRow? = RallyT.selectAll().where { RallyT.id eq id }.firstOrNull()?.let {
    RallyRow(it[RallyT.id], it[RallyT.allianceId], it[RallyT.leaderId], it[RallyT.targetId], it[RallyT.launchAt], it[RallyT.state])
}

private fun setRallyState(id: Long, s: RallyState) {
    RallyT.update({ RallyT.id eq id }) { it[state] = s }
}

fun rallyMarches(rallyId: Long): List<MarchRow> = allMarches().filter { it.rallyId == rallyId }

/** Valid rally target: zombie nest, or another player's unprotected base outside the alliance. */
private fun Ctx.rallyTargetProblem(pid: Long, target: MapObjectRow?): ResultFailure? {
    target ?: return fail(ErrorCode.TARGET_INVALID, "Das Ziel existiert nicht mehr.")
    return when (target.kind) {
        MapObjectKind.NEST -> null
        MapObjectKind.BASE -> when {
            target.playerId == pid || sameAlliance(pid, target.playerId!!) -> fail(ErrorCode.TARGET_INVALID, "Allianzmitglieder können nicht angegriffen werden.")
            else -> {
                settle(target.playerId)
                if (isProtected(target.playerId)) fail(ErrorCode.TARGET_SHIELDED, "Die Basis ist geschützt.") else null
            }
        }
        else -> fail(ErrorCode.TARGET_INVALID, "Sammelangriffe gehen nur gegen Nester und Spielerbasen.")
    }
}

fun Ctx.createRally(pid: Long, req: RallyRequest): Res<Long> {
    settle(pid)
    val m = membership(pid) ?: return fail(ErrorCode.NOT_IN_ALLIANCE, "Sammelangriffe gehen nur in einer Allianz.")
    ensure(req.waitMinutes in balance.rally.waitMinutes, ErrorCode.VALIDATION) { "Wartezeit ${balance.rally.waitMinutes.joinString()} Minuten." }?.let { return it }
    ensure(rules.inBounds(req.x, req.y), ErrorCode.VALIDATION) { "Das Feld liegt außerhalb der Karte." }?.let { return it }
    val target = mapObjectAt(req.x, req.y)
    rallyTargetProblem(pid, target)?.let { return it }
    val army = validateArmy(pid, req.heroId, req.troops).orReturn { return it }
    if (target!!.kind == MapObjectKind.BASE) endOwnProtection(pid)
    val base = baseOf(pid)!!
    val launch = now + rules.rallyWaitMs(req.waitMinutes)
    val rallyId = RallyT.insert {
        it[allianceId] = m.allianceId
        it[leaderId] = pid
        it[targetId] = target.id
        it[launchAt] = launch
        it[state] = RallyState.WAITING
    }[RallyT.id]
    insertMarch(pid, MarchKind.RALLY, req.heroId, army, base.x, base.y, base.x, base.y, null, MarchState.WAITING, launch, rallyId = rallyId)
    schedule(EventType.RALLY_LAUNCH, launch, EventPayload(rallyId = rallyId))
    target.playerId?.let { victim ->
        notify(victim, WsEvent.Incoming(0, accountName(pid), MarchKind.RALLY, launch))
        dirty(victim)
    }
    notifyAlliance(m.allianceId)
    return ok(rallyId)
}

private fun List<Int>.joinString() = joinToString(", ")

fun Ctx.joinRally(pid: Long, rallyId: Long, req: RallyJoinRequest): Res<Long> {
    settle(pid)
    val r = rallyRow(rallyId)
    ensure(r != null && r.state == RallyState.WAITING, ErrorCode.NOT_FOUND) { "Der Sammelangriff läuft nicht mehr." }?.let { return it }
    r!!
    ensure(allianceIdOf(pid) == r.allianceId, ErrorCode.NOT_IN_ALLIANCE) { "Nur für Mitglieder der Allianz." }?.let { return it }
    ensure(r.leaderId != pid, ErrorCode.VALIDATION) { "Du hast den Sammelangriff gestartet." }?.let { return it }
    val marches = rallyMarches(rallyId)
    ensure(marches.none { it.playerId == pid }, ErrorCode.VALIDATION) { "Du nimmst bereits teil." }?.let { return it }
    ensure(marches.count { it.playerId != r.leaderId } < balance.rally.maxJoiners, ErrorCode.MARCH_LIMIT) { "Der Sammelangriff ist voll." }?.let { return it }
    rallyTargetProblem(pid, mapObject(r.targetId))?.let { return it }
    val army = validateArmy(pid, req.heroId, req.troops).orReturn { return it }
    val base = baseOf(pid)!!
    val leaderBase = baseOf(r.leaderId)!!
    val arrive = now + travelMs(pid, base.x.toDouble(), base.y.toDouble(), leaderBase.x, leaderBase.y, rules.slowestSecPerTile(army))
    ensure(arrive < r.launchAt, ErrorCode.VALIDATION) { "Dein Marsch käme zu spät an." }?.let { return it }
    if (mapObject(r.targetId)?.kind == MapObjectKind.BASE) endOwnProtection(pid)
    val id = insertMarch(pid, MarchKind.RALLY, req.heroId, army, base.x, base.y, leaderBase.x, leaderBase.y, null, MarchState.OUTBOUND, arrive,
        rallyId = rallyId, hostId = r.leaderId)
    schedule(EventType.MARCH_ARRIVE, arrive, EventPayload(marchId = id))
    notifyAlliance(r.allianceId)
    return ok(id)
}

/** Participant reaches the starter's base and waits there. */
fun Ctx.onRallyJoinArrive(m: MarchRow) {
    val r = m.rallyId?.let { rallyRow(it) }
    if (r == null || r.state != RallyState.WAITING) {
        startReturn(m.id, m.toX.toDouble(), m.toY.toDouble())
        return
    }
    MarchT.update({ MarchT.id eq m.id }) { it[state] = MarchState.WAITING }
    changedMarches += m.id
    notifyAlliance(r.allianceId)
}

/** Starter cancels before departure: everyone returns home. */
fun Ctx.cancelRally(pid: Long, rallyId: Long): Res<Unit> {
    val r = rallyRow(rallyId)
    ensure(r != null && r.leaderId == pid, ErrorCode.NOT_FOUND) { "Kein eigener Sammelangriff." }?.let { return it }
    ensure(r!!.state == RallyState.WAITING, ErrorCode.VALIDATION) { "Der Sammelangriff ist bereits gestartet." }?.let { return it }
    setRallyState(rallyId, RallyState.CANCELLED)
    cancelEvents(EventType.RALLY_LAUNCH) { it.rallyId == rallyId }
    rallyMarches(rallyId).forEach { m ->
        when {
            m.playerId == pid && m.state == MarchState.WAITING -> dissolveMarch(m.id)
            m.state == MarchState.WAITING -> startReturn(m.id, m.toX.toDouble(), m.toY.toDouble())
            m.state == MarchState.OUTBOUND -> {
                val (x, y) = m.positionAt(now)
                startReturn(m.id, x, y, now - m.departAt)
            }
        }
    }
    mapObject(r.targetId)?.playerId?.let { dirty(it) }
    notifyAlliance(r.allianceId)
    return OK
}

/** Waiting time is over: all waiting marches depart together. Travel time depends on the slowest unit. */
fun Ctx.onRallyLaunch(rallyId: Long) {
    val r = rallyRow(rallyId) ?: return
    if (r.state != RallyState.WAITING) return
    val marches = rallyMarches(rallyId)
    marches.filter { it.state == MarchState.OUTBOUND }.forEach { m ->
        val (x, y) = m.positionAt(now)
        startReturn(m.id, x, y, now - m.departAt)
    }
    val waiting = marches.filter { it.state == MarchState.WAITING }
    val leaderMarch = waiting.firstOrNull { it.playerId == r.leaderId }
    val target = mapObject(r.targetId)
    val leaderBase = baseOf(r.leaderId)
    if (leaderMarch == null || target == null || leaderBase == null) {
        setRallyState(rallyId, RallyState.CANCELLED)
        waiting.forEach { if (it.playerId == r.leaderId) dissolveMarch(it.id) else startReturn(it.id, it.toX.toDouble(), it.toY.toDouble()) }
        notifyAlliance(r.allianceId)
        return
    }
    val slowest = waiting.flatMap { it.troops }.let { rules.slowestSecPerTile(it) }
    val arrive = now + travelMs(r.leaderId, leaderBase.x.toDouble(), leaderBase.y.toDouble(), target.x, target.y, slowest)
    waiting.forEach { m ->
        MarchT.update({ MarchT.id eq m.id }) {
            it[state] = MarchState.OUTBOUND
            it[fromX] = leaderBase.x
            it[fromY] = leaderBase.y
            it[toX] = target.x
            it[toY] = target.y
            it[departAt] = now
            it[arriveAt] = arrive
            it[targetId] = target.id
            it[hostId] = null
        }
        changedMarches += m.id
        dirty(m.playerId)
    }
    setRallyState(rallyId, RallyState.MARCHING)
    schedule(EventType.MARCH_ARRIVE, arrive, EventPayload(marchId = leaderMarch.id, rallyId = rallyId))
    target.playerId?.let { victim ->
        notify(victim, WsEvent.Incoming(leaderMarch.id, accountName(r.leaderId), MarchKind.RALLY, arrive))
        dirty(victim)
    }
    notifyAlliance(r.allianceId)
}

/** Arrival at the target: one joint battle, then each march returns home directly. */
fun Ctx.onRallyArrive(rallyId: Long) {
    val r = rallyRow(rallyId) ?: return
    if (r.state != RallyState.MARCHING) return
    val marches = rallyMarches(rallyId).filter { it.state == MarchState.OUTBOUND }
    setRallyState(rallyId, RallyState.DONE)
    if (marches.isEmpty()) return
    marches.forEach { settle(it.playerId) }
    val target = mapObject(r.targetId)?.takeIf { it.x == marches.first().toX && it.y == marches.first().toY }
    val valid = when (target?.kind) {
        MapObjectKind.NEST -> true
        MapObjectKind.BASE -> validPlayerTarget(r.leaderId, target)
        else -> false
    }
    if (valid.not()) {
        marches.forEach { targetUnavailable(it) }
        notifyAlliance(r.allianceId)
        return
    }
    target!!
    marches.map { it.playerId }.distinct().forEach { achievementEvent(it, AchievementKind.RALLY_FIGHT) }
    if (target.kind == MapObjectKind.BASE) battleBase(marches, target, MarchKind.RALLY) else battleNest(r, marches, target)
    notifyAlliance(r.allianceId)
}

/** Battle against a nest: full rewards per participant, gifts for all alliance members. */
private fun Ctx.battleNest(r: RallyRow, marches: List<MarchRow>, nest: MapObjectRow) {
    val level = nest.level
    val setup = BattleSetup(vsMonsters = true, wallBonus = 0.0)
    val fighters = marches.map { marchFighter(it) }
    setup.attackers += fighters
    setup.defenders += Fighter(null, "Zombie-Nest Stufe $level", null, 0, null, emptyList(), Bonuses.NONE, monster = rules.nest(level), monsterLevel = level)
    val out = fight(setup)
    marches.zip(fighters).forEach { (m, f) -> setMarchTroops(m.id, f.survivors) }
    val reward = rules.nestReward(level)
    val rewardCost = Cost(reward.food, reward.wood, reward.steel)
    if (out.attackerWon) {
        marches.zip(fighters).forEach { (m, f) ->
            credit(m.playerId, rewardCost)
            addItem(m.playerId, reward.speedupItem, reward.speedups)
            awardXp(f, reward.heroXp)
            achievementValue(m.playerId, AchievementKind.NEST_LEVEL, level.toLong())
        }
        MapObjectT.deleteWhere { MapObjectT.id eq nest.id }
        removedObjects += nest.id
        giveAllianceGifts(r.allianceId, level)
    }
    val drops = if (out.attackerWon) listOf(ItemCount(reward.speedupItem, reward.speedups)) else emptyList()
    val report = BattleReport(
        at = now, x = nest.x, y = nest.y, marchKind = MarchKind.RALLY, targetKind = MapObjectKind.NEST, targetLevel = level,
        isAttacker = true, won = out.attackerWon, attackerWon = out.attackerWon, fought = out.result.fought, rounds = out.result.rounds,
        attackers = fighters.map { it.toReport() }, defenders = out.defenders.map { it.toReport() },
        drops = drops, rewards = if (out.attackerWon) rewardCost else Cost.ZERO,
    )
    marches.map { it.playerId }.distinct().forEach { pid ->
        addReport(pid, report, if (out.attackerWon) "Nest Stufe $level besiegt" else "Niederlage gegen Nest Stufe $level")
    }
    marches.forEach { startReturn(it.id, nest.x.toDouble(), nest.y.toDouble()) }
}

/** Running rallies of the own alliance. */
fun Ctx.alliRallies(pid: Long): List<RallyDto> {
    val aid = allianceIdOf(pid) ?: return emptyList()
    return RallyT.selectAll().where { RallyT.allianceId eq aid }
        .filter { it[RallyT.state] == RallyState.WAITING || it[RallyT.state] == RallyState.MARCHING }
        .mapNotNull { row ->
            val r = rallyRow(row[RallyT.id])!!
            val t = mapObject(r.targetId) ?: return@mapNotNull null
            val marches = rallyMarches(r.id)
            RallyDto(
                id = r.id, allianceId = r.allianceId, leaderId = r.leaderId, leaderName = accountName(r.leaderId), targetId = t.id,
                targetKind = t.kind, targetName = if (t.kind == MapObjectKind.BASE) accountName(t.playerId!!) else "Zombie-Nest",
                targetLevel = t.level, x = t.x, y = t.y, launchAt = r.launchAt, state = r.state,
                participants = marches.map { RallyParticipantDto(it.playerId, accountName(it.playerId), it.id, it.units, it.state, it.arriveAt) },
                arriveAt = if (r.state == RallyState.MARCHING) marches.firstOrNull()?.arriveAt else null,
            )
        }
}

