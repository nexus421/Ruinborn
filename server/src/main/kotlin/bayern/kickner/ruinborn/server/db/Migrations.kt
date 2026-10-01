package bayern.kickner.ruinborn.server.db

import java.sql.Connection

/**
 * Custom minimal migrations (concept section 14): `db/index.txt` lists the files `V001__….sql`, `V002__….sql`
 * and so on. At startup all files with a number greater than `schema_version` run in one transaction.
 */
object Migrations {
    private val nameRegex = Regex("""^V(\d{3})__.+\.sql$""")

    data class Migration(val version: Int, val name: String, val sql: String)

    fun available(): List<Migration> {
        val index = resource("db/index.txt") ?: error("db/index.txt fehlt")
        return index.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
            val v = nameRegex.matchEntire(name)?.groupValues?.get(1)?.toInt() ?: error("Ungültiger Migrationsname $name")
            Migration(v, name, resource("db/$name") ?: error("Migration $name fehlt"))
        }.sortedBy { it.version }
    }

    /** Runs missing migrations and returns the new schema version. */
    fun migrate(db: Db): Int = db.newConnection(false).use { c ->
        c.autoCommit = false
        val current = currentVersion(c)
        val pending = available().filter { it.version > current }
        runCatching {
            pending.forEach { m -> statements(m.sql).forEach { s -> c.createStatement().use { it.execute(s) } } }
            val target = pending.lastOrNull()?.version ?: current
            if (target != current) {
                c.createStatement().use { it.execute("DELETE FROM schema_version") }
                c.prepareStatement("INSERT INTO schema_version (version) VALUES (?)").use { it.setInt(1, target); it.executeUpdate() }
            }
            c.commit()
            target
        }.getOrElse {
            c.rollback()
            throw IllegalStateException("Migration fehlgeschlagen: ${it.message}", it)
        }
    }

    fun currentVersion(c: Connection): Int {
        val exists = c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='schema_version'").use { it.next() && it.getInt(1) > 0 }
        }
        if (exists.not()) return 0
        return c.createStatement().use { st -> st.executeQuery("SELECT max(version) FROM schema_version").use { if (it.next()) it.getInt(1) else 0 } }
    }

    /** Splits a script into statements. Comments starting with `--` are removed. */
    fun statements(sql: String): List<String> =
        sql.lines().joinToString("\n") { line -> line.substringBefore("--") }
            .split(';').map { it.trim() }.filter { it.isNotEmpty() }

    private fun resource(path: String): String? =
        Migrations::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
}
