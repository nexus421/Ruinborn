package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.dto.BuildRequest
import bayern.kickner.ruinborn.shared.dto.HealRequest
import bayern.kickner.ruinborn.shared.dto.ItemUseRequest
import bayern.kickner.ruinborn.shared.dto.SystemReport
import bayern.kickner.ruinborn.shared.dto.TrainRequest
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.Tech
import bayern.kickner.ruinborn.shared.model.TimerKind
import bayern.kickner.ruinborn.shared.model.normalized
import bayern.kickner.ruinborn.shared.rules.floorSafe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update

/** Settles a player again within the same command (e.g. after a timer finished instantly). */
fun Ctx.resettle(pid: Long) {
    settled -= pid
    settle(pid)
}

private fun Ctx.pay(pid: Long, cost: Cost): Res<Unit> {
    val stock = player(pid).stock
    ensure(stock.canAfford(cost), ErrorCode.NOT_ENOUGH_RESOURCES) { "Nicht genug Ressourcen." }?.let { return it }
    saveStock(pid, stock - cost)
    return OK
}

/** Credit without capacity limit (loot, rewards, chests, refunds). */
fun Ctx.credit(pid: Long, gain: Cost) {
    if (gain.isZero()) return
    settle(pid)
    saveStock(pid, player(pid).stock + gain)
    dirty(pid)
}

private fun Ctx.speedBonus(pid: Long, kind: BonusKind): Double {
    val p = player(pid)
    return economyBonuses(researchLevels(pid), p.catchupUntil > now)[kind]
}

// ---------------------------------------------------------------- Construction

fun Ctx.build(pid: Long, req: BuildRequest): Res<Unit> {
    settle(pid)
    val b = buildings(pid)
    val hq = b.hqLevel()
    val existing = b[req.plot]
    val type: BuildingType
    val level: Int
    if (existing == null) {
        type = req.plot.fixedType ?: req.type ?: return fail(ErrorCode.VALIDATION, "Bitte einen Gebäudetyp wählen.")
        ensure(req.plot.accepts(type), ErrorCode.VALIDATION) { "Dieses Gebäude passt nicht auf den Platz." }?.let { return it }
        if (req.plot.isResourcePlot) {
            val need = balance.resourcePlots.getValue(req.plot)
            ensure(hq >= need, ErrorCode.REQUIREMENT_NOT_MET) { "Der Platz wird ab HQ $need frei." }?.let { return it }
        }
        val unlock = rules.building(type).unlockHq
        ensure(hq >= unlock, ErrorCode.REQUIREMENT_NOT_MET) { "Das Gebäude wird ab HQ $unlock freigeschaltet." }?.let { return it }
        level = 1
    } else {
        ensure(req.type == null || req.type == existing.type, ErrorCode.VALIDATION) { "Auf dem Platz steht ein anderes Gebäude." }?.let { return it }
        type = existing.type
        level = existing.level + 1
    }
    ensure(level <= rules.building(type).maxLevel, ErrorCode.MAX_LEVEL) { "Die Maximalstufe ist erreicht." }?.let { return it }
    if (type == BuildingType.HQ) {
        val wall = b.levelOf(BuildingType.WALL)
        ensure(wall >= level - 1, ErrorCode.REQUIREMENT_NOT_MET) { "HQ $level erfordert Mauer ${level - 1}." }?.let { return it }
    } else {
        ensure(level <= hq, ErrorCode.REQUIREMENT_NOT_MET) { "Kein Gebäude darf höher als das HQ sein." }?.let { return it }
    }
    val running = timers(pid).filter { it.kind == TimerKind.BUILD }
    ensure(running.none { it.payload.plot == req.plot }, ErrorCode.QUEUE_FULL) { "Dieses Gebäude wird bereits gebaut." }?.let { return it }
    ensure(running.size < balance.timers.buildQueues, ErrorCode.QUEUE_FULL) { "Beide Bauwarteschlangen sind belegt." }?.let { return it }
    val cost = rules.buildingCost(type, level)
    pay(pid, cost).orReturn { return it }
    val duration = rules.buildingTimeMs(type, level, speedBonus(pid, BonusKind.BUILD_SPEED))
    insertTimer(pid, TimerKind.BUILD, req.plot.name, TimerPayload(plot = req.plot, type = type, level = level), cost, now, duration)
    addDaily(pid, DailyTask.BUILDER, 1)
    dirty(pid)
    return OK
}

