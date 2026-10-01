package bayern.kickner.ruinborn.server

import bayern.kickner.ruinborn.server.db.MapObjectT
import bayern.kickner.ruinborn.server.engine.GameError
import bayern.kickner.ruinborn.server.engine.Res
import bayern.kickner.ruinborn.server.engine.baseOf
import bayern.kickner.ruinborn.server.engine.build
import bayern.kickner.ruinborn.server.engine.cancelRally
import bayern.kickner.ruinborn.server.engine.changeTroops
import bayern.kickner.ruinborn.server.engine.claimGift
import bayern.kickner.ruinborn.server.engine.createAlliance
import bayern.kickner.ruinborn.server.engine.createRally
import bayern.kickner.ruinborn.server.engine.credit
import bayern.kickner.ruinborn.server.engine.ensureHeroes
import bayern.kickner.ruinborn.server.engine.giftList
import bayern.kickner.ruinborn.server.engine.helpAll
import bayern.kickner.ruinborn.server.engine.joinAlliance
import bayern.kickner.ruinborn.server.engine.joinRally
import bayern.kickner.ruinborn.server.engine.leaveAlliance
import bayern.kickner.ruinborn.server.engine.manageMember
import bayern.kickner.ruinborn.server.engine.mapObjectAt
import bayern.kickner.ruinborn.server.engine.ok
import bayern.kickner.ruinborn.server.engine.requestHelp
import bayern.kickner.ruinborn.server.engine.startMarch
import bayern.kickner.ruinborn.server.engine.answerRequest
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.dto.AllianceCreateRequest
import bayern.kickner.ruinborn.shared.dto.BuildRequest
import bayern.kickner.ruinborn.shared.dto.MarchRequest
import bayern.kickner.ruinborn.shared.dto.RallyJoinRequest
import bayern.kickner.ruinborn.shared.dto.RallyRequest
import bayern.kickner.ruinborn.shared.model.AchievementId
import bayern.kickner.ruinborn.shared.model.AllianceRank
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.JoinMode
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.model.Plot
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AllianceFlowTest {
    private val w = TestWorld()

    @AfterTest
    fun tearDown() = w.close()

    private fun expectError(code: ErrorCode, r: Res<*>) {
        assertIs<ResultOf2.Failure<*>>(r, "Fehler $code erwartet, war $r")
        assertEquals(code, (r.value as GameError).code)
    }

    private fun inf(n: Int) = listOf(TroopCount(UnitType.INFANTRY, 1, n))

    private fun player(name: String, hq: Int = 4): Long {
        val pid = w.register(name)
        w.setLevels(pid, Plot.HQ to hq, Plot.MAUER to hq - 1, Plot.ALLIANZ to 1, Plot.SAMMELPUNKT to hq)
        w.run { ensureHeroes(pid, hq) }
        return pid
    }

    private fun alliance(leader: Long, name: String = "Die Ersten", tag: String = "ERS", mode: JoinMode = JoinMode.OPEN): Long =
        w.exec { createAlliance(leader, AllianceCreateRequest(name, tag, mode, "Hallo")) }

    /** Places the base right next to a target so travel times are short and predictable. */
    private fun moveBase(pid: Long, x: Int, y: Int) = w.run {
        mapObjectAt(x, y)?.let { o -> MapObjectT.deleteWhere { MapObjectT.id eq o.id } }
        MapObjectT.update({ MapObjectT.id eq baseOf(pid)!!.id }) {
            it[MapObjectT.x] = x
            it[MapObjectT.y] = y
        }
    }

    @Test
    fun foundingRulesAndUniqueness() {
        val low = w.register("klein")
        expectError(ErrorCode.REQUIREMENT_NOT_MET, w.tryExec { createAlliance(low, AllianceCreateRequest("Abc", "ABC", JoinMode.OPEN)) })
        val a = player("anna")
        val b = player("bert")
        alliance(a)
        expectError(ErrorCode.ALREADY_IN_ALLIANCE, w.tryExec { createAlliance(a, AllianceCreateRequest("Zweite", "ZWE", JoinMode.OPEN)) })
        expectError(ErrorCode.NAME_TAKEN, w.tryExec { createAlliance(b, AllianceCreateRequest("die ersten", "XYZ", JoinMode.OPEN)) })
        expectError(ErrorCode.NAME_TAKEN, w.tryExec { createAlliance(b, AllianceCreateRequest("Andere", "ERS", JoinMode.OPEN)) })
        expectError(ErrorCode.VALIDATION, w.tryExec { createAlliance(b, AllianceCreateRequest("Andere", "er5", JoinMode.OPEN)) })
        assertTrue(w.state(a).achievements.single { it.id == AchievementId.JOIN_ALLIANCE }.completed)
    }

    @Test
    fun requestModeRanksAndJoinBlock() {
        val a = player("anna")
        val b = player("bert")
        val c = player("carl")
        val aid = alliance(a, mode = JoinMode.REQUEST)
        w.exec { joinAlliance(b, aid) }
        assertNull(w.state(b).alliance, "auf Anfrage: noch kein Mitglied")
        w.exec { answerRequest(a, b, true) }
        assertEquals(AllianceRank.MEMBER, w.state(b).alliance!!.rank)
        w.exec { joinAlliance(c, aid) }
        expectError(ErrorCode.FORBIDDEN, w.tryExec { answerRequest(b, c, true) })
        w.exec { manageMember(a, b, "promote") }
        w.exec { answerRequest(b, c, true) }
        expectError(ErrorCode.FORBIDDEN, w.tryExec { manageMember(b, a, "kick") })
        w.exec { manageMember(b, c, "kick") }
        assertNull(w.state(c).alliance)
        expectError(ErrorCode.ALLIANCE_BLOCKED, w.tryExec { joinAlliance(c, aid) })
        expectError(ErrorCode.VALIDATION, w.tryExec { leaveAlliance(a) })
        w.exec { manageMember(a, b, "make-leader") }
        assertEquals(AllianceRank.LEADER, w.state(b).alliance!!.rank)
        w.exec { leaveAlliance(a) }
        w.advance(12 * MS_PER_HOUR)
        w.exec { joinAlliance(c, aid) }
    }

    @Test
    fun allianceHelpShortensTimers() {
        val a = player("anna")
        val b = player("bert")
        val aid = alliance(a)
        w.exec { joinAlliance(b, aid) }
        w.run { credit(a, Cost(50_000, 50_000, 50_000)) }
        w.setLevels(a, Plot.MAUER to 4)
        w.exec { build(a, BuildRequest(Plot.HQ)) } // HQ 5: 120 × 1.3^4 = 342.7 s → 343 s
        val t = w.state(a).timers.single()
        expectError(ErrorCode.NOT_FOUND, w.tryExec { requestHelp(b, t.id) })
        w.exec { requestHelp(a, t.id) }
        // Alliance center 1: 5 + ⌊1/2⌋ = 5 helps
        assertEquals(5, w.state(a).timers.single().helpMax)
        assertEquals(1, w.state(b).openAllianceHelps)
        assertEquals(1, w.exec { helpAll(b) })
        val after = w.state(a).timers.single()
        assertEquals(t.endsAt - 60_000, after.endsAt)
        assertEquals(1, after.helpCount)
        assertEquals(0, w.exec { helpAll(b) }, "pro Timer nur einmal")
        assertEquals(1, w.state(b).daily.single { it.task.name == "COMRADE" }.progress)
    }

    @Test
    fun reinforcementDefendsAndLeavesWithAlliance() {
        val a = player("anna")
        val b = player("bert")
        val aid = alliance(a)
        w.exec { joinAlliance(b, aid) }
        val ab = w.state(a).base
        moveBase(b, ab.x + if (ab.x < 50) 2 else -2, ab.y)
        w.exec { startMarch(b, MarchRequest(MarchKind.REINFORCE, HeroId.RHEA, inf(100), ab.x, ab.y)) }
        expectError(ErrorCode.VALIDATION, w.tryExec { startMarch(b, MarchRequest(MarchKind.REINFORCE, HeroId.RHEA, inf(10), ab.x, ab.y)) })
        w.advance(60_000)
        assertEquals(MarchState.STATIONED, w.state(b).marches.single().state)
        assertEquals(100L, w.state(a).limits.reinforcementsStationed)
        w.exec { leaveAlliance(b) }
        assertEquals(MarchState.RETURNING, w.state(b).marches.single().state)
        w.advance(60_000)
        assertTrue(w.state(b).marches.isEmpty())
    }

    @Test
    fun rallyWithThreeParticipantsDefeatsNestAndGivesGifts() {
        val a = player("anna", 10)
        val b = player("bert", 10)
        val c = player("carl", 10)
        val d = player("dora", 10)
        val aid = alliance(a)
        listOf(b, c, d).forEach { p -> w.exec { joinAlliance(p, aid) } }
        // Strong armies: 3,000 infantry T1 each (rally point 10: march size 4,100)
        listOf(a, b, c).forEach { p -> w.run { changeTroops(p, UnitType.INFANTRY, 1, 2_800, 0) } }
        val ab = w.state(a).base
        val nx = ab.x + if (ab.x < 50) 3 else -3
        val ny = ab.y
        w.run {
            mapObjectAt(nx, ny)?.let { o -> MapObjectT.deleteWhere { MapObjectT.id eq o.id } }
        }
        w.placeObject(a, MapObjectKind.NEST, 1, dx = 3, dy = 0)
        moveBase(b, ab.x, ab.y + if (ab.y < 50) 2 else -2)
        moveBase(c, ab.x, ab.y + if (ab.y < 50) 4 else -4)
        val rallyId = w.exec { createRally(a, RallyRequest(nx, ny, 5, HeroId.RHEA, inf(3_000))) }
        w.exec { joinRally(b, rallyId, RallyJoinRequest(HeroId.RHEA, inf(3_000))) }
        w.exec { joinRally(c, rallyId, RallyJoinRequest(HeroId.RHEA, inf(3_000))) }
        expectError(ErrorCode.VALIDATION, w.tryExec { joinRally(a, rallyId, RallyJoinRequest(HeroId.RHEA, inf(10))) })
        w.advance(5 * 60_000) // departure after 5 min
        assertTrue(w.state(a).marches.single().state == MarchState.OUTBOUND)
        w.advance(90_000) // 3 tiles × 30 s
        assertNull(w.exec { ok(mapObjectAt(nx, ny)) }, "Nest besiegt")
        listOf(a, b, c).forEach { p ->
            assertTrue(w.state(p).achievements.single { it.id == AchievementId.RALLY_FIGHT }.completed)
            assertEquals(1, w.state(p).items.first { it.item == ItemId.SPEED_60M }.count)
        }
        // Gift also for Dora, who did not take part
        val gift = w.exec { ok(giftList(d)) }.single()
        val before = w.amount(d, Resource.FOOD)
        w.exec { claimGift(d, gift.id) }
        assertEquals(before + 5_000, w.amount(d, Resource.FOOD))
        assertEquals(1, w.state(d).items.single { it.item == ItemId.SPEED_15M }.count)
    }

    @Test
    fun lateJoinRejectedAndCancelSendsEveryoneHome() {
        val a = player("anna", 10)
        val b = player("bert", 10)
        val aid = alliance(a)
        w.exec { joinAlliance(b, aid) }
        val (nx, ny) = w.placeObject(a, MapObjectKind.NEST, 1, dx = 3)
        val rallyId = w.exec { createRally(a, RallyRequest(nx, ny, 5, HeroId.RHEA, inf(100))) }
        // Bert far away: arrives after the waiting time is over
        val ab = w.state(a).base
        moveBase(b, if (ab.x < 50) 99 else 0, if (ab.y < 50) 99 else 0)
        expectError(ErrorCode.VALIDATION, w.tryExec { joinRally(b, rallyId, RallyJoinRequest(HeroId.RHEA, inf(10))) })
        w.exec { cancelRally(a, rallyId) }
        assertTrue(w.state(a).marches.isEmpty())
        assertEquals(200, w.state(a).troops.single().home)
    }

    @Test
    fun leavingWithWaitingRallyMarchAndStationedReinforcement() {
        val a = player("anna", 10)
        val b = player("bert", 10)
        val aid = alliance(a)
        w.exec { joinAlliance(b, aid) }
        val ab = w.state(a).base
        moveBase(b, ab.x, ab.y + if (ab.y < 50) 2 else -2)
        w.run { changeTroops(b, UnitType.INFANTRY, 1, 200, 0) }
        val (nx, ny) = w.placeObject(a, MapObjectKind.NEST, 1, dx = 3)
        val rallyId = w.exec { createRally(a, RallyRequest(nx, ny, 10, HeroId.RHEA, inf(100))) }
        w.exec { joinRally(b, rallyId, RallyJoinRequest(HeroId.RHEA, inf(100))) }
        w.exec { startMarch(b, MarchRequest(MarchKind.REINFORCE, HeroId.VIKTOR, inf(100), ab.x, ab.y)) }
        w.advance(60_000)
        assertEquals(setOf(MarchState.WAITING, MarchState.STATIONED), w.state(b).marches.map { it.state }.toSet())
        w.exec { leaveAlliance(b) }
        assertTrue(w.state(b).marches.all { it.state == MarchState.RETURNING })
        w.advance(60_000)
        assertTrue(w.state(b).marches.isEmpty())
        assertEquals(400, w.state(b).troops.single().home)
    }
}
