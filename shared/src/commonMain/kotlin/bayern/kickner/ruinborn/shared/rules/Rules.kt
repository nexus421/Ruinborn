package bayern.kickner.ruinborn.shared.rules

import bayern.kickner.ruinborn.shared.balance.Balance
import bayern.kickner.ruinborn.shared.balance.BuildingBalance
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.ChestSize
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.UnitType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Rounds down with protection against floating point artifacts (e.g. 6599.9999999 instead of 6600). */
fun floorSafe(x: Double): Long = floor(x + 1e-9).toLong()

/** Rounds up with protection against floating point artifacts (e.g. 60.0000001 instead of 60). */
fun ceilSafe(x: Double): Long = ceil(x - 1e-9).toLong()

/** X(L) = X₀ · w^(L−1) */
fun growth(x0: Double, w: Double, level: Int): Double = x0 * w.pow(level - 1)

const val MS_PER_SEC = 1000L
const val MS_PER_HOUR = 3_600_000L
const val MS_PER_DAY = 86_400_000L

/** Stats of a troop (type and tier) without bonuses. */
data class UnitStats(
    val atk: Double,
    val def: Double,
    val hp: Double,
    val load: Double,
    val secPerTile: Double,
    val cost: Cost,
    val trainSec: Double,
) {
    /** Combat power = attack + defense + HP ÷ 10, with base values without bonuses. */
    val power: Double get() = atk + def + hp / 10.0
}

data class MonsterStats(val count: Int, val atk: Double, val def: Double, val hp: Double)

data class ZombieReward(val food: Long, val wood: Long, val steel: Long, val heroXp: Long, val drop: ItemId)

data class NestReward(val food: Long, val wood: Long, val steel: Long, val speedups: Int, val speedupItem: ItemId, val heroXp: Long)

/**
 * All formulas of the concept in one place. Server and client use the same class so displayed
 * costs and durations match the server exactly.
 *
 * Durations are returned in milliseconds (whole seconds, at least 1 s). `gameSpeed` divides all
 * durations and multiplies production and gathering rates.
 */
class Rules(val balance: Balance, val gameSpeed: Double = 1.0) {
    init {
        require(gameSpeed > 0.0) { "gameSpeed muss > 0 sein" }
    }

    // ---------------------------------------------------------------- General

    /** T = ⌈T₀ / ((1 + B) · g)⌉ seconds, at least 1 s, as ms. */
    fun durationMs(baseSec: Double, speedBonus: Double = 0.0): Long =
        max(1L, ceilSafe(baseSec / ((1.0 + speedBonus) * gameSpeed))) * MS_PER_SEC

    /** W = ⌊W₀ · (1 + B)⌋ */
    fun value(w0: Double, bonus: Double = 0.0): Long = floorSafe(w0 * (1.0 + bonus))

    // ---------------------------------------------------------------- Buildings

    fun building(type: BuildingType): BuildingBalance = balance.buildings.getValue(type)

    fun buildingCost(type: BuildingType, level: Int): Cost {
        val b = building(type)
        val f = b.costGrowth.pow(level - 1)
        return Cost(floorSafe(b.baseCost[0] * f), floorSafe(b.baseCost[1] * f), floorSafe(b.baseCost[2] * f))
    }

    fun buildingTimeMs(type: BuildingType, level: Int, buildSpeedBonus: Double = 0.0): Long {
        val b = building(type)
        return durationMs(growth(b.baseTimeSec, b.timeGrowth, level), buildSpeedBonus)
    }

    /** Production per hour of a resource building (with production bonus, without gameSpeed). */
    fun productionPerHour(type: BuildingType, level: Int, bonus: Double = 0.0): Long {
        if (level <= 0) return 0
        val b = building(type)
        return value(growth(b.prodPerHour!!, b.prodGrowth!!, level), bonus)
    }

    fun producedResource(type: BuildingType): Resource? = when (type) {
        BuildingType.FARM -> Resource.FOOD
        BuildingType.SAWMILL -> Resource.WOOD
        BuildingType.STEEL_MILL -> Resource.STEEL
        else -> null
    }

