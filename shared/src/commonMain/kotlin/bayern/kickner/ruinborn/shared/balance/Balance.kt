package bayern.kickner.ruinborn.shared.balance

import bayern.kickner.ruinborn.shared.model.AchievementId
import bayern.kickner.ruinborn.shared.model.AchievementKind
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.ChestSize
import bayern.kickner.ruinborn.shared.model.Cosmetic
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.Tech
import bayern.kickner.ruinborn.shared.model.UnitType
import kotlinx.serialization.Serializable

/**
 * All game numbers from `balance.json` (concept section 13, "balance file").
 * Each top-level key maps to exactly one data class. Required fields have no default values
 * so an incomplete file fails to decode. [validate] additionally checks value ranges
 * and the completeness of the maps.
 */
@Serializable
data class Balance(
    val version: Int,
    val start: StartBalance,
    val buildings: Map<BuildingType, BuildingBalance>,
    val resourcePlots: Map<Plot, Int>,
    val timers: TimersBalance,
    val research: ResearchBalance,
    val units: Map<UnitType, UnitBalance>,
    val tiers: TiersBalance,
    val hospital: HospitalBalance,
    val heroes: HeroesBalance,
    val map: MapBalance,
    val zombies: ZombiesBalance,
    val nests: NestsBalance,
    val fields: FieldsBalance,
    val spawn: SpawnBalance,
    val relocation: RelocationBalance,
    val marches: MarchesBalance,
    val combat: CombatBalance,
    val scouting: ScoutingBalance,
    val protection: ProtectionBalance,
    val catchup: CatchupBalance,
    val alliance: AllianceBalance,
    val rally: RallyBalance,
    val gifts: GiftsBalance,
    val items: ItemsBalance,
    val daily: DailyBalance,
    val achievements: List<AchievementBalance>,
    val cosmetics: CosmeticsBalance,
    val chat: ChatBalance,
    val reports: ReportsBalance,
    val rankings: RankingsBalance,
)

/** Amount per resource. A list `[F, W, S]` in `balance.json`, an object in DTOs. */
@Serializable
data class Cost(val food: Long = 0, val wood: Long = 0, val steel: Long = 0) {
    operator fun get(r: Resource): Long = when (r) {
        Resource.FOOD -> food
        Resource.WOOD -> wood
        Resource.STEEL -> steel
    }

    operator fun plus(o: Cost) = Cost(food + o.food, wood + o.wood, steel + o.steel)
    operator fun times(n: Long) = Cost(food * n, wood * n, steel * n)
    val total: Long get() = food + wood + steel
    fun isZero() = food == 0L && wood == 0L && steel == 0L

    companion object {
        val ZERO = Cost()
        fun of(list: List<Long>) = Cost(list[0], list[1], list[2])
        fun of(r: Resource, amount: Long) = when (r) {
            Resource.FOOD -> Cost(food = amount)
            Resource.WOOD -> Cost(wood = amount)
            Resource.STEEL -> Cost(steel = amount)
        }
    }
}

@Serializable
data class StartBalance(
    val buildings: Map<Plot, Int>,
    val resourceBuildings: Map<Plot, BuildingType>,
    val food: Long,
    val wood: Long,
    val steel: Long,
    val troops: List<StartTroop>,
    val heroes: List<HeroId>,
    val items: Map<ItemId, Int>,
    val spawnZone: Int,
    val minBaseDistance: Double,
)

@Serializable
data class StartTroop(val type: UnitType, val tier: Int, val count: Int)

/**
 * A building type. The general values apply to all types, the effect values only to the respective type
 * ([validate] reports missing required effects).
 */
