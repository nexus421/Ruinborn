package bayern.kickner.ruinborn.server

import bayern.kickner.klogger.KLogger
import bayern.kickner.ruinborn.server.log.Logging
import bayern.kickner.ruinborn.shared.balance.BalanceCodec
import bayern.kickner.ruinborn.shared.balance.DefaultBalance
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/** Start: `java -jar ruinborn-server.jar --config /etc/ruinborn/config.json [--new-world]`. */
fun main(args: Array<String>) {
    val configPath = args.indexOf("--config").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: "dev/config.json"
    val cfgFile = File(configPath)
    if (cfgFile.isFile.not()) { System.err.println("Konfiguration fehlt: $configPath"); exitProcess(1) }
    val config = runCatching { ServerConfig.load(cfgFile) }.getOrElse { System.err.println("config.json ungültig: ${it.message}"); exitProcess(1) }
    config.problems().takeIf { it.isNotEmpty() }?.let { System.err.println("config.json: " + it.joinToString("; ")); exitProcess(1) }
    Logging.setup(config.logLevel)
    if ("--new-world" in args) {
        val done = NewWorld.run(config, ::readlnOrNull, ::println)
        exitProcess(if (done) 0 else 2)
    }

    val balanceFile = File(config.balancePath)
    if (balanceFile.exists().not()) {
        balanceFile.absoluteFile.parentFile?.mkdirs()
        balanceFile.writeText(DefaultBalance.JSON)
        KLogger.info("Main") { "Standard-balance.json nach ${balanceFile.path} geschrieben" }
    }
    val (balance, problems) = BalanceCodec.load(balanceFile.readText())
    if (balance == null) { KLogger.crash("Main") { "balance.json fehlerhaft:\n" + problems.joinToString("\n") }; exitProcess(1) }

    val app = App(config, balance)
    app.start()
    val server = embeddedServer(CIO, port = config.port, host = config.bindHost) { app.module(this) }
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(1_000, 5_000)
        runBlocking { app.stop() }
    })
    KLogger.info("Main") { "Ruinborn-Server lauscht auf ${config.bindHost}:${config.port}" }
    server.start(wait = true)
}
