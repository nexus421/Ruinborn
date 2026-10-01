package bayern.kickner.ruinborn.server.admin

import bayern.kickner.ruinborn.server.db.AccountT
import bayern.kickner.ruinborn.server.db.AuditLogT
import bayern.kickner.ruinborn.server.db.ChatMessageT
import bayern.kickner.ruinborn.server.db.ChatReportT
import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.server.db.SessionT
import bayern.kickner.ruinborn.server.engine.Ctx
import bayern.kickner.ruinborn.server.engine.EventType
import bayern.kickner.ruinborn.server.engine.INACTIVITY_SHIELD_UNTIL
import bayern.kickner.ruinborn.server.engine.Res
import bayern.kickner.ruinborn.server.engine.accountByName
import bayern.kickner.ruinborn.server.engine.accountName
import bayern.kickner.ruinborn.server.engine.activateShield
import bayern.kickner.ruinborn.server.engine.addItem
import bayern.kickner.ruinborn.server.engine.addReport
import bayern.kickner.ruinborn.server.engine.allPlayerIds
import bayern.kickner.ruinborn.server.engine.baseOf
import bayern.kickner.ruinborn.server.engine.credit
import bayern.kickner.ruinborn.server.engine.deleteChat
import bayern.kickner.ruinborn.server.engine.fail
import bayern.kickner.ruinborn.server.engine.formatTime
import bayern.kickner.ruinborn.server.engine.ok
import bayern.kickner.ruinborn.server.engine.player
import bayern.kickner.ruinborn.server.engine.resettle
import bayern.kickner.ruinborn.server.engine.setTimerEnd
import bayern.kickner.ruinborn.server.engine.settle
import bayern.kickner.ruinborn.server.engine.systemChat
import bayern.kickner.ruinborn.server.engine.timers
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.dto.SystemReport
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.rules.MS_PER_DAY
import bayern.kickner.ruinborn.shared.rules.MS_PER_HOUR
import bayern.kickner.ruinborn.shared.rules.Validation
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** A parsed admin command from the chat, e.g. `/ban Max 7d Beleidigung`. */
data class AdminCommand(val name: String, val args: List<String>, val raw: String) {
    companion object {
        fun parse(text: String): AdminCommand? {
            if (text.startsWith("/").not()) return null
            val parts = text.trim().removePrefix("/").split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            return AdminCommand(parts.first().lowercase(), parts.drop(1), text.trim())
        }
    }
}

/** Duration in the format `30m`, `12h`, `7d` or `perm`. perm yields a duration until the year 9999. */
fun parseDuration(s: String, now: Long): Long? {
    if (s.equals("perm", ignoreCase = true)) return INACTIVITY_SHIELD_UNTIL - now
    val m = Regex("^(\\d+)([mhd])$").matchEntire(s.lowercase()) ?: return null
    val n = m.groupValues[1].toLong()
    if (n <= 0) return null
    return when (m.groupValues[2]) {
        "m" -> n * 60_000
        "h" -> n * MS_PER_HOUR
        else -> n * MS_PER_DAY
    }
}

const val ADMIN_HELP = "Befehle: /ban <name> <dauer> [grund], /unban <name>, /mute <name> <dauer>, /unmute <name>, /del <id>, " +
    "/rename <alt> <neu>, /password <name> <neues-passwort>, /shield <name> <dauer>, /announce <text>, /reports, /resolve <id>" +
    " – Dauer: 30m, 12h, 7d oder perm."

private fun Ctx.audit(adminId: Long, action: String, target: String?, details: String?) {
    AuditLogT.insert {
        it[AuditLogT.adminId] = adminId
        it[AuditLogT.action] = action
        it[AuditLogT.target] = target
        it[AuditLogT.details] = details
        it[createdAt] = now
    }
}

/**
 * Executes an admin command and returns the system message for the admin. `/password` arrives with an already
 * computed hash ([passwordHash]) because hashing never runs in the engine. The audit log never stores a password.
 */
