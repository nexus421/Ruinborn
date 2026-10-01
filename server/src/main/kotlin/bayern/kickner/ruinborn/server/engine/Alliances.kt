package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.AllianceGiftT
import bayern.kickner.ruinborn.server.db.AllianceMemberT
import bayern.kickner.ruinborn.server.db.AllianceRequestT
import bayern.kickner.ruinborn.server.db.AllianceT
import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.server.db.RallyT
import bayern.kickner.ruinborn.server.db.TimerHelpT
import bayern.kickner.ruinborn.server.db.TimerT
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.dto.AllianceCreateRequest
import bayern.kickner.ruinborn.shared.dto.AllianceRef
import bayern.kickner.ruinborn.shared.dto.AllianceSettingsRequest
import bayern.kickner.ruinborn.shared.dto.SystemReport
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.model.AchievementKind
import bayern.kickner.ruinborn.shared.model.AllianceRank
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.model.JoinMode
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.model.RallyState
import bayern.kickner.ruinborn.shared.model.TimerKind
import bayern.kickner.ruinborn.shared.rules.Validation
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

fun Ctx.allianceRef(pid: Long): AllianceRef? {
    val m = membership(pid) ?: return null
    val a = AllianceT.selectAll().where { AllianceT.id eq m.allianceId }.firstOrNull() ?: return null
    return AllianceRef(m.allianceId, a[AllianceT.name], a[AllianceT.tag], m.rank)
}

/** All members receive `alliance_changed`. */
fun Ctx.notifyAlliance(allianceId: Long?) {
    allianceId ?: return
    allianceMembers(allianceId).forEach { notify(it.playerId, WsEvent.AllianceChanged); dirty(it.playerId) }
}

private fun Ctx.addMember(pid: Long, allianceId: Long, rank: AllianceRank) {
    AllianceMemberT.insert {
        it[playerId] = pid
        it[AllianceMemberT.allianceId] = allianceId
        it[AllianceMemberT.rank] = rank
        it[joinedAt] = now
    }
    AllianceRequestT.deleteWhere { AllianceRequestT.playerId eq pid }
    achievementEvent(pid, AchievementKind.ALLIANCE_JOINED)
    changedObjects += listOfNotNull(baseOf(pid)?.id)
    notifyAlliance(allianceId)
}

private fun Ctx.checkCanJoin(pid: Long): ResultFailure? {
    ensure(membership(pid) == null, ErrorCode.ALREADY_IN_ALLIANCE) { "Du bist bereits in einer Allianz." }?.let { return it }
    val block = player(pid).allianceBlockUntil
    ensure(block <= now, ErrorCode.ALLIANCE_BLOCKED) { "Beitrittssperre bis ${formatTime(block)}." }?.let { return it }
    return null
}

typealias ResultFailure = kotnexlib.ResultOf2.Failure<GameError>

fun Ctx.createAlliance(pid: Long, req: AllianceCreateRequest): Res<Long> {
    settle(pid)
    checkCanJoin(pid)?.let { return it }
    val need = balance.alliance.foundMinHq
    ensure(hqOf(pid) >= need, ErrorCode.REQUIREMENT_NOT_MET) { "Gründen ist ab HQ $need möglich." }?.let { return it }
    Validation.allianceName(balance, req.name)?.let { return fail(ErrorCode.VALIDATION, it) }
    Validation.allianceTag(balance, req.tag)?.let { return fail(ErrorCode.VALIDATION, it) }
    Validation.allianceDescription(balance, req.description)?.let { return fail(ErrorCode.VALIDATION, it) }
    ensure(AllianceT.selectAll().where { AllianceT.name eq req.name }.empty(), ErrorCode.NAME_TAKEN) { "Der Allianzname ist vergeben." }?.let { return it }
    ensure(AllianceT.selectAll().where { AllianceT.tag eq req.tag }.empty(), ErrorCode.NAME_TAKEN) { "Das Kürzel ist vergeben." }?.let { return it }
    val id = AllianceT.insert {
        it[name] = req.name
        it[tag] = req.tag
        it[leaderId] = pid
        it[joinMode] = req.joinMode
        it[description] = req.description
        it[createdAt] = now
    }[AllianceT.id]
    addMember(pid, id, AllianceRank.LEADER)
    return ok(id)
}

