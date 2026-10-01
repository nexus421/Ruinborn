package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.AccountT
import bayern.kickner.ruinborn.server.db.AllianceGiftT
import bayern.kickner.ruinborn.server.db.AllianceMemberT
import bayern.kickner.ruinborn.server.db.AllianceRequestT
import bayern.kickner.ruinborn.server.db.AllianceT
import bayern.kickner.ruinborn.server.db.ChatMessageT
import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.server.db.ReportT
import bayern.kickner.ruinborn.shared.ApiJson
import bayern.kickner.ruinborn.shared.dto.AllianceDetailDto
import bayern.kickner.ruinborn.shared.dto.AllianceMemberDto
import bayern.kickner.ruinborn.shared.dto.AllianceRequestDto
import bayern.kickner.ruinborn.shared.dto.AllianceSummaryDto
import bayern.kickner.ruinborn.shared.dto.ChatMessageDto
import bayern.kickner.ruinborn.shared.dto.GiftDto
import bayern.kickner.ruinborn.shared.dto.HelpRequestDto
import bayern.kickner.ruinborn.shared.dto.PlayerProfileDto
import bayern.kickner.ruinborn.shared.dto.Pos
import bayern.kickner.ruinborn.shared.dto.RankingDto
import bayern.kickner.ruinborn.shared.dto.RankingEntryDto
import bayern.kickner.ruinborn.shared.dto.ReportDto
import bayern.kickner.ruinborn.shared.model.AllianceRank
import bayern.kickner.ruinborn.shared.model.ErrorCode
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

// Read queries. They use the read-only connection (except where they write, see comment).

fun Ctx.chatHistory(pid: Long, apiChannel: String, before: Long?, limit: Int): Res<List<ChatMessageDto>> {
    val channel = channelKey(pid, apiChannel).orReturn { return it }
    val n = limit.coerceIn(1, balance.chat.pageSize)
    val (names, frames) = chatDirectory()
    val rows = ChatMessageT.selectAll()
        .where { if (before != null) (ChatMessageT.channel eq channel) and (ChatMessageT.id less before) else ChatMessageT.channel eq channel }
        .orderBy(ChatMessageT.id, SortOrder.DESC).limit(n).toList()
    return ok(rows.map { chatDto(it, names, frames) }.reversed())
}

private fun storedReport(text: String): StoredReport = ApiJson.decodeFromString(StoredReport.serializer(), text)

fun reportList(pid: Long, before: Long?, limit: Int): List<ReportDto> =
    ReportT.selectAll()
        .where { if (before != null) (ReportT.playerId eq pid) and (ReportT.id less before) else ReportT.playerId eq pid }
        .orderBy(ReportT.id, SortOrder.DESC).limit(limit).map {
            ReportDto(it[ReportT.id], it[ReportT.kind], it[ReportT.createdAt], it[ReportT.read], storedReport(it[ReportT.payload]).title)
        }

/** Single report. Marks it as read (writes, therefore goes through the engine). */
fun Ctx.openReport(pid: Long, id: Long): Res<ReportDto> {
    val row = ReportT.selectAll().where { (ReportT.id eq id) and (ReportT.playerId eq pid) }.firstOrNull()
        ?: return fail(ErrorCode.NOT_FOUND, "Bericht nicht gefunden.")
    if (row[ReportT.read].not()) {
        ReportT.update({ ReportT.id eq id }) { it[read] = true }
        dirty(pid)
    }
    val s = storedReport(row[ReportT.payload])
    return ok(ReportDto(id, row[ReportT.kind], row[ReportT.createdAt], true, s.title, s.data))
}

fun Ctx.deleteReport(pid: Long, id: Long): Res<Unit> {
    val n = ReportT.deleteWhere { (ReportT.id eq id) and (playerId eq pid) }
    ensure(n > 0, ErrorCode.NOT_FOUND) { "Bericht nicht gefunden." }?.let { return it }
    dirty(pid)
    return OK
}

