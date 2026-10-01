package bayern.kickner.ruinborn.server.sim

import bayern.kickner.ruinborn.server.App
import bayern.kickner.ruinborn.server.ServerConfig
import bayern.kickner.ruinborn.server.engine.Ctx
import bayern.kickner.ruinborn.server.engine.ManualClock
import bayern.kickner.ruinborn.server.engine.Res
import bayern.kickner.ruinborn.server.engine.accountByName
import bayern.kickner.ruinborn.server.engine.allMarches
import bayern.kickner.ruinborn.server.engine.build
import bayern.kickner.ruinborn.server.engine.claimAchievement
import bayern.kickner.ruinborn.server.engine.claimDaily
import bayern.kickner.ruinborn.server.engine.heal
import bayern.kickner.ruinborn.server.engine.ok
import bayern.kickner.ruinborn.server.engine.playerState
import bayern.kickner.ruinborn.server.engine.register
import bayern.kickner.ruinborn.server.engine.research
import bayern.kickner.ruinborn.server.engine.speedup
import bayern.kickner.ruinborn.server.engine.startMarch
import bayern.kickner.ruinborn.server.engine.train
import bayern.kickner.ruinborn.server.engine.useItem
import bayern.kickner.ruinborn.server.db.MapObjectT
import bayern.kickner.ruinborn.server.engine.toMapObject
import bayern.kickner.ruinborn.server.log.Logging
import bayern.kickner.ruinborn.shared.balance.BalanceCodec
import bayern.kickner.ruinborn.shared.combat.CombatStack
import bayern.kickner.ruinborn.shared.combat.simulateCombat
import bayern.kickner.ruinborn.shared.dto.BuildRequest
import bayern.kickner.ruinborn.shared.dto.HealRequest
import bayern.kickner.ruinborn.shared.dto.ItemUseRequest
import bayern.kickner.ruinborn.shared.dto.MarchRequest
import bayern.kickner.ruinborn.shared.dto.PlayerState
import bayern.kickner.ruinborn.shared.dto.TrainRequest
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.Tech
import bayern.kickner.ruinborn.shared.model.TimerKind
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.UnitType
import bayern.kickner.ruinborn.shared.rules.MS_PER_DAY
import bayern.kickner.ruinborn.shared.rules.MS_PER_HOUR
import kotlinx.coroutines.runBlocking
import kotnexlib.ResultOf2
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.random.Random

/** Player types of the simulation with logins per day (concept section 17). */
enum class BotType(val loginsPerDay: Int) { GELEGENHEIT(2), NORMAL(4), AKTIV(8) }

data class Bot(val id: Long, val name: String, val type: BotType)

/**
 * Balancing simulation: real engine with a controllable clock, three player types with two bots each, 60 days by default.
 * On every login: claim rewards, fill the build queues (HQ prerequisites first, then production, then
 * military), start research and training, attack the highest allowed zombies, send free marches gathering.
 * Output: CSV with day, player type, HQ level, power and resources, plus an evaluation of the target values.
 */
fun main(args: Array<String>) {
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val balanceFile = arg("--balance")?.let(::File)
    val out = File(arg("--out") ?: "sim-result.csv")
    val days = arg("--days")?.toIntOrNull() ?: 60
    Logging.setup("WARN")
    val balance = balanceFile?.takeIf { it.isFile }?.let { BalanceCodec.load(it.readText()).first ?: error("balance.json ungültig") } ?: BalanceCodec.default
    val result = Simulation(balance, days).run()
    out.writeText(result.csv)
    println(result.summary)
    println("CSV geschrieben: ${out.path}")
}

class SimResult(val csv: String, val summary: String, val firstDayReached: Map<BotType, Map<Int, Int?>>)

class Simulation(private val balance: bayern.kickner.ruinborn.shared.balance.Balance, private val days: Int) {
    private val start = Instant.parse("2026-10-01T05:00:00Z").toEpochMilli()
    private val clock = ManualClock(start)
    private val dir = Files.createTempDirectory("ruinborn-sim").toFile()
    private val config = ServerConfig(
        dbPath = File(dir, "sim.db").path, balancePath = File(dir, "balance.json").path, backupDir = File(dir, "backups").path,
        apkPath = File(dir, "app.apk").path, inviteCode = "sim", maxPlayers = 100, gameSpeed = 1.0, passwordIterations = 1,
        backupTime = "03:30",
    )
    private val app = App(config, balance, clock, Random(1))
    private val random = Random(2)

    private fun <T> exec(block: Ctx.() -> Res<T>): Res<T> = runBlocking { app.engine.exec(block) }
    private fun state(b: Bot): PlayerState = (exec { ok(playerState(b.id)) } as ResultOf2.Success).value