/** Join (open) or request to join (on request). */
fun Ctx.joinAlliance(pid: Long, allianceId: Long): Res<Unit> {
    settle(pid)
    checkCanJoin(pid)?.let { return it }
    val a = AllianceT.selectAll().where { AllianceT.id eq allianceId }.firstOrNull() ?: return fail(ErrorCode.NOT_FOUND, "Allianz nicht gefunden.")
    ensure(hqOf(pid) >= balance.alliance.joinMinHq, ErrorCode.REQUIREMENT_NOT_MET) { "Beitritt ab HQ ${balance.alliance.joinMinHq}." }?.let { return it }
    ensure(allianceMembers(allianceId).size < balance.alliance.maxMembers, ErrorCode.ALLIANCE_FULL) { "Die Allianz ist voll." }?.let { return it }
    if (a[AllianceT.joinMode] == JoinMode.OPEN) addMember(pid, allianceId, AllianceRank.MEMBER)
    else {
        AllianceRequestT.insertIgnore {
            it[AllianceRequestT.allianceId] = allianceId
            it[playerId] = pid
            it[createdAt] = now
        }
        notifyAlliance(allianceId)
    }
    dirty(pid)
    return OK
}

private fun Ctx.requireRank(pid: Long, vararg ranks: AllianceRank): Res<MembershipRow> {
    val m = membership(pid) ?: return fail(ErrorCode.NOT_IN_ALLIANCE, "Du bist in keiner Allianz.")
    ensure(m.rank in ranks, ErrorCode.FORBIDDEN) { "Dafür fehlt dir das Recht." }?.let { return it }
    return ok(m)
}

fun Ctx.allianceSettings(pid: Long, req: AllianceSettingsRequest): Res<Unit> {
    val m = requireRank(pid, AllianceRank.LEADER, AllianceRank.OFFICER).orReturn { return it }
    Validation.allianceDescription(balance, req.description)?.let { return fail(ErrorCode.VALIDATION, it) }
    AllianceT.update({ AllianceT.id eq m.allianceId }) {
        it[joinMode] = req.joinMode
        it[description] = req.description
    }
    notifyAlliance(m.allianceId)
    return OK
}

fun Ctx.answerRequest(pid: Long, applicant: Long, accept: Boolean): Res<Unit> {
    val m = requireRank(pid, AllianceRank.LEADER, AllianceRank.OFFICER).orReturn { return it }
    val exists = AllianceRequestT.selectAll().where { (AllianceRequestT.allianceId eq m.allianceId) and (AllianceRequestT.playerId eq applicant) }.empty().not()
    ensure(exists, ErrorCode.NOT_FOUND) { "Keine offene Anfrage." }?.let { return it }
    AllianceRequestT.deleteWhere { (allianceId eq m.allianceId) and (playerId eq applicant) }
    if (accept) {
        checkCanJoin(applicant)?.let { return it }
        ensure(allianceMembers(m.allianceId).size < balance.alliance.maxMembers, ErrorCode.ALLIANCE_FULL) { "Die Allianz ist voll." }?.let { return it }
        addMember(applicant, m.allianceId, AllianceRank.MEMBER)
    } else notifyAlliance(m.allianceId)
    dirty(applicant)
    return OK
}

/** Consequences of leaving (concept section 9): reinforcements and waiting rally marches return home, gifts expire. */
private fun Ctx.leaveEffects(pid: Long, allianceId: Long) {
    val base = baseOf(pid)
    allMarches().forEach { mr ->
        val own = mr.playerId == pid
        when {
            own && mr.kind == MarchKind.REINFORCE && (mr.state == MarchState.OUTBOUND || mr.state == MarchState.STATIONED) -> {
                val (x, y) = mr.positionAt(now)
                startReturn(mr.id, x, y, if (mr.state == MarchState.OUTBOUND) now - mr.departAt else null)
            }
            mr.kind == MarchKind.REINFORCE && mr.hostId == pid && mr.playerId != pid ->
                startReturn(mr.id, (base?.x ?: mr.toX).toDouble(), (base?.y ?: mr.toY).toDouble(), if (mr.state == MarchState.OUTBOUND) now - mr.departAt else null)
            own && mr.kind == MarchKind.RALLY && mr.rallyId != null && (mr.state == MarchState.WAITING || (mr.state == MarchState.OUTBOUND && mr.hostId != null)) -> {
                val rally = rallyRow(mr.rallyId)
                if (rally != null && rally.leaderId == pid) cancelRally(pid, rally.id)
                else if (mr.state == MarchState.WAITING) startReturn(mr.id, mr.toX.toDouble(), mr.toY.toDouble())
                else startReturn(mr.id, mr.positionAt(now).first, mr.positionAt(now).second, now - mr.departAt)
            }
        }
    }
    AllianceGiftT.deleteWhere { (playerId eq pid) and (claimed eq false) }
    AllianceMemberT.deleteWhere { playerId eq pid }
    PlayerT.update({ PlayerT.id eq pid }) { it[allianceBlockUntil] = now + rules.hoursMs(balance.alliance.joinBlockHours) }
    changedObjects += listOfNotNull(base?.id)
    notify(pid, WsEvent.AllianceChanged)
    dirty(pid)
    notifyAlliance(allianceId)
}

