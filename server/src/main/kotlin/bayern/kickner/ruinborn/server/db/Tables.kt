package bayern.kickner.ruinborn.server.db

import bayern.kickner.ruinborn.shared.model.AchievementId
import bayern.kickner.ruinborn.shared.model.AllianceRank
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.Cosmetic
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.JoinMode
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.RallyState
import bayern.kickner.ruinborn.shared.model.ReportKind
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.Role
import bayern.kickner.ruinborn.shared.model.Tech
import bayern.kickner.ruinborn.shared.model.TimerKind
import bayern.kickner.ruinborn.shared.model.UnitType
import org.jetbrains.exposed.v1.core.Table

// Table objects mirror the SQL migrations (db/V001__init.sql). SchemaUtils.create is not used.

private const val ENUM_LEN = 32

object SchemaVersionT : Table("schema_version") {
    val version = integer("version")
}

object ServerMetaT : Table("server_meta") {
    val key = text("key")
    val value = text("value")
    override val primaryKey = PrimaryKey(key)
}

object AccountT : Table("account") {
    val id = long("id").autoIncrement()
    val username = text("username")
    val pwHash = text("pw_hash")
    val role = enumerationByName("role", ENUM_LEN, Role::class)
    val createdAt = long("created_at")
    val lastLoginAt = long("last_login_at")
    val bannedUntil = long("banned_until")
    val banReason = text("ban_reason").nullable()
    val mutedUntil = long("muted_until")
    override val primaryKey = PrimaryKey(id)
}

object SessionT : Table("session") {
    val tokenHash = text("token_hash")
    val accountId = long("account_id")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(tokenHash)
}

object PlayerT : Table("player") {
    val id = long("id")
    val food = double("food")
    val wood = double("wood")
    val steel = double("steel")
    val resAt = long("res_at")
    val protectionUntil = long("protection_until")
    val shieldUntil = long("shield_until")
    val catchupUntil = long("catchup_until")
    val lastActiveAt = long("last_active_at")
    val maxZombieLevel = integer("max_zombie_level")
    val zombiesDefeated = long("zombies_defeated")
    val troopsTrained = long("troops_trained")
    val skin = enumerationByName("skin", ENUM_LEN, Cosmetic::class)
    val frame = enumerationByName("frame", ENUM_LEN, Cosmetic::class)
    val allianceBlockUntil = long("alliance_block_until")
    override val primaryKey = PrimaryKey(id)
}

object BuildingT : Table("building") {
    val playerId = long("player_id")
    val plot = enumerationByName("plot", ENUM_LEN, Plot::class)
    val type = enumerationByName("type", ENUM_LEN, BuildingType::class)
    val level = integer("level")
    override val primaryKey = PrimaryKey(playerId, plot)
}

object TimerT : Table("timer") {
    val id = long("id").autoIncrement()
    val playerId = long("player_id")
    val kind = enumerationByName("kind", ENUM_LEN, TimerKind::class)
    val target = text("target")
    val payload = text("payload")
    val cost = text("cost")
    val startedAt = long("started_at")
    val endsAt = long("ends_at")
    val totalMs = long("total_ms")
    val helpMax = integer("help_max")
    val helpCount = integer("help_count")
    override val primaryKey = PrimaryKey(id)
}

object TimerHelpT : Table("timer_help") {
    val timerId = long("timer_id")
    val helperId = long("helper_id")
    override val primaryKey = PrimaryKey(timerId, helperId)
}

object ResearchT : Table("research") {
    val playerId = long("player_id")
    val tech = enumerationByName("tech", ENUM_LEN, Tech::class)
    val level = integer("level")
    override val primaryKey = PrimaryKey(playerId, tech)
}

object TroopT : Table("troop") {
    val playerId = long("player_id")
    val type = enumerationByName("type", ENUM_LEN, UnitType::class)
    val tier = integer("tier")
    val home = integer("home")
    val wounded = integer("wounded")
    override val primaryKey = PrimaryKey(playerId, type, tier)
}

object HeroT : Table("hero") {
    val playerId = long("player_id")
    val hero = enumerationByName("hero", ENUM_LEN, HeroId::class)
    val level = integer("level")
    val xp = long("xp")
    val marchId = long("march_id").nullable()
    override val primaryKey = PrimaryKey(playerId, hero)
}

object ItemT : Table("item") {
    val playerId = long("player_id")
    val item = enumerationByName("item", ENUM_LEN, ItemId::class)
    val count = integer("count")
    override val primaryKey = PrimaryKey(playerId, item)
}

object MapObjectT : Table("map_object") {
    val id = long("id").autoIncrement()
    val kind = enumerationByName("kind", ENUM_LEN, MapObjectKind::class)
    val x = integer("x")
    val y = integer("y")
    val zone = integer("zone")
    val level = integer("level")
    val resType = enumerationByName("res_type", ENUM_LEN, Resource::class).nullable()
    val amount = long("amount")
    val playerId = long("player_id").nullable()
    val occupiedBy = long("occupied_by").nullable()
    override val primaryKey = PrimaryKey(id)
}

