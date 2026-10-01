package bayern.kickner.ruinborn.server

import bayern.kickner.ruinborn.server.engine.Ctx
import bayern.kickner.ruinborn.server.engine.ManualClock
import bayern.kickner.ruinborn.server.engine.Res
import bayern.kickner.ruinborn.server.engine.baseOf
import bayern.kickner.ruinborn.server.engine.mapObjectAt
import bayern.kickner.ruinborn.server.engine.ok
import bayern.kickner.ruinborn.server.engine.playerState
import bayern.kickner.ruinborn.server.engine.register
import bayern.kickner.ruinborn.server.engine.setBuilding
import bayern.kickner.ruinborn.server.db.MapObjectT
import bayern.kickner.ruinborn.shared.balance.Balance
import bayern.kickner.ruinborn.shared.balance.BalanceCodec
import bayern.kickner.ruinborn.shared.dto.PlayerState
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.Resource
import kotlinx.coroutines.runBlocking
import kotnexlib.ResultOf2
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.random.Random

/** 01.10.2026 10:00 UTC, fixed start time of all tests. */
val T0: Long = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli()

fun testConfig(dir: File, gameSpeed: Double = 1.0, maxPlayers: Int = 50, devMode: Boolean = true) = ServerConfig(
    dbPath = File(dir, "test.db").path,
    balancePath = File(dir, "balance.json").path,
    backupDir = File(dir, "backups").path,
    apkPath = File(dir, "app.apk").path,
    inviteCode = "code",
    admins = listOf("admin"),
    maxPlayers = maxPlayers,
    gameSpeed = gameSpeed,
    devMode = devMode,
    passwordIterations = 10,
)

/**
 * Test world: real engine with a temporary SQLite file, controllable clock and fixed randomness.
 * [exec] runs a command in the engine and returns the success value (errors fail the test).
 */
class TestWorld(
    gameSpeed: Double = 1.0,
    maxPlayers: Int = 50,
    val dir: File = Files.createTempDirectory("ruinborn-test").toFile(),
    start: Long = T0,
    val balance: Balance = BalanceCodec.default,
    devMode: Boolean = true,
) : AutoCloseable {
    val clock = ManualClock(start)
    val config = testConfig(dir, gameSpeed, maxPlayers, devMode)
    var app = App(config, balance, clock, Random(42))
        private set

    init {
        bayern.kickner.ruinborn.server.log.Logging.setup("WARN")
        app.start()
    }

    fun <T> tryExec(block: Ctx.() -> Res<T>): Res<T> = runBlocking { app.engine.exec(block) }

    fun <T> exec(block: Ctx.() -> Res<T>): T = when (val r = tryExec(block)) {
        is ResultOf2.Success -> r.value
        is ResultOf2.Failure -> error("Befehl fehlgeschlagen: ${r.value}")
    }

    fun run(block: Ctx.() -> Unit) = exec { block(); ok(Unit) }

    fun register(name: String): Long {
        exec { register(name, "v1\$x\$y") }
        return exec { ok(bayern.kickner.ruinborn.server.engine.accountByName(name)!!.id) }
    }

    fun state(pid: Long): PlayerState = exec { ok(playerState(pid)) }

    /** Advances the clock and processes due events. */
    fun advance(ms: Long) {
        clock.advance(ms)
        runBlocking { app.engine.tick() }
    }

    fun amount(pid: Long, r: Resource): Long = state(pid).resources.first { it.resource == r }.amount

    /** Sets building levels directly (test shortcut instead of hours of building). */
    fun setLevels(pid: Long, vararg levels: Pair<Plot, Int>) = run {
        levels.forEach { (plot, l) -> setBuilding(pid, plot, plot.fixedType ?: BuildingType.FARM, l) }
    }

    /** Creates a map object on a free spot near the base. */
    fun placeObject(pid: Long, kind: MapObjectKind, level: Int, dx: Int = 2, dy: Int = 0, res: Resource? = null, amount: Long = 0): Pair<Int, Int> =
        exec {
            val base = baseOf(pid)!!
            // Towards the map center so the tile is guaranteed to be on the map.
            val x = base.x + if (base.x < 50) dx else -dx
            val y = base.y + if (base.y < 50) dy else -dy
            mapObjectAt(x, y)?.let { o -> MapObjectT.deleteWhere { MapObjectT.id eq o.id } }
            MapObjectT.insert {
                it[MapObjectT.kind] = kind
                it[MapObjectT.x] = x
                it[MapObjectT.y] = y
                it[zone] = rules.zoneOf(x, y)
                it[MapObjectT.level] = level
                it[resType] = res
                it[MapObjectT.amount] = amount
                it[playerId] = null
                it[occupiedBy] = null
            }
            ok(x to y)
        }

    /** Simulates a restart: stop the engine, start again with the same database. */
    fun restart() {
        runBlocking { app.stop() }
        app = App(config, balance, clock, Random(7))
        app.start()
    }

    override fun close() {
        runBlocking { app.stop() }
        dir.deleteRecursively()
    }
}
