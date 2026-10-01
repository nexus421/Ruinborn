package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.shared.model.AchievementKind
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.Tech
import bayern.kickner.ruinborn.shared.model.TimerKind
import bayern.kickner.ruinborn.shared.rules.Bonuses
import bayern.kickner.ruinborn.shared.rules.Rates
import bayern.kickner.ruinborn.shared.rules.Stock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update

/** Economy values of a player at a point in time. [perHour] without gameSpeed (display), [rates] with it. */
data class Economy(
    val bonuses: Bonuses,
    val perHour: Map<Resource, Long>,
    val rates: Rates,
    val capacity: Long,
    val protectedAmount: Long,
)

/** Economy bonuses: research and catch-up bonus (heroes only affect their own march). */
fun Ctx.economyBonuses(research: Map<Tech, Int>, catchupActive: Boolean): Bonuses =
    Bonuses.research(balance, research) + Bonuses.catchup(balance, catchupActive)

fun Ctx.economy(buildings: Map<Plot, BuildingRow>, research: Map<Tech, Int>, catchupActive: Boolean): Economy {
    val bon = economyBonuses(research, catchupActive)
    val perHour = Resource.entries.associateWith { 0L }.toMutableMap()
    buildings.values.forEach { b ->
        val res = rules.producedResource(b.type) ?: return@forEach
        perHour[res] = perHour.getValue(res) + rules.productionPerHour(b.type, b.level, bon[BonusKind.prod(res)])
    }
    val g = rules.gameSpeed
    val rates = Rates(perHour.getValue(Resource.FOOD) * g, perHour.getValue(Resource.WOOD) * g, perHour.getValue(Resource.STEEL) * g)
    val cap = rules.storageCapacity(buildings.levelOf(BuildingType.WAREHOUSE), bon[BonusKind.STORAGE])
    return Economy(bon, perHour, rates, cap, rules.protectedAmount(cap))
}

fun Ctx.economyOf(pid: Long): Economy {
    val p = player(pid)
    return economy(buildings(pid), researchLevels(pid), p.catchupUntil > now)
}

/**
 * Settles a player up to [now] (concept section 13, "player-owned timers"):
 * 1. all timers with end ≤ now in ascending order of end, ties by ID.
 * 2. for each timer materialize resources up to its end, apply the effect, delete the timer, check achievements.
 * 3. finally materialize resources up to now.
 * The end of the catch-up bonus is also a breakpoint so production is exact.
 */
fun Ctx.settle(pid: Long) {
    if (settled.add(pid).not()) return
    val p = playerOrNull(pid) ?: return
    var stock = p.stock
    var b = buildings(pid)
    var r = researchLevels(pid)

    fun advance(to: Long) {
        if (to <= stock.at) return
        val e = economy(b, r, p.catchupUntil > stock.at)
        stock = stock.materialize(to, e.rates, e.capacity)
    }

    /** Materializes up to [to]. If the catch-up bonus ends in between, its end is a breakpoint. */
    fun advanceSplit(to: Long) {
        if (p.catchupUntil in (stock.at + 1)..to) advance(p.catchupUntil)
        advance(to)
    }

    val due = timers(pid).filter { it.endsAt <= now }
    due.forEach { t ->
        advanceSplit(t.endsAt)
        // Store the amounts so follow-up effects (e.g. rewards) build on the correct state.
        saveStock(pid, stock)
        applyTimer(pid, t)
        stock = player(pid).stock
        b = buildings(pid)
        r = researchLevels(pid)
    }
    advanceSplit(now)
    saveStock(pid, stock)
}

/** Effect of an expired timer. */
private fun Ctx.applyTimer(pid: Long, t: TimerRow) {
    val pl = t.payload
    deleteTimer(t.id)
    when (t.kind) {
        TimerKind.BUILD -> {
            setBuilding(pid, pl.plot!!, pl.type!!, pl.level!!)
            if (pl.type == BuildingType.HQ) onHqLevel(pid, pl.level, t.endsAt)
        }
        TimerKind.RESEARCH -> {
            setResearch(pid, pl.tech!!, pl.level!!)
            achievementEvent(pid, AchievementKind.RESEARCH_DONE, 1, t.endsAt)
        }
        TimerKind.TRAIN -> {
            changeTroops(pid, pl.unitType!!, pl.tier!!, pl.count!!, 0)
            val trained = player(pid).troopsTrained + pl.count
            PlayerT.update({ PlayerT.id eq pid }) { it[troopsTrained] = trained }
            achievementValue(pid, AchievementKind.UNITS_TRAINED, trained, t.endsAt)
        }
        TimerKind.HEAL -> pl.units.forEach { u -> changeTroops(pid, u.type, u.tier, u.count, -u.count) }
    }
    dirty(pid)
}

/** Consequences of a new HQ level: unlock heroes, end beginner protection if needed, achievements. */
fun Ctx.onHqLevel(pid: Long, level: Int, at: Long) {
    ensureHeroes(pid, level)
    if (level >= balance.protection.newbieEndsAtHq) {
        val p = player(pid)
        if (p.protectionUntil > at) PlayerT.update({ PlayerT.id eq pid }) { it[protectionUntil] = at }
    }
    achievementValue(pid, AchievementKind.HQ_LEVEL, level.toLong(), at)
}

/** Creates all heroes whose unlock HQ level has been reached. */
fun Ctx.ensureHeroes(pid: Long, hqLevel: Int) {
    val have = heroes(pid).map { it.hero }.toSet()
    HeroId.entries.filter { it !in have && rules.heroUnlockHq(it) <= hqLevel }.forEach { insertHero(pid, it) }
}
