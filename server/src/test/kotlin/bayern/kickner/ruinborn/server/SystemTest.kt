package bayern.kickner.ruinborn.server

import bayern.kickner.ruinborn.server.admin.AdminCommand
import bayern.kickner.ruinborn.server.admin.adminCommand
import bayern.kickner.ruinborn.server.admin.parseDuration
import bayern.kickner.ruinborn.server.db.Db
import bayern.kickner.ruinborn.server.db.Migrations
import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.server.engine.EventType
import bayern.kickner.ruinborn.server.engine.GameError
import bayern.kickner.ruinborn.server.engine.GameTime
import bayern.kickner.ruinborn.server.engine.Res
import bayern.kickner.ruinborn.server.engine.addItem
import bayern.kickner.ruinborn.server.engine.accountById
import bayern.kickner.ruinborn.server.engine.markActive
import bayern.kickner.ruinborn.server.engine.chatHistory
import bayern.kickner.ruinborn.server.engine.itemCount
import bayern.kickner.ruinborn.server.engine.login
import bayern.kickner.ruinborn.server.engine.playerOrNull
import bayern.kickner.ruinborn.server.engine.ok
import bayern.kickner.ruinborn.server.engine.postChat
import bayern.kickner.ruinborn.server.engine.ranking
import bayern.kickner.ruinborn.server.engine.reportChat
import bayern.kickner.ruinborn.server.engine.TimerPayload
import bayern.kickner.ruinborn.server.engine.insertTimer
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.TimerKind
import bayern.kickner.ruinborn.shared.rules.Bonuses
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.rules.MS_PER_DAY
import bayern.kickner.ruinborn.shared.rules.MS_PER_HOUR
import kotnexlib.ResultOf2
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SystemTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun expectError(code: ErrorCode, r: Res<*>) {
        assertIs<ResultOf2.Failure<*>>(r, "Fehler $code erwartet, war $r")
        assertEquals(code, (r.value as GameError).code)
    }

    @Test
    fun dailyResetExactlyOnceAt4InBothDstTransitions() {
        for (start in listOf("2026-10-23T12:00:00Z", "2027-03-26T12:00:00Z")) {
            TestWorld(start = Instant.parse(start).toEpochMilli()).use { w ->
                val resets = mutableListOf<Long>()
                repeat(3) {
                    val next = w.app.game.events.all().first { it.type == EventType.DAILY_RESET }.dueAt
                    resets += next
                    w.clock.time = next
                    w.advance(0)
                }
                val local = resets.map { Instant.ofEpochMilli(it).atZone(berlin) }
                assertTrue(local.all { it.toLocalTime() == LocalTime.of(4, 0) }, "alle um 04:00 Ortszeit: $local")
                assertEquals(3, local.map { it.toLocalDate() }.distinct().size, "genau einmal je Tag")
                // On the DST change day the interval is 25 h or 23 h
                val gaps = resets.zipWithNext { a, b -> (b - a) / MS_PER_HOUR }
                assertTrue(gaps.contains(25L) || gaps.contains(23L), "Zeitumstellung erkannt: $gaps")
            }
        }
    }

    @Test
    fun dayKeyUsesLastReset() {
        val z = berlin
        val t = LocalTime.of(4, 0)
        assertEquals("2026-10-24", GameTime.dayKey(Instant.parse("2026-10-25T01:59:00Z").toEpochMilli(), z, t))
        assertEquals("2026-10-25", GameTime.dayKey(Instant.parse("2026-10-25T03:00:00Z").toEpochMilli(), z, t))
    }

    @Test
    fun catchupBonusForPlayersFarBelowMedian() {
        TestWorld().use { w ->
            val strong = (1..3).map { w.register("stark$it").also { p -> w.setLevels(p, Plot.HQ to 10) } }
            val weak = w.register("neu")
            val next = w.app.game.events.all().first { it.type == EventType.DAILY_RESET }.dueAt
            w.clock.time = next
            w.advance(0)
            // Median of [1, 10, 10, 10] = (10 + 10) / 2 = 10 → HQ 1 ≤ 7
            assertTrue(w.state(weak).catchupUntil > w.clock.now())
            assertEquals(0L, w.state(strong.first()).catchupUntil)
            // New players are checked against the current median right away
            val late = w.register("spaet")
            assertTrue(w.state(late).catchupUntil > w.clock.now())
            // +25 % production: farm 1 = 125/h
            assertEquals(125L, w.state(late).resources.first().perHour)
        }
    }

    @Test
    fun timerDuringCatchupBonusCountsFromItsEnd() {
        TestWorld().use { w ->
            val p = w.register("aufholer")
            val rules = w.app.game.rules
            val farms = w.state(p).buildings.filter { it.type == BuildingType.FARM }
            val plot = farms.first().plot
            val bonus = Bonuses.catchup(w.balance, true)[BonusKind.prod(Resource.FOOD)]
            val food0 = w.amount(p, Resource.FOOD)
            w.run {
                PlayerT.update({ PlayerT.id eq p }) { it[catchupUntil] = T0 + 2 * MS_PER_HOUR }
                insertTimer(p, TimerKind.BUILD, plot.name, TimerPayload(plot = plot, type = BuildingType.FARM, level = 2), Cost.ZERO, T0, MS_PER_HOUR)
            }
            w.clock.time = T0 + 3 * MS_PER_HOUR
            // 1 h farm 1 with bonus, 1 h farm 2 with bonus, 1 h farm 2 without bonus (other farms 2 h with, 1 h without bonus)
            val others = farms.drop(1).sumOf { 2 * rules.productionPerHour(BuildingType.FARM, it.level, bonus) + rules.productionPerHour(BuildingType.FARM, it.level) }
            val expected = food0 + rules.productionPerHour(BuildingType.FARM, 1, bonus) + rules.productionPerHour(BuildingType.FARM, 2, bonus) +
                rules.productionPerHour(BuildingType.FARM, 2) + others
            assertTrue(kotlin.math.abs(expected - w.amount(p, Resource.FOOD)) <= 1, "erwartet $expected, ist ${w.amount(p, Resource.FOOD)}")
        }
    }

    @Test
    fun inactivityShieldAndClearOnActivity() {
        TestWorld().use { w ->
            val p = w.register("schlaf")
            w.run { PlayerT.update({ PlayerT.id eq p }) { it[lastActiveAt] = T0 - 8 * MS_PER_DAY } }
            val next = w.app.game.events.all().first { it.type == EventType.DAILY_RESET }.dueAt
            w.clock.time = next
            w.advance(0)
            assertTrue(w.state(p).shieldUntil > w.clock.now() + 365 * MS_PER_DAY)
            w.run { markActive(p) }
            assertEquals(0L, w.state(p).shieldUntil)
        }
    }

    @Test
    fun migrationsFromEmptyDatabaseAreIdempotent() {
        val dir = Files.createTempDirectory("mig").toFile()
        try {
            val db = Db(File(dir, "m.db").path)
            assertEquals(1, Migrations.migrate(db))
            assertEquals(1, Migrations.migrate(db))
            val tables = db.newConnection().use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { it.next(); it.getInt(1) }
            }
            assertEquals(29, tables)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun backupCreatesFileAndKeepsNewest() {
        TestWorld().use { w ->
            w.register("max")
            repeat(16) { i -> w.app.backup.runNow(T0 + i * MS_PER_DAY) }
            val files = File(w.config.backupDir).listFiles()!!.map { it.name }.sorted()
            assertEquals(14, files.size)
            assertEquals("ruinborn-2026-10-03.db", files.first())
            // The backup before a new world is not part of the rotation and is kept.
            File(w.config.backupDir, "ruinborn-vor-neuer-welt-1.db").writeText("x")
            w.app.backup.runNow(T0 + 16 * MS_PER_DAY)
            val after = File(w.config.backupDir).listFiles()!!.map { it.name }
            assertEquals(15, after.size)
            assertTrue("ruinborn-vor-neuer-welt-1.db" in after)
            assertTrue("ruinborn-2026-10-03.db" !in after)
            // The backup is a complete database
            val copy = Db(File(w.config.backupDir, files.last()).path)
            assertEquals(1, copy.newConnection().use { Migrations.currentVersion(it) })
        }
    }

    @Test
    fun chatLimitsAndAdminCommands() {
        TestWorld().use { w ->
            val admin = w.register("admin")
            val p = w.register("max")
            w.exec { postChat(p, "world", "Hallo Welt") }
            expectError(ErrorCode.RATE_LIMITED, w.tryExec { postChat(p, "world", "Nochmal") })
            w.advance(2_000)
            expectError(ErrorCode.VALIDATION, w.tryExec { postChat(p, "world", "x".repeat(301)) })
            expectError(ErrorCode.NOT_IN_ALLIANCE, w.tryExec { postChat(p, "alliance", "Hi") })
            val msgId = w.exec { chatHistory(p, "world", null, 50) }.single().id
            w.exec { reportChat(admin, msgId) }
            assertTrue(w.exec { adminCommand(admin, AdminCommand.parse("/reports")!!) }.contains("Hallo Welt"))
            w.exec { adminCommand(admin, AdminCommand.parse("/del $msgId")!!) }
            assertEquals("Nachricht entfernt", w.exec { chatHistory(p, "world", null, 50) }.single().text)
            w.exec { adminCommand(admin, AdminCommand.parse("/mute max 30m")!!) }
            expectError(ErrorCode.MUTED, w.tryExec { postChat(p, "world", "Darf ich?") })
            w.exec { adminCommand(admin, AdminCommand.parse("/shield max 12h")!!) }
            assertEquals(w.clock.now() + 12 * MS_PER_HOUR, w.state(p).shieldUntil)
            w.exec { adminCommand(admin, AdminCommand.parse("/give max SPEED_8H 3")!!) }
            assertEquals(3, w.exec { ok(itemCount(p, ItemId.SPEED_8H)) })
            w.exec { adminCommand(admin, AdminCommand.parse("/ban max 7d Test")!!) }
            assertTrue(w.exec { ok(accountById(p)!!.bannedUntil) } > w.clock.now())
            w.exec { adminCommand(admin, AdminCommand.parse("/rename max moritz")!!) }
            assertEquals("moritz", w.state(p).name)
            expectError(ErrorCode.VALIDATION, w.tryExec { adminCommand(admin, AdminCommand.parse("/unbekannt")!!) })
        }
    }

    @Test
    fun devCommandsOnlyInDevMode() {
        TestWorld(devMode = false).use { w ->
            val admin = w.register("admin")
            w.register("max")
            expectError(ErrorCode.FORBIDDEN, w.tryExec { adminCommand(admin, AdminCommand.parse("/give max SPEED_8H 3")!!) })
            w.run { addItem(admin, ItemId.SPEED_1M, 1) }
        }
    }

    @Test
    fun durationsAndRankings() {
        assertEquals(30 * 60_000L, parseDuration("30m", 0))
        assertEquals(12 * MS_PER_HOUR, parseDuration("12h", 0))
        assertEquals(7 * MS_PER_DAY, parseDuration("7d", 0))
        assertTrue(parseDuration("perm", 0)!! > 1000L * MS_PER_DAY)
        assertEquals(null, parseDuration("5x", 0))
        TestWorld().use { w ->
            val a = w.register("anna")
            val b = w.register("bert")
            w.setLevels(b, Plot.HQ to 5)
            val r = w.exec { ranking(a, "player-power") }
            assertEquals(listOf("bert", "anna"), r.entries.map { it.name })
            assertEquals(2, r.own!!.rank)
            expectError(ErrorCode.NOT_FOUND, w.tryExec { ranking(a, "unsinn") })
        }
    }
}