    fun run(): SimResult {
        app.start()
        // Backups are not needed in the simulation.
        app.game.backupTrigger = {}
        val bots = BotType.entries.flatMap { t -> (1..2).map { i -> "${t.name.lowercase()}$i" to t } }.map { (name, t) ->
            exec { register(name, "v1\$sim\$sim") }
            val id = (exec { ok(accountByName(name)!!.id) } as ResultOf2.Success).value
            Bot(id, name, t)
        }
        val csv = StringBuilder("tag;typ;spieler;hq;macht;nahrung;holz;stahl;zombies;max_zombie;beschleuniger_h;einheiten\n")
        val reached = BotType.entries.associateWith { mutableMapOf<Int, Int?>(10 to null, 15 to null, 20 to null) }
        for (day in 1..days) {
            val dayStart = start + (day - 1) * MS_PER_DAY
            val logins = bots.flatMap { b ->
                val n = b.type.loginsPerDay
                // Logins between 8:00 and 22:00 local time (≈ 06–20 UTC), evenly distributed with slight jitter.
                (0 until n).map { i -> dayStart + MS_PER_HOUR + (i * 14 * MS_PER_HOUR / (n - 1).coerceAtLeast(1)) + random.nextLong(0, 15 * 60_000) to b }
            }.sortedBy { it.first }
            logins.forEach { (t, b) ->
                if (t > clock.now()) clock.time = t
                runBlocking { app.engine.tick() }
                act(b)
            }
            clock.time = maxOf(clock.now(), dayStart + 18 * MS_PER_HOUR)
            runBlocking { app.engine.tick() }
            bots.forEach { b ->
                val s = state(b)
                val hq = s.buildings.first { it.plot == Plot.HQ }.level
                val speedH = s.items.filter { it.item.isSpeedup }.sumOf { balance.items.speedups.getValue(it.item) * it.count } / 3600
                val units = s.troops.sumOf { it.home + it.wounded } + s.marches.sumOf { it.troopCount }
                csv.append("$day;${b.type};${b.name};$hq;${s.power};${s.resources.joinToString(";") { it.amount.toString() }};${s.zombiesDefeated};${s.maxZombieLevel};$speedH;$units\n")
                reached.getValue(b.type).keys.toList().forEach { target -> if (hq >= target && reached.getValue(b.type)[target] == null) reached.getValue(b.type)[target] = day }
            }
        }
        runBlocking { app.stop() }
        dir.deleteRecursively()
        return SimResult(csv.toString(), summary(reached), reached)
    }

    private fun summary(reached: Map<BotType, Map<Int, Int?>>): String = buildString {
        appendLine("Erreichte HQ-Stufen (erster Tag, schnellster Bot je Typ):")
        reached.forEach { (t, m) -> appendLine("  $t: " + m.entries.joinToString(", ") { "HQ ${it.key} an Tag ${it.value ?: "–"}" }) }
        val n = reached.getValue(BotType.NORMAL)
        fun check(target: Int, range: IntRange) = n[target]?.let { it in range } ?: false
        appendLine("Zielwerte Normal-Spieler: HQ 10 an Tag 7–10: ${check(10, 7..10)}, HQ 15 an Tag 21–28: ${check(15, 21..28)}, HQ 20 an Tag 45–60: ${check(20, 45..60)}")
        val casual = reached.getValue(BotType.GELEGENHEIT)
        val slow = listOf(10, 15, 20).all { t -> val a = n[t]; val c = casual[t]; a == null || (c != null && c <= a * 1.3) }
        appendLine("Gelegenheitsspieler höchstens 30 % langsamer: $slow")
    }

    // ---------------------------------------------------------------- Bot behavior

    private fun act(b: Bot) {
        var s = state(b)
        s.achievements.filter { it.completed && it.claimed.not() }.forEach { a -> exec { claimAchievement(b.id, a.id) } }
        s.daily.filter { it.task != DailyTask.BONUS && it.claimed.not() && it.progress >= it.target }.forEach { d -> exec { claimDaily(b.id, d.task) } }
        exec { claimDaily(b.id, DailyTask.BONUS) }
        s = state(b)
        s.items.filter { it.item.isChest }.forEach { i -> exec { useItem(b.id, ItemUseRequest(i.item, i.count)) } }
        s.items.filter { it.item.isHeroBook }.forEach { i -> exec { useItem(b.id, ItemUseRequest(i.item, i.count, heroId = bayern.kickner.ruinborn.shared.model.HeroId.RHEA)) } }
        buildStep(b)
        researchStep(b)
        speedupStep(b)
        trainStep(b)
        healStep(b)
        marchStep(b)
    }

