package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.AccountT
import bayern.kickner.ruinborn.server.db.ChatMessageT
import bayern.kickner.ruinborn.server.db.ChatReportT
import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.shared.dto.ChatMessageDto
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.model.Cosmetic
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.rules.Validation
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

const val DELETED_TEXT = "Nachricht entfernt"

/** Maps the API channel (`world` or `alliance`) to the stored channel (`world` or `alliance:<id>`). */
fun channelKey(pid: Long, apiChannel: String): Res<String> = when (apiChannel) {
    "world" -> ok("world")
    "alliance" -> allianceIdOf(pid)?.let { ok("alliance:$it") } ?: fail(ErrorCode.NOT_IN_ALLIANCE, "Du bist in keiner Allianz.")
    else -> fail(ErrorCode.NOT_FOUND, "Unbekannter Kanal.")
}

fun chatDto(row: ResultRow, names: Map<Long, String>, frames: Map<Long, Cosmetic>): ChatMessageDto {
    val sender = row[ChatMessageT.senderId]
    val deleted = row[ChatMessageT.deleted]
    return ChatMessageDto(
        id = row[ChatMessageT.id], channel = row[ChatMessageT.channel].substringBefore(':'), senderId = sender,
        senderName = sender?.let { names[it] } ?: "System", frame = sender?.let { frames[it] } ?: Cosmetic.FRAME_DEFAULT,
        text = if (deleted) DELETED_TEXT else row[ChatMessageT.text], createdAt = row[ChatMessageT.createdAt], deleted = deleted,
        system = sender == null,
    )
}

fun chatDirectory(): Pair<Map<Long, String>, Map<Long, Cosmetic>> =
    AccountT.selectAll().associate { it[AccountT.id] to it[AccountT.username] } to PlayerT.selectAll().associate { it[PlayerT.id] to it[PlayerT.frame] }

/** Delivers a message to all recipients of its channel. */
fun Ctx.deliverChat(channel: String, dto: ChatMessageDto) {
    if (channel == "world") broadcast(WsEvent.Chat(dto))
    else channel.substringAfter(':').toLongOrNull()?.let { aid -> allianceMembers(aid).forEach { notify(it.playerId, WsEvent.Chat(dto)) } }
}

fun Ctx.postChat(pid: Long, apiChannel: String, text: String): Res<ChatMessageDto> {
    val acc = accountById(pid)!!
    ensure(acc.mutedUntil <= now, ErrorCode.MUTED) { "Du darfst bis ${formatTime(acc.mutedUntil)} nicht schreiben." }?.let { return it }
    Validation.chatText(balance, text)?.let { return fail(ErrorCode.VALIDATION, it) }
    val last = game.lastChatAt[pid] ?: 0L
    ensure(now - last >= balance.chat.minIntervalMs, ErrorCode.RATE_LIMITED) { "Bitte kurz warten." }?.let { return it }
    val channel = channelKey(pid, apiChannel).orReturn { return it }
    val id = ChatMessageT.insert {
        it[ChatMessageT.channel] = channel
        it[senderId] = pid
        it[ChatMessageT.text] = text.trim()
        it[createdAt] = now
        it[deleted] = false
    }[ChatMessageT.id]
    game.lastChatAt[pid] = now
    val (names, frames) = chatDirectory()
    val dto = chatDto(ChatMessageT.selectAll().where { ChatMessageT.id eq id }.first(), names, frames)
    deliverChat(channel, dto)
    return ok(dto)
}

/** System message in world chat (e.g. admin announcement). */
fun Ctx.systemChat(text: String) {
    val id = ChatMessageT.insert {
        it[channel] = "world"
        it[senderId] = null
        it[ChatMessageT.text] = text.take(balance.chat.maxLength)
        it[createdAt] = now
        it[deleted] = false
    }[ChatMessageT.id]
    val (names, frames) = chatDirectory()
    deliverChat("world", chatDto(ChatMessageT.selectAll().where { ChatMessageT.id eq id }.first(), names, frames))
}

fun Ctx.reportChat(pid: Long, messageId: Long): Res<Unit> {
    ensure(ChatMessageT.selectAll().where { ChatMessageT.id eq messageId }.empty().not(), ErrorCode.NOT_FOUND) { "Nachricht nicht gefunden." }?.let { return it }
    ChatReportT.insert {
        it[ChatReportT.messageId] = messageId
        it[reporterId] = pid
        it[createdAt] = now
        it[resolved] = false
    }
    return OK
}

fun Ctx.deleteChat(messageId: Long): Res<Unit> {
    val row = ChatMessageT.selectAll().where { ChatMessageT.id eq messageId }.firstOrNull() ?: return fail(ErrorCode.NOT_FOUND, "Nachricht nicht gefunden.")
    ChatMessageT.update({ ChatMessageT.id eq messageId }) { it[deleted] = true }
    val (names, frames) = chatDirectory()
    deliverChat(row[ChatMessageT.channel], chatDto(ChatMessageT.selectAll().where { ChatMessageT.id eq messageId }.first(), names, frames))
    return OK
}