fun Ctx.leaveAlliance(pid: Long): Res<Unit> {
    val m = membership(pid) ?: return fail(ErrorCode.NOT_IN_ALLIANCE, "Du bist in keiner Allianz.")
    val members = allianceMembers(m.allianceId)
    if (m.rank == AllianceRank.LEADER && members.size > 1) return fail(ErrorCode.VALIDATION, "Übergib zuerst die Anführung.")
    leaveEffects(pid, m.allianceId)
    if (members.size == 1) deleteAlliance(m.allianceId)
    return OK
}

fun Ctx.disbandAlliance(pid: Long): Res<Unit> {
    val m = requireRank(pid, AllianceRank.LEADER).orReturn { return it }
    ensure(allianceMembers(m.allianceId).size == 1, ErrorCode.VALIDATION) { "Auflösen geht nur als letztes Mitglied." }?.let { return it }
    return leaveAlliance(pid)
}

private fun Ctx.deleteAlliance(allianceId: Long) {
    RallyT.selectAll().where { (RallyT.allianceId eq allianceId) and (RallyT.state eq RallyState.WAITING) }.forEach { r ->
        cancelRally(r[RallyT.leaderId], r[RallyT.id])
    }
    AllianceRequestT.deleteWhere { AllianceRequestT.allianceId eq allianceId }
    AllianceT.deleteWhere { AllianceT.id eq allianceId }
}

/** Manage a member: kick, promote, demote, make-leader. */
fun Ctx.manageMember(pid: Long, target: Long, action: String): Res<Unit> {
    val me = membership(pid) ?: return fail(ErrorCode.NOT_IN_ALLIANCE, "Du bist in keiner Allianz.")
    val other = membership(target)
    ensure(other != null && other.allianceId == me.allianceId, ErrorCode.NOT_FOUND) { "Kein Mitglied deiner Allianz." }?.let { return it }
    ensure(target != pid, ErrorCode.VALIDATION) { "Das geht nicht mit dir selbst." }?.let { return it }
    other!!
    when (action) {
        "kick" -> {
            val allowed = me.rank == AllianceRank.LEADER || (me.rank == AllianceRank.OFFICER && other.rank == AllianceRank.MEMBER)
            ensure(allowed, ErrorCode.FORBIDDEN) { "Dafür fehlt dir das Recht." }?.let { return it }
            leaveEffects(target, me.allianceId)
            addReport(target, SystemReport("Allianz", "Du wurdest aus der Allianz entfernt."), "Aus der Allianz entfernt")
        }
        "promote", "demote", "make-leader" -> {
            ensure(me.rank == AllianceRank.LEADER, ErrorCode.FORBIDDEN) { "Nur der Anführer darf das." }?.let { return it }
            when (action) {
                "promote" -> setRank(target, AllianceRank.OFFICER)
                "demote" -> setRank(target, AllianceRank.MEMBER)
                else -> {
                    setRank(target, AllianceRank.LEADER)
                    setRank(pid, AllianceRank.OFFICER)
                    AllianceT.update({ AllianceT.id eq me.allianceId }) { it[leaderId] = target }
                }
            }
            notifyAlliance(me.allianceId)
        }
        else -> return fail(ErrorCode.NOT_FOUND, "Unbekannte Aktion.")
    }
    return OK
}

private fun setRank(pid: Long, rank: AllianceRank) {
    AllianceMemberT.update({ AllianceMemberT.playerId eq pid }) { it[AllianceMemberT.rank] = rank }
}

/** Daily reset: after 7 days of inactivity, leadership passes to the officer (otherwise the member) with the most recent login. */
fun Ctx.transferInactiveLeaders() {
    val limit = now - rules.daysMs(balance.alliance.leaderInactiveDays)
    AllianceT.selectAll().forEach { a ->
        val aid = a[AllianceT.id]
        val leader = a[AllianceT.leaderId]
        if ((playerOrNull(leader)?.lastActiveAt ?: 0) >= limit) return@forEach
        val others = allianceMembers(aid).filter { it.playerId != leader }
        val best = (others.filter { it.rank == AllianceRank.OFFICER }.ifEmpty { others })
            .maxByOrNull { playerOrNull(it.playerId)?.lastActiveAt ?: 0 } ?: return@forEach
        setRank(best.playerId, AllianceRank.LEADER)
        setRank(leader, AllianceRank.OFFICER)
        AllianceT.update({ AllianceT.id eq aid }) { it[leaderId] = best.playerId }
        notifyAlliance(aid)
    }
}