    /** Reserve for the next HQ step (the HQ itself or the wall it requires). */
    private fun reserve(s: PlayerState): bayern.kickner.ruinborn.shared.balance.Cost {
        val rules = app.game.rules
        val hq = s.buildings.first { it.plot == Plot.HQ }.level
        if (hq >= 20) return bayern.kickner.ruinborn.shared.balance.Cost.ZERO
        val building = s.timers.any { it.kind == TimerKind.BUILD && it.plot == Plot.HQ }
        val next = if (building) hq + 2 else hq + 1
        if (next > 20) return bayern.kickner.ruinborn.shared.balance.Cost.ZERO
        val wall = s.buildings.firstOrNull { it.plot == Plot.MAUER }?.level ?: 0
        val wallCost = if (wall < next - 1) rules.buildingCost(BuildingType.WALL, wall + 1) else bayern.kickner.ruinborn.shared.balance.Cost.ZERO
        return rules.buildingCost(BuildingType.HQ, next) + wallCost
    }

    private fun surplus(s: PlayerState): List<Long> {
        val r = reserve(s)
        return s.resources.map { (it.amount - r[it.resource]).coerceAtLeast(0) }
    }

    private fun affordableAfterReserve(s: PlayerState, req: BuildRequest): Boolean {
        val rules = app.game.rules
        val existing = s.buildings.firstOrNull { it.plot == req.plot }
        val type = existing?.type ?: req.plot.fixedType ?: req.type ?: return false
        val cost = rules.buildingCost(type, (existing?.level ?: 0) + 1)
        val sp = surplus(s)
        return sp[0] >= cost.food && sp[1] >= cost.wood && sp[2] >= cost.steel
    }

    private fun buildStep(b: Bot) {
        repeat(balance.timers.buildQueues) {
            val s = state(b)
            val running = s.timers.filter { it.kind == TimerKind.BUILD }
            if (running.size >= balance.timers.buildQueues) return
            val levels = s.buildings.associate { it.plot to it.level }
            val hq = levels[Plot.HQ] ?: 1
            val busy = running.mapNotNull { it.plot }.toSet()
            val candidates = mutableListOf<BuildRequest>()
            candidates += BuildRequest(Plot.HQ)
            if ((levels[Plot.MAUER] ?: 0) < hq) candidates += BuildRequest(Plot.MAUER)
            candidates += BuildRequest(Plot.LAGER)
            // Production: scarcest resource first (lowest amount relative to its rate).
            val scarce = s.resources.sortedBy { it.amount.toDouble() / (it.perHour + 1) }.map { it.resource }
            fun typeOf(r: bayern.kickner.ruinborn.shared.model.Resource) = when (r) {
                bayern.kickner.ruinborn.shared.model.Resource.FOOD -> BuildingType.FARM
                bayern.kickner.ruinborn.shared.model.Resource.WOOD -> BuildingType.SAWMILL
                bayern.kickner.ruinborn.shared.model.Resource.STEEL -> BuildingType.STEEL_MILL
            }
            Plot.entries.filter { it.isResourcePlot && levels[it] == null }.forEach { p -> candidates += BuildRequest(p, typeOf(scarce.first())) }
            candidates += s.buildings.filter { it.plot.isResourcePlot }
                .sortedWith(compareBy({ scarce.indexOf(app.game.rules.producedResource(it.type)) }, { it.level }))
                .map { BuildRequest(it.plot) }
            listOf(Plot.LABOR, Plot.SAMMELPUNKT, Plot.KASERNE, Plot.LAZARETT, Plot.FABRIK, Plot.SCHIESSSTAND, Plot.ALLIANZ)
                .sortedBy { levels[it] ?: 0 }.forEach { candidates += BuildRequest(it) }
            // Always try HQ and wall, everything else only from the surplus above the HQ reserve.
            val started = candidates.filter { it.plot !in busy }.any { req ->
                // Production and warehouse are investments and may dip into the reserve.
                val priority = req.plot == Plot.HQ || req.plot == Plot.MAUER || req.plot == Plot.LAGER || req.plot.isResourcePlot
                (priority || affordableAfterReserve(s, req)) && exec { build(b.id, req) } is ResultOf2.Success
            }
            if (started.not()) return
        }
    }

    private fun researchStep(b: Bot) {
        val s = state(b)
        if (s.timers.any { it.kind == TimerKind.RESEARCH }) return
        val order = listOf(Tech.CONSTRUCTION, Tech.AGRICULTURE, Tech.WOODWORKING, Tech.METALLURGY, Tech.STORAGE, Tech.RESEARCH_METHODS, Tech.DRILL, Tech.ZOMBIOLOGY, Tech.GATHERING)
        val levels = s.research.associate { it.tech to it.level }
        (order + Tech.entries.filter { it !in order }).sortedBy { levels[it] ?: 0 }.any { t -> exec { research(b.id, t) } is ResultOf2.Success }
    }