    /** Warehouse capacity K per resource. */
    fun storageCapacity(warehouseLevel: Int, storageBonus: Double = 0.0): Long {
        if (warehouseLevel <= 0) return 0
        val b = building(BuildingType.WAREHOUSE)
        return value(growth(b.capacityBase!!, b.capacityGrowth!!, warehouseLevel), storageBonus)
    }

    /** Protected share of K (rounded down). */
    fun protectedAmount(capacity: Long): Long = floorSafe(capacity * building(BuildingType.WAREHOUSE).protectedShare!!)

    fun orderSize(trainingBuilding: BuildingType, level: Int): Int {
        if (level <= 0) return 0
        val b = building(trainingBuilding)
        return b.orderBase!! + b.orderPerLevel!! * (level - 1)
    }

    fun hospitalCapacity(level: Int, bonus: Double = 0.0): Long {
        if (level <= 0) return 0
        val b = building(BuildingType.HOSPITAL)
        return value(growth(b.capacityBase!!, b.capacityGrowth!!, level), bonus)
    }

    fun marchSize(rallyPointLevel: Int, bonus: Double = 0.0): Long {
        if (rallyPointLevel <= 0) return 0
        val b = building(BuildingType.RALLY_POINT)
        return value((b.marchBase!! + b.marchPerLevel!! * (rallyPointLevel - 1)).toDouble(), bonus)
    }

    /** Maximum number of alliance helps per timer: a fixed value without alliance center, otherwise base + ⌊L / divisor⌋. */
    fun helpMax(allianceCenterLevel: Int): Int {
        if (allianceCenterLevel <= 0) return balance.alliance.helpsWithoutCenter
        val b = building(BuildingType.ALLIANCE_CENTER)
        return b.helpBase!! + allianceCenterLevel / b.helpLevelDivisor!!
    }

    /** Reinforcement capacity. None without an alliance center. */
    fun reinforceCapacity(allianceCenterLevel: Int): Long {
        if (allianceCenterLevel <= 0) return 0
        val b = building(BuildingType.ALLIANCE_CENTER)
        return (b.reinforceBase!! + b.reinforcePerLevel!! * (allianceCenterLevel - 1)).toLong()
    }

    /** Wall bonus on defense. */
    fun wallDefenseBonus(wallLevel: Int): Double = building(BuildingType.WALL).defensePerLevel!! * wallLevel

    /** Lab level required by research level n (2n − 1). */
    fun labRequiredFor(researchLevel: Int): Int {
        val b = building(BuildingType.LAB)
        return b.labPerResearchLevel!! * researchLevel + b.labOffset!!
    }

    /** Level of the training building from which a troop tier can be trained. */
    fun tierUnlockLevel(tier: Int): Int = balance.tiers.unlockLevels[tier - 1]

    /** Highest unlocked troop tier for a given building level (0 = none). */
    fun maxTierFor(buildingLevel: Int): Int = balance.tiers.unlockLevels.count { buildingLevel >= it }

    // ---------------------------------------------------------------- Research

    fun researchCost(level: Int): Cost {
        val r = balance.research
        val f = r.costGrowth.pow(level - 1)
        return Cost(floorSafe(r.baseCost[0] * f), floorSafe(r.baseCost[1] * f), floorSafe(r.baseCost[2] * f))
    }

    fun researchTimeMs(level: Int, researchSpeedBonus: Double = 0.0): Long =
        durationMs(growth(balance.research.baseTimeSec, balance.research.timeGrowth, level), researchSpeedBonus)

    // ---------------------------------------------------------------- Troops

    fun unitStats(type: UnitType, tier: Int): UnitStats {
        val u = balance.units.getValue(type)
        val t = balance.tiers
        val s = t.statGrowth.pow(tier - 1)
        val cost = Cost(floorSafe(u.cost[0] * s), floorSafe(u.cost[1] * s), floorSafe(u.cost[2] * s))
        return UnitStats(
            atk = u.atk * s,
            def = u.def * s,
            hp = u.hp * s,
            load = u.load * t.loadGrowth.pow(tier - 1),
            secPerTile = u.secPerTile,
            cost = cost,
            trainSec = u.trainSec * t.trainGrowth.pow(tier - 1),
        )
    }