fun Ctx.demolish(pid: Long, plot: Plot): Res<Unit> {
    settle(pid)
    ensure(plot.isResourcePlot, ErrorCode.VALIDATION) { "Nur Ressourcengebäude können abgerissen werden." }?.let { return it }
    ensure(buildings(pid)[plot] != null, ErrorCode.NOT_FOUND) { "Auf dem Platz steht kein Gebäude." }?.let { return it }
    ensure(timers(pid).none { it.kind == TimerKind.BUILD && it.payload.plot == plot }, ErrorCode.VALIDATION) {
        "Während eines Ausbaus ist kein Abriss möglich."
    }?.let { return it }
    removeBuilding(pid, plot)
    dirty(pid)
    return OK
}

// ---------------------------------------------------------------- Research

fun Ctx.research(pid: Long, tech: Tech): Res<Unit> {
    settle(pid)
    val b = buildings(pid)
    val lab = b.levelOf(BuildingType.LAB)
    ensure(lab >= 1, ErrorCode.REQUIREMENT_NOT_MET) { "Zuerst ein Forschungslabor bauen." }?.let { return it }
    val level = (researchLevels(pid)[tech] ?: 0) + 1
    ensure(level <= balance.research.maxLevel, ErrorCode.MAX_LEVEL) { "Die Maximalstufe ist erreicht." }?.let { return it }
    val needLab = rules.labRequiredFor(level)
    ensure(lab >= needLab, ErrorCode.REQUIREMENT_NOT_MET) { "Stufe $level erfordert Forschungslabor $needLab." }?.let { return it }
    ensure(timers(pid).none { it.kind == TimerKind.RESEARCH }, ErrorCode.QUEUE_FULL) { "Es läuft bereits eine Forschung." }?.let { return it }
    val cost = rules.researchCost(level)
    pay(pid, cost).orReturn { return it }
    val duration = rules.researchTimeMs(level, speedBonus(pid, BonusKind.RESEARCH_SPEED))
    insertTimer(pid, TimerKind.RESEARCH, tech.name, TimerPayload(tech = tech, level = level), cost, now, duration)
    dirty(pid)
    return OK
}

// ---------------------------------------------------------------- Training and healing

fun Ctx.train(pid: Long, req: TrainRequest): Res<Unit> {
    settle(pid)
    val unit = req.building.trains ?: return fail(ErrorCode.VALIDATION, "Dieses Gebäude bildet keine Truppen aus.")
    ensure(req.type == null || req.type == unit, ErrorCode.VALIDATION) { "Dieses Gebäude bildet ${unit.name} aus." }?.let { return it }
    val level = buildings(pid).levelOf(req.building)
    ensure(level >= 1, ErrorCode.REQUIREMENT_NOT_MET) { "Das Gebäude ist noch nicht gebaut." }?.let { return it }
    ensure(req.tier in 1..balance.tiers.maxTier, ErrorCode.VALIDATION) { "Ungültige Truppenstufe." }?.let { return it }
    val needed = rules.tierUnlockLevel(req.tier)
    ensure(level >= needed, ErrorCode.REQUIREMENT_NOT_MET) { "Stufe T${req.tier} erfordert Gebäudestufe $needed." }?.let { return it }
    val max = rules.orderSize(req.building, level)
    ensure(req.count in 1..max, ErrorCode.VALIDATION) { "Anzahl 1 bis $max." }?.let { return it }
    ensure(timers(pid).none { it.kind == TimerKind.TRAIN && it.target == req.building.name }, ErrorCode.QUEUE_FULL) {
        "In diesem Gebäude läuft bereits eine Ausbildung."
    }?.let { return it }
    val cost = rules.trainCost(unit, req.tier, req.count)
    pay(pid, cost).orReturn { return it }
    val duration = rules.trainTimeMs(unit, req.tier, req.count, speedBonus(pid, BonusKind.TRAIN_SPEED))
    insertTimer(pid, TimerKind.TRAIN, req.building.name, TimerPayload(unitType = unit, tier = req.tier, count = req.count), cost, now, duration)
    // The daily task "drill" counts when the order starts.
    addDaily(pid, DailyTask.DRILL, req.count.toLong())
    dirty(pid)
    return OK
}

