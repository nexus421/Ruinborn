package bayern.kickner.ruinborn.shared.model

import kotlinx.serialization.Serializable

/** The three resources (concept section 3). */
@Serializable
enum class Resource { FOOD, WOOD, STEEL }

/** Building types. Slots carry the German IDs from the concept, types the English names as in `balance.json`. */
@Serializable
enum class BuildingType(val isResource: Boolean = false, val trains: UnitType? = null) {
    HQ,
    WALL,
    WAREHOUSE,
    FARM(isResource = true),
    SAWMILL(isResource = true),
    STEEL_MILL(isResource = true),
    BARRACKS(trains = UnitType.INFANTRY),
    FACTORY(trains = UnitType.VEHICLE),
    RANGE(trains = UnitType.SHOOTER),
    HOSPITAL,
    LAB,
    RALLY_POINT,
    ALLIANCE_CENTER,
    ;

    companion object {
        val RESOURCE_TYPES = entries.filter { it.isResource }
        fun trainerOf(unit: UnitType): BuildingType = entries.first { it.trains == unit }
    }
}

/** Building slots: 10 fixed slots with exactly one building type and 10 freely usable resource slots. */
@Serializable
enum class Plot(val fixedType: BuildingType?) {
    HQ(BuildingType.HQ),
    MAUER(BuildingType.WALL),
    LAGER(BuildingType.WAREHOUSE),
    KASERNE(BuildingType.BARRACKS),
    FABRIK(BuildingType.FACTORY),
    SCHIESSSTAND(BuildingType.RANGE),
    LAZARETT(BuildingType.HOSPITAL),
    LABOR(BuildingType.LAB),
    SAMMELPUNKT(BuildingType.RALLY_POINT),
    ALLIANZ(BuildingType.ALLIANCE_CENTER),
    R1(null), R2(null), R3(null), R4(null), R5(null), R6(null), R7(null), R8(null), R9(null), R10(null),
    ;

    val isResourcePlot: Boolean get() = fixedType == null

    fun accepts(type: BuildingType): Boolean = if (isResourcePlot) type.isResource else fixedType == type

    companion object {
        fun ofType(type: BuildingType): Plot? = entries.firstOrNull { it.fixedType == type }
    }
}

/** Troop types in the counter triangle (section 5). */
@Serializable
enum class UnitType { INFANTRY, VEHICLE, SHOOTER }

/** The 15 technologies (section 4), in table order. */
@Serializable
enum class Tech {
    AGRICULTURE, WOODWORKING, METALLURGY, STORAGE, CONSTRUCTION, RESEARCH_METHODS, GATHERING, LOGISTICS,
    DRILL, ARMOR, BALLISTICS, FIELD_MEDICINE, TRAINING_METHODS, MARCH_ORDER, ZOMBIOLOGY,
}

/** The five heroes in unlock order (breaks ties when choosing the defense hero). */
@Serializable
enum class HeroId { RHEA, VIKTOR, KAYA, BROCK, NOVA }

/** Kinds of bonuses. Bonuses of the same kind are added, never multiplied. */
@Serializable
enum class BonusKind {
    PROD_FOOD, PROD_WOOD, PROD_STEEL, STORAGE,
    BUILD_SPEED, RESEARCH_SPEED, TRAIN_SPEED, HEAL_SPEED, HOSPITAL_CAP,
    GATHER_SPEED, LOAD, MARCH_SPEED, MARCH_SIZE,
    INFANTRY_ATK, INFANTRY_DEF, VEHICLE_ATK, VEHICLE_DEF, SHOOTER_ATK, SHOOTER_DEF,
    ZOMBIE_ATK,
    ;

    companion object {
        fun prod(r: Resource): BonusKind = when (r) {
            Resource.FOOD -> PROD_FOOD
            Resource.WOOD -> PROD_WOOD
            Resource.STEEL -> PROD_STEEL
        }

        fun atk(u: UnitType): BonusKind = when (u) {
            UnitType.INFANTRY -> INFANTRY_ATK
            UnitType.VEHICLE -> VEHICLE_ATK
            UnitType.SHOOTER -> SHOOTER_ATK
        }

        fun def(u: UnitType): BonusKind = when (u) {
            UnitType.INFANTRY -> INFANTRY_DEF
            UnitType.VEHICLE -> VEHICLE_DEF
            UnitType.SHOOTER -> SHOOTER_DEF
        }
    }
}