/** Marks all reports as read (convenience for the client). */
fun Ctx.markAllReportsRead(pid: Long): Res<Unit> {
    ReportT.update({ (ReportT.playerId eq pid) and (ReportT.read eq false) }) { it[read] = true }
    dirty(pid)
    return OK
}

// ---------------------------------------------------------------- Alliances

private fun Ctx.allianceSummary(row: org.jetbrains.exposed.v1.core.ResultRow, viewer: Long?): AllianceSummaryDto {
    val id = row[AllianceT.id]
    val members = allianceMembers(id)
    val requested = viewer != null && AllianceRequestT.selectAll().where { (AllianceRequestT.allianceId eq id) and (AllianceRequestT.playerId eq viewer) }.empty().not()
    return AllianceSummaryDto(
        id, row[AllianceT.name], row[AllianceT.tag], members.size, members.sumOf { powerOf(it.playerId) }, row[AllianceT.joinMode],
        accountName(row[AllianceT.leaderId]), requested,
    )
}

fun Ctx.searchAlliances(viewer: Long, query: String?): List<AllianceSummaryDto> {
    val q = query?.trim()?.lowercase().orEmpty()
    return AllianceT.selectAll().filter { q.isEmpty() || q in it[AllianceT.name].lowercase() || q in it[AllianceT.tag].lowercase() }
        .map { allianceSummary(it, viewer) }.sortedByDescending { it.power }
}

fun Ctx.allianceDetail(viewer: Long, id: Long): Res<AllianceDetailDto> {
    val row = AllianceT.selectAll().where { AllianceT.id eq id }.firstOrNull() ?: return fail(ErrorCode.NOT_FOUND, "Allianz nicht gefunden.")
    val members = allianceMembers(id).map { m ->
        val p = playerOrNull(m.playerId)
        AllianceMemberDto(m.playerId, accountName(m.playerId), m.rank, powerOf(m.playerId), hqOf(m.playerId), p?.lastActiveAt ?: 0, p?.frame ?: balance.cosmetics.defaultFrame)
    }.sortedWith(compareBy<AllianceMemberDto> { it.rank.ordinal }.thenByDescending { it.power })
    return ok(AllianceDetailDto(allianceSummary(row, viewer), row[AllianceT.description], row[AllianceT.createdAt], members))
}

fun Ctx.allianceRequests(pid: Long): Res<List<AllianceRequestDto>> {
    val m = membership(pid) ?: return fail(ErrorCode.NOT_IN_ALLIANCE, "Du bist in keiner Allianz.")
    ensure(m.rank != AllianceRank.MEMBER, ErrorCode.FORBIDDEN) { "Nur Anführer und Offiziere." }?.let { return it }
    val limit = now - rules.daysMs(balance.alliance.requestExpiryDays)
    return ok(AllianceRequestT.selectAll().where { (AllianceRequestT.allianceId eq m.allianceId) and (AllianceRequestT.createdAt greater limit) }.map {
        val p = it[AllianceRequestT.playerId]
        AllianceRequestDto(p, accountName(p), hqOf(p), powerOf(p), it[AllianceRequestT.createdAt])
    })
}

fun Ctx.helpRequests(pid: Long): Res<List<HelpRequestDto>> {
    membership(pid) ?: return fail(ErrorCode.NOT_IN_ALLIANCE, "Du bist in keiner Allianz.")
    return ok(helpList(pid).map { HelpRequestDto(it.id, it.playerId, accountName(it.playerId), it.kind, it.target, it.helpCount, it.helpMax, it.endsAt) })
}

fun Ctx.giftList(pid: Long): List<GiftDto> =
    AllianceGiftT.selectAll().where { (AllianceGiftT.playerId eq pid) and (AllianceGiftT.claimed eq false) and (AllianceGiftT.expiresAt greater now) }
        .map { GiftDto(it[AllianceGiftT.id], it[AllianceGiftT.level], it[AllianceGiftT.createdAt], it[AllianceGiftT.expiresAt]) }