@Serializable
data class BuildingBalance(
    val baseCost: List<Long>,
    val baseTimeSec: Double,
    val costGrowth: Double,
    val timeGrowth: Double,
    val maxLevel: Int,
    val unlockHq: Int,
    /** FARM, SAWMILL, STEEL_MILL: production per hour at level 1 and growth. */
    val prodPerHour: Double? = null,
    val prodGrowth: Double? = null,
    /** WAREHOUSE: capacity per resource, growth and protected share. */
    val capacityBase: Double? = null,
    val capacityGrowth: Double? = null,
    val protectedShare: Double? = null,
    /** WALL: defense bonus per level. */
    val defensePerLevel: Double? = null,
    /** BARRACKS, FACTORY, RANGE: order size = orderBase + orderPerLevel × (L−1). */
    val orderBase: Int? = null,
    val orderPerLevel: Int? = null,
    /** HOSPITAL: capacity = capacityBase × capacityGrowth^(L−1) (uses the capacity fields). */
    /** RALLY_POINT: march size = marchBase + marchPerLevel × (L−1). */
    val marchBase: Int? = null,
    val marchPerLevel: Int? = null,
    /** ALLIANCE_CENTER: helps = helpBase + ⌊L / helpLevelDivisor⌋, reinforcement capacity = reinforceBase + reinforcePerLevel × (L−1). */
    val helpBase: Int? = null,
    val helpLevelDivisor: Int? = null,
    val reinforceBase: Int? = null,
    val reinforcePerLevel: Int? = null,
    /** LAB: research level n requires lab ≥ labPerResearchLevel × n + labOffset. */
    val labPerResearchLevel: Int? = null,
    val labOffset: Int? = null,
)

@Serializable
data class TimersBalance(
    val buildQueues: Int,
    val cancelRefundShare: Double,
)

@Serializable
data class ResearchBalance(
    val baseCost: List<Long>,
    val costGrowth: Double,
    val baseTimeSec: Double,
    val timeGrowth: Double,
    val maxLevel: Int,
    val unlockHq: Int,
    /** Effect per research level. */
    val techs: Map<Tech, Map<BonusKind, Double>>,
)

@Serializable
data class UnitBalance(
    val atk: Double,
    val def: Double,
    val hp: Double,
    val load: Double,
    val secPerTile: Double,
    val cost: List<Long>,
    val trainSec: Double,
)

@Serializable
data class TiersBalance(
    val maxTier: Int,
    val statGrowth: Double,
    val loadGrowth: Double,
    val trainGrowth: Double,
    val unlockLevels: List<Int>,
)

@Serializable
data class HospitalBalance(
    val healCostShare: Double,
    val healTimeShare: Double,
)

@Serializable
data class HeroesBalance(
    val maxLevel: Int,
    val xpBase: Double,
    val xpGrowth: Double,
    /** PvP experience = pvpXpFactor × Σ (enemy units lost × their tier). */
    val pvpXpFactor: Double,
    val heroes: Map<HeroId, HeroBalance>,
)

@Serializable
data class HeroBalance(
    val unlockHq: Int,
    val bonusPerLevel: Map<BonusKind, Double>,
)

@Serializable
data class MapBalance(
    val width: Int,
    val height: Int,
    val centerX: Int,
    val centerY: Int,
    /** Zone 3 if r ≤ zone3MaxR, zone 2 if r ≤ zone2MaxR, otherwise zone 1. */
    val zone3MaxR: Double,
    val zone2MaxR: Double,
)

@Serializable
data class ZombiesBalance(
    val minLevel: Int,
    val maxLevel: Int,
    val countBase: Double,
    val countGrowth: Double,
    val atkBase: Double,
    val defBase: Double,
    val hpBase: Double,
    val statGrowth: Double,
    val rewardBase: Double,
    val rewardGrowth: Double,
    val steelShare: Double,
    val xpBase: Double,
    val xpGrowth: Double,
    val dropChance: Double,
    /** Drop per level range: the first entry with level ≤ maxLevel applies. */
    val drops: List<LevelDrop>,
)

@Serializable
data class LevelDrop(val maxLevel: Int, val item: ItemId)

@Serializable
data class NestsBalance(
    val minLevel: Int,
    val maxLevel: Int,
    val countBase: Double,
    val countGrowth: Double,
    val atkBase: Double,
    val defBase: Double,
    val hpBase: Double,
    val statGrowth: Double,
    val rewardFoodWoodBase: Double,
    val rewardSteelBase: Double,
    val rewardGrowth: Double,
    val speedupItem: ItemId,
    val speedupsPerLevel: Int,
    val xpBase: Double,
    val xpGrowth: Double,
)

@Serializable
data class FieldsBalance(
    val minLevel: Int,
    val maxLevel: Int,
    val stockBase: Double,
    val stockGrowth: Double,
    val steelShare: Double,
    val rateBase: Double,
    val ratePerLevel: Double,
)

@Serializable
data class SpawnBalance(
    val intervalSec: Long,
    val maxAttempts: Int,
    val zones: List<ZoneSpawn>,
)