fun Ctx.heal(pid: Long, req: HealRequest): Res<Unit> {
    settle(pid)
    val units = req.units.normalized()
    ensure(units.isNotEmpty(), ErrorCode.VALIDATION) { "Keine Einheiten gewählt." }?.let { return it }
    ensure(req.units.all { it.count >= 0 }, ErrorCode.VALIDATION) { "Ungültige Anzahl." }?.let { return it }
    val have = troops(pid)
    units.forEach { u ->
        val w = have.firstOrNull { it.type == u.type && it.tier == u.tier }?.wounded ?: 0
        ensure(u.count <= w, ErrorCode.NOT_ENOUGH_TROOPS) { "So viele Verwundete gibt es nicht." }?.let { return it }
    }
    ensure(timers(pid).none { it.kind == TimerKind.HEAL }, ErrorCode.QUEUE_FULL) { "Es läuft bereits eine Heilung." }?.let { return it }
    val cost = rules.healCost(units)
    pay(pid, cost).orReturn { return it }
    val duration = rules.healTimeMs(units, speedBonus(pid, BonusKind.HEAL_SPEED))
    insertTimer(pid, TimerKind.HEAL, "HOSPITAL", TimerPayload(units = units), cost, now, duration)
    dirty(pid)
    return OK
}

// ---------------------------------------------------------------- Timers

fun Ctx.ownTimer(pid: Long, timerId: Long): Res<TimerRow> {
    settle(pid)
    val t = timerById(timerId)
    ensure(t != null && t.playerId == pid, ErrorCode.NOT_FOUND) { "Der Auftrag existiert nicht (mehr)." }?.let { return it }
    return ok(t!!)
}

/** Cancelling refunds 50 % of the costs. */
fun Ctx.cancelTimer(pid: Long, timerId: Long): Res<Unit> {
    val t = ownTimer(pid, timerId).orReturn { return it }
    val share = balance.timers.cancelRefundShare
    deleteTimer(t.id)
    credit(pid, Cost(floorSafe(t.cost.food * share), floorSafe(t.cost.wood * share), floorSafe(t.cost.steel * share)))
    dirty(pid)
    return OK
}

/** Speed-ups reduce the remaining time by their duration divided by g. Any excess is lost. */
fun Ctx.speedup(pid: Long, timerId: Long, item: ItemId, count: Int): Res<Unit> {
    ensure(item.isSpeedup, ErrorCode.VALIDATION) { "Das ist kein Beschleuniger." }?.let { return it }
    ensure(count >= 1, ErrorCode.VALIDATION) { "Anzahl mindestens 1." }?.let { return it }
    val t = ownTimer(pid, timerId).orReturn { return it }
    ensure(itemCount(pid, item) >= count, ErrorCode.NOT_ENOUGH_ITEMS) { "Nicht genug Beschleuniger." }?.let { return it }
    addItem(pid, item, -count)
    reduceTimer(t, rules.speedupMs(item) * count)
    return OK
}

/** Shortens a timer (not below "now") and settles the owner immediately. */
fun Ctx.reduceTimer(t: TimerRow, ms: Long) {
    setTimerEnd(t.id, maxOf(now, t.endsAt - ms))
    resettle(t.playerId)
    dirty(t.playerId)
}

