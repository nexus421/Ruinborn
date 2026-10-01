package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.DefenseLossT
import bayern.kickner.ruinborn.server.db.MapObjectT
import bayern.kickner.ruinborn.server.db.MarchT
import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.combat.distributeCost
import bayern.kickner.ruinborn.shared.combat.loot
import bayern.kickner.ruinborn.shared.dto.BattleReport
import bayern.kickner.ruinborn.shared.dto.ItemCount
import bayern.kickner.ruinborn.shared.model.AchievementKind
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.rules.Bonuses
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

// ---------------------------------------------------------------- Zombies

/** Attack on a zombie group. Rewards are granted immediately, without a load limit. */
fun Ctx.battleZombie(m: MarchRow, target: MapObjectRow): Boolean {
    val level = target.level
    val setup = BattleSetup(vsMonsters = true, wallBonus = 0.0)
    val me = marchFighter(m)
    setup.attackers += me
    setup.defenders += Fighter(null, "Zombies Stufe $level", null, 0, null, emptyList(), Bonuses.NONE, monster = rules.zombie(level), monsterLevel = level)
    val out = fight(setup)
    setMarchTroops(m.id, me.survivors)
    var rewards = Cost.ZERO
    val drops = mutableListOf<ItemCount>()
    if (out.attackerWon) {
        val r = rules.zombieReward(level)
        rewards = Cost(r.food, r.wood, r.steel)
        credit(m.playerId, rewards)
        awardXp(me, r.heroXp)
        if (random.nextDouble() < balance.zombies.dropChance) {
            addItem(m.playerId, r.drop, 1)
            drops += ItemCount(r.drop, 1)
        }
        val p = player(m.playerId)
        val maxLevel = maxOf(p.maxZombieLevel, level)
        val defeated = p.zombiesDefeated + 1
        PlayerT.update({ PlayerT.id eq m.playerId }) {
            it[maxZombieLevel] = maxLevel
            it[zombiesDefeated] = defeated
        }
        addDaily(m.playerId, DailyTask.ZOMBIE_HUNT, 1)
        achievementValue(m.playerId, AchievementKind.ZOMBIE_WINS, defeated)
        achievementValue(m.playerId, AchievementKind.ZOMBIE_LEVEL, maxLevel.toLong())
        MapObjectT.deleteWhere { MapObjectT.id eq target.id }
        removedObjects += target.id
    }
    // If the attacker loses, the zombies stay fully healed (object unchanged).
    val report = BattleReport(
        at = now, x = target.x, y = target.y, marchKind = MarchKind.ATTACK, targetKind = MapObjectKind.ZOMBIE, targetLevel = level,
        isAttacker = true, won = out.attackerWon, attackerWon = out.attackerWon, fought = out.result.fought, rounds = out.result.rounds,
        attackers = listOf(me.toReport()), defenders = out.defenders.map { it.toReport() }, drops = drops, rewards = rewards,
    )
    addReport(m.playerId, report, if (out.attackerWon) "Sieg gegen Zombies Stufe $level" else "Niederlage gegen Zombies Stufe $level")
    startReturn(m.id, target.x.toDouble(), target.y.toDouble())
    return true
}

// ---------------------------------------------------------------- Bases

/**
 * Battle against a player base: troops at home with the defense hero and stationed reinforcements defend,
 * all with the host's wall bonus. On victory loot by formula, in rallies by share of load.
 */
fun Ctx.battleBase(attackMarches: List<MarchRow>, target: MapObjectRow, kind: MarchKind): Boolean {
    val defPid = target.playerId!!
    settle(defPid)
    attackMarches.forEach { settle(it.playerId) }
    val setup = BattleSetup(vsMonsters = false, wallBonus = wallBonusOf(defPid))
    val attackers = attackMarches.map { marchFighter(it) }
    setup.attackers += attackers
    val home = homeFighter(defPid)
    setup.defenders += home
    val reinforcements = allMarches().filter { it.kind == MarchKind.REINFORCE && it.hostId == defPid && it.state == MarchState.STATIONED }
    val reinfFighters = reinforcements.map { marchFighter(it, reinforcement = true) }
    setup.defenders += reinfFighters
    val out = fight(setup)

    attackMarches.zip(attackers).forEach { (m, f) -> setMarchTroops(m.id, f.survivors) }
    reinforcements.zip(reinfFighters).forEach { (m, f) ->
        setMarchTroops(m.id, f.survivors)
        if (f.survivors.isEmpty()) startReturn(m.id, target.x.toDouble(), target.y.toDouble())
    }

    var totalLoot = Cost.ZERO
    val lootShares = mutableMapOf<Long, Cost>()
    if (out.attackerWon) {
        val econ = economyOf(defPid)
        val stock = player(defPid).stock
        val loads = attackMarches.map { m -> loadOf(march(m.id)!!) }
        totalLoot = loot(stock.plunderable(econ.protectedAmount), loads.sum())
        saveStock(defPid, stock - totalLoot)
        distributeCost(totalLoot, loads).forEachIndexed { i, share ->
            val m = attackMarches[i]
            lootShares[m.id] = share
            MarchT.update({ MarchT.id eq m.id }) { it[cargo] = costToJson(march(m.id)!!.cargo + share) }
        }
        val xp = pvpXp(out.defenders)
        attackers.forEach { awardXp(it, xp) }
        registerDefenseLoss(defPid)
    } else {
        val xp = pvpXp(out.attackers)
        (listOf(home) + reinfFighters).forEach { awardXp(it, xp) }
        if (out.result.fought || out.defenders.any { it.troops.isNotEmpty() }) achievementEvent(defPid, AchievementKind.DEFENSE_WIN)
    }

    val attackerReports = out.attackers.map { it.toReport() }
    val defenderReports = out.defenders.filter { it.troops.isNotEmpty() || it.playerId == defPid }.map { it.toReport() }
    fun report(forAttacker: Boolean, loot: Cost) = BattleReport(
        at = now, x = target.x, y = target.y, marchKind = kind, targetKind = MapObjectKind.BASE, isAttacker = forAttacker,
        won = if (forAttacker) out.attackerWon else out.attackerWon.not(), attackerWon = out.attackerWon, fought = out.result.fought,
        rounds = out.result.rounds, attackers = attackerReports, defenders = defenderReports, loot = loot,
    )
    val defName = accountName(defPid)
    attackMarches.map { it.playerId }.distinct().forEach { pid ->
        val share = attackMarches.filter { it.playerId == pid }.fold(Cost.ZERO) { acc, m -> acc + (lootShares[m.id] ?: Cost.ZERO) }
        addReport(pid, report(true, share), (if (out.attackerWon) "Sieg" else "Niederlage") + " gegen $defName")
    }
    val attackerName = accountName(attackMarches.first().playerId)
    (listOf(defPid) + reinforcements.map { it.playerId }).distinct().forEach { pid ->
        addReport(pid, report(false, totalLoot), (if (out.attackerWon) "Basis verloren gegen " else "Verteidigung gegen ") + attackerName)
    }
    attackMarches.forEach { startReturn(it.id, target.x.toDouble(), target.y.toDouble()) }
    changedObjects += target.id
    dirty(defPid)
    return true
}

