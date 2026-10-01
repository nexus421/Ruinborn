package bayern.kickner.ruinborn.server

import bayern.kickner.ruinborn.server.db.MapObjectT
import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.server.engine.GameError
import bayern.kickner.ruinborn.server.engine.Res
import bayern.kickner.ruinborn.server.engine.addItem
import bayern.kickner.ruinborn.server.engine.baseOf
import bayern.kickner.ruinborn.server.engine.changeTroops
import bayern.kickner.ruinborn.server.engine.mapObjectAt
import bayern.kickner.ruinborn.server.engine.mapSnapshot
import bayern.kickner.ruinborn.server.engine.ok
import bayern.kickner.ruinborn.server.engine.openReport
import bayern.kickner.ruinborn.server.engine.recall
import bayern.kickner.ruinborn.server.engine.reportList
import bayern.kickner.ruinborn.server.engine.startMarch
import bayern.kickner.ruinborn.server.engine.useItem
import bayern.kickner.ruinborn.shared.dto.BattleReport
import bayern.kickner.ruinborn.shared.dto.GatherReport
import bayern.kickner.ruinborn.shared.dto.ItemUseRequest
import bayern.kickner.ruinborn.shared.dto.MarchRequest
import bayern.kickner.ruinborn.shared.dto.ScoutReport
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.ReportKind
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.UnitType
import bayern.kickner.ruinborn.shared.rules.MS_PER_HOUR
import kotnexlib.ResultOf2
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapFlowTest {
    private val w = TestWorld()

    @AfterTest
    fun tearDown() = w.close()

    private fun expectError(code: ErrorCode, r: Res<*>) {
        assertIs<ResultOf2.Failure<*>>(r, "Fehler $code erwartet, war $r")
        assertEquals(code, (r.value as GameError).code)
    }

    private fun inf(n: Int) = listOf(TroopCount(UnitType.INFANTRY, 1, n))

    private fun lastReport(pid: Long) = w.exec { openReport(pid, reportList(pid, null, 1).first().id) }

    /** Time until all marches of a player are home. */
    private fun untilHome(pid: Long) {
        repeat(20) {
            val m = w.state(pid).marches
            if (m.isEmpty()) return
            w.advance((m.maxOf { it.arriveAt } - w.clock.now()).coerceAtLeast(1_000))
        }
        error("Märsche kommen nicht heim")
    }

    @Test
    fun mapIsSpawnedAtStart() {
        val snap = w.exec { ok(mapSnapshot(0)) }
        assertEquals(100, snap.width)
        val count = snap.objects.groupingBy { it.kind }.eachCount()
        assertEquals(580, count[MapObjectKind.ZOMBIE])
        assertEquals(6, count[MapObjectKind.NEST])
        assertEquals(290, count[MapObjectKind.FIELD])
        assertTrue(snap.objects.filter { it.kind == MapObjectKind.NEST }.all { w.app.game.rules.zoneOf(it.x, it.y) >= 2 })
    }

    @Test
    fun zombieLevel1ConceptExampleEndToEnd() {
        val pid = w.register("max")
        val (x, y) = w.placeObject(pid, MapObjectKind.ZOMBIE, 1, dx = 3, dy = 4)
        w.exec { startMarch(pid, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(200), x, y)) }
        val s = w.state(pid)
        val m = s.marches.single()
        // 5 tiles × 30 s = 150 s
        assertEquals(T0 + 150_000, m.arriveAt)
        assertEquals(0, s.troops.sumOf { it.home })
        assertNotNull(s.heroes.single { it.hero == HeroId.RHEA }.marchId)
        w.advance(150_000)
        val report = lastReport(pid).payload
        assertIs<BattleReport>(report)
        assertTrue(report.won)
        assertEquals(3, report.rounds)
        assertEquals(9, report.attackers.single().stacks.single().wounded, "Lazarett hat Platz für alle 9 Verluste")
        // Rhea (level 1, +1 %) improves the stats slightly. Losses stay at 9
        val s2 = w.state(pid)
        // 400 food reward immediately, plus 150 s of production (100/h → 4)
        assertEquals(2404L, s2.resources.first().amount)
        assertEquals(1, s2.maxZombieLevel)
        assertEquals(1, s2.daily.first { it.task.name == "ZOMBIE_HUNT" }.progress)
        assertNull(w.exec { ok(mapObjectAt(x, y)) }, "besiegte Zombies verschwinden")
        untilHome(pid)
        val t = w.state(pid).troops.single()
        assertEquals(191, t.home)
        assertEquals(9, t.wounded)
        val rhea = w.state(pid).heroes.single { it.hero == HeroId.RHEA }
        assertEquals(1, rhea.level, "50 XP reichen nicht für Stufe 2 (100)")
        assertEquals(50L, rhea.xp)
    }

    @Test
    fun zombieLevelLockAndLostFightLeavesZombies() {
        val pid = w.register("max")
        val (x, y) = w.placeObject(pid, MapObjectKind.ZOMBIE, 3)
        expectError(ErrorCode.ZOMBIE_LEVEL_LOCKED, w.tryExec { startMarch(pid, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(10), x, y)) })
        val (x2, y2) = w.placeObject(pid, MapObjectKind.ZOMBIE, 1, dx = 0, dy = 2)
        w.exec { startMarch(pid, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(10), x2, y2)) }
        expectError(ErrorCode.HERO_BUSY, w.tryExec { startMarch(pid, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(10), x2, y2)) })
        untilHome(pid)
        val r = lastReport(pid)
        // The last report is the battle report (returning home creates no report)
        val battle = r.payload as BattleReport
        assertTrue(battle.won.not())
        assertEquals(1, w.exec { ok(mapObjectAt(x2, y2)) }!!.level, "Zombies bleiben vollständig zurück")
    }

    @Test
    fun gatherUntilLoadThenCargoHome() {
        val pid = w.register("max")
        val (x, y) = w.placeObject(pid, MapObjectKind.FIELD, 1, res = Resource.WOOD, amount = 30_000)
        w.exec { startMarch(pid, MarchRequest(MarchKind.GATHER, HeroId.RHEA, inf(100), x, y)) }
        w.advance(60_000) // 2 tiles × 30 s
        val m = w.state(pid).marches.single()
        assertEquals(MarchState.GATHERING, m.state)
        // Load 100 × 12 = 1,200. Rate 4,000/h → 1,080 s
        assertEquals(1_200L, m.load)
        assertEquals(w.clock.now() + 1_080_000, m.gatherEndAt)
        w.advance(1_080_000)
        assertEquals(28_800L, w.exec { ok(mapObjectAt(x, y)) }!!.amount)
        untilHome(pid)
        assertEquals(2000L + 1200 + (100.0 * 1_200_000 / MS_PER_HOUR).toLong(), w.amount(pid, Resource.WOOD))
        val rep = lastReport(pid)
        assertEquals(ReportKind.GATHER, rep.kind)
        assertEquals(1_200L, (rep.payload as GatherReport).amount)
        assertTrue(w.state(pid).achievements.single { it.id.name == "FIRST_GATHER" }.completed)
    }

    @Test
    fun recallFromOutboundReturnsAfterElapsedTime() {
        val pid = w.register("max")
        val (x, y) = w.placeObject(pid, MapObjectKind.ZOMBIE, 1, dx = 10)
        w.exec { startMarch(pid, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(50), x, y)) }
        w.advance(100_000)
        val marchId = w.state(pid).marches.single().id
        w.exec { recall(pid, marchId) }
        val m = w.state(pid).marches.single()
        assertEquals(MarchState.RETURNING, m.state)
        assertEquals(w.clock.now() + 100_000, m.arriveAt)
        w.advance(100_000)
        assertTrue(w.state(pid).marches.isEmpty(), "Märsche: ${w.state(pid).marches} Uhr ${w.clock.now()}")
        assertEquals(200, w.state(pid).troops.single().home)
    }

    @Test
    fun restartProcessesOverdueEventsInOrder() {
        val pid = w.register("max")
        val (x, y) = w.placeObject(pid, MapObjectKind.ZOMBIE, 1, dx = 3, dy = 4)
        w.exec { startMarch(pid, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(200), x, y)) }
        runCatching { kotlinx.coroutines.runBlocking { w.app.stop() } }
        // The server is "off" while the march arrives and returns home
        w.clock.advance(10 * 60_000)
        w.restart()
        w.advance(0)
        val s = w.state(pid)
        assertTrue(s.marches.isEmpty(), "Ankunft und Heimkehr nachgeholt")
        assertEquals(191, s.troops.single().home)
        assertEquals(1, s.maxZombieLevel)
    }

    /** Places a player's base on a free tile next to (x, y). */
    private fun moveBaseNear(pid: Long, x: Int, y: Int) = w.run {
        val spot = listOf(0 to 3, 3 to 0, 0 to -3, -3 to 0, 3 to 3, -3 to -3).map { (dx, dy) -> x + dx to y + dy }
            .first { (px, py) -> rules.inBounds(px, py) && mapObjectAt(px, py) == null }
        MapObjectT.update({ MapObjectT.id eq baseOf(pid)!!.id }) {
            it[MapObjectT.x] = spot.first
            it[MapObjectT.y] = spot.second
        }
    }

    /** Free tile in a zone (for relocation tests). */
    private fun freeTile(zone: Int): Pair<Int, Int> = w.exec {
        for (x in 0 until 100) for (y in 0 until 100) {
            if (rules.zoneOf(x, y) == zone && mapObjectAt(x, y) == null) return@exec ok(x to y)
        }
        error("kein freies Feld")
    }

    @Test
    fun twoMarchesSameMillisecondProcessedByMarchId() {
        val a = w.register("anna")
        val b = w.register("bert")
        val (x, y) = w.placeObject(a, MapObjectKind.ZOMBIE, 1, dx = 3, dy = 4)
        // Put Bert's base on the point mirrored at the zombies: same distance, same arrival.
        w.run {
            val baseA = baseOf(a)!!
            val mx = 2 * x - baseA.x
            val my = 2 * y - baseA.y
            mapObjectAt(mx, my)?.let { o -> MapObjectT.deleteWhere { MapObjectT.id eq o.id } }
            MapObjectT.update({ MapObjectT.id eq baseOf(b)!!.id }) {
                it[MapObjectT.x] = mx
                it[MapObjectT.y] = my
            }
        }
        w.exec { startMarch(a, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(200), x, y)) }
        w.exec { startMarch(b, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(200), x, y)) }
        assertEquals(w.state(a).marches.single().arriveAt, w.state(b).marches.single().arriveAt)
        w.advance(150_000)
        assertTrue((lastReport(a).payload as BattleReport).won, "kleinere Marsch-ID kämpft zuerst")
        assertEquals("Ziel nicht verfügbar", lastReport(b).title)
    }

    @Test
    fun relocateOnlyWithoutMarchesAndByZone() {
        val pid = w.register("max")
        w.run { addItem(pid, ItemId.RELOCATE, 2) }
        val (cx, cy) = freeTile(3)
        expectError(ErrorCode.REQUIREMENT_NOT_MET, w.tryExec { useItem(pid, ItemUseRequest(ItemId.RELOCATE, x = cx, y = cy)) })
        val (fx, fy) = freeTile(1)
        val (zx, zy) = w.placeObject(pid, MapObjectKind.ZOMBIE, 1, dx = 10)
        w.exec { startMarch(pid, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(10), zx, zy)) }
        expectError(ErrorCode.VALIDATION, w.tryExec { useItem(pid, ItemUseRequest(ItemId.RELOCATE, x = fx, y = fy)) })
        untilHome(pid)
        w.exec { useItem(pid, ItemUseRequest(ItemId.RELOCATE, x = fx, y = fy)) }
        assertEquals(fx to fy, w.state(pid).base.let { it.x to it.y })
        assertEquals(1, w.state(pid).items.single { it.item == ItemId.RELOCATE }.count)
    }

    @Test
    fun scoutingCostsFoodAndCreatesReports() {
        val a = w.register("anna")
        val b = w.register("bert")
        val bb = w.state(b).base
        expectError(ErrorCode.TARGET_SHIELDED, w.tryExec { startMarch(a, MarchRequest(MarchKind.SCOUT, x = bb.x, y = bb.y)) })
        w.run { PlayerT.update({ PlayerT.id eq b }) { it[protectionUntil] = 0 } }
        w.exec { startMarch(a, MarchRequest(MarchKind.SCOUT, x = bb.x, y = bb.y)) }
        assertEquals(1500L, w.amount(a, Resource.FOOD))
        assertTrue(w.state(a).protectionUntil <= w.clock.now(), "eigene PvP-Aktion beendet den Neulingsschutz")
        assertEquals(1, w.state(b).incoming.size)
        expectError(ErrorCode.MARCH_LIMIT, w.tryExec { startMarch(a, MarchRequest(MarchKind.SCOUT, x = bb.x, y = bb.y)) })
        untilHome(a)
        val rep = w.exec { ok(reportList(a, null, 10)) }.first { it.kind == ReportKind.SCOUT }
        val scout = w.exec { openReport(a, rep.id) }.payload as ScoutReport
        assertEquals(200, scout.troopsHome.single().count)
        assertEquals(1, scout.wallLevel)
        assertEquals(HeroId.RHEA, scout.defenseHero)
        assertTrue(w.exec { ok(reportList(b, null, 10)) }.any { it.title.startsWith("Du wurdest von anna aufgeklärt") })
    }

    @Test
    fun pvpAttackWithLootAndShieldTurnsBack() {
        val a = w.register("anna")
        val b = w.register("bert")
        w.run {
            PlayerT.update({ PlayerT.id eq b }) { it[protectionUntil] = 0 }
            changeTroops(a, UnitType.INFANTRY, 1, 300, 0)
            changeTroops(b, UnitType.INFANTRY, 1, -200, 0)
        }
        w.setLevels(a, Plot.SAMMELPUNKT to 2)
        val bb = w.state(b).base
        w.exec { startMarch(a, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(500), bb.x, bb.y)) }
        assertEquals(1, w.state(b).incoming.size)
        untilHome(a)
        // Bert had no troops: victory without battle. Lootable 750/750/0 (protected 1,250)
        val food = w.amount(a, Resource.FOOD)
        assertTrue(food >= 2000 + 750, "Beute gutgeschrieben: $food")
        assertTrue(w.amount(b, Resource.FOOD) <= 1250 + 50)
        // Second attack: Bert activates a shield in the meantime
        w.run { addItem(b, ItemId.SHIELD_8H, 1) }
        w.exec { startMarch(a, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(500), bb.x, bb.y)) }
        w.exec { useItem(b, ItemUseRequest(ItemId.SHIELD_8H)) }
        untilHome(a)
        assertTrue(w.exec { ok(reportList(a, null, 5)) }.any { it.title == "Ziel nicht verfügbar" })
    }

    @Test
    fun recoveryShieldAfterThreeLostDefenses() {
        val a = w.register("anna")
        val b = w.register("bert")
        w.run {
            PlayerT.update({ PlayerT.id eq b }) { it[protectionUntil] = 0 }
            changeTroops(b, UnitType.INFANTRY, 1, -200, 0)
        }
        val bb = w.state(b).base
        repeat(3) {
            w.exec { startMarch(a, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(100), bb.x, bb.y)) }
            untilHome(a)
        }
        assertTrue(w.state(b).shieldUntil > w.clock.now(), "Erholungsschild aktiv")
        expectError(ErrorCode.TARGET_SHIELDED, w.tryExec { startMarch(a, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(100), bb.x, bb.y)) })
    }

    @Test
    fun attackOnGatheringMarchTakesCargo() {
        val a = w.register("anna")
        val b = w.register("bert")
        val (x, y) = w.placeObject(b, MapObjectKind.FIELD, 1, res = Resource.FOOD, amount = 30_000)
        moveBaseNear(a, x, y)
        w.exec { startMarch(b, MarchRequest(MarchKind.GATHER, HeroId.RHEA, inf(100), x, y)) }
        w.advance(60_000)
        w.advance(5 * 60_000)
        // Anna attacks the gathering march (marches on the map are never protected)
        w.exec { startMarch(a, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(200), x, y)) }
        val arrive = w.state(a).marches.single().arriveAt
        w.advance(arrive - w.clock.now())
        val rep = lastReport(a).payload as BattleReport
        assertTrue(rep.won)
        assertTrue(rep.againstGatherer)
        assertEquals(Resource.FOOD, rep.loot.let { if (it.food > 0) Resource.FOOD else Resource.WOOD })
        val bm = w.state(b).marches.single()
        assertEquals(MarchState.RETURNING, bm.state)
        assertEquals(0L, bm.cargo!!.total)
        assertNull(w.exec { ok(mapObjectAt(x, y)) }!!.occupiedBy)
    }

    @Test
    fun nestOnlyViaRallyAndMarchSizeLimit() {
        val pid = w.register("max")
        val (x, y) = w.placeObject(pid, MapObjectKind.NEST, 1)
        expectError(ErrorCode.TARGET_INVALID, w.tryExec { startMarch(pid, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(10), x, y)) })
        val (zx, zy) = w.placeObject(pid, MapObjectKind.ZOMBIE, 1, dx = 4)
        expectError(ErrorCode.MARCH_SIZE, w.tryExec { startMarch(pid, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(501), zx, zy)) })
        expectError(ErrorCode.NOT_ENOUGH_TROOPS, w.tryExec { startMarch(pid, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(201), zx, zy)) })
        expectError(ErrorCode.MARCH_SIZE, w.tryExec { startMarch(pid, MarchRequest(MarchKind.ATTACK, HeroId.RHEA, inf(0), zx, zy)) })
    }
}
