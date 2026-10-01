package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.shared.combat.CombatResult
import bayern.kickner.ruinborn.shared.combat.CombatStack
import bayern.kickner.ruinborn.shared.combat.simulateCombat
import bayern.kickner.ruinborn.shared.combat.splitCasualties
import bayern.kickner.ruinborn.shared.dto.BattleParticipant
import bayern.kickner.ruinborn.shared.dto.StackReport
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.normalized
import bayern.kickner.ruinborn.shared.rules.Bonuses
import bayern.kickner.ruinborn.shared.rules.MonsterStats

/**
 * A participant in a battle: troops of one owner from a march or from the base, or zombies.
 * Stack IDs are assigned by [BattleSetup].
 */
class Fighter(
    val playerId: Long?,
    val name: String,
    val hero: HeroId?,
    val heroLevel: Int,
    /** March the troops come from. `null` = troops at home or zombies. */
    val marchId: Long?,
    val troops: List<TroopCount>,
    val bonuses: Bonuses,
    val reinforcement: Boolean = false,
    val monster: MonsterStats? = null,
    val monsterLevel: Int = 0,
) {
    val stacks = mutableListOf<Pair<Int, TroopCount?>>()
    var heroXp: Long = 0
    val losses = mutableMapOf<Int, Int>()
    var wounded: List<TroopCount> = emptyList()
    var dead: List<TroopCount> = emptyList()
    var survivors: List<TroopCount> = troops
}

class BattleSetup(val vsMonsters: Boolean, val wallBonus: Double) {
    private var nextId = 1
    val attackers = mutableListOf<Fighter>()
    val defenders = mutableListOf<Fighter>()

    fun stacksOf(side: List<Fighter>, ctx: Ctx, defending: Boolean): List<CombatStack> = side.flatMap { f ->
        if (f.monster != null) {
            val id = nextId++
            f.stacks += id to null
            listOf(CombatStack(id, 0, null, f.monsterLevel, f.monster.count, f.monster.atk, f.monster.def, f.monster.hp))
        } else f.troops.normalized().map { t ->
            val id = nextId++
            f.stacks += id to t
            val s = ctx.rules.unitStats(t.type, t.tier)
            val atkBonus = f.bonuses[BonusKind.atk(t.type)] + if (vsMonsters) f.bonuses[BonusKind.ZOMBIE_ATK] else 0.0
            val defBonus = f.bonuses[BonusKind.def(t.type)] + if (defending) wallBonus else 0.0
            CombatStack(id, f.playerId ?: 0, t.type, t.tier, t.count, s.atk * (1 + atkBonus), s.def * (1 + defBonus), s.hp)
        }
    }
}

/** Result including the applied losses. */
class BattleOutcome(val result: CombatResult, val attackers: List<Fighter>, val defenders: List<Fighter>) {
    val attackerWon: Boolean get() = result.attackerWon
}

/**
 * Runs the battle and splits the losses per owner between hospital and death (concept section 7).
 * Troops at home are deducted directly. For march troops [Fighter.survivors] returns the new count,
 * which the caller writes into the march.
 */