object MarchT : Table("march") {
    val id = long("id").autoIncrement()
    val playerId = long("player_id")
    val kind = enumerationByName("kind", ENUM_LEN, MarchKind::class)
    val hero = enumerationByName("hero", ENUM_LEN, HeroId::class).nullable()
    val troops = text("troops")
    val fromX = integer("from_x")
    val fromY = integer("from_y")
    val toX = integer("to_x")
    val toY = integer("to_y")
    val targetId = long("target_id").nullable()
    val state = enumerationByName("state", ENUM_LEN, MarchState::class)
    val departAt = long("depart_at")
    val arriveAt = long("arrive_at")
    val gatherStart = long("gather_start")
    val gatherRate = double("gather_rate")
    val cargo = text("cargo")
    val rallyId = long("rally_id").nullable()
    val hostId = long("host_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

object RallyT : Table("rally") {
    val id = long("id").autoIncrement()
    val allianceId = long("alliance_id")
    val leaderId = long("leader_id")
    val targetId = long("target_id")
    val launchAt = long("launch_at")
    val state = enumerationByName("state", ENUM_LEN, RallyState::class)
    override val primaryKey = PrimaryKey(id)
}

object AllianceT : Table("alliance") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val tag = text("tag")
    val leaderId = long("leader_id")
    val joinMode = enumerationByName("join_mode", ENUM_LEN, JoinMode::class)
    val description = text("description")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AllianceMemberT : Table("alliance_member") {
    val playerId = long("player_id")
    val allianceId = long("alliance_id")
    val rank = enumerationByName("rank", ENUM_LEN, AllianceRank::class)
    val joinedAt = long("joined_at")
    override val primaryKey = PrimaryKey(playerId)
}

object AllianceRequestT : Table("alliance_request") {
    val allianceId = long("alliance_id")
    val playerId = long("player_id")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(allianceId, playerId)
}

object AllianceGiftT : Table("alliance_gift") {
    val id = long("id").autoIncrement()
    val playerId = long("player_id")
    val level = integer("level")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val claimed = bool("claimed")
    override val primaryKey = PrimaryKey(id)
}

object ChatMessageT : Table("chat_message") {
    val id = long("id").autoIncrement()
    val channel = text("channel")
    val senderId = long("sender_id").nullable()
    val text = text("text")
    val createdAt = long("created_at")
    val deleted = bool("deleted")
    override val primaryKey = PrimaryKey(id)
}

object ChatReportT : Table("chat_report") {
    val id = long("id").autoIncrement()
    val messageId = long("message_id")
    val reporterId = long("reporter_id")
    val createdAt = long("created_at")
    val resolved = bool("resolved")
    override val primaryKey = PrimaryKey(id)
}

object ReportT : Table("report") {
    val id = long("id").autoIncrement()
    val playerId = long("player_id")
    val kind = enumerationByName("kind", ENUM_LEN, ReportKind::class)
    val createdAt = long("created_at")
    val read = bool("read")
    val payload = text("payload")
    override val primaryKey = PrimaryKey(id)
}

object DailyProgressT : Table("daily_progress") {
    val playerId = long("player_id")
    val day = text("day")
    val task = enumerationByName("task", ENUM_LEN, DailyTask::class)
    val progress = long("progress")
    val claimed = bool("claimed")
    override val primaryKey = PrimaryKey(playerId, day, task)
}

object AchievementT : Table("achievement") {
    val playerId = long("player_id")
    val achievement = enumerationByName("achievement", ENUM_LEN, AchievementId::class)
    val completedAt = long("completed_at")
    val claimedAt = long("claimed_at")
    override val primaryKey = PrimaryKey(playerId, achievement)
}

object CosmeticT : Table("cosmetic") {
    val playerId = long("player_id")
    val cosmetic = enumerationByName("cosmetic", ENUM_LEN, Cosmetic::class)
    override val primaryKey = PrimaryKey(playerId, cosmetic)
}

object DefenseLossT : Table("defense_loss") {
    val playerId = long("player_id")
    val at = long("at")
}

object ScheduledEventT : Table("scheduled_event") {
    val id = long("id").autoIncrement()
    val dueAt = long("due_at")
    val type = text("type")
    val payload = text("payload")
    override val primaryKey = PrimaryKey(id)
}

object ProcessedRequestT : Table("processed_request") {
    val playerId = long("player_id")
    val requestId = text("request_id")
    val createdAt = long("created_at")
    val status = integer("status")
    val response = text("response")
    override val primaryKey = PrimaryKey(playerId, requestId)
}

object AuditLogT : Table("audit_log") {
    val id = long("id").autoIncrement()
    val adminId = long("admin_id").nullable()
    val action = text("action")
    val target = text("target").nullable()
    val details = text("details").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}
