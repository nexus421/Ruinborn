package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.AchievementT
import bayern.kickner.ruinborn.server.db.CosmeticT
import bayern.kickner.ruinborn.server.db.DailyProgressT
import bayern.kickner.ruinborn.shared.balance.Reward
import bayern.kickner.ruinborn.shared.model.AchievementId
import bayern.kickner.ruinborn.shared.model.AchievementKind
import bayern.kickner.ruinborn.shared.model.Cosmetic
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.model.ItemId
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Time calculations in the game time zone (concept section 2: day boundaries in `Europe/Berlin`). */
object GameTime {
    /** Next point in time with local time [time] strictly after [after] (also correct on DST change days). */
    fun nextOccurrence(after: Long, zone: ZoneId, time: LocalTime): Long {
        val local = Instant.ofEpochMilli(after).atZone(zone)
        var candidate = ZonedDateTime.of(local.toLocalDate(), time, zone)
        while (candidate.toInstant().toEpochMilli() <= after) candidate = ZonedDateTime.of(candidate.toLocalDate().plusDays(1), time, zone)
        return candidate.toInstant().toEpochMilli()
    }

    /** Last point in time with local time [time] that is ≤ [at]. */
    fun lastOccurrence(at: Long, zone: ZoneId, time: LocalTime): Long {
        val local = Instant.ofEpochMilli(at).atZone(zone)
        var candidate = ZonedDateTime.of(local.toLocalDate(), time, zone)
        while (candidate.toInstant().toEpochMilli() > at) candidate = ZonedDateTime.of(candidate.toLocalDate().minusDays(1), time, zone)
        return candidate.toInstant().toEpochMilli()
    }

    /** Day of the daily tasks: date of the last reset (YYYY-MM-DD). */
    fun dayKey(at: Long, zone: ZoneId, time: LocalTime): String =
        Instant.ofEpochMilli(lastOccurrence(at, zone, time)).atZone(zone).toLocalDate().toString()
}

fun Ctx.dayKey(at: Long = now): String = GameTime.dayKey(at, config.zone, config.resetTime)
fun Ctx.nextDailyReset(): Long = GameTime.nextOccurrence(now, config.zone, config.resetTime)

// ---------------------------------------------------------------- Daily tasks

data class DailyRow(val task: DailyTask, val progress: Long, val claimed: Boolean)

fun Ctx.dailyRows(pid: Long): Map<DailyTask, DailyRow> {
    val day = dayKey()
    return DailyProgressT.selectAll().where { (DailyProgressT.playerId eq pid) and (DailyProgressT.day eq day) }
        .associate { it[DailyProgressT.task] to DailyRow(it[DailyProgressT.task], it[DailyProgressT.progress], it[DailyProgressT.claimed]) }
}

/** Increases the progress of a daily task (counts since the last reset). */
fun Ctx.addDaily(pid: Long, task: DailyTask, amount: Long) {
    if (amount <= 0) return
    val day = dayKey()
    val cur = dailyRows(pid)[task]
    DailyProgressT.upsert {
        it[playerId] = pid
        it[DailyProgressT.day] = day
        it[DailyProgressT.task] = task
        it[progress] = (cur?.progress ?: 0) + amount
        it[claimed] = cur?.claimed ?: false
    }
    dirty(pid)
}

/** Claims the reward of a daily task. The daily bonus counts the claimed tasks. */
fun Ctx.claimDaily(pid: Long, task: DailyTask): Res<Unit> {
    settle(pid)
    val rows = dailyRows(pid)
    val cfg = balance.daily.tasks.getValue(task)
    val progress = if (task == DailyTask.BONUS) rows.values.count { it.task != DailyTask.BONUS && it.claimed }.toLong() else rows[task]?.progress ?: 0
    ensure(rows[task]?.claimed != true, ErrorCode.VALIDATION) { "Die Belohnung wurde bereits abgeholt." }?.let { return it }
    ensure(progress >= cfg.target, ErrorCode.REQUIREMENT_NOT_MET) { "Die Aufgabe ist noch nicht erfüllt." }?.let { return it }
    val day = dayKey()
    DailyProgressT.upsert {
        it[playerId] = pid
        it[DailyProgressT.day] = day
        it[DailyProgressT.task] = task
        it[DailyProgressT.progress] = progress
        it[claimed] = true
    }
    grantRewards(pid, cfg.rewards)
    dirty(pid)
    return OK
}

