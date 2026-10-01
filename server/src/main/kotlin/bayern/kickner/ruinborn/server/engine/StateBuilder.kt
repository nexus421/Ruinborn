package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.shared.dto.AchievementDto
import bayern.kickner.ruinborn.shared.dto.BuildingDto
import bayern.kickner.ruinborn.shared.dto.CosmeticsDto
import bayern.kickner.ruinborn.shared.dto.DailyDto
import bayern.kickner.ruinborn.shared.dto.HeroDto
import bayern.kickner.ruinborn.shared.dto.ItemCount
import bayern.kickner.ruinborn.shared.dto.LimitsDto
import bayern.kickner.ruinborn.shared.dto.PlayerState
import bayern.kickner.ruinborn.shared.dto.Pos
import bayern.kickner.ruinborn.shared.dto.ResearchDto
import bayern.kickner.ruinborn.shared.dto.ResourceDto
import bayern.kickner.ruinborn.shared.dto.TimerDto
import bayern.kickner.ruinborn.shared.dto.TroopDto
import bayern.kickner.ruinborn.shared.model.AchievementKind
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.Tech
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.rules.Bonuses

/** Power of a player ("player power" ranking): troops except wounded including marches, buildings, research, heroes. */
fun Ctx.powerOf(pid: Long): Long {
    val home = troops(pid).map { TroopCount(it.type, it.tier, it.home) }
    val marching = marchesOf(pid).flatMap { it.troops }
    val bLevels = buildings(pid).values.sumOf { it.level }
    val rLevels = researchLevels(pid).values.sum()
    val hLevels = heroes(pid).sumOf { it.level }
    return rules.playerPower(home + marching, bLevels, rLevels, hLevels)
}

/** March size of a player: rally point + march formation. */
fun Ctx.marchSizeOf(pid: Long): Long {
    val bon = Bonuses.research(balance, researchLevels(pid))
    return rules.marchSize(buildings(pid).levelOf(BuildingType.RALLY_POINT), bon[BonusKind.MARCH_SIZE])
}

fun Ctx.hospitalCapacityOf(pid: Long): Long {
    val bon = Bonuses.research(balance, researchLevels(pid))
    return rules.hospitalCapacity(buildings(pid).levelOf(BuildingType.HOSPITAL), bon[BonusKind.HOSPITAL_CAP])
}

/** Complete game state after `settle` (concept section 15, `PlayerState`). */
fun Ctx.playerState(pid: Long): PlayerState {
    settle(pid)
    val acc = accountById(pid)!!
    val p = player(pid)
    val b = buildings(pid)
    val research = researchLevels(pid)
    val econ = economy(b, research, p.catchupUntil > now)
    val hq = b.hqLevel()
    val heroRows = heroes(pid)
    val marchRows = marchesOf(pid)
    val base = baseOf(pid)
    val daily = dailyRows(pid)
    val claimedCount = daily.values.count { it.task != DailyTask.BONUS && it.claimed }.toLong()
    val achievements = achievementStates(pid)
    val cosmetics = unlockedCosmetics(pid)

    fun achievementProgress(kind: AchievementKind, target: Long, completed: Boolean): Long = when (kind) {
        AchievementKind.HQ_LEVEL -> hq.toLong()
        AchievementKind.ZOMBIE_WINS -> p.zombiesDefeated
        AchievementKind.ZOMBIE_LEVEL -> p.maxZombieLevel.toLong()
        AchievementKind.UNITS_TRAINED -> p.troopsTrained
        else -> if (completed) target else 0
    }.coerceAtMost(target)

    return PlayerState(
        serverTime = now,
        playerId = pid,
        name = acc.username,
        role = acc.role,
        resources = Resource.entries.map { r ->
            ResourceDto(r, p.stock.whole(r), econ.capacity, econ.protectedAmount, (econ.perHour.getValue(r) * rules.gameSpeed).toLong())
        },
        buildings = b.values.sortedBy { it.plot.ordinal }.map { BuildingDto(it.plot, it.type, it.level) },
        timers = timers(pid).map { t ->
            TimerDto(
                id = t.id, kind = t.kind, target = t.target, startedAt = t.startedAt, endsAt = t.endsAt, totalMs = t.totalMs,
                helpRequested = t.helpMax > 0, helpCount = t.helpCount, helpMax = t.helpMax,
                plot = t.payload.plot, buildingType = t.payload.type, level = t.payload.level, tech = t.payload.tech,
                unitType = t.payload.unitType, tier = t.payload.tier, count = t.payload.count, units = t.payload.units, cost = t.cost,
            )
        },
        research = Tech.entries.map { ResearchDto(it, research[it] ?: 0) },
        troops = troops(pid).filter { it.home > 0 || it.wounded > 0 }.map { TroopDto(it.type, it.tier, it.home, it.wounded) },
        heroes = HeroId.entries.map { h ->
            val row = heroRows.firstOrNull { it.hero == h }
            if (row == null) HeroDto(h, 1, 0, rules.heroXpForNext(1), null, unlocked = false, unlockHq = rules.heroUnlockHq(h))
            else HeroDto(
                h, row.level, row.xp, if (row.level >= balance.heroes.maxLevel) 0 else rules.heroXpForNext(row.level), row.marchId,
                unlocked = true, unlockHq = rules.heroUnlockHq(h),
            )
        },
        items = items(pid).entries.sortedBy { it.key.ordinal }.map { ItemCount(it.key, it.value) },
        marches = marchRows.map { marchDto(it, true) },
        base = Pos(base?.x ?: 0, base?.y ?: 0),
        protectionUntil = p.protectionUntil,
        shieldUntil = p.shieldUntil,
        catchupUntil = p.catchupUntil,
        alliance = allianceRef(pid),
        daily = DailyTask.entries.map { t ->
            val row = daily[t]
            val target = balance.daily.tasks.getValue(t).target
            val progress = if (t == DailyTask.BONUS) claimedCount else row?.progress ?: 0
            DailyDto(t, progress.coerceAtMost(target), target, row?.claimed ?: false)
        },
        nextDailyResetAt = nextDailyReset(),
        achievements = balance.achievements.map { a ->
            val st = achievements.first { it.id == a.id }
            AchievementDto(a.id, achievementProgress(a.kind, a.target, st.completed), a.target, st.completed, st.claimed)
        },
        cosmetics = CosmeticsDto(cosmetics.sortedBy { it.ordinal }, p.skin, p.frame),
        maxZombieLevel = p.maxZombieLevel,
        zombiesDefeated = p.zombiesDefeated,
        unreadReports = unreadReportCount(pid),
        power = powerOf(pid),
        incoming = incomingFor(pid),
        openAllianceHelps = openHelpCount(pid),
        openGifts = openGiftCount(pid),
        limits = LimitsDto(
            buildQueues = balance.timers.buildQueues,
            marchSize = marchSizeOf(pid),
            hospitalCapacity = hospitalCapacityOf(pid),
            reinforceCapacity = rules.reinforceCapacity(b.levelOf(BuildingType.ALLIANCE_CENTER)),
            reinforcementsStationed = stationedUnitsAt(pid),
            freeHeroes = heroRows.count { it.marchId == null },
            maxZombieAttackLevel = (p.maxZombieLevel + 1).coerceAtMost(balance.zombies.maxLevel),
        ),
        allianceBlockUntil = p.allianceBlockUntil,
        devMode = config.devMode,
        gameSpeed = rules.gameSpeed,
    )
}