    /** Speed-ups go to the HQ upgrade, otherwise to the longest build timer. */
    private fun speedupStep(b: Bot) {
        val s = state(b)
        val timer = s.timers.filter { it.kind == TimerKind.BUILD }.maxByOrNull { if (it.plot == Plot.HQ) Long.MAX_VALUE else it.endsAt } ?: return
        s.items.filter { it.item.isSpeedup }.forEach { i -> exec { speedup(b.id, timer.id, i.item, i.count) } }
    }

    private fun trainStep(b: Bot) {
        val s = state(b)
        if (s.timers.any { it.kind == TimerKind.TRAIN && it.target == BuildingType.BARRACKS.name }) return
        val level = s.buildings.firstOrNull { it.plot == Plot.KASERNE }?.level ?: return
        val rules = app.game.rules
        val tier = rules.maxTierFor(level)
        val cost = rules.unitStats(UnitType.INFANTRY, tier).cost
        // Like a real player: one fifth of the resources goes into the military per login (training runs in parallel to building).
        val budget = s.resources.map { it.amount / 5 }
        val affordable = minOf(budget[0] / cost.food.coerceAtLeast(1), budget[1] / cost.wood.coerceAtLeast(1), budget[2] / cost.steel.coerceAtLeast(1))
        val count = minOf(rules.orderSize(BuildingType.BARRACKS, level).toLong(), affordable).toInt()
        if (count >= 10) exec { train(b.id, TrainRequest(BuildingType.BARRACKS, tier, count)) }
    }

    private fun healStep(b: Bot) {
        val s = state(b)
        val wounded = s.troops.filter { it.wounded > 0 }.map { TroopCount(it.type, it.tier, it.wounded) }
        if (wounded.isNotEmpty()) exec { heal(b.id, HealRequest(wounded)) }
    }

    /** Strongest troops first, up to the march size. */
    private fun army(s: PlayerState, max: Long): List<TroopCount> {
        var left = max
        return s.troops.filter { it.home > 0 }.sortedByDescending { it.tier }.mapNotNull { t ->
            val n = minOf(left, t.home.toLong()).toInt()
            left -= n
            if (n > 0) TroopCount(t.type, t.tier, n) else null
        }
    }

    private fun predictWin(troops: List<TroopCount>, level: Int): Boolean {
        val rules = app.game.rules
        val att = troops.mapIndexed { i, t -> val u = rules.unitStats(t.type, t.tier); CombatStack(i + 1, 1, t.type, t.tier, t.count, u.atk, u.def, u.hp) }
        val z = rules.zombie(level)
        val r = simulateCombat(att, listOf(CombatStack(999, 0, null, level, z.count, z.atk, z.def, z.hp)), balance.combat)
        return r.attackerWon && r.losses.filterKeys { it != 999 }.values.sum() < troops.sumOf { it.count } / 4
    }

    private fun marchStep(b: Bot) {
        repeat(5) {
            val s = state(b)
            val hero = s.heroes.firstOrNull { it.unlocked && it.marchId == null } ?: return
            val base = s.base
            val troops = army(s, s.limits.marchSize)
            if (troops.isEmpty()) return
            val objects = (exec {
                ok(MapObjectT.selectAll().map { it.toMapObject() })
            } as ResultOf2.Success).value
            val targeted = (exec { ok(allMarches().mapNotNull { it.targetId }.toSet()) } as ResultOf2.Success).value
            fun dist(x: Int, y: Int) = app.game.rules.distance(base.x, base.y, x, y)
            val maxLevel = s.limits.maxZombieAttackLevel
            val zombie = objects.filter { it.kind == MapObjectKind.ZOMBIE && it.level <= maxLevel && it.id !in targeted && dist(it.x, it.y) <= 30 }
                .sortedWith(compareByDescending<bayern.kickner.ruinborn.server.engine.MapObjectRow> { it.level }.thenBy { dist(it.x, it.y) })
                .firstOrNull { predictWin(troops, it.level) }
            val ok = if (zombie != null) {
                exec { startMarch(b.id, MarchRequest(MarchKind.ATTACK, hero.hero, troops, zombie.x, zombie.y)) } is ResultOf2.Success
            } else {
                val field = objects.filter { it.kind == MapObjectKind.FIELD && it.occupiedBy == null && it.id !in targeted }.minByOrNull { dist(it.x, it.y) }
                field != null && exec { startMarch(b.id, MarchRequest(MarchKind.GATHER, hero.hero, troops, field.x, field.y)) } is ResultOf2.Success
            }
            if (ok.not()) return
        }
    }
}
