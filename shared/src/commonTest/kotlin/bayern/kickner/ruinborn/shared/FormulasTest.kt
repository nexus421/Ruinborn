package bayern.kickner.ruinborn.shared

import bayern.kickner.ruinborn.shared.balance.BalanceCodec
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.Tech
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.UnitType
import bayern.kickner.ruinborn.shared.rules.Bonuses
import bayern.kickner.ruinborn.shared.rules.Rules
import kotlin.test.Test
import kotlin.test.assertEquals

/** Samples from the concept (sections 2 to 6). */
class FormulasTest {
    private val b = BalanceCodec.default
    private val rules = Rules(b)

    @Test
    fun hq10CostsAndTimeMatchConcept() {
        assertEquals(Cost(10_330, 10_330, 5_165), rules.buildingCost(BuildingType.HQ, 10))
        assertEquals(1_273_000L, rules.buildingTimeMs(BuildingType.HQ, 10)) // 21 min 13 s
    }

    @Test
    fun hq20CostsAndTimeMatchConcept() {
        assertEquals(Cost(298_815, 298_815, 149_407), rules.buildingCost(BuildingType.HQ, 20))
        assertEquals((4 * 3600 + 52 * 60 + 24) * 1000L, rules.buildingTimeMs(BuildingType.HQ, 20))
    }

    @Test
    fun level1IsBaseValue() {
        assertEquals(Cost(500, 500, 250), rules.buildingCost(BuildingType.HQ, 1))
        assertEquals(120_000L, rules.buildingTimeMs(BuildingType.HQ, 1))
    }

    @Test
    fun productionAndCapacity() {
        assertEquals(6_938L, rules.productionPerHour(BuildingType.FARM, 20))
        assertEquals(100L, rules.productionPerHour(BuildingType.FARM, 1))
        assertEquals(50L, rules.productionPerHour(BuildingType.STEEL_MILL, 1))
        assertEquals(976_955L, rules.storageCapacity(20))
        assertEquals(5_000L, rules.storageCapacity(1))
        assertEquals(1_250L, rules.protectedAmount(5_000))
        // Storage technology +5 % per level also increases the protected amount
        assertEquals(5_250L, rules.storageCapacity(1, 0.05))
    }

    @Test
    fun buildingEffects() {
        assertEquals(100, rules.orderSize(BuildingType.BARRACKS, 1))
        assertEquals(250, rules.orderSize(BuildingType.BARRACKS, 4))
        assertEquals(300L, rules.hospitalCapacity(1))
        assertEquals(500L, rules.marchSize(1))
        assertEquals(1_700L, rules.marchSize(4))
        assertEquals(3, rules.helpMax(0))
        assertEquals(5, rules.helpMax(1))
        assertEquals(15, rules.helpMax(20))
        assertEquals(0L, rules.reinforceCapacity(0))
        assertEquals(2_000L, rules.reinforceCapacity(1))
        assertEquals(0.1, rules.wallDefenseBonus(5), 1e-12)
        assertEquals(19, rules.labRequiredFor(10))
        assertEquals(1, rules.labRequiredFor(1))
    }

    @Test
    fun researchLevel10MatchesConcept() {
        assertEquals(Cost(68_719, 68_719, 34_359), rules.researchCost(10))
        assertEquals((1 * 3600 + 43 * 60 + 19) * 1000L, rules.researchTimeMs(10))
    }

    @Test
    fun unitTiers() {
        val inf1 = rules.unitStats(UnitType.INFANTRY, 1)
        assertEquals(32.0, inf1.power, 1e-9)
        val inf2 = rules.unitStats(UnitType.INFANTRY, 2)
        assertEquals(16.0, inf2.atk, 1e-9)
        assertEquals(Cost(48, 32, 8), inf2.cost)
        assertEquals(15.6, inf2.load, 1e-9)
        assertEquals(14.0, inf2.trainSec, 1e-9)
        assertEquals(30.0, inf2.secPerTile, 1e-9)
        assertEquals(6.5536, rules.unitStats(UnitType.SHOOTER, 5).atk / 14.0, 1e-9)
        assertEquals(1, rules.maxTierFor(1))
        assertEquals(2, rules.maxTierFor(5))
        assertEquals(5, rules.maxTierFor(20))
        assertEquals(1_000_000L, rules.trainTimeMs(UnitType.INFANTRY, 1, 100))
        assertEquals(Cost(1500, 1000, 250), rules.healCost(listOf(TroopCount(UnitType.INFANTRY, 1, 100))))
        assertEquals(300_000L, rules.healTimeMs(listOf(TroopCount(UnitType.INFANTRY, 1, 100))))
    }

    @Test
    fun zombies() {
        val z1 = rules.zombie(1)
        assertEquals(60, z1.count)
        val z20 = rules.zombie(20)
        assertEquals(4_163, z20.count)
        assertEquals(77.51, z20.atk, 0.01)
        assertEquals(68.90, z20.def, 0.01)
        assertEquals(775.15, z20.hp, 0.01)
        val r1 = rules.zombieReward(1)
        assertEquals(400L, r1.food)
        assertEquals(160L, r1.steel)
        assertEquals(50L, r1.heroXp)
        assertEquals(ItemId.SPEED_5M, r1.drop)
        assertEquals(ItemId.SPEED_3H, rules.zombieReward(16).drop)
    }