class NewWorldTest {
    @Test
    fun newWorldKeepsAccountsAndRecreatesPlayerOnLogin() {
        val w = TestWorld()
        try {
            val pid = w.register("max")
            kotlinx.coroutines.runBlocking { w.app.stop() }
            val lines = mutableListOf<String>()
            assertEquals(false, NewWorld.run(w.config, { "nein" }, { lines += it }))
            assertEquals(true, NewWorld.run(w.config, { NewWorld.CONFIRMATION }, { lines += it }))
            w.restart()
            assertEquals(null, w.exec { ok(playerOrNull(pid)) })
            w.exec { login(pid) }
            assertEquals(2000L, w.state(pid).resources.first().amount)
            assertTrue(File(w.config.backupDir).listFiles()!!.any { it.name.startsWith("ruinborn-vor-neuer-welt") })
        } finally {
            w.close()
        }
    }
}

class SimulationTest {
    @Test
    fun simulationRunsAndWritesCsv() {
        val r = bayern.kickner.ruinborn.server.sim.Simulation(bayern.kickner.ruinborn.shared.balance.BalanceCodec.default, 2).run()
        val lines = r.csv.trim().lines()
        assertEquals(1 + 2 * 6, lines.size, "Kopfzeile + 2 Tage × 6 Bots")
        assertTrue(lines.first().startsWith("tag;typ;spieler;hq"))
        assertTrue(r.summary.contains("Zielwerte Normal-Spieler"))
        assertTrue(lines.drop(1).all { it.split(';')[3].toInt() >= 2 }, "alle Bots bauen mindestens HQ 2")
    }
}