/** Three lost defenses within 12 h trigger the recovery shield. Counting then starts over. */
fun Ctx.registerDefenseLoss(pid: Long) {
    DefenseLossT.insert {
        it[playerId] = pid
        it[at] = now
    }
    val prot = balance.protection
    val since = now - rules.hoursMs(prot.recoveryWindowHours)
    val count = DefenseLossT.selectAll().where { (DefenseLossT.playerId eq pid) and (DefenseLossT.at greaterEq since) }.count()
    if (count >= prot.recoveryLosses) {
        DefenseLossT.deleteWhere { DefenseLossT.playerId eq pid }
        activateShield(pid, rules.hoursMs(prot.recoveryShieldHours), "Erholungsschild")
    }
}

// ---------------------------------------------------------------- Gathering marches

/** Attack on another player's gathering march. Marches on the map are never protected. */
fun Ctx.battleGatherer(m: MarchRow, field: MapObjectRow): Boolean {
    val g = field.occupiedBy?.let { march(it) } ?: return false
    if (g.state != MarchState.GATHERING || g.playerId == m.playerId || sameAlliance(m.playerId, g.playerId)) return false
    settle(g.playerId)
    val gatheredBefore = gatheredAt(g, now)
    val setup = BattleSetup(vsMonsters = false, wallBonus = 0.0)
    val attacker = marchFighter(m)
    val defender = marchFighter(g)
    setup.attackers += attacker
    setup.defenders += defender
    val out = fight(setup)
    setMarchTroops(m.id, attacker.survivors)
    setMarchTroops(g.id, defender.survivors)

    var taken = Cost.ZERO
    if (out.attackerWon) {
        // The loser returns home without cargo. The field's supply drops by the amount gathered.
        finishGathering(g, keepCargo = false)
        MarchT.update({ MarchT.id eq g.id }) { it[cargo] = costToJson(Cost.ZERO) }
        val capacity = loadOf(march(m.id)!!)
        taken = Cost.of(field.resType!!, minOf(gatheredBefore, capacity))
        MarchT.update({ MarchT.id eq m.id }) { it[cargo] = costToJson(taken) }
        startReturn(g.id, field.x.toDouble(), field.y.toDouble())
        awardXp(attacker, pvpXp(listOf(defender)))
    } else {
        awardXp(defender, pvpXp(listOf(attacker)))
        val updated = march(g.id)!!
        val gathered = gatheredAt(updated, now)
        val limit = minOf(loadOf(updated), mapObject(field.id)?.amount ?: 0)
        if (updated.units == 0 || gathered >= limit) {
            finishGathering(updated)
            startReturn(g.id, field.x.toDouble(), field.y.toDouble())
        } else {
            cancelEvents(EventType.GATHER_END) { it.marchId == g.id }
            schedule(EventType.GATHER_END, gatherEndOf(updated), EventPayload(marchId = g.id))
        }
    }
    fun report(forAttacker: Boolean) = BattleReport(
        at = now, x = field.x, y = field.y, marchKind = MarchKind.ATTACK, targetKind = MapObjectKind.FIELD, targetLevel = field.level,
        isAttacker = forAttacker, won = if (forAttacker) out.attackerWon else out.attackerWon.not(), attackerWon = out.attackerWon,
        fought = out.result.fought, rounds = out.result.rounds, attackers = listOf(attacker.toReport()), defenders = listOf(defender.toReport()),
        loot = taken, againstGatherer = true,
    )
    addReport(m.playerId, report(true), (if (out.attackerWon) "Sieg" else "Niederlage") + " gegen Sammelmarsch von ${accountName(g.playerId)}")
    addReport(g.playerId, report(false), "Sammelmarsch angegriffen von ${accountName(m.playerId)}")
    startReturn(m.id, field.x.toDouble(), field.y.toDouble())
    changedMarches += g.id
    dirty(g.playerId)
    return true
}