    fun trainCost(type: UnitType, tier: Int, count: Int): Cost = unitStats(type, tier).cost * count.toLong()

    fun trainTimeMs(type: UnitType, tier: Int, count: Int, trainSpeedBonus: Double = 0.0): Long =
        durationMs(unitStats(type, tier).trainSec * count, trainSpeedBonus)

    /** Healing costs: 50 % of the training costs (rounded down per resource). */
    fun healCost(units: List<TroopCount>): Cost {
        val share = balance.hospital.healCostShare
        var total = Cost.ZERO
        units.forEach { u ->
            val c = trainCost(u.type, u.tier, u.count)
            total += Cost(floorSafe(c.food * share), floorSafe(c.wood * share), floorSafe(c.steel * share))
        }
        return total
    }

    /** Healing time: 30 % of the training time of all selected units, with healing speed bonus. */
    fun healTimeMs(units: List<TroopCount>, healSpeedBonus: Double = 0.0): Long {
        val sec = units.sumOf { unitStats(it.type, it.tier).trainSec * it.count } * balance.hospital.healTimeShare
        return durationMs(sec, healSpeedBonus)
    }

    /**
     * Player power: combat power of all own units except wounded, plus a fixed value per building,
     * research and hero level (concept section 11).
     */
    fun playerPower(troops: List<TroopCount>, buildingLevels: Int, researchLevels: Int, heroLevels: Int): Long {
        val r = balance.rankings
        return power(troops) + r.powerPerBuildingLevel * buildingLevels + r.powerPerResearchLevel * researchLevels +
            r.powerPerHeroLevel * heroLevels
    }

    /** Combat power of a troop list (base values without bonuses). */
    fun power(troops: List<TroopCount>): Long = floorSafe(troops.sumOf { unitStats(it.type, it.tier).power * it.count })

    /** Load of a troop list including load bonuses. */
    fun load(troops: List<TroopCount>, loadBonus: Double = 0.0): Long =
        value(troops.sumOf { unitStats(it.type, it.tier).load * it.count }, loadBonus)

    /** Speed of the slowest unit (largest value in s per tile). */
    fun slowestSecPerTile(troops: List<TroopCount>): Double = troops.filter { it.count > 0 }.maxOf { balance.units.getValue(it.type).secPerTile }

    // ---------------------------------------------------------------- Heroes

    /** Experience to level up from L to L+1. */
    fun heroXpForNext(level: Int): Long = floorSafe(growth(balance.heroes.xpBase, balance.heroes.xpGrowth, level))

    /** Applies experience and returns the new level and remaining experience. At max level no more experience is gained. */
    fun applyHeroXp(level: Int, xp: Long, gained: Long): Pair<Int, Long> {
        val maxLevel = balance.heroes.maxLevel
        if (level >= maxLevel) return maxLevel to 0L
        var l = level
        var x = xp + gained
        while (l < maxLevel && x >= heroXpForNext(l)) {
            x -= heroXpForNext(l)
            l++
        }
        if (l >= maxLevel) x = 0
        return l to x
    }

    fun heroUnlockHq(hero: HeroId): Int = balance.heroes.heroes.getValue(hero).unlockHq

    // ---------------------------------------------------------------- Map

    fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Double {
        val dx = (x1 - x2).toDouble()
        val dy = (y1 - y2).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    /** Zone by distance r from the center: 3 (core), 2 (middle), 1 (outer). */
    fun zoneOf(x: Int, y: Int): Int {
        val m = balance.map
        val r = distance(x, y, m.centerX, m.centerY)
        return when {
            r <= m.zone3MaxR -> 3
            r <= m.zone2MaxR -> 2
            else -> 1
        }
    }

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until balance.map.width && y in 0 until balance.map.height

    /** Travel time T = ⌈d · s / ((1 + B) · g)⌉ seconds as ms. */
    fun marchTimeMs(distance: Double, secPerTile: Double, marchSpeedBonus: Double = 0.0): Long =
        durationMs(distance * secPerTile, marchSpeedBonus)

    fun zombie(level: Int): MonsterStats {
        val z = balance.zombies
        val s = z.statGrowth.pow(level - 1)
        return MonsterStats(floorSafe(growth(z.countBase, z.countGrowth, level)).toInt(), z.atkBase * s, z.defBase * s, z.hpBase * s)
    }

    fun zombieReward(level: Int): ZombieReward {
        val z = balance.zombies
        val fw = floorSafe(growth(z.rewardBase, z.rewardGrowth, level))
        val steel = floorSafe(growth(z.rewardBase, z.rewardGrowth, level) * z.steelShare)
        val drop = z.drops.first { level <= it.maxLevel }.item
        return ZombieReward(fw, fw, steel, floorSafe(growth(z.xpBase, z.xpGrowth, level)), drop)
    }

    fun nest(level: Int): MonsterStats {
        val n = balance.nests
        val s = n.statGrowth.pow(level - 1)
        return MonsterStats(floorSafe(growth(n.countBase, n.countGrowth, level)).toInt(), n.atkBase * s, n.defBase * s, n.hpBase * s)
    }

    fun nestReward(level: Int): NestReward {
        val n = balance.nests
        val fw = floorSafe(growth(n.rewardFoodWoodBase, n.rewardGrowth, level))
        val st = floorSafe(growth(n.rewardSteelBase, n.rewardGrowth, level))
        return NestReward(fw, fw, st, n.speedupsPerLevel * level, n.speedupItem, floorSafe(growth(n.xpBase, n.xpGrowth, level)))
    }

    /** Supply of a resource field. */
    fun fieldStock(level: Int, resource: Resource): Long {
        val f = balance.fields
        val base = growth(f.stockBase, f.stockGrowth, level)
        return floorSafe(if (resource == Resource.STEEL) base * f.steelShare else base)
    }

    /** Gathering rate per hour of a march on a field, with gathering speed bonus and gameSpeed. */
    fun gatherRatePerHour(level: Int, resource: Resource, gatherSpeedBonus: Double = 0.0): Double {
        val f = balance.fields
        val base = f.rateBase + f.ratePerLevel * (level - 1)
        val r = if (resource == Resource.STEEL) base * f.steelShare else base
        return value(r, gatherSpeedBonus) * gameSpeed
    }

    // ---------------------------------------------------------------- Timer helpers

    /** Each alliance help shortens by max(60 s ÷ g, 1 % of the original total duration). */
    fun helpReductionMs(totalMs: Long): Long {
        val a = balance.alliance
        return max(ceilSafe(a.helpMinSec * MS_PER_SEC / gameSpeed), floorSafe(totalMs * a.helpShare))
    }

    /** Effect of a speed-up (duration divided by g). */
    fun speedupMs(item: ItemId): Long = floorSafe(balance.items.speedups.getValue(item) * MS_PER_SEC / gameSpeed)

    /** Waiting time of a rally (divided by g). */
    fun rallyWaitMs(minutes: Int): Long = max(MS_PER_SEC, floorSafe(minutes * 60.0 * MS_PER_SEC / gameSpeed))

    /** Chest size by HQ level when claimed. */
    fun chestSizeFor(hqLevel: Int): ChestSize = balance.daily.chestSizes.first { hqLevel <= it.maxHq }.size

    /** Hours → ms (protection and shield durations are not divided by gameSpeed). */
    fun hoursMs(hours: Double): Long = floorSafe(hours * MS_PER_HOUR)

    fun daysMs(days: Double): Long = floorSafe(days * MS_PER_DAY)

    /** Production bonus kind for a resource. */
    fun prodBonus(bonuses: Bonuses, r: Resource): Double = bonuses[BonusKind.prod(r)]
}
