package bayern.kickner.ruinborn.server

import bayern.kickner.ruinborn.server.engine.build
import bayern.kickner.ruinborn.server.engine.cancelTimer
import bayern.kickner.ruinborn.server.engine.claimAchievement
import bayern.kickner.ruinborn.server.engine.claimDaily
import bayern.kickner.ruinborn.server.engine.demolish
import bayern.kickner.ruinborn.server.engine.heal
import bayern.kickner.ruinborn.server.engine.research
import bayern.kickner.ruinborn.server.engine.speedup
import bayern.kickner.ruinborn.server.engine.train
import bayern.kickner.ruinborn.server.engine.useItem
import bayern.kickner.ruinborn.server.engine.changeTroops
import bayern.kickner.ruinborn.server.engine.addItem
import bayern.kickner.ruinborn.server.engine.credit
import bayern.kickner.ruinborn.server.engine.setHeroLevel
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.dto.BuildRequest
import bayern.kickner.ruinborn.shared.dto.HealRequest
import bayern.kickner.ruinborn.shared.dto.ItemUseRequest
import bayern.kickner.ruinborn.shared.dto.TrainRequest
import bayern.kickner.ruinborn.shared.model.AchievementId
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.Tech
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.UnitType
import bayern.kickner.ruinborn.shared.rules.MS_PER_HOUR
import kotnexlib.ResultOf2
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BaseFlowTest {
    private val w = TestWorld()

    @AfterTest
    fun tearDown() = w.close()

    private fun <T> expectError(code: ErrorCode, r: bayern.kickner.ruinborn.server.engine.Res<T>) {
        assertIs<ResultOf2.Failure<*>>(r, "Fehler $code erwartet, war $r")
        assertEquals(code, (r.value as bayern.kickner.ruinborn.server.engine.GameError).code)
    }

    @Test
    fun startStateMatchesConcept() {
        val pid = w.register("max")
        val s = w.state(pid)
        assertEquals(listOf(2000L, 2000L, 1000L), s.resources.map { it.amount })
        assertEquals(5000L, s.resources.first().capacity)
        assertEquals(1250L, s.resources.first().protectedAmount)
        assertEquals(listOf(100L, 100L, 50L), s.resources.map { it.perHour })
        val levels = s.buildings.associate { it.plot to (it.type to it.level) }
        assertEquals(BuildingType.FARM to 1, levels[Plot.R1])
        assertEquals(BuildingType.SAWMILL to 1, levels[Plot.R2])
        assertEquals(BuildingType.STEEL_MILL to 1, levels[Plot.R3])
        assertEquals(setOf(Plot.HQ, Plot.MAUER, Plot.LAGER, Plot.KASERNE, Plot.LAZARETT, Plot.SAMMELPUNKT, Plot.R1, Plot.R2, Plot.R3), levels.keys)
        assertEquals(200, s.troops.single { it.type == UnitType.INFANTRY && it.tier == 1 }.home)
        assertEquals(listOf(HeroId.RHEA), s.heroes.filter { it.unlocked }.map { it.hero })
        assertEquals(5, s.items.single { it.item == ItemId.SPEED_5M }.count)
        assertEquals(T0 + 72 * MS_PER_HOUR, s.protectionUntil)
        assertEquals(1, w.app.game.rules.zoneOf(s.base.x, s.base.y))
        assertEquals(AchievementId.HQ_2, s.achievements.first { it.completed.not() }.id)
        assertEquals(6400L + 50 * 9 + 200, s.power)
    }

    @Test
    fun buildQueuesCostsAndCompletion() {
        val pid = w.register("max")
        w.setLevels(pid, Plot.HQ to 3, Plot.MAUER to 2)
        w.exec { build(pid, BuildRequest(Plot.MAUER)) }
        w.exec { build(pid, BuildRequest(Plot.LAGER)) }
        // Wall 3 costs 588/784/392, warehouse 2 costs 280/420/140
        assertEquals(2000L - 588 - 280, w.amount(pid, Resource.FOOD))
        expectError(ErrorCode.QUEUE_FULL, w.tryExec { build(pid, BuildRequest(Plot.KASERNE)) })
        expectError(ErrorCode.QUEUE_FULL, w.tryExec { build(pid, BuildRequest(Plot.MAUER)) })
        // Wall 3 takes ⌈152.1⌉ = 153 s, warehouse 2 78 s
        w.advance(78_000)
        assertEquals(1, w.state(pid).timers.size)
        w.advance(75_000)
        val s = w.state(pid)
        assertEquals(3, s.buildings.single { it.plot == Plot.MAUER }.level)
        assertEquals(2, s.buildings.single { it.plot == Plot.LAGER }.level)
        assertTrue(s.timers.isEmpty())
    }

    @Test
    fun firstHqUpgradeAndAchievement() {
        val pid = w.register("max")
        expectError(ErrorCode.REQUIREMENT_NOT_MET, w.tryExec { build(pid, BuildRequest(Plot.LAGER)) })
        w.exec { build(pid, BuildRequest(Plot.HQ)) }
        w.exec { build(pid, BuildRequest(Plot.R4, BuildingType.FARM)) }
        assertEquals(2000L - 700 - 120, w.amount(pid, Resource.FOOD))
        w.advance(156_000)
        val s = w.state(pid)
        assertEquals(2, s.buildings.single { it.plot == Plot.HQ }.level)
        assertEquals(BuildingType.FARM, s.buildings.single { it.plot == Plot.R4 }.type)
        assertTrue(s.achievements.single { it.id == AchievementId.HQ_2 }.completed)
        w.exec { claimAchievement(pid, AchievementId.HQ_2) }
        assertEquals(7, w.state(pid).items.single { it.item == ItemId.SPEED_5M }.count)
        expectError(ErrorCode.VALIDATION, w.tryExec { claimAchievement(pid, AchievementId.HQ_2) })
    }

    @Test
    fun hqRequiresWallAndBuildingsCannotExceedHq() {
        val pid = w.register("max")
        w.setLevels(pid, Plot.HQ to 2)
        expectError(ErrorCode.REQUIREMENT_NOT_MET, w.tryExec { build(pid, BuildRequest(Plot.HQ)) })
        w.setLevels(pid, Plot.MAUER to 2)
        expectError(ErrorCode.REQUIREMENT_NOT_MET, w.tryExec { build(pid, BuildRequest(Plot.MAUER)) })
        expectError(ErrorCode.REQUIREMENT_NOT_MET, w.tryExec { build(pid, BuildRequest(Plot.R5, BuildingType.FARM)) })
        expectError(ErrorCode.REQUIREMENT_NOT_MET, w.tryExec { build(pid, BuildRequest(Plot.LABOR)) })
        w.exec { build(pid, BuildRequest(Plot.FABRIK)) }
        w.exec { build(pid, BuildRequest(Plot.R4, BuildingType.FARM)) }
        expectError(ErrorCode.VALIDATION, w.tryExec { build(pid, BuildRequest(Plot.R6, BuildingType.HQ)) })
    }

    @Test
    fun cancelRefundsHalf() {
        val pid = w.register("max")
        w.exec { build(pid, BuildRequest(Plot.HQ)) }
        val t = w.state(pid).timers.single()
        w.exec { cancelTimer(pid, t.id) }
        assertEquals(2000L - 700 + 350, w.amount(pid, Resource.FOOD))
        assertEquals(1000L - 350 + 175, w.amount(pid, Resource.STEEL))
    }

    @Test
    fun speedupShortensAndOverflowIsLost() {
        val pid = w.register("max")
        w.exec { build(pid, BuildRequest(Plot.HQ)) }
        val t = w.state(pid).timers.single()
        w.exec { speedup(pid, t.id, ItemId.SPEED_5M, 1) }
        val s = w.state(pid)
        assertTrue(s.timers.isEmpty(), "5 min > 156 s: Timer sofort fertig")
        assertEquals(2, s.buildings.single { it.plot == Plot.HQ }.level)
        assertEquals(4, s.items.single { it.item == ItemId.SPEED_5M }.count)
        expectError(ErrorCode.NOT_FOUND, w.tryExec { speedup(pid, 999, ItemId.SPEED_5M, 1) })
        expectError(ErrorCode.VALIDATION, w.tryExec { speedup(pid, 999, ItemId.SHIELD_8H, 1) })
    }

    @Test
    fun offlineDaysStopAtCapacityAndProcessTimersInOrder() {
        val pid = w.register("max")
        w.setLevels(pid, Plot.HQ to 2)
        w.exec { build(pid, BuildRequest(Plot.LAGER)) }
        w.exec { build(pid, BuildRequest(Plot.R1)) }
        // 3 days offline: warehouse 2 = 6,600, farm 2 = 125/h
        w.advance(72 * MS_PER_HOUR)
        val s = w.state(pid)
        assertEquals(6600L, s.resources.first().capacity)
        // Steel: 1,000 − 140 (warehouse 2) − 70 (farm 2) + 50 × 72
        assertEquals(listOf(6600L, 6600L, 4390L), s.resources.map { it.amount })
        assertEquals(125L, s.resources.first().perHour)
    }

    @Test
    fun productionIsExactAcrossTimerCompletion() {
        val pid = w.register("max")
        w.setLevels(pid, Plot.HQ to 2)
        w.exec { build(pid, BuildRequest(Plot.R1)) } // farm 2 after 58.5 s → 59 s
        val food0 = w.amount(pid, Resource.FOOD)
        w.advance(MS_PER_HOUR)
        // 59 s at 100/h, rest of the hour at 125/h
        val expected = food0 + (100.0 * 59 / 3600 + 125.0 * (3600 - 59) / 3600).toLong()
        assertEquals(expected, w.amount(pid, Resource.FOOD))
    }

    @Test
    fun demolishOnlyResourcePlots() {
        val pid = w.register("max")
        w.exec { demolish(pid, Plot.R1) }
        assertTrue(w.state(pid).buildings.none { it.plot == Plot.R1 })
        expectError(ErrorCode.VALIDATION, w.tryExec { demolish(pid, Plot.HQ) })
        w.exec { build(pid, BuildRequest(Plot.R1, BuildingType.SAWMILL)) }
        // New construction running, no building on the slot yet
        expectError(ErrorCode.NOT_FOUND, w.tryExec { demolish(pid, Plot.R1) })
    }

    @Test
    fun researchNeedsLab() {
        val pid = w.register("max")
        expectError(ErrorCode.REQUIREMENT_NOT_MET, w.tryExec { research(pid, Tech.AGRICULTURE) })
        w.setLevels(pid, Plot.HQ to 3, Plot.LABOR to 1)
        w.run { credit(pid, Cost(10_000, 10_000, 10_000)) }
        w.exec { research(pid, Tech.AGRICULTURE) }
        expectError(ErrorCode.QUEUE_FULL, w.tryExec { research(pid, Tech.DRILL) })
        w.advance(300_000)
        val s = w.state(pid)
        assertEquals(1, s.research.single { it.tech == Tech.AGRICULTURE }.level)
        assertEquals(105L, s.resources.first().perHour)
        // Level 2 requires lab 3
        expectError(ErrorCode.REQUIREMENT_NOT_MET, w.tryExec { research(pid, Tech.AGRICULTURE) })
        assertTrue(s.achievements.single { it.id == AchievementId.FIRST_RESEARCH }.completed)
    }

    @Test
    fun trainingAndDrillDailyTask() {
        val pid = w.register("max")
        expectError(ErrorCode.VALIDATION, w.tryExec { train(pid, TrainRequest(BuildingType.BARRACKS, 1, 101)) })
        expectError(ErrorCode.REQUIREMENT_NOT_MET, w.tryExec { train(pid, TrainRequest(BuildingType.BARRACKS, 2, 10)) })
        w.exec { train(pid, TrainRequest(BuildingType.BARRACKS, 1, 50)) }
        assertEquals(2000L - 50 * 30, w.amount(pid, Resource.FOOD))
        assertEquals(50, w.state(pid).daily.single { it.task == DailyTask.DRILL }.progress)
        w.advance(500_000)
        assertEquals(250, w.state(pid).troops.single().home)
    }

    @Test
    fun healingCostsHalfAndReturnsUnits() {
        val pid = w.register("max")
        w.run { changeTroops(pid, UnitType.INFANTRY, 1, -20, 20) }
        w.exec { heal(pid, HealRequest(listOf(TroopCount(UnitType.INFANTRY, 1, 20)))) }
        assertEquals(2000L - 300, w.amount(pid, Resource.FOOD))
        expectError(ErrorCode.QUEUE_FULL, w.tryExec { heal(pid, HealRequest(listOf(TroopCount(UnitType.INFANTRY, 1, 1)))) })
        w.advance(60_000)
        val t = w.state(pid).troops.single()
        assertEquals(200, t.home)
        assertEquals(0, t.wounded)
    }

    @Test
    fun itemsChestsShieldsBooks() {
        val pid = w.register("max")
        w.run {
            addItem(pid, ItemId.RES_FOOD_L, 2)
            addItem(pid, ItemId.SHIELD_8H, 1)
            addItem(pid, ItemId.HERO_XP_S, 1)
        }
        w.exec { useItem(pid, ItemUseRequest(ItemId.RES_FOOD_L, 2)) }
        assertEquals(102_000L, w.amount(pid, Resource.FOOD), "Kisten dürfen die Kapazität überschreiten")
        w.exec { useItem(pid, ItemUseRequest(ItemId.SHIELD_8H)) }
        assertEquals(T0 + 8 * MS_PER_HOUR, w.state(pid).shieldUntil)
        w.exec { useItem(pid, ItemUseRequest(ItemId.HERO_XP_S, heroId = HeroId.RHEA)) }
        // 500 XP: 100 + 125 + 156 → level 4, 119 left
        val rhea = w.state(pid).heroes.single { it.hero == HeroId.RHEA }
        assertEquals(4, rhea.level)
        assertEquals(119L, rhea.xp)
        w.run { setHeroLevel(pid, HeroId.RHEA, 30, 0); addItem(pid, ItemId.HERO_XP_S, 1) }
        expectError(ErrorCode.VALIDATION, w.tryExec { useItem(pid, ItemUseRequest(ItemId.HERO_XP_S, heroId = HeroId.RHEA)) })
        expectError(ErrorCode.VALIDATION, w.tryExec { useItem(pid, ItemUseRequest(ItemId.SPEED_5M)) })
    }

    @Test
    fun dailyTaskClaimAndBonus() {
        val pid = w.register("max")
        w.exec { build(pid, BuildRequest(Plot.HQ)) }
        w.exec { claimDaily(pid, DailyTask.BUILDER) }
        assertEquals(1, w.state(pid).items.single { it.item == ItemId.RES_FOOD_S }.count, "HQ 1: kleine Kiste")
        expectError(ErrorCode.VALIDATION, w.tryExec { claimDaily(pid, DailyTask.BUILDER) })
        expectError(ErrorCode.REQUIREMENT_NOT_MET, w.tryExec { claimDaily(pid, DailyTask.BONUS) })
        expectError(ErrorCode.REQUIREMENT_NOT_MET, w.tryExec { claimDaily(pid, DailyTask.ZOMBIE_HUNT) })
    }

    @Test
    fun newbieProtectionEndsAtHq6() {
        val pid = w.register("max")
        w.setLevels(pid, Plot.HQ to 5, Plot.MAUER to 5)
        w.run { credit(pid, Cost(100_000, 100_000, 100_000)) }
        w.exec { build(pid, BuildRequest(Plot.HQ)) }
        w.advance(2 * MS_PER_HOUR)
        val s = w.state(pid)
        assertTrue(s.protectionUntil < w.clock.now())
        assertTrue(s.heroes.single { it.hero == HeroId.VIKTOR }.unlocked)
    }
}