    @Test
    fun nestsAndFields() {
        assertEquals(3_000, rules.nest(1).count)
        assertEquals(12_000, rules.nest(3).count)
        val nr = rules.nestReward(2)
        assertEquals(40_000L, nr.food)
        assertEquals(16_000L, nr.steel)
        assertEquals(2, nr.speedups)
        assertEquals(30_000L, rules.fieldStock(1, Resource.FOOD))
        assertEquals(15_000L, rules.fieldStock(1, Resource.STEEL))
        assertEquals(240_000L, rules.fieldStock(4, Resource.WOOD))
        assertEquals(4_000.0, rules.gatherRatePerHour(1, Resource.FOOD), 1e-9)
        assertEquals(2_500.0, rules.gatherRatePerHour(2, Resource.STEEL), 1e-9)
    }

    @Test
    fun zonesAndDistance() {
        assertEquals(3, rules.zoneOf(50, 50))
        assertEquals(3, rules.zoneOf(65, 50))
        assertEquals(2, rules.zoneOf(66, 50))
        assertEquals(2, rules.zoneOf(82, 50))
        assertEquals(1, rules.zoneOf(83, 50))
        assertEquals(1, rules.zoneOf(0, 0))
        assertEquals(5.0, rules.distance(0, 0, 3, 4), 1e-12)
    }

    @Test
    fun marchTime() {
        // 5 tiles, infantry 30 s per tile
        assertEquals(150_000L, rules.marchTimeMs(5.0, 30.0))
        // Logistics +30 %: ⌈150 / 1.3⌉ = 116 s
        assertEquals(116_000L, rules.marchTimeMs(5.0, 30.0, 0.3))
    }

    @Test
    fun gameSpeedDividesDurationsNotShields() {
        val fast = Rules(b, 20.0)
        assertEquals(6_000L, fast.buildingTimeMs(BuildingType.HQ, 1))
        assertEquals(15_000L, fast.speedupMs(ItemId.SPEED_5M))
        assertEquals(3_000L, fast.helpReductionMs(1_000L))
        assertEquals(8 * 3_600_000L, fast.hoursMs(8.0))
        assertEquals(80_000.0, fast.gatherRatePerHour(1, Resource.FOOD), 1e-9)
        // Duration at least 1 s
        assertEquals(1_000L, Rules(b, 10_000.0).buildingTimeMs(BuildingType.HQ, 1))
    }

    @Test
    fun helpReduction() {
        assertEquals(60_000L, rules.helpReductionMs(1_000_000L))
        assertEquals(100_000L, rules.helpReductionMs(10_000_000L))
    }

    @Test
    fun bonusesAreAdded() {
        val research = Bonuses.research(b, mapOf(Tech.DRILL to 10))
        val hero = Bonuses.hero(b, HeroId.RHEA, 10)
        val sum = research + hero
        assertEquals(0.3, sum[BonusKind.INFANTRY_ATK], 1e-12)
        assertEquals(0.0, sum[BonusKind.VEHICLE_ATK], 1e-12)
        assertEquals(0.5, Bonuses.catchup(b, true)[BonusKind.BUILD_SPEED], 1e-12)
        assertEquals(0.0, Bonuses.catchup(b, false)[BonusKind.BUILD_SPEED], 1e-12)
        // HQ 10 with construction technology 10 (+30 %) and catch-up bonus (+50 %): ⌈1272.54 / 1.8⌉ = 707 s
        val speed = (Bonuses.research(b, mapOf(Tech.CONSTRUCTION to 10)) + Bonuses.catchup(b, true))[BonusKind.BUILD_SPEED]
        assertEquals(707_000L, rules.buildingTimeMs(BuildingType.HQ, 10, speed))
    }

    @Test
    fun heroXp() {
        assertEquals(100L, rules.heroXpForNext(1))
        assertEquals(125L, rules.heroXpForNext(2))
        // 250 XP at level 1: 100 → level 2, 125 → level 3, 25 left
        assertEquals(3 to 25L, rules.applyHeroXp(1, 0, 250))
        assertEquals(30 to 0L, rules.applyHeroXp(29, 0, 1_000_000))
        assertEquals(30 to 0L, rules.applyHeroXp(30, 0, 100))
    }

    @Test
    fun power() {
        assertEquals(6_400L, rules.power(listOf(TroopCount(UnitType.INFANTRY, 1, 200))))
        assertEquals(6_400L + 50L * 9 + 200L, rules.playerPower(listOf(TroopCount(UnitType.INFANTRY, 1, 200)), 9, 0, 1))
    }

    @Test
    fun chestSizeByHq() {
        assertEquals("S", rules.chestSizeFor(7).name)
        assertEquals("M", rules.chestSizeFor(8).name)
        assertEquals("L", rules.chestSizeFor(15).name)
    }
}
