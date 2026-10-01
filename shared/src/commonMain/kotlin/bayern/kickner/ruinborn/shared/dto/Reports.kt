package bayern.kickner.ruinborn.shared.dto

import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.ReportKind
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.UnitType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Report in the inbox. Without [payload] in the list, with it when fetched individually. */
@Serializable
data class ReportDto(
    val id: Long,
    val kind: ReportKind,
    val createdAt: Long,
    val read: Boolean,
    val title: String = "",
    val payload: ReportPayload? = null,
)

@Serializable
sealed class ReportPayload

/** A stack in the battle report. Zombies and nests have no type. */
@Serializable
data class StackReport(
    val type: UnitType? = null,
    val tier: Int = 0,
    val before: Int,
    val wounded: Int,
    val dead: Int,
    val survived: Int,
)

@Serializable
data class BattleParticipant(
    val playerId: Long? = null,
    val name: String,
    val hero: HeroId? = null,
    val heroLevel: Int = 0,
    val heroXp: Long = 0,
    val stacks: List<StackReport>,
    /** Only for the defender of a base: reinforcement instead of the base owner. */
    val reinforcement: Boolean = false,
)

@Serializable
@SerialName("battle")
data class BattleReport(
    val at: Long,
    val x: Int,
    val y: Int,
    val marchKind: MarchKind,
    val targetKind: MapObjectKind,
    val targetLevel: Int = 0,
    /** From the recipient's point of view: were they the attacker? */
    val isAttacker: Boolean,
    val won: Boolean,
    val attackerWon: Boolean,
    val fought: Boolean,
    val rounds: Int,
    val attackers: List<BattleParticipant>,
    val defenders: List<BattleParticipant>,
    val loot: Cost = Cost.ZERO,
    val drops: List<ItemCount> = emptyList(),
    val rewards: Cost = Cost.ZERO,
    val againstGatherer: Boolean = false,
) : ReportPayload()

@Serializable
data class ScoutResource(val resource: Resource, val amount: Long, val plunderable: Long)

@Serializable
@SerialName("scout")
data class ScoutReport(
    val at: Long,
    val targetId: Long,
    val targetName: String,
    val x: Int,
    val y: Int,
    val resources: List<ScoutResource>,
    val troopsHome: List<TroopCount>,
    val wallLevel: Int,
    val reinforcementsTotal: Int,
    val defenseHero: HeroId? = null,
    val defenseHeroLevel: Int = 0,
) : ReportPayload()

@Serializable
@SerialName("gather")
data class GatherReport(
    val at: Long,
    val x: Int,
    val y: Int,
    val resource: Resource,
    val amount: Long,
) : ReportPayload()

@Serializable
@SerialName("system")
data class SystemReport(val title: String, val text: String) : ReportPayload()