/** Items (section 10). All of them can only be earned in game. */
@Serializable
enum class ItemId {
    SPEED_1M, SPEED_5M, SPEED_15M, SPEED_60M, SPEED_3H, SPEED_8H,
    RES_FOOD_S, RES_FOOD_M, RES_FOOD_L,
    RES_WOOD_S, RES_WOOD_M, RES_WOOD_L,
    RES_STEEL_S, RES_STEEL_M, RES_STEEL_L,
    SHIELD_8H, SHIELD_24H,
    RELOCATE,
    HERO_XP_S, HERO_XP_L,
    ;

    val isSpeedup: Boolean get() = name.startsWith("SPEED_")
    val isChest: Boolean get() = name.startsWith("RES_")
    val isShield: Boolean get() = name.startsWith("SHIELD_")
    val isHeroBook: Boolean get() = name.startsWith("HERO_XP_")

    companion object {
        /** Resource chest of one resource in size S, M or L. */
        fun chest(resource: Resource, size: ChestSize): ItemId = valueOf("RES_${resource.shortId}_${size.name}")
    }
}

@Serializable
enum class ChestSize { S, M, L }

private val Resource.shortId: String
    get() = when (this) {
        Resource.FOOD -> "FOOD"
        Resource.WOOD -> "WOOD"
        Resource.STEEL -> "STEEL"
    }

/** Daily tasks (section 10). BONUS is the daily bonus after 4 claimed tasks. */
@Serializable
enum class DailyTask { ZOMBIE_HUNT, BUILDER, DRILL, GATHERER, COMRADE, BONUS }

/** The 20 achievements in concept order (determines "next goal"). */
@Serializable
enum class AchievementId {
    HQ_2, FIRST_ZOMBIE, FIRST_GATHER, HQ_3, FIRST_RESEARCH, TRAIN_500, JOIN_ALLIANCE, HQ_5, ZOMBIE_5, SCOUT_PLAYER,
    RALLY_FIGHT, HQ_10, ZOMBIE_10, DEFENSE_WIN, NEST_3, HQ_15, ZOMBIE_15, ZOMBIES_200, ZOMBIE_20, HQ_20,
}

/** What an achievement measures. */
@Serializable
enum class AchievementKind {
    HQ_LEVEL, ZOMBIE_WINS, ZOMBIE_LEVEL, GATHER_HOME, RESEARCH_DONE, UNITS_TRAINED, ALLIANCE_JOINED,
    SCOUT_PLAYER, RALLY_FIGHT, DEFENSE_WIN, NEST_LEVEL,
}

/** Cosmetics without game stats: base skins and profile frames. */
@Serializable
enum class Cosmetic(val isSkin: Boolean) {
    SKIN_DEFAULT(true), SKIN_TIN(true), SKIN_FORT(true), SKIN_CITADEL(true),
    FRAME_DEFAULT(false), FRAME_BULWARK(false), FRAME_NEST(false), FRAME_PLAGUE(false), FRAME_LEGEND(false),
}

@Serializable
enum class Role { PLAYER, ADMIN }

@Serializable
enum class AllianceRank { LEADER, OFFICER, MEMBER }

@Serializable
enum class JoinMode { OPEN, REQUEST }

@Serializable
enum class TimerKind { BUILD, RESEARCH, TRAIN, HEAL }

@Serializable
enum class MarchKind { ATTACK, GATHER, SCOUT, REINFORCE, RALLY }

@Serializable
enum class MarchState { OUTBOUND, GATHERING, STATIONED, WAITING, RETURNING }

@Serializable
enum class RallyState { WAITING, MARCHING, DONE, CANCELLED }

@Serializable
enum class ReportKind { BATTLE, SCOUT, GATHER, SYSTEM }

@Serializable
enum class MapObjectKind { BASE, ZOMBIE, NEST, FIELD }

/** Error codes with HTTP status (section 15). */
@Serializable
enum class ErrorCode(val httpStatus: Int) {
    VALIDATION(400), INVALID_INVITE(400),
    NOT_ENOUGH_RESOURCES(409), NOT_ENOUGH_ITEMS(409), NOT_ENOUGH_TROOPS(409), QUEUE_FULL(409),
    REQUIREMENT_NOT_MET(409), MAX_LEVEL(409), HERO_BUSY(409), MARCH_LIMIT(409), MARCH_SIZE(409),
    TARGET_INVALID(409), TARGET_SHIELDED(409), ZOMBIE_LEVEL_LOCKED(409), ALLIANCE_FULL(409),
    ALREADY_IN_ALLIANCE(409), NOT_IN_ALLIANCE(409), ALLIANCE_BLOCKED(409), NAME_TAKEN(409), SERVER_FULL(409),
    UNAUTHORIZED(401),
    FORBIDDEN(403), BANNED(403), MUTED(403),
    NOT_FOUND(404),
    CLIENT_OUTDATED(426),
    RATE_LIMITED(429),
    INTERNAL(500),
}
