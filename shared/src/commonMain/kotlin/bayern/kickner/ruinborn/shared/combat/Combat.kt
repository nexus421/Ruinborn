package bayern.kickner.ruinborn.shared.combat

import bayern.kickner.ruinborn.shared.balance.CombatBalance
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.UnitType
import bayern.kickner.ruinborn.shared.rules.floorSafe
import kotlin.math.min

/**
 * A stack: all units of one owner with the same type and tier. A zombie group or a
 * nest is a single stack without a type ([unitType] = null). The values are the effective values including bonuses,
 * as unrounded decimals.
 */
data class CombatStack(
    val id: Int,
    val ownerId: Long,
    val unitType: UnitType?,
    val tier: Int,
    val count: Int,
    val atk: Double,
    val def: Double,
    val hp: Double,
)

/** Result of a battle. [losses] = losses per stack ID (all units that had to leave the battle). */
data class CombatResult(
    val rounds: Int,
    val attackerWon: Boolean,
    val fought: Boolean,
    val losses: Map<Int, Int>,
    /** First-round damage per target stack (for tests and reports). */
    val firstRoundDamage: Map<Int, Double>,
) {
    fun lossOf(stackId: Int): Int = losses[stackId] ?: 0
}

/**
 * Deterministic, round-based combat (concept section 7).
 *
 * In each round (at most maxRounds) every stack X deals damage to every enemy stack Y, using the counts
 * from the start of the round: D = n_X · a_X · (n_Y · h_Y / Σ n_Z · h_Z) · k · 100 / (100 + v_Y).
 * Each stack accumulates damage in a carry-over C. Losses = min(n, ⌊C ÷ h⌋), C −= losses · h.
 * Losses on both sides are deducted at the same time.
 *
 * The attacker only wins if the defender has no units left and the attacker still has at least one.
 * Otherwise the defender wins (also after the round limit and on mutual destruction).
 * If the defender has no units from the start, the attacker wins without a battle.
 */
fun simulateCombat(attackers: List<CombatStack>, defenders: List<CombatStack>, cfg: CombatBalance): CombatResult {
    val att = attackers.filter { it.count > 0 }
    val def = defenders.filter { it.count > 0 }
    require((att + def).map { it.id }.toSet().size == att.size + def.size) { "Stapel-IDs müssen eindeutig sein" }
    if (def.isEmpty()) return CombatResult(0, att.isNotEmpty(), false, emptyMap(), emptyMap())
    if (att.isEmpty()) return CombatResult(0, false, false, emptyMap(), emptyMap())

    val all = att + def
    val count = all.associate { it.id to it.count }.toMutableMap()
    val carry = all.associate { it.id to 0.0 }.toMutableMap()
    val firstRound = mutableMapOf<Int, Double>()
    var rounds = 0

    fun alive(side: List<CombatStack>) = side.sumOf { count.getValue(it.id) }

    while (rounds < cfg.maxRounds && alive(att) > 0 && alive(def) > 0) {
        rounds++
        val damage = mutableMapOf<Int, Double>()
        fun hit(from: List<CombatStack>, to: List<CombatStack>) {
            val totalHp = to.sumOf { count.getValue(it.id) * it.hp }
            if (totalHp <= 0.0) return
            from.forEach { x ->
                val nx = count.getValue(x.id)
                if (nx <= 0) return@forEach
                to.forEach { y ->
                    val ny = count.getValue(y.id)
                    if (ny <= 0) return@forEach
                    val k = if (x.unitType != null && y.unitType != null && cfg.counters[x.unitType] == y.unitType) cfg.counterMultiplier else 1.0
                    val s = nx * x.atk * (ny * y.hp / totalHp) * k * (cfg.defenseConstant / (cfg.defenseConstant + y.def))
                    damage[y.id] = (damage[y.id] ?: 0.0) + s
                }
            }
        }
        hit(att, def)
        hit(def, att)
        if (rounds == 1) firstRound.putAll(damage)

        val roundLosses = mutableMapOf<Int, Int>()
        all.forEach { y ->
            val n = count.getValue(y.id)
            if (n <= 0) return@forEach
            val u = carry.getValue(y.id) + (damage[y.id] ?: 0.0)
            val lost = min(n.toLong(), floorSafe(u / y.hp)).toInt()
            carry[y.id] = u - lost * y.hp
            roundLosses[y.id] = lost
        }
        roundLosses.forEach { (id, lost) -> count[id] = count.getValue(id) - lost }
    }

    val losses = all.associate { it.id to it.count - count.getValue(it.id) }.filterValues { it > 0 }
    val attackerWon = alive(def) == 0 && alive(att) > 0
    return CombatResult(rounds, attackerWon, true, losses, firstRound)
}

/**
 * Splits an owner's losses between hospital and death: starting with the highest tier (same tier in
 * the order [order], i.e. infantry first, then vehicles, then shooters) until the free capacity is used up.
 * The rest die.
 */
fun splitCasualties(losses: List<TroopCount>, freeSpace: Long, order: List<UnitType>): Casualties {
    var free = freeSpace.coerceAtLeast(0)
    val wounded = mutableListOf<TroopCount>()
    val dead = mutableListOf<TroopCount>()
    losses.filter { it.count > 0 }
        .sortedWith(compareByDescending<TroopCount> { it.tier }.thenBy { order.indexOf(it.type) })
        .forEach { l ->
            val w = min(free, l.count.toLong()).toInt()
            free -= w
            if (w > 0) wounded += TroopCount(l.type, l.tier, w)
            if (l.count - w > 0) dead += TroopCount(l.type, l.tier, l.count - w)
        }
    return Casualties(wounded, dead)
}

data class Casualties(val wounded: List<TroopCount>, val dead: List<TroopCount>)

/**
 * Loot against a base: B_R = min(P_R, ⌊C · P_R / ΣP⌋). If ΣP = 0 there is no loot.
 * [plunderable] = lootable amount per resource, [capacity] = load of all surviving attackers.
 */
fun loot(plunderable: Cost, capacity: Long): Cost {
    val sum = plunderable.total
    if (sum <= 0L || capacity <= 0L) return Cost.ZERO
    fun one(r: Resource): Long {
        val p = plunderable[r]
        return min(p, floorSafe(capacity.toDouble() * p / sum))
    }
    return Cost(one(Resource.FOOD), one(Resource.WOOD), one(Resource.STEEL))
}

/** Splits [total] by shares (rounded down), e.g. the loot of a rally by load. */
fun distributeByShare(total: Long, shares: List<Long>): List<Long> {
    val sum = shares.sum()
    if (sum <= 0L) return shares.map { 0L }
    return shares.map { floorSafe(total.toDouble() * it / sum) }
}

/** Splits loot (per resource) by shares. */
fun distributeCost(total: Cost, shares: List<Long>): List<Cost> {
    val f = distributeByShare(total.food, shares)
    val w = distributeByShare(total.wood, shares)
    val s = distributeByShare(total.steel, shares)
    return shares.indices.map { Cost(f[it], w[it], s[it]) }
}