fun Ctx.adminCommand(adminId: Long, cmd: AdminCommand, passwordHash: String? = null): Res<String> {
    fun target(i: Int) = cmd.args.getOrNull(i)?.let { accountByName(it) }
    fun missing() = fail(ErrorCode.VALIDATION, "Ungültige Angaben. $ADMIN_HELP")
    return when (cmd.name) {
        "ban" -> {
            val acc = target(0) ?: return missing()
            val dur = cmd.args.getOrNull(1)?.let { parseDuration(it, now) } ?: return missing()
            val reason = cmd.args.drop(2).joinToString(" ").ifBlank { null }
            val until = now + dur
            AccountT.update({ AccountT.id eq acc.id }) {
                it[bannedUntil] = until
                it[banReason] = reason
            }
            SessionT.deleteWhere { SessionT.accountId eq acc.id }
            endSessions(acc.id, "Sperre")
            activateShield(acc.id, dur, "Sperrschild")
            audit(adminId, "ban", acc.username, "${cmd.args.getOrNull(1)} ${reason ?: ""}".trim())
            ok("${acc.username} ist gesperrt bis ${formatTime(until)}.")
        }
        "unban" -> {
            val acc = target(0) ?: return missing()
            AccountT.update({ AccountT.id eq acc.id }) {
                it[bannedUntil] = 0
                it[banReason] = null
            }
            if (player(acc.id).shieldUntil == acc.bannedUntil) {
                PlayerT.update({ PlayerT.id eq acc.id }) { it[shieldUntil] = now }
                cancelEvents(EventType.SHIELD_END) { it.playerId == acc.id }
                changedObjects += listOfNotNull(baseOf(acc.id)?.id)
                dirty(acc.id)
            }
            audit(adminId, "unban", acc.username, null)
            ok("Sperre von ${acc.username} aufgehoben.")
        }
        "mute" -> {
            val acc = target(0) ?: return missing()
            val dur = cmd.args.getOrNull(1)?.let { parseDuration(it, now) } ?: return missing()
            AccountT.update({ AccountT.id eq acc.id }) { it[mutedUntil] = now + dur }
            audit(adminId, "mute", acc.username, cmd.args.getOrNull(1))
            ok("${acc.username} darf bis ${formatTime(now + dur)} nicht schreiben.")
        }
        "unmute" -> {
            val acc = target(0) ?: return missing()
            AccountT.update({ AccountT.id eq acc.id }) { it[mutedUntil] = 0 }
            audit(adminId, "unmute", acc.username, null)
            ok("${acc.username} darf wieder schreiben.")
        }
        "del" -> {
            val id = cmd.args.getOrNull(0)?.toLongOrNull() ?: return missing()
            deleteChat(id).let { if (it is kotnexlib.ResultOf2.Failure) return it }
            audit(adminId, "del", id.toString(), null)
            ok("Nachricht $id gelöscht.")
        }
        "rename" -> {
            val acc = target(0) ?: return missing()
            val newName = cmd.args.getOrNull(1) ?: return missing()
            Validation.username(newName)?.let { return fail(ErrorCode.VALIDATION, it) }
            val other = accountByName(newName)
            if (other != null && other.id != acc.id) return fail(ErrorCode.NAME_TAKEN, "Der Name ist bereits vergeben.")
            AccountT.update({ AccountT.id eq acc.id }) { it[username] = newName }
            changedObjects += listOfNotNull(baseOf(acc.id)?.id)
            dirty(acc.id)
            audit(adminId, "rename", acc.username, newName)
            ok("${acc.username} heißt jetzt $newName.")
        }
        "password" -> {
            val acc = target(0) ?: return missing()
            val hash = passwordHash ?: return missing()
            AccountT.update({ AccountT.id eq acc.id }) { it[pwHash] = hash }
            SessionT.deleteWhere { SessionT.accountId eq acc.id }
            endSessions(acc.id, "Passwort geändert")
            audit(adminId, "password", acc.username, null)
            ok("Neues Passwort für ${acc.username} gesetzt, alle Sitzungen beendet.")
        }
        "shield" -> {
            val acc = target(0) ?: return missing()
            val dur = cmd.args.getOrNull(1)?.let { parseDuration(it, now) } ?: return missing()
            settle(acc.id)
            activateShield(acc.id, dur, "Schutzschild")
            audit(adminId, "shield", acc.username, cmd.args.getOrNull(1))
            ok("${acc.username} hat einen Schild bis ${formatTime(player(acc.id).shieldUntil)}.")
        }
        "announce" -> {
            val text = cmd.raw.removePrefix("/").trim().removePrefix(cmd.name).trim()
            if (text.isBlank()) return missing()
            allPlayerIds().forEach { addReport(it, SystemReport("Durchsage", text), "Durchsage: ${text.take(40)}") }
            broadcast(WsEvent.Notice(text))
            systemChat(text)
            audit(adminId, "announce", null, text)
            ok("Durchsage gesendet.")
        }
        "reports" -> {
            val open = ChatReportT.selectAll().where { ChatReportT.resolved eq false }.map { r ->
                val msg = ChatMessageT.selectAll().where { ChatMessageT.id eq r[ChatReportT.messageId] }.firstOrNull()
                val sender = msg?.get(ChatMessageT.senderId)?.let { accountName(it) } ?: "?"
                "#${r[ChatReportT.id]} Nachricht ${r[ChatReportT.messageId]} von $sender (gemeldet von ${accountName(r[ChatReportT.reporterId])}): " +
                    (msg?.get(ChatMessageT.text)?.take(60) ?: "–")
            }
            ok(if (open.isEmpty()) "Keine offenen Meldungen." else open.joinToString("\n"))
        }
        "resolve" -> {
            val id = cmd.args.getOrNull(0)?.toLongOrNull() ?: return missing()
            val n = ChatReportT.update({ ChatReportT.id eq id }) { it[resolved] = true }
            if (n == 0) return fail(ErrorCode.NOT_FOUND, "Meldung $id nicht gefunden.")
            audit(adminId, "resolve", id.toString(), null)
            ok("Meldung $id erledigt.")
        }
        "give", "res", "finish" -> {
            if (config.devMode.not()) return fail(ErrorCode.FORBIDDEN, "Nur im Entwicklungsmodus verfügbar.")
            val acc = target(0) ?: return missing()
            settle(acc.id)
            val msg = when (cmd.name) {
                "give" -> {
                    val item = cmd.args.getOrNull(1)?.let { runCatching { ItemId.valueOf(it.uppercase()) }.getOrNull() } ?: return missing()
                    val n = cmd.args.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: return missing()
                    addItem(acc.id, item, n)
                    "$n × $item an ${acc.username}."
                }
                "res" -> {
                    val v = cmd.args.drop(1).mapNotNull { it.toLongOrNull() }
                    if (v.size != 3 || v.any { it < 0 }) return missing()
                    credit(acc.id, Cost(v[0], v[1], v[2]))
                    "Ressourcen an ${acc.username} gutgeschrieben."
                }
                else -> {
                    timers(acc.id).forEach { setTimerEnd(it.id, now) }
                    resettle(acc.id)
                    "Alle Timer von ${acc.username} sind fertig."
                }
            }
            dirty(acc.id)
            audit(adminId, cmd.name, acc.username, cmd.args.drop(1).joinToString(" "))
            ok(msg)
        }
        "help" -> ok(ADMIN_HELP)
        else -> fail(ErrorCode.VALIDATION, "Unbekannter Befehl. $ADMIN_HELP")
    }
}