// ---------------------------------------------------------------- Items

fun Ctx.useItem(pid: Long, req: ItemUseRequest): Res<Unit> {
    settle(pid)
    val item = req.item
    ensure(req.count >= 1, ErrorCode.VALIDATION) { "Anzahl mindestens 1." }?.let { return it }
    ensure(itemCount(pid, item) >= req.count, ErrorCode.NOT_ENOUGH_ITEMS) { "Nicht genug Items." }?.let { return it }
    when {
        item.isSpeedup -> return fail(ErrorCode.VALIDATION, "Beschleuniger werden direkt an einem Auftrag benutzt.")
        item.isChest -> {
            val chest = balance.items.chests.getValue(item)
            addItem(pid, item, -req.count)
            credit(pid, Cost.of(chest.resource, chest.amount * req.count))
        }
        item.isShield -> {
            ensure(req.count == 1, ErrorCode.VALIDATION) { "Schilde werden einzeln benutzt." }?.let { return it }
            addItem(pid, item, -1)
            activateShield(pid, rules.hoursMs(balance.items.shields.getValue(item)), "Friedensschild")
        }
        item.isHeroBook -> {
            val h = req.heroId ?: return fail(ErrorCode.VALIDATION, "Bitte einen Helden wählen.")
            val row = hero(pid, h) ?: return fail(ErrorCode.REQUIREMENT_NOT_MET, "Der Held ist noch nicht freigeschaltet.")
            ensure(row.level < balance.heroes.maxLevel, ErrorCode.VALIDATION) { "Der Held hat die Maximalstufe erreicht." }?.let { return it }
            addItem(pid, item, -req.count)
            giveHeroXp(pid, h, balance.items.heroBooks.getValue(item) * req.count)
        }
        item == ItemId.RELOCATE -> {
            ensure(req.count == 1, ErrorCode.VALIDATION) { "Umzugsgutscheine werden einzeln benutzt." }?.let { return it }
            val x = req.x ?: return fail(ErrorCode.VALIDATION, "Bitte ein Zielfeld wählen.")
            val y = req.y ?: return fail(ErrorCode.VALIDATION, "Bitte ein Zielfeld wählen.")
            relocate(pid, x, y).orReturn { return it }
            addItem(pid, item, -1)
        }
    }
    dirty(pid)
    return OK
}

fun Ctx.giveHeroXp(pid: Long, h: bayern.kickner.ruinborn.shared.model.HeroId, xp: Long) {
    val row = hero(pid, h) ?: return
    val (level, rest) = rules.applyHeroXp(row.level, row.xp, xp)
    setHeroLevel(pid, h, level, rest)
    dirty(pid)
}

/** Shield: end = max(previous end, now + duration). System report at the start, event for the end. */
fun Ctx.activateShield(pid: Long, durationMs: Long, label: String) {
    val p = player(pid)
    val until = maxOf(p.shieldUntil.takeIf { it != INACTIVITY_SHIELD_UNTIL } ?: 0L, now + durationMs)
    PlayerT.update({ PlayerT.id eq pid }) { it[shieldUntil] = until }
    addReport(pid, SystemReport("$label aktiv", "Deine Basis ist geschützt bis ${formatTime(until)}."), "$label aktiv")
    cancelEvents(EventType.SHIELD_END) { it.playerId == pid }
    schedule(EventType.SHIELD_END, until, EventPayload(playerId = pid))
    changedObjects += listOfNotNull(baseOf(pid)?.id)
    dirty(pid)
}

fun Ctx.onShieldEnd(pid: Long) {
    val p = playerOrNull(pid) ?: return
    if (p.shieldUntil > now) return
    addReport(pid, SystemReport("Schild beendet", "Der Schild deiner Basis ist abgelaufen."), "Schild beendet")
    changedObjects += listOfNotNull(baseOf(pid)?.id)
}

fun Ctx.formatTime(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(config.zone).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
