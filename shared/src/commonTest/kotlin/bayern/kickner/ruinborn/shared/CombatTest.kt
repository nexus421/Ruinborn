package bayern.kickner.ruinborn.shared

import bayern.kickner.ruinborn.shared.balance.BalanceCodec
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.combat.CombatStack
import bayern.kickner.ruinborn.shared.combat.distributeByShare
import bayern.kickner.ruinborn.shared.combat.loot
import bayern.kickner.ruinborn.shared.combat.simulateCombat
import bayern.kickner.ruinborn.shared.combat.splitCasualties
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.UnitType
import bayern.kickner.ruinborn.shared.rules.Rules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CombatTest {
    private val b = BalanceCodec.default
    private val rules = Rules(b)
    private val cfg = b.combat

    private fun stack(id: Int, owner: Long, type: UnitType, count: Int, tier: Int = 1): CombatStack {
        val s = rules.unitStats(type, tier)
        return CombatStack(id, owner, type, tier, count, s.atk, s.def, s.hp)
    }

    private fun zombies(id: Int, level: Int): CombatStack {
        val z = rules.zombie(level)
        return CombatStack(id, 0, null, level, z.count, z.atk, z.def, z.hp)
    }

    @Test
    fun conceptExample200InfantryVsZombiesLevel1() {
        val result = simulateCombat(listOf(stack(1, 7, UnitType.INFANTRY, 200)), listOf(zombies(2, 1)), cfg)
        assertEquals(1_851.85, result.firstRoundDamage.getValue(2), 0.01)
        assertEquals(482.14, result.firstRoundDamage.getValue(1), 0.01)
        assertEquals(3, result.rounds)
        assertTrue(result.attackerWon)
        assertEquals(60, result.lossOf(2))
        assertEquals(9, result.lossOf(1))
    }

    @Test
    fun equalArmiesGiveEqualLossesAndDefenderWinsMutualDestruction() {
        val result = simulateCombat(listOf(stack(1, 1, UnitType.INFANTRY, 500)), listOf(stack(2, 2, UnitType.INFANTRY, 500)), cfg)
        assertEquals(result.lossOf(1), result.lossOf(2))
        assertFalse(result.attackerWon)
    }

    @Test
    fun counterBonusApplies() {
        // Infantry counters shooters: 25 % more damage
        val vsShooter = simulateCombat(listOf(stack(1, 1, UnitType.INFANTRY, 100)), listOf(stack(2, 2, UnitType.SHOOTER, 100)), cfg)
        val vsVehicle = simulateCombat(listOf(stack(1, 1, UnitType.INFANTRY, 100)), listOf(stack(2, 2, UnitType.VEHICLE, 100)), cfg)
        val expectedVsShooter = 100 * 10.0 * 1.25 * 100 / (100 + 8)
        val expectedVsVehicle = 100 * 10.0 * 1.0 * 100 / (100 + 10)
        assertEquals(expectedVsShooter, vsShooter.firstRoundDamage.getValue(2), 1e-9)
        assertEquals(expectedVsVehicle, vsVehicle.firstRoundDamage.getValue(2), 1e-9)
    }

    @Test
    fun noCounterBonusAgainstZombies() {
        val r = simulateCombat(listOf(stack(1, 1, UnitType.INFANTRY, 100)), listOf(zombies(2, 1)), cfg)
        assertEquals(100 * 10.0 * 100 / 108, r.firstRoundDamage.getValue(2), 1e-9)
    }

    @Test
    fun roundLimitMeansDefenderWins() {
        // Very tough defenders, barely any damage: after 20 rounds the defender wins
        val att = CombatStack(1, 1, UnitType.INFANTRY, 1, 10, 0.1, 10.0, 100.0)
        val def = CombatStack(2, 2, UnitType.INFANTRY, 1, 10, 0.1, 10.0, 100_000.0)
        val r = simulateCombat(listOf(att), listOf(def), cfg)
        assertEquals(20, r.rounds)
        assertFalse(r.attackerWon)
    }

    @Test
    fun emptyBaseMeansWinWithoutFight() {
        val r = simulateCombat(listOf(stack(1, 1, UnitType.INFANTRY, 10)), emptyList(), cfg)
        assertTrue(r.attackerWon)
        assertFalse(r.fought)
        assertEquals(0, r.rounds)
    }

    @Test
    fun damageIsSplitByHpShare() {
        val defenders = listOf(stack(2, 2, UnitType.INFANTRY, 100), stack(3, 2, UnitType.VEHICLE, 100))
        val r = simulateCombat(listOf(stack(1, 1, UnitType.VEHICLE, 100)), defenders, cfg)
        val share2 = 100 * 100.0 / (100 * 100.0 + 100 * 120.0)
        // Vehicles counter infantry
        assertEquals(100 * 12.0 * share2 * 1.25 * 100 / 112, r.firstRoundDamage.getValue(2), 1e-9)
    }

    @Test
    fun casualtiesFillHospitalHighestTierFirstThenTypeOrder() {
        val losses = listOf(
            TroopCount(UnitType.SHOOTER, 1, 50),
            TroopCount(UnitType.INFANTRY, 1, 50),
            TroopCount(UnitType.VEHICLE, 2, 30),
        )
        val c = splitCasualties(losses, 60, b.combat.hospitalOrder)
        assertEquals(listOf(TroopCount(UnitType.VEHICLE, 2, 30), TroopCount(UnitType.INFANTRY, 1, 30)), c.wounded)
        assertEquals(listOf(TroopCount(UnitType.INFANTRY, 1, 20), TroopCount(UnitType.SHOOTER, 1, 50)), c.dead)
    }

    @Test
    fun casualtiesWithoutSpaceAllDie() {
        val c = splitCasualties(listOf(TroopCount(UnitType.INFANTRY, 1, 5)), 0, b.combat.hospitalOrder)
        assertTrue(c.wounded.isEmpty())
        assertEquals(5, c.dead.single().count)
    }

    @Test
    fun lootFormula() {
        assertEquals(Cost(500, 300, 200), loot(Cost(5_000, 3_000, 2_000), 1_000))
        assertEquals(Cost(100, 0, 50), loot(Cost(100, 0, 50), 10_000))
        assertEquals(Cost.ZERO, loot(Cost.ZERO, 10_000))
        assertEquals(Cost.ZERO, loot(Cost(100, 100, 100), 0))
    }

    @Test
    fun distributionByShareRoundsDown() {
        assertEquals(listOf(333L, 666L), distributeByShare(1_000, listOf(1, 2)))
        assertEquals(listOf(0L, 0L), distributeByShare(1_000, listOf(0, 0)))
    }
}