// ---------------------------------------------------------------- Alliance help

/** Request help: once per timer. The maximum count is fixed when requesting. */
fun Ctx.requestHelp(pid: Long, timerId: Long): Res<Unit> {
    val t = ownTimer(pid, timerId).orReturn { return it }
    ensure(t.kind != TimerKind.TRAIN, ErrorCode.VALIDATION) { "Für Ausbildung gibt es keine Allianzhilfe." }?.let { return it }
    val m = membership(pid) ?: return fail(ErrorCode.NOT_IN_ALLIANCE, "Du bist in keiner Allianz.")
    ensure(t.helpMax == 0, ErrorCode.VALIDATION) { "Hilfe wurde bereits angefordert." }?.let { return it }
    val max = rules.helpMax(buildings(pid).levelOf(BuildingType.ALLIANCE_CENTER))
    TimerT.update({ TimerT.id eq t.id }) { it[helpMax] = max }
    notifyAlliance(m.allianceId)
    return OK
}

private fun Ctx.helpableTimers(pid: Long, settleOwners: Boolean): List<TimerRow> {
    val m = membership(pid) ?: return emptyList()
    val helped = TimerHelpT.selectAll().where { TimerHelpT.helperId eq pid }.map { it[TimerHelpT.timerId] }.toSet()
    return allianceMembers(m.allianceId).filter { it.playerId != pid }.flatMap { member ->
        if (settleOwners) settle(member.playerId)
        timers(member.playerId).filter { it.helpMax > 0 && it.helpCount < it.helpMax && it.id !in helped && it.endsAt > now }
    }
}

fun Ctx.openHelpCount(pid: Long): Int = helpableTimers(pid, false).size

fun Ctx.helpList(pid: Long): List<TimerRow> = helpableTimers(pid, true)

/** "Help all": each help reduces the remaining time by max(60 s ÷ g, 1 % of the total duration). */
fun Ctx.helpAll(pid: Long): Res<Int> {
    val m = membership(pid) ?: return fail(ErrorCode.NOT_IN_ALLIANCE, "Du bist in keiner Allianz.")
    val list = helpableTimers(pid, true)
    list.forEach { t ->
        TimerHelpT.insert {
            it[timerId] = t.id
            it[helperId] = pid
        }
        TimerT.update({ TimerT.id eq t.id }) { it[helpCount] = t.helpCount + 1 }
        reduceTimer(timerById(t.id)!!, rules.helpReductionMs(t.totalMs))
    }
    if (list.isNotEmpty()) {
        addDaily(pid, DailyTask.COMRADE, list.size.toLong())
        notifyAlliance(m.allianceId)
    }
    dirty(pid)
    return ok(list.size)
}

// ---------------------------------------------------------------- Alliance gifts

fun Ctx.giveAllianceGifts(allianceId: Long, level: Int) {
    val expires = now + rules.daysMs(balance.gifts.expiryDays)
    allianceMembers(allianceId).forEach { m ->
        AllianceGiftT.insert {
            it[playerId] = m.playerId
            it[AllianceGiftT.level] = level
            it[createdAt] = now
            it[expiresAt] = expires
            it[claimed] = false
        }
        dirty(m.playerId)
    }
    notifyAlliance(allianceId)
}

fun Ctx.openGiftCount(pid: Long): Int =
    AllianceGiftT.selectAll().where { (AllianceGiftT.playerId eq pid) and (AllianceGiftT.claimed eq false) and (AllianceGiftT.expiresAt greater now) }.count().toInt()

fun Ctx.claimGift(pid: Long, giftId: Long): Res<Unit> {
    settle(pid)
    val g = AllianceGiftT.selectAll().where { (AllianceGiftT.id eq giftId) and (AllianceGiftT.playerId eq pid) }.firstOrNull()
    ensure(g != null && g[AllianceGiftT.claimed].not() && g[AllianceGiftT.expiresAt] > now, ErrorCode.NOT_FOUND) { "Kein offenes Geschenk." }?.let { return it }
    val level = g!![AllianceGiftT.level]
    AllianceGiftT.update({ AllianceGiftT.id eq giftId }) { it[claimed] = true }
    val amount = balance.gifts.foodWoodPerLevel * level
    credit(pid, Cost(food = amount, wood = amount))
    addItem(pid, balance.gifts.item, balance.gifts.itemCount)
    dirty(pid)
    return OK
}
