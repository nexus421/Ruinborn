package bayern.kickner.ruinborn.server.jobs

import bayern.kickner.klogger.KLogger
import bayern.kickner.ruinborn.server.App
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/** Daily backup via `VACUUM INTO` on a separate connection outside the engine. The 14 newest are kept. */
class Backup(private val app: App) {
    fun runAsync(at: Long) {
        app.scope.launch { runCatching { runNow(at) }.onFailure { KLogger.error("Backup") { "Sicherung fehlgeschlagen: ${it.message}" } } }
    }

    fun runNow(at: Long): File {
        val dir = File(app.config.backupDir).also { it.mkdirs() }
        val day = Instant.ofEpochMilli(at).atZone(app.config.zone).toLocalDate()
        val target = File(dir, "ruinborn-$day.db")
        if (target.exists()) target.delete()
        app.db.newConnection(false).use { c ->
            c.prepareStatement("VACUUM INTO ?").use { it.setString(1, target.absolutePath); it.execute() }
        }
        // Only the daily backups rotate. The backup made before a new world is left alone.
        dir.listFiles { f -> DAILY.matches(f.name) }
            ?.sortedByDescending { it.name }?.drop(app.config.backupKeep)?.forEach { it.delete() }
        KLogger.info("Backup") { "Sicherung erstellt: ${target.path}" }
        return target
    }

    private companion object {
        val DAILY = Regex("""^ruinborn-\d{4}-\d{2}-\d{2}\.db$""")
    }
}