fun Ctx.fight(setup: BattleSetup): BattleOutcome {
    val att = setup.stacksOf(setup.attackers, this, defending = false)
    val def = setup.stacksOf(setup.defenders, this, defending = true)
    val result = simulateCombat(att, def, balance.combat)
    val all = setup.attackers + setup.defenders
    all.forEach { f -> f.stacks.forEach { (id, _) -> f.losses[id] = result.lossOf(id) } }

    // Losses per owner go to the hospital together, starting with the highest tier.
    all.filter { it.playerId != null }.groupBy { it.playerId!! }.forEach { (pid, fighters) ->
        val lossList = fighters.flatMap { f -> f.stacks.mapNotNull { (id, t) -> t?.let { TroopCount(it.type, it.tier, f.losses[id] ?: 0) } } }
        val free = (hospitalCapacityOf(pid) - woundedTotal(pid)).coerceAtLeast(0)
        val cas = splitCasualties(lossList, free, balance.combat.hospitalOrder)
        // Distribute wounded/dead back to the individual fighters (for reports and march counts).
        val woundedLeft = cas.wounded.associate { (it.type to it.tier) to it.count }.toMutableMap()
        fighters.forEach { f ->
            val w = mutableListOf<TroopCount>()
            val d = mutableListOf<TroopCount>()
            val surv = mutableListOf<TroopCount>()
            f.stacks.forEach { (id, t) ->
                t ?: return@forEach
                val lost = f.losses[id] ?: 0
                val key = t.type to t.tier
                val wn = minOf(lost, woundedLeft[key] ?: 0)
                woundedLeft[key] = (woundedLeft[key] ?: 0) - wn
                if (wn > 0) w += TroopCount(t.type, t.tier, wn)
                if (lost - wn > 0) d += TroopCount(t.type, t.tier, lost - wn)
                if (t.count - lost > 0) surv += TroopCount(t.type, t.tier, t.count - lost)
                // Troops at home: deduct losses, wounded go to the own hospital.
                if (f.marchId == null) changeTroops(pid, t.type, t.tier, -lost, wn) else if (wn > 0) changeTroops(pid, t.type, t.tier, 0, wn)
            }
            f.wounded = w
            f.dead = d
            f.survivors = surv
        }
        dirty(pid)
    }
    return BattleOutcome(result, setup.attackers, setup.defenders)
}

/** Report participant from a fighter. */
fun Fighter.toReport(): BattleParticipant = BattleParticipant(
    playerId = playerId,
    name = name,
    hero = hero,
    heroLevel = heroLevel,
    heroXp = heroXp,
    reinforcement = reinforcement,
    stacks = stacks.map { (id, t) ->
        val lost = losses[id] ?: 0
        if (t == null) {
            StackReport(null, monsterLevel, monster!!.count, 0, lost, monster.count - lost)
        } else {
            val w = wounded.firstOrNull { it.type == t.type && it.tier == t.tier }?.count ?: 0
            StackReport(t.type, t.tier, t.count, w, lost - w, t.count - lost)
        }
    },
)

/** Fighter for a march with its owner's research and hero. */
fun Ctx.marchFighter(m: MarchRow, reinforcement: Boolean = false): Fighter {
    val h = m.hero?.let { hero(m.playerId, it) }
    return Fighter(
        m.playerId, accountName(m.playerId), m.hero, h?.level ?: 0, m.id, m.troops,
        Bonuses.research(balance, researchLevels(m.playerId)) + heroBonus(m.playerId, m.hero), reinforcement,
    )
}

/** Defense hero: highest level at home, ties broken by unlock order. */
fun Ctx.defenseHero(pid: Long): HeroRow? =
    heroes(pid).filter { it.marchId == null }.sortedWith(compareByDescending<HeroRow> { it.level }.thenBy { it.hero.ordinal }).firstOrNull()

/** The troops at home of a base with research and defense hero. */
fun Ctx.homeFighter(pid: Long): Fighter {
    val h = defenseHero(pid)
    return Fighter(
        pid, accountName(pid), h?.hero, h?.level ?: 0, null, homeTroops(pid),
        Bonuses.research(balance, researchLevels(pid)) + (h?.let { Bonuses.hero(balance, it.hero, it.level) } ?: Bonuses.NONE),
    )
}

fun Ctx.wallBonusOf(pid: Long): Double = rules.wallDefenseBonus(buildings(pid).levelOf(BuildingType.WALL))

/** Experience from PvP battles: factor × Σ (enemy losses × tier). */
fun Ctx.pvpXp(losers: List<Fighter>): Long {
    val sum = losers.sumOf { f -> f.stacks.sumOf { (id, t) -> (f.losses[id] ?: 0).toLong() * (t?.tier ?: 0) } }
    return (sum * balance.heroes.pvpXpFactor).toLong()
}

/** Credits hero experience to a fighter (only with a hero). */
fun Ctx.awardXp(f: Fighter, xp: Long) {
    val pid = f.playerId ?: return
    val h = f.hero ?: return
    if (xp <= 0) return
    f.heroXp = xp
    giveHeroXp(pid, h, xp)
}
