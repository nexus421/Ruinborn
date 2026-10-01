package bayern.kickner.ruinborn.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalTime
import java.time.ZoneId

/** Server configuration from `config.json` (concept section 16). */
@Serializable
data class ServerConfig(
    val bindHost: String = "127.0.0.1",
    val port: Int = 8080,
    val publicUrl: String = "http://localhost:8080",
    val dbPath: String,
    val balancePath: String,
    val backupDir: String,
    val backupKeep: Int = 14,
    val apkPath: String,
    val inviteCode: String,
    val admins: List<String> = emptyList(),
    val maxPlayers: Int = 50,
    val gameSpeed: Double = 1.0,
    val timezone: String = "Europe/Berlin",
    val dailyResetTime: String = "04:00",
    val backupTime: String = "03:30",
    val cleanupTime: String = "03:45",
    val minClientVersion: Int = 1,
    val latestClientVersion: Int = 1,
    val logLevel: String = "INFO",
    val devMode: Boolean = false,
    /**
     * Iterations of password hashing. Must not be changed after the first start, otherwise stored hashes
     * no longer match. Tests use a small value to keep run times short.
     */
    val passwordIterations: Int = 100_000,
) {
    val zone: ZoneId get() = ZoneId.of(timezone)
    val resetTime: LocalTime get() = LocalTime.parse(dailyResetTime)
    val apkUrl: String get() = publicUrl.trimEnd('/') + "/download/ruinborn.apk"

    /** Validates the configuration and returns all problems. */
    fun problems(): List<String> = buildList {
        if (port !in 1..65535) add("port ungültig")
        if (inviteCode.isBlank()) add("inviteCode darf nicht leer sein")
        if (maxPlayers <= 0) add("maxPlayers muss > 0 sein")
        if (gameSpeed <= 0.0) add("gameSpeed muss > 0 sein")
        if (backupKeep <= 0) add("backupKeep muss > 0 sein")
        if (passwordIterations <= 0) add("passwordIterations muss > 0 sein")
        if (minClientVersion > latestClientVersion) add("minClientVersion darf nicht größer als latestClientVersion sein")
        runCatching { ZoneId.of(timezone) }.onFailure { add("timezone ungültig: $timezone") }
        listOf("dailyResetTime" to dailyResetTime, "backupTime" to backupTime, "cleanupTime" to cleanupTime).forEach { (k, v) ->
            runCatching { LocalTime.parse(v) }.onFailure { add("$k ungültig: $v") }
        }
        if (logLevel.uppercase() !in setOf("DEBUG", "INFO", "WARN", "ERROR")) add("logLevel ungültig: $logLevel")
    }

    fun isAdmin(username: String): Boolean = admins.any { it.equals(username, ignoreCase = true) }

    companion object {
        private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }

        fun load(file: File): ServerConfig = json.decodeFromString(serializer(), file.readText())
        fun encode(c: ServerConfig): String = json.encodeToString(serializer(), c)
    }
}
