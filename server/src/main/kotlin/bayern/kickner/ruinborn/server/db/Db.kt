package bayern.kickner.ruinborn.server.db

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection

/**
 * Access to the SQLite file (concept sections 13 and 14).
 *
 * - [write]: engine only. One connection per transaction, pragmas set on open.
 * - [read]: read-only (`SQLiteConfig.setReadOnly(true)`), for HTTP handlers on `Dispatchers.IO`.
 * - `journal_mode=WAL` is set once at startup ([enableWal]). The mode stays stored in the file.
 */
class Db(val path: String) {
    val url = "jdbc:sqlite:$path"

    init {
        File(path).absoluteFile.parentFile?.mkdirs()
    }

    fun newConnection(readOnly: Boolean = false): Connection {
        val cfg = SQLiteConfig().apply {
            setReadOnly(readOnly)
            enforceForeignKeys(true)
            setBusyTimeout(5000)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        }
        return cfg.createConnection(url)
    }

    private fun dbConfig(readOnly: Boolean) = DatabaseConfig {
        // SQLite only knows two isolation levels. SERIALIZABLE is the default.
        defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        // sqlite-jdbc does not allow switching after opening. Exposed has to use the connection's value.
        defaultReadOnly = readOnly
    }

    val write: Database = Database.connect(getNewConnection = { newConnection(false) }, databaseConfig = dbConfig(false))
    val read: Database = Database.connect(getNewConnection = { newConnection(true) }, databaseConfig = dbConfig(true))

    fun enableWal() {
        newConnection(false).use { c -> c.createStatement().use { it.execute("PRAGMA journal_mode=WAL") } }
    }
}
