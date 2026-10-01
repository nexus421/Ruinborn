package bayern.kickner.ruinborn.shared.dto

import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.model.AchievementId
import bayern.kickner.ruinborn.shared.model.AllianceRank
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.Cosmetic
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.JoinMode
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.RallyState
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.Role
import bayern.kickner.ruinborn.shared.model.Tech
import bayern.kickner.ruinborn.shared.model.TimerKind
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.UnitType
import kotlinx.serialization.Serializable

// All DTOs: new fields always get a default value (concept section 15, versioning).

@Serializable
data class ErrorDto(val code: ErrorCode, val message: String = "")

@Serializable
data class VersionInfo(val minClientVersion: Int, val latestClientVersion: Int, val apkUrl: String)

@Serializable
data class Pos(val x: Int, val y: Int)

@Serializable
data class ItemCount(val item: ItemId, val count: Int)

// ---------------------------------------------------------------- Login

@Serializable
data class RegisterRequest(val username: String, val password: String, val inviteCode: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class TokenResponse(val token: String)

@Serializable
data class PasswordChangeRequest(val oldPassword: String, val newPassword: String)

@Serializable
data class OkResponse(val ok: Boolean = true)

// ---------------------------------------------------------------- Game state

@Serializable
data class ResourceDto(
    val resource: Resource,
    val amount: Long,
    val capacity: Long,
    val protectedAmount: Long,
    val perHour: Long,
)

@Serializable
data class BuildingDto(val plot: Plot, val type: BuildingType, val level: Int)

@Serializable
data class TimerDto(
    val id: Long,
    val kind: TimerKind,
    /** BUILD: slot, RESEARCH: technology, TRAIN: training building, HEAL: "HOSPITAL". */
    val target: String,
    val startedAt: Long,
    val endsAt: Long,
    val totalMs: Long,
    val helpRequested: Boolean = false,
    val helpCount: Int = 0,
    val helpMax: Int = 0,
    val plot: Plot? = null,
    val buildingType: BuildingType? = null,
    val level: Int? = null,
    val tech: Tech? = null,
    val unitType: UnitType? = null,
    val tier: Int? = null,
    val count: Int? = null,
    val units: List<TroopCount> = emptyList(),
    val cost: Cost = Cost.ZERO,
)

@Serializable
data class ResearchDto(val tech: Tech, val level: Int)

@Serializable
data class TroopDto(val type: UnitType, val tier: Int, val home: Int, val wounded: Int)

@Serializable
data class HeroDto(
    val hero: HeroId,
    val level: Int,
    val xp: Long,
    val xpNext: Long,
    val marchId: Long? = null,
    val unlocked: Boolean = true,
    val unlockHq: Int = 1,
)

@Serializable
data class AllianceRef(val id: Long, val name: String, val tag: String, val rank: AllianceRank)

@Serializable
data class DailyDto(val task: DailyTask, val progress: Long, val target: Long, val claimed: Boolean)

@Serializable
data class AchievementDto(
    val id: AchievementId,
    val progress: Long,
    val target: Long,
    val completed: Boolean,
    val claimed: Boolean,
)

@Serializable
data class CosmeticsDto(val unlocked: List<Cosmetic>, val skin: Cosmetic, val frame: Cosmetic)

/** Incoming hostile march (for the red banner, also after a reconnect). */
@Serializable
data class IncomingDto(
    val marchId: Long,
    val attacker: String,
    val kind: MarchKind,
    val arriveAt: Long,
    /** For rallies: departure time while they are still waiting. */
    val launchAt: Long? = null,
    val rallyId: Long? = null,
)

/** Derived limits so the client does not have to recompute them. */
@Serializable
data class LimitsDto(
    val buildQueues: Int = 2,
    val marchSize: Long = 0,
    val hospitalCapacity: Long = 0,
    val reinforceCapacity: Long = 0,
    val reinforcementsStationed: Long = 0,
    val freeHeroes: Int = 0,
    val maxZombieAttackLevel: Int = 1,
)

@Serializable
data class PlayerState(
    val serverTime: Long,
    val playerId: Long,
    val name: String,
    val role: Role = Role.PLAYER,
    val resources: List<ResourceDto>,
    val buildings: List<BuildingDto>,
    val timers: List<TimerDto>,
    val research: List<ResearchDto>,
    val troops: List<TroopDto>,
    val heroes: List<HeroDto>,
    val items: List<ItemCount>,
    val marches: List<MarchDto>,
    val base: Pos,
    val protectionUntil: Long = 0,
    val shieldUntil: Long = 0,
    val catchupUntil: Long = 0,
    val alliance: AllianceRef? = null,
    val daily: List<DailyDto> = emptyList(),
    val nextDailyResetAt: Long = 0,
    val achievements: List<AchievementDto> = emptyList(),
    val cosmetics: CosmeticsDto,
    val maxZombieLevel: Int = 0,
    val zombiesDefeated: Long = 0,
    val unreadReports: Int = 0,
    val power: Long = 0,
    val incoming: List<IncomingDto> = emptyList(),
    val openAllianceHelps: Int = 0,
    val openGifts: Int = 0,
    val limits: LimitsDto = LimitsDto(),
    val allianceBlockUntil: Long = 0,
    val devMode: Boolean = false,
    /** Game speed of the server. The client computes costs and durations with the same formulas. */
    val gameSpeed: Double = 1.0,
)

// ---------------------------------------------------------------- Map

@Serializable
data class MapObjectDto(
    val id: Long,
    val kind: MapObjectKind,
    val x: Int,
    val y: Int,
    val level: Int = 0,
    val resType: Resource? = null,
    val amount: Long? = null,
    val playerId: Long? = null,
    val playerName: String? = null,
    val allianceId: Long? = null,
    val allianceTag: String? = null,
    val skin: Cosmetic? = null,
    val shielded: Boolean = false,
    val occupiedByMarchId: Long? = null,
    val power: Long? = null,
)

@Serializable
data class MarchDto(
    val id: Long,
    val playerId: Long,
    val kind: MarchKind,
    val state: MarchState,
    val fromX: Int,
    val fromY: Int,
    val toX: Int,
    val toY: Int,
    val departAt: Long,
    val arriveAt: Long,
    val troopCount: Int,
    /** Not set for other players' marches. */
    val heroId: HeroId? = null,
    val playerName: String? = null,
    val allianceId: Long? = null,
    val allianceTag: String? = null,
    val targetId: Long? = null,
    val rallyId: Long? = null,
    /** Only for own marches. */
    val troops: List<TroopCount>? = null,
    val cargo: Cost? = null,
    val gatherEndAt: Long? = null,
    val gatherRatePerHour: Double? = null,
    val gatherStartAt: Long? = null,
    val load: Long? = null,
    val hostId: Long? = null,
)

@Serializable
data class MapSnapshot(val width: Int, val height: Int, val objects: List<MapObjectDto>, val marches: List<MarchDto>)

// ---------------------------------------------------------------- Commands

@Serializable
data class BuildRequest(val plot: Plot, val type: BuildingType? = null)

@Serializable
data class DemolishRequest(val plot: Plot)

@Serializable
data class ResearchRequest(val tech: Tech)

@Serializable
data class TrainRequest(val building: BuildingType, val tier: Int, val count: Int, val type: UnitType? = null)

@Serializable
data class HealRequest(val units: List<TroopCount>)

@Serializable
data class SpeedupRequest(val item: ItemId, val count: Int)

@Serializable
data class ItemUseRequest(val item: ItemId, val count: Int = 1, val heroId: HeroId? = null, val x: Int? = null, val y: Int? = null)

@Serializable
data class MarchRequest(val kind: MarchKind, val heroId: HeroId? = null, val troops: List<TroopCount> = emptyList(), val x: Int, val y: Int)

@Serializable
data class RallyRequest(val x: Int, val y: Int, val waitMinutes: Int, val heroId: HeroId, val troops: List<TroopCount>)

@Serializable
data class RallyJoinRequest(val heroId: HeroId, val troops: List<TroopCount>)

@Serializable
data class CosmeticEquipRequest(val skin: Cosmetic? = null, val frame: Cosmetic? = null)

// ---------------------------------------------------------------- Alliances

@Serializable
data class AllianceCreateRequest(val name: String, val tag: String, val joinMode: JoinMode, val description: String = "")

@Serializable
data class AllianceSettingsRequest(val joinMode: JoinMode, val description: String)

@Serializable
data class AllianceSummaryDto(
    val id: Long,
    val name: String,
    val tag: String,
    val members: Int,
    val power: Long,
    val joinMode: JoinMode,
    val leaderName: String,
    val requested: Boolean = false,
)

@Serializable
data class AllianceMemberDto(
    val playerId: Long,
    val name: String,
    val rank: AllianceRank,
    val power: Long,
    val hqLevel: Int,
    val lastLoginAt: Long,
    val frame: Cosmetic = Cosmetic.FRAME_DEFAULT,
)

@Serializable
data class AllianceDetailDto(
    val summary: AllianceSummaryDto,
    val description: String,
    val createdAt: Long,
    val members: List<AllianceMemberDto>,
)

@Serializable
data class AllianceRequestDto(val playerId: Long, val name: String, val hqLevel: Int, val power: Long, val createdAt: Long)

@Serializable
data class HelpRequestDto(
    val timerId: Long,
    val playerId: Long,
    val playerName: String,
    val kind: TimerKind,
    val target: String,
    val helpCount: Int,
    val helpMax: Int,
    val endsAt: Long,
)

@Serializable
data class RallyParticipantDto(
    val playerId: Long,
    val name: String,
    val marchId: Long,
    val units: Int,
    val state: MarchState,
    val arriveAt: Long,
)

@Serializable
data class RallyDto(
    val id: Long,
    val allianceId: Long,
    val leaderId: Long,
    val leaderName: String,
    val targetId: Long,
    val targetKind: MapObjectKind,
    val targetName: String,
    val targetLevel: Int,
    val x: Int,
    val y: Int,
    val launchAt: Long,
    val state: RallyState,
    val participants: List<RallyParticipantDto>,
    val arriveAt: Long? = null,
)

@Serializable
data class GiftDto(val id: Long, val level: Int, val createdAt: Long, val expiresAt: Long)

// ---------------------------------------------------------------- Chat

@Serializable
data class ChatMessageDto(
    val id: Long,
    val channel: String,
    val senderId: Long?,
    val senderName: String,
    val frame: Cosmetic = Cosmetic.FRAME_DEFAULT,
    val text: String,
    val createdAt: Long,
    val deleted: Boolean = false,
    /** System message (e.g. admin announcement). */
    val system: Boolean = false,
)

@Serializable
data class ChatPostRequest(val text: String)

/** Response to a chat message: either the sent message or the result of an admin command. */
@Serializable
data class ChatPostResponse(val message: ChatMessageDto? = null, val system: String? = null)

@Serializable
data class ChatReportDto(val id: Long, val messageId: Long, val text: String, val senderName: String, val reporterName: String, val createdAt: Long)

// ---------------------------------------------------------------- Rankings and profile

@Serializable
data class RankingEntryDto(
    val rank: Int,
    val id: Long,
    val name: String,
    val value: Long,
    val allianceTag: String? = null,
    val frame: Cosmetic? = null,
)

@Serializable
data class RankingDto(val kind: String, val entries: List<RankingEntryDto>, val own: RankingEntryDto? = null)

@Serializable
data class PlayerProfileDto(
    val id: Long,
    val name: String,
    val allianceId: Long? = null,
    val allianceTag: String? = null,
    val allianceName: String? = null,
    val power: Long,
    val hqLevel: Int,
    val skin: Cosmetic,
    val frame: Cosmetic,
    val zombiesDefeated: Long,
    val base: Pos? = null,
    val createdAt: Long = 0,
)