// ---------------------------------------------------------------- Rankings and profile

fun Ctx.ranking(viewer: Long, kind: String): Res<RankingDto> {
    val topN = balance.rankings.topN
    val tags = AllianceT.selectAll().associate { it[AllianceT.id] to it[AllianceT.tag] }
    val memberOf = AllianceMemberT.selectAll().associate { it[AllianceMemberT.playerId] to it[AllianceMemberT.allianceId] }
    val players = PlayerT.selectAll().associate { it[PlayerT.id] to it }
    val names = AccountT.selectAll().associate { it[AccountT.id] to it[AccountT.username] }
    data class E(val id: Long, val name: String, val value: Long, val tag: String?, val frame: bayern.kickner.ruinborn.shared.model.Cosmetic?)
    val entries: List<E> = when (kind) {
        "player-power" -> players.keys.map { E(it, names[it] ?: "?", powerOf(it), memberOf[it]?.let(tags::get), players[it]!![PlayerT.frame]) }
        "zombies" -> players.keys.map { E(it, names[it] ?: "?", players[it]!![PlayerT.zombiesDefeated], memberOf[it]?.let(tags::get), players[it]!![PlayerT.frame]) }
        "alliance-power" -> AllianceT.selectAll().map { a ->
            val id = a[AllianceT.id]
            E(id, a[AllianceT.name], allianceMembers(id).sumOf { powerOf(it.playerId) }, a[AllianceT.tag], null)
        }
        else -> return fail(ErrorCode.NOT_FOUND, "Unbekannte Rangliste.")
    }
    val sorted = entries.sortedWith(compareByDescending<E> { it.value }.thenBy { it.name.lowercase() })
    val ranked = sorted.mapIndexed { i, e -> RankingEntryDto(i + 1, e.id, e.name, e.value, e.tag, e.frame) }
    val ownId = if (kind == "alliance-power") allianceIdOf(viewer) else viewer
    return ok(RankingDto(kind, ranked.take(topN), ranked.firstOrNull { it.id == ownId }))
}

fun Ctx.profile(id: Long): Res<PlayerProfileDto> {
    val p = playerOrNull(id) ?: return fail(ErrorCode.NOT_FOUND, "Spieler nicht gefunden.")
    val acc = accountById(id)!!
    val aid = allianceIdOf(id)
    val a = aid?.let { AllianceT.selectAll().where { AllianceT.id eq it }.firstOrNull() }
    val base = baseOf(id)
    return ok(
        PlayerProfileDto(
            id, acc.username, aid, a?.get(AllianceT.tag), a?.get(AllianceT.name), powerOf(id), hqOf(id), p.skin, p.frame,
            p.zombiesDefeated, base?.let { Pos(it.x, it.y) }, acc.createdAt,
        ),
    )
}

fun Ctx.equipCosmetics(pid: Long, skin: bayern.kickner.ruinborn.shared.model.Cosmetic?, frame: bayern.kickner.ruinborn.shared.model.Cosmetic?): Res<Unit> {
    val unlocked = unlockedCosmetics(pid)
    if (skin != null) {
        ensure(skin.isSkin && skin in unlocked, ErrorCode.VALIDATION) { "Dieser Basis-Skin ist nicht freigeschaltet." }?.let { return it }
        PlayerT.update({ PlayerT.id eq pid }) { it[PlayerT.skin] = skin }
        changedObjects += listOfNotNull(baseOf(pid)?.id)
    }
    if (frame != null) {
        ensure(frame.isSkin.not() && frame in unlocked, ErrorCode.VALIDATION) { "Dieser Profilrahmen ist nicht freigeschaltet." }?.let { return it }
        PlayerT.update({ PlayerT.id eq pid }) { it[PlayerT.frame] = frame }
    }
    dirty(pid)
    return OK
}
