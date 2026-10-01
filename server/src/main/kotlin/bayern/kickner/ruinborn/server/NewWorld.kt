package bayern.kickner.ruinborn.server

import bayern.kickner.ruinborn.server.db.Db
import bayern.kickner.ruinborn.server.db.Migrations
import java.io.File

/**
 * New world (concept section 16): with the service stopped, create a backup, require the confirmation "NEUE WELT",
 * delete all game progress, keep accounts. All sessions end. The next login creates the starting state.
 */
object NewWorld {
    const val CONFIRMATION = "NEUE WELT"

    /** Tables holding game progress or world data. Deleting from `player` removes all dependent rows via ON DELETE CASCADE. */
    private val tables = listOf(
        "rally", "march", "map_object", "alliance_request", "alliance_member", "alliance", "chat_report", "chat_message",
        "scheduled_event", "processed_request", "player", "session", "server_meta",
    )

    fun run(config: ServerConfig, input: () -> String?, out: (String) -> Unit): Boolean {
        val db = Db(config.dbPath)
        Migrations.migrate(db)
        val dir = File(config.backupDir).also { it.mkdirs() }
        val backup = File(dir, "ruinborn-vor-neuer-welt-${System.currentTimeMillis()}.db")
        db.newConnection().use { c -> c.prepareStatement("VACUUM INTO ?").use { it.setString(1, backup.absolutePath); it.execute() } }
        out("Sicherung angelegt: ${backup.path}")
        out("Alle Spielstände werden gelöscht, Konten bleiben erhalten. Zum Bestätigen „$CONFIRMATION“ eingeben:")
        if (input()?.trim() != CONFIRMATION) {
            out("Abgebrochen, nichts geändert.")
            return false
        }
        db.newConnection().use { c ->
            c.autoCommit = false
            c.createStatement().use { st -> tables.forEach { st.execute("DELETE FROM $it") } }
            c.commit()
        }
        out("Neue Welt angelegt. Beim nächsten Start wird die Karte neu befüllt.")
        return true
    }
}