@Serializable
data class ZoneSpawn(
    val zone: Int,
    val zombies: SpawnRange,
    val nests: SpawnRange,
    val fields: SpawnRange,
)

@Serializable
data class SpawnRange(val minLevel: Int, val maxLevel: Int, val count: Int)

@Serializable
data class RelocationBalance(
    /** Minimum HQ level per zone (zone 1 is always allowed). */
    val minHqByZone: Map<Int, Int>,
)

@Serializable
data class MarchesBalance(
    val maxScouts: Int,
    val minUnits: Int,
)

@Serializable
data class CombatBalance(
    val maxRounds: Int,
    val counterMultiplier: Double,
    val defenseConstant: Double,
    /** Who counters whom: key beats value. */
    val counters: Map<UnitType, UnitType>,
    /** Order in which losses of the same tier go to the hospital. */
    val hospitalOrder: List<UnitType>,
)

@Serializable
data class ScoutingBalance(
    val foodCost: Long,
    val secPerTile: Double,
)

@Serializable
data class ProtectionBalance(
    val newbieHours: Double,
    val newbieEndsAtHq: Int,
    val recoveryLosses: Int,
    val recoveryWindowHours: Double,
    val recoveryShieldHours: Double,
    val inactivityDays: Double,
)

@Serializable
data class CatchupBalance(
    val activeDays: Double,
    val levelsBelowMedian: Int,
    val bonuses: Map<BonusKind, Double>,
)

@Serializable
data class AllianceBalance(
    val maxMembers: Int,
    val foundMinHq: Int,
    val joinMinHq: Int,
    val nameMinLength: Int,
    val nameMaxLength: Int,
    val tagLength: Int,
    val descriptionMaxLength: Int,
    val requestExpiryDays: Double,
    val joinBlockHours: Double,
    val leaderInactiveDays: Double,
    val helpsWithoutCenter: Int,
    val helpMinSec: Double,
    val helpShare: Double,
)

@Serializable
data class RallyBalance(
    val waitMinutes: List<Int>,
    val maxJoiners: Int,
)

@Serializable
data class GiftsBalance(
    val foodWoodPerLevel: Long,
    val item: ItemId,
    val itemCount: Int,
    val expiryDays: Double,
)

@Serializable
data class ItemsBalance(
    /** Speed-ups: duration in seconds (divided by gameSpeed). */
    val speedups: Map<ItemId, Long>,
    val chests: Map<ItemId, ChestBalance>,
    /** Peace shields: duration in hours. */
    val shields: Map<ItemId, Double>,
    /** Hero manuals: experience. */
    val heroBooks: Map<ItemId, Long>,
)

@Serializable
data class ChestBalance(val resource: Resource, val amount: Long)

/**
 * A reward: either a fixed item, a resource chest whose size depends on the HQ level when claimed
 * (`chest`), or a cosmetic. The fields are optional because this is a union.
 */
@Serializable
data class Reward(
    val item: ItemId? = null,
    val chest: Resource? = null,
    val chestSize: ChestSize? = null,
    val cosmetic: Cosmetic? = null,
    val count: Int = 1,
)

@Serializable
data class DailyBalance(
    val tasks: Map<DailyTask, DailyTaskBalance>,
    /** Chest size by HQ level: the first entry with HQ ≤ maxHq applies. */
    val chestSizes: List<ChestSizeByHq>,
)

@Serializable
data class DailyTaskBalance(val target: Long, val rewards: List<Reward>)

@Serializable
data class ChestSizeByHq(val maxHq: Int, val size: ChestSize)

@Serializable
data class AchievementBalance(
    val id: AchievementId,
    val kind: AchievementKind,
    val target: Long,
    val rewards: List<Reward>,
)

@Serializable
data class CosmeticsBalance(
    val defaultSkin: Cosmetic,
    val defaultFrame: Cosmetic,
)

@Serializable
data class ChatBalance(
    val maxLength: Int,
    val minIntervalMs: Long,
    val historyDays: Double,
    val pageSize: Int,
)

@Serializable
data class ReportsBalance(
    val retentionDays: Double,
    val pageSize: Int,
)

@Serializable
data class RankingsBalance(
    val topN: Int,
    val powerPerBuildingLevel: Long,
    val powerPerResearchLevel: Long,
    val powerPerHeroLevel: Long,
)