// ---------------------------------------------------------------- Achievements

private fun Ctx.completedAchievements(pid: Long): Set<AchievementId> =
    AchievementT.selectAll().where { AchievementT.playerId eq pid }.map { it[AchievementT.achievement] }.toSet()

/** Marks all achievements of kind [kind] with target ≤ [value] as completed. */
fun Ctx.achievementValue(pid: Long, kind: AchievementKind, value: Long, at: Long = now) {
    val done = completedAchievements(pid)
    balance.achievements.filter { it.kind == kind && it.target <= value && it.id !in done }.forEach { a ->
        AchievementT.insertIgnore {
            it[playerId] = pid
            it[achievement] = a.id
            it[completedAt] = at
            it[claimedAt] = 0
        }
        dirty(pid)
    }
}

/** One-time events (e.g. "first research") count like a value. */
fun Ctx.achievementEvent(pid: Long, kind: AchievementKind, value: Long = 1, at: Long = now) = achievementValue(pid, kind, value, at)

fun Ctx.claimAchievement(pid: Long, id: AchievementId): Res<Unit> {
    settle(pid)
    val row = AchievementT.selectAll().where { (AchievementT.playerId eq pid) and (AchievementT.achievement eq id) }.firstOrNull()
    ensure(row != null, ErrorCode.REQUIREMENT_NOT_MET) { "Der Erfolg ist noch nicht erfüllt." }?.let { return it }
    ensure(row!![AchievementT.claimedAt] == 0L, ErrorCode.VALIDATION) { "Die Belohnung wurde bereits abgeholt." }?.let { return it }
    AchievementT.update({ (AchievementT.playerId eq pid) and (AchievementT.achievement eq id) }) { it[claimedAt] = now }
    grantRewards(pid, balance.achievements.first { it.id == id }.rewards)
    dirty(pid)
    return OK
}

data class AchievementState(val id: AchievementId, val completed: Boolean, val claimed: Boolean)

fun Ctx.achievementStates(pid: Long): List<AchievementState> {
    val rows = AchievementT.selectAll().where { AchievementT.playerId eq pid }.associate { it[AchievementT.achievement] to it[AchievementT.claimedAt] }
    return balance.achievements.map { AchievementState(it.id, it.id in rows, (rows[it.id] ?: 0L) > 0L) }
}

// ---------------------------------------------------------------- Rewards and cosmetics

/** Credits rewards. Chests without a fixed size depend on the HQ level when claimed. */
fun Ctx.grantRewards(pid: Long, rewards: List<Reward>) {
    val hq = buildings(pid).hqLevel()
    rewards.forEach { r ->
        when {
            r.item != null -> addItem(pid, r.item!!, r.count)
            r.chest != null -> addItem(pid, ItemId.chest(r.chest!!, r.chestSize ?: rules.chestSizeFor(hq)), r.count)
            r.cosmetic != null -> unlockCosmetic(pid, r.cosmetic!!)
        }
    }
}

fun Ctx.unlockCosmetic(pid: Long, c: Cosmetic) {
    CosmeticT.insertIgnore {
        it[playerId] = pid
        it[cosmetic] = c
    }
}

fun Ctx.unlockedCosmetics(pid: Long): Set<Cosmetic> =
    CosmeticT.selectAll().where { CosmeticT.playerId eq pid }.map { it[CosmeticT.cosmetic] }.toSet() +
        setOf(balance.cosmetics.defaultSkin, balance.cosmetics.defaultFrame)

/** Records an achievement directly (for tests and admin tools). */
fun Ctx.insertAchievement(pid: Long, id: AchievementId) {
    AchievementT.insert {
        it[playerId] = pid
        it[achievement] = id
        it[completedAt] = now
        it[claimedAt] = 0
    }
}
