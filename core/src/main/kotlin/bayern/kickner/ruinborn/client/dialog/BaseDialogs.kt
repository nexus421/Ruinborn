package bayern.kickner.ruinborn.client.dialog

import bayern.kickner.ruinborn.client.RuinbornGame
import bayern.kickner.ruinborn.client.render.SpriteMap
import bayern.kickner.ruinborn.client.render.gfx.IconSprite
import bayern.kickner.ruinborn.client.ui.Fmt
import bayern.kickner.ruinborn.client.ui.Palette
import bayern.kickner.ruinborn.client.ui.label
import bayern.kickner.ruinborn.client.ui.timerTitle
import bayern.kickner.ruinborn.client.ui.tr
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.dto.BuildRequest
import bayern.kickner.ruinborn.shared.dto.DemolishRequest
import bayern.kickner.ruinborn.shared.dto.HealRequest
import bayern.kickner.ruinborn.shared.dto.ItemUseRequest
import bayern.kickner.ruinborn.shared.dto.PasswordChangeRequest
import bayern.kickner.ruinborn.shared.dto.PlayerState
import bayern.kickner.ruinborn.shared.dto.ResearchRequest
import bayern.kickner.ruinborn.shared.dto.SpeedupRequest
import bayern.kickner.ruinborn.shared.dto.TrainRequest
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.Tech
import bayern.kickner.ruinborn.shared.model.TimerKind
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.rules.Bonuses
import bayern.kickner.ruinborn.shared.rules.MS_PER_HOUR
import bayern.kickner.ruinborn.shared.rules.Rules
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import ktx.actors.onChange
import ktx.actors.onClick

// ---------------------------------------------------------------- Helpers

fun PlayerState.stock(): Map<Resource, Long> = resources.associate { it.resource to it.amount }
fun PlayerState.researchBonuses(rules: Rules): Bonuses = Bonuses.research(rules.balance, research.associate { it.tech to it.level })
fun PlayerState.speedBonus(rules: Rules, kind: BonusKind, now: Long): Double =
    (researchBonuses(rules) + Bonuses.catchup(rules.balance, catchupUntil > now))[kind]
fun PlayerState.levelOf(type: BuildingType): Int = buildings.filter { it.type == type }.maxOfOrNull { it.level } ?: 0
fun PlayerState.hq(): Int = levelOf(BuildingType.HQ)
fun PlayerState.canAfford(c: Cost) = Resource.entries.all { r -> (resources.firstOrNull { it.resource == r }?.amount ?: 0) >= c[r] }

/** Reason why building is not possible, or `null` (the server checks anyway, this is only for display). */
fun buildProblem(p: PlayerState, rules: Rules, plot: Plot, type: BuildingType, level: Int): String? {
    val b = rules.building(type)
    val hq = p.hq()
    val builds = p.timers.filter { it.kind == TimerKind.BUILD }
    return when {
        level > b.maxLevel -> tr("build.maxLevel")
        builds.any { it.plot == plot } -> tr("build.running")
        type == BuildingType.HQ && p.levelOf(BuildingType.WALL) < level - 1 -> tr("build.needWall", level - 1)
        type != BuildingType.HQ && level > hq -> tr("build.needHq", level)
        level == 1 && hq < b.unlockHq -> tr("build.needHq", b.unlockHq)
        level == 1 && plot.isResourcePlot && hq < rules.balance.resourcePlots.getValue(plot) -> tr("build.needHq", rules.balance.resourcePlots.getValue(plot))
        builds.size >= p.limits.buildQueues -> tr("build.queuesFull")
        p.canAfford(rules.buildingCost(type, level)).not() -> tr("common.notEnough")
        else -> null
    }
}

// ---------------------------------------------------------------- Buildings

/** Building: info, upgrade, demolish, or the build menu for empty slots (concept section 12). */
class BuildingDialog(game: RuinbornGame, private val plot: Plot) : GameDialog(game, plot.label()) {
    override fun build() {
        val p = state.player ?: return
        val rules = state.rules ?: return
        val now = game.client.api.serverTime.now()
        val existing = p.buildings.firstOrNull { it.plot == plot }
        val timer = p.timers.firstOrNull { it.kind == TimerKind.BUILD && it.plot == plot }
        val research = p.researchBonuses(rules).toMap()
        titleLabel.setText(existing?.let { "${it.type.label()} · " + tr("common.level", it.level) } ?: plot.label())

        if (timer != null) {
            section(timerTitle(timer))
            content.add(ui.bar({ ((game.client.api.serverTime.now() - timer.startedAt).toFloat() / (timer.endsAt - timer.startedAt).coerceAtLeast(1)) })).height(16f).row()
            content.add(ui.button(tr("timer.actions")) { TimerActionDialog(game, timer.id).show(stage) }).height(70f).row()
        }
        if (existing == null) {
            val types = if (plot.isResourcePlot) BuildingType.RESOURCE_TYPES else listOf(plot.fixedType!!)
            if (timer == null) {
                section(tr("build.new"))
                types.forEach { t -> buildOption(p, rules, t, 1, research, now) }
            }
            return
        }
        content.add(ui.label(existing.type.let { tr("desc.${it.name}") }, "muted", wrap = true)).row()
        content.add(ui.label(tr("build.current", effectText(rules, existing.type, existing.level, research)), wrap = true)).row()
        if (existing.level < rules.building(existing.type).maxLevel) {
            section(tr("build.upgradeTo", existing.level + 1))
            buildOption(p, rules, existing.type, existing.level + 1, research, now)
        } else hint(tr("build.maxLevel"))
        // On to the building's functions
        val functions = Table()
        when (existing.type) {
            BuildingType.BARRACKS, BuildingType.FACTORY, BuildingType.RANGE ->
                functions.add(ui.button(tr("building.train")) { TrainDialog(game, existing.type).show(stage) }).growX().height(70f)
            BuildingType.HOSPITAL -> functions.add(ui.button(tr("building.heal")) { HospitalDialog(game).show(stage) }).growX().height(70f)
            BuildingType.LAB -> functions.add(ui.button(tr("building.research")) { ResearchDialog(game).show(stage) }).growX().height(70f)
            BuildingType.ALLIANCE_CENTER -> functions.add(ui.button(tr("nav.alliance")) { AllianceDialog(game).show(stage) }).growX().height(70f)
            BuildingType.RALLY_POINT -> functions.add(ui.button(tr("nav.heroes")) { HeroesDialog(game).show(stage) }).growX().height(70f)
            else -> {}
        }
        if (functions.hasChildren()) content.add(functions).row()
        if (existing.type == BuildingType.ALLIANCE_CENTER) {
            hint(tr("build.reinforcements", Fmt.num(p.limits.reinforcementsStationed), Fmt.num(p.limits.reinforceCapacity)))
        }
        if (plot.isResourcePlot && timer == null) {
            content.add(ui.button(tr("build.demolish"), "danger") {
                ConfirmDialog(game, tr("build.demolish"), tr("build.demolishConfirm", existing.type.label(), existing.level)) {
                    game.client.command({ demolish(DemolishRequest(plot)) }) { close() }
                }.show(stage)
            }).height(64f).padTop(16f).row()
        }
    }

    private fun buildOption(p: PlayerState, rules: Rules, type: BuildingType, level: Int, research: Map<BonusKind, Double>, now: Long) {
        val row = ui.row()
        row.add(ui.icon(SpriteMap.buildingIcon(type), 48f)).size(48f).padRight(12f)
        val info = Table()
        info.add(ui.label(if (level == 1) type.label() else tr("build.upgradeTo", level), "bold", wrap = true)).growX().left().row()
        info.add(ui.label(effectText(rules, type, level, research), "small", wrap = true)).growX().left().row()
        val cost = rules.buildingCost(type, level)
        val duration = rules.buildingTimeMs(type, level, p.speedBonus(rules, BonusKind.BUILD_SPEED, now))
        info.add(ui.cost(cost, p.stock())).left().row()
        info.add(ui.label(tr("common.duration", Fmt.duration(duration)), "muted")).left().row()
        val problem = buildProblem(p, rules, plot, type, level)
        if (problem != null) info.add(ui.label(problem, "bad", wrap = true)).growX().left().row()
        row.add(info).growX()
        row.add(ui.button(if (level == 1) tr("build.build") else tr("build.upgrade"), enabled = problem == null) {
            val req = BuildRequest(plot, if (plot.isResourcePlot && level == 1) type else null)
            game.confirmIf(stage, duration > MS_PER_HOUR, tr("confirm.long", Fmt.duration(duration))) {
                game.client.command({ build(req) }) { if (it is bayern.kickner.ruinborn.client.net.ApiResult.Ok) close() }
            }
        }).width(170f).height(70f)
        content.add(row).row()
    }
}

// ---------------------------------------------------------------- Training

class TrainDialog(game: RuinbornGame, private val building: BuildingType) : GameDialog(game, building.label()) {
    private var tier = 1
    private var count = 0

    override fun build() {
        val p = state.player ?: return
        val rules = state.rules ?: return
        val unit = building.trains ?: return
        val level = p.levelOf(building)
        val now = game.client.api.serverTime.now()
        if (level == 0) {
            hint(tr("train.notBuilt")); return
        }
        val maxTier = rules.maxTierFor(level)
        tier = tier.coerceIn(1, maxTier)
        val running = p.timers.firstOrNull { it.kind == TimerKind.TRAIN && it.target == building.name }
        if (running != null) {
            section(timerTitle(running))
            content.add(ui.bar({ ((game.client.api.serverTime.now() - running.startedAt).toFloat() / (running.endsAt - running.startedAt).coerceAtLeast(1)) })).height(16f).row()
            content.add(ui.button(tr("timer.actions")) { TimerActionDialog(game, running.id).show(stage) }).height(64f).row()
        }
        section(tr("train.tier"))
        val tiers = Table()
        val group = ButtonGroup<TextButton>().apply { setMaxCheckCount(1); setMinCheckCount(1) }
        for (t in 1..rules.balance.tiers.maxTier) {
            val b = TextButton("T$t", ui.skin, "tab")
            b.isDisabled = t > maxTier
            group.add(b)
            if (t == tier) b.isChecked = true
            b.onChange { if (isChecked && t != tier) { tier = t; refresh() } }
            tiers.add(b).growX().height(60f).padRight(6f)
        }
        content.add(tiers).row()
        if (maxTier < rules.balance.tiers.maxTier) hint(tr("train.nextTier", maxTier + 1, rules.tierUnlockLevel(maxTier + 1)))
        val stats = rules.unitStats(unit, tier)
        content.add(ui.label(tr("train.stats", Fmt.num(stats.atk.toLong()), Fmt.num(stats.def.toLong()), Fmt.num(stats.hp.toLong()), Fmt.num(stats.load.toLong()), stats.power.toLong()), "small", wrap = true)).row()
        val orderSize = rules.orderSize(building, level)
        val affordable = Resource.entries.minOf { r ->
            val c = stats.cost[r]
            if (c <= 0) Long.MAX_VALUE else (p.stock()[r] ?: 0) / c
        }.coerceAtMost(orderSize.toLong()).toInt()
        if (count == 0) count = affordable.coerceAtLeast(1)
        count = count.coerceIn(1, orderSize)
        val countLabel = ui.label("", "bold")
        val costTable = Table()
        val timeLabel = ui.label("", "muted")
        fun updateInfo() {
            countLabel.setText(tr("train.count", Fmt.num(count), Fmt.num(orderSize)))
            costTable.clearChildren()
            costTable.add(ui.cost(rules.trainCost(unit, tier, count), p.stock()))
            timeLabel.setText(tr("common.duration", Fmt.duration(rules.trainTimeMs(unit, tier, count, p.speedBonus(rules, BonusKind.TRAIN_SPEED, now)))))
        }
        val slider = Slider(1f, orderSize.toFloat(), 1f, false, ui.skin).apply { value = count.toFloat() }
        slider.onChange { count = value.toInt(); updateInfo() }
        content.add(countLabel).row()
        val sliderRow = Table()
        sliderRow.add(slider).growX().padRight(10f)
        sliderRow.add(ui.button(tr("train.max"), "secondary") { count = affordable.coerceAtLeast(1); slider.value = count.toFloat(); updateInfo() }).height(60f)
        content.add(sliderRow).row()
        content.add(costTable).left().row()
        content.add(timeLabel).left().row()
        updateInfo()
        val home = p.troops.filter { it.type == unit }.sumOf { it.home }
        hint(tr("train.home", Fmt.num(home), unit.label()))
        content.add(ui.button(tr("train.start"), enabled = running == null) {
            game.client.command({ train(TrainRequest(building, tier, count)) }) { count = 0 }
        }).height(76f).row()
    }
}

// ---------------------------------------------------------------- Research

class ResearchDialog(game: RuinbornGame) : GameDialog(game, tr("research.title")) {
    override fun build() {
        val p = state.player ?: return
        val rules = state.rules ?: return
        val now = game.client.api.serverTime.now()
        val lab = p.levelOf(BuildingType.LAB)
        if (lab == 0) hint(tr("research.noLab", rules.building(BuildingType.LAB).unlockHq))
        val running = p.timers.firstOrNull { it.kind == TimerKind.RESEARCH }
        if (running != null) {
            section(timerTitle(running))
            content.add(ui.button(tr("timer.actions")) { TimerActionDialog(game, running.id).show(stage) }).height(64f).row()
        }
        Tech.entries.forEach { tech ->
            val level = p.research.firstOrNull { it.tech == tech }?.level ?: 0
            val effects = rules.balance.research.techs.getValue(tech)
            val row = ui.row()
            row.add(ui.icon(IconSprite.RESEARCH, 44f)).size(44f).padRight(10f)
            val info = Table()
            info.add(ui.label(tech.label(), "bold", wrap = true)).growX().left().row()
            info.add(ui.label(tr("research.level", level, rules.balance.research.maxLevel), "accent")).left().row()
            info.add(ui.label(tr("research.effect", bonusText(effects), bonusText(effects, level).ifBlank { "–" }), "small", wrap = true)).growX().left().row()
            var enabled = false
            if (level < rules.balance.research.maxLevel) {
                val next = level + 1
                val need = rules.labRequiredFor(next)
                val cost = rules.researchCost(next)
                info.add(ui.cost(cost, p.stock())).left().row()
                info.add(ui.label(tr("common.duration", Fmt.duration(rules.researchTimeMs(next, p.speedBonus(rules, BonusKind.RESEARCH_SPEED, now)))), "muted")).left().row()
                if (lab < need) info.add(ui.label(tr("research.needLab", need), "bad", wrap = true)).growX().left().row()
                enabled = lab >= need && running == null && p.canAfford(cost)
            }
            row.add(info).growX()
            if (level < rules.balance.research.maxLevel) {
                row.add(ui.button(tr("research.start"), enabled = enabled) {
                    game.client.command({ research(ResearchRequest(tech)) })
                }).width(160f).height(64f)
            }
            content.add(row).row()
        }
    }
}

// ---------------------------------------------------------------- Hospital

class HospitalDialog(game: RuinbornGame) : GameDialog(game, tr("hospital.title")) {
    private val selection = mutableMapOf<Pair<bayern.kickner.ruinborn.shared.model.UnitType, Int>, Int>()

    override fun build() {
        val p = state.player ?: return
        val rules = state.rules ?: return
        val now = game.client.api.serverTime.now()
        val wounded = p.troops.filter { it.wounded > 0 }
        content.add(ui.label(tr("hospital.capacity", Fmt.num(wounded.sumOf { it.wounded }), Fmt.num(p.limits.hospitalCapacity)), "bold")).row()
        val running = p.timers.firstOrNull { it.kind == TimerKind.HEAL }
        if (running != null) {
            section(timerTitle(running))
            content.add(ui.button(tr("timer.actions")) { TimerActionDialog(game, running.id).show(stage) }).height(64f).row()
        }
        if (wounded.isEmpty()) {
            empty(tr("hospital.empty")); return
        }
        val summary = ui.label("", "small", wrap = true)
        fun units() = selection.filterValues { it > 0 }.map { (k, v) -> TroopCount(k.first, k.second, v) }
        fun update() {
            val u = units()
            summary.setText(
                if (u.isEmpty()) tr("hospital.none") else tr("hospital.summary", Fmt.num(u.sumOf { it.count }),
                    Fmt.duration(rules.healTimeMs(u, p.speedBonus(rules, BonusKind.HEAL_SPEED, now)))),
            )
        }
        wounded.forEach { t ->
            val key = t.type to t.tier
            if (key !in selection) selection[key] = t.wounded
            selection[key] = selection.getValue(key).coerceAtMost(t.wounded)
            val row = ui.row()
            row.add(ui.label("${t.type.label()} T${t.tier}", "bold")).width(220f).left()
            val amount = ui.label(Fmt.num(selection.getValue(key)))
            val slider = Slider(0f, t.wounded.toFloat(), 1f, false, ui.skin).apply { value = selection.getValue(key).toFloat() }
            slider.onChange {
                selection[key] = value.toInt()
                amount.setText(Fmt.num(value.toInt()))
                update()
            }
            row.add(slider).growX().padRight(10f)
            row.add(amount).width(90f)
            content.add(row).row()
        }
        update()
        content.add(summary).row()
        content.add(ui.cost(rules.healCost(units()), p.stock())).left().row()
        content.add(ui.button(tr("hospital.heal"), enabled = running == null) {
            val u = units()
            if (u.isNotEmpty()) game.client.command({ heal(HealRequest(u)) })
        }).height(76f).row()
    }
}

// ---------------------------------------------------------------- Heroes

class HeroesDialog(game: RuinbornGame) : GameDialog(game, tr("heroes.title")) {
    override fun build() {
        val p = state.player ?: return
        val rules = state.rules ?: return
        hint(tr("heroes.hint"))
        val books = p.items.filter { it.item.isHeroBook && it.count > 0 }
        p.heroes.forEach { h ->
            val row = ui.row()
            row.add(ui.icon(SpriteMap.hero(h.hero), 72f)).size(72f).padRight(12f)
            val info = Table()
            info.add(ui.label(h.hero.label() + if (h.unlocked) " · " + tr("common.level", h.level) else "", "bold", wrap = true)).growX().left().row()
            val per = rules.balance.heroes.heroes.getValue(h.hero).bonusPerLevel
            if (h.unlocked) {
                info.add(ui.label(tr("heroes.bonus", bonusText(per, h.level)), "small", wrap = true)).growX().left().row()
                if (h.level < rules.balance.heroes.maxLevel) {
                    info.add(ui.bar({ h.xp.toFloat() / h.xpNext.coerceAtLeast(1) }, Palette.info)).growX().height(10f).row()
                    info.add(ui.label(tr("heroes.xp", Fmt.num(h.xp), Fmt.num(h.xpNext)), "muted")).left().row()
                }
                info.add(ui.label(if (h.marchId != null) tr("heroes.onMarch") else tr("heroes.home"), if (h.marchId != null) "accent" else "good")).left().row()
            } else {
                info.add(ui.label(tr("heroes.bonus", bonusText(per)) + " " + tr("heroes.perLevel"), "small", wrap = true)).growX().left().row()
                info.add(ui.label(tr("heroes.locked", h.unlockHq), "bad")).left().row()
            }
            row.add(info).growX()
            if (h.unlocked && books.isNotEmpty() && h.level < rules.balance.heroes.maxLevel) {
                val col = Table()
                books.forEach { b ->
                    col.add(ui.button("${b.item.label()} (${b.count})", "secondary") {
                        game.client.command({ useItem(ItemUseRequest(b.item, 1, heroId = h.hero)) })
                    }).height(56f).growX().row()
                }
                row.add(col).width(220f)
            }
            content.add(row).row()
        }
    }
}

// ---------------------------------------------------------------- Inventory

class InventoryDialog(game: RuinbornGame) : GameDialog(game, tr("inventory.title")) {
    override fun build() {
        val p = state.player ?: return
        val rules = state.rules ?: return
        if (p.items.isEmpty()) {
            empty(tr("inventory.empty")); return
        }
        p.items.forEach { it ->
            val item = it.item
            val row = ui.row()
            row.add(ui.icon(SpriteMap.item(item), 48f)).size(48f).padRight(12f)
            val info = Table()
            info.add(ui.label("${item.label()} × ${it.count}", "bold", wrap = true)).growX().left().row()
            info.add(ui.label(itemDescription(rules, item), "small", wrap = true)).growX().left().row()
            row.add(info).growX()
            when {
                item.isChest -> {
                    row.add(ui.button(tr("inventory.use"), "secondary") { game.client.command({ useItem(ItemUseRequest(item, 1)) }) }).height(56f).padRight(6f)
                    if (it.count > 1) row.add(ui.button(tr("inventory.useAll")) { game.client.command({ useItem(ItemUseRequest(item, it.count)) }) }).height(56f)
                }
                item.isShield -> row.add(ui.button(tr("inventory.use")) {
                    ConfirmDialog(game, item.label(), tr("inventory.shieldConfirm")) { game.client.command({ useItem(ItemUseRequest(item, 1)) }) }.show(stage)
                }).height(56f)
                item.isHeroBook -> row.add(ui.button(tr("nav.heroes"), "secondary") { HeroesDialog(game).show(stage) }).height(56f)
                item == ItemId.RELOCATE -> row.add(ui.button(tr("nav.map"), "secondary") { close(); game.showMap(); game.client.onToast(tr("inventory.relocateHint")) }).height(56f)
                else -> {}
            }
            content.add(row).row()
        }
    }
}

fun itemDescription(rules: Rules, item: ItemId): String = when {
    item.isSpeedup -> tr("item.desc.speedup", Fmt.duration(rules.speedupMs(item)))
    item.isChest -> rules.balance.items.chests.getValue(item).let { tr("item.desc.chest", Fmt.num(it.amount), it.resource.label()) }
    item.isShield -> tr("item.desc.shield", Fmt.duration(rules.hoursMs(rules.balance.items.shields.getValue(item))))
    item.isHeroBook -> tr("item.desc.book", Fmt.num(rules.balance.items.heroBooks.getValue(item)))
    else -> tr("item.desc.relocate")
}

// ---------------------------------------------------------------- Timer actions

/** Tapping a timer: speed up, request help, cancel (concept section 12). */
class TimerActionDialog(game: RuinbornGame, private val timerId: Long) : GameDialog(game, tr("timer.title")) {
    override fun build() {
        val p = state.player ?: return
        val rules = state.rules ?: return
        val t = p.timers.firstOrNull { it.id == timerId }
        if (t == null) {
            hint(tr("timer.done")); return
        }
        section(timerTitle(t))
        val left = ui.label("", "large")
        content.add(left).row()
        content.add(ui.bar({ ((game.client.api.serverTime.now() - t.startedAt).toFloat() / (t.endsAt - t.startedAt).coerceAtLeast(1)) })).height(16f).row()
        left.addAction(com.badlogic.gdx.scenes.scene2d.actions.Actions.forever(com.badlogic.gdx.scenes.scene2d.actions.Actions.run {
            left.setText(tr("timer.left", Fmt.countdown(t.endsAt - game.client.api.serverTime.now())))
        }))
        val speedups = p.items.filter { it.item.isSpeedup }.sortedBy { rules.speedupMs(it.item) }
        if (speedups.isNotEmpty()) {
            section(tr("timer.speedup"))
            speedups.forEach { s ->
                val row = ui.row()
                row.add(ui.icon(IconSprite.SPEEDUP, 40f)).size(40f).padRight(10f)
                row.add(ui.label("${s.item.label()} × ${s.count}", "bold", wrap = true)).growX().left()
                row.add(ui.button("1×", "secondary") { game.client.command({ speedup(t.id, SpeedupRequest(s.item, 1)) }) }).height(56f).padRight(6f)
                val needed = ((t.endsAt - game.client.api.serverTime.now()) / rules.speedupMs(s.item).coerceAtLeast(1) + 1).toInt().coerceIn(1, s.count)
                if (needed > 1) row.add(ui.button("$needed×") { game.client.command({ speedup(t.id, SpeedupRequest(s.item, needed)) }) }).height(56f)
                content.add(row).row()
            }
        } else hint(tr("timer.noSpeedups"))
        if (t.kind != TimerKind.TRAIN && p.alliance != null) {
            if (t.helpRequested) hint(tr("timer.helpState", t.helpCount, t.helpMax))
            else content.add(ui.button(tr("timer.requestHelp")) { game.client.command({ requestHelp(t.id) }) }).height(70f).row()
        }
        content.add(ui.button(tr("timer.cancel"), "danger") {
            ConfirmDialog(game, tr("timer.cancel"), tr("timer.cancelConfirm")) { game.client.command({ cancelTimer(t.id) }) { close() } }.show(stage)
        }).height(64f).padTop(12f).row()
    }
}

// ---------------------------------------------------------------- More and settings

class MoreDialog(game: RuinbornGame) : GameDialog(game, tr("nav.more")) {
    override val rebuildOnState = false
    override fun build() {
        fun entry(icon: IconSprite, text: String, open: () -> GameDialog) {
            val b = ui.row()
            b.add(ui.icon(icon, 44f)).size(44f).padRight(14f)
            b.add(ui.label(text, "bold")).growX().left()
            b.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
            b.onClick { val s = stage; close(); open().show(s) }
            content.add(b).height(76f).row()
        }
        entry(IconSprite.CRATE, tr("inventory.title")) { InventoryDialog(game) }
        entry(IconSprite.TASK, tr("tasks.title")) { TasksDialog(game) }
        entry(IconSprite.RESEARCH, tr("research.title")) { ResearchDialog(game) }
        entry(IconSprite.TROPHY, tr("rankings.title")) { RankingsDialog(game) }
        entry(IconSprite.STAR, tr("profile.title")) { ProfileDialog(game, null) }
        entry(IconSprite.GEAR, tr("settings.title")) { SettingsDialog(game) }
    }
}

class SettingsDialog(game: RuinbornGame) : GameDialog(game, tr("settings.title")) {
    override val rebuildOnState = false
    override fun build() {
        val p = state.player
        content.add(ui.label(tr("settings.account", p?.name ?: "–"), "bold")).row()
        content.add(ui.label(tr("settings.version", bayern.kickner.ruinborn.client.BuildInfo.VERSION_NAME, bayern.kickner.ruinborn.client.BuildInfo.VERSION_CODE), "muted")).row()
        content.add(ui.label(tr("settings.server", game.client.api.baseUrl), "muted")).row()
        if (p?.devMode == true) content.add(ui.label(tr("settings.devMode"), "accent")).row()
        section(tr("settings.password"))
        val old = TextField("", ui.skin).apply { messageText = tr("settings.oldPassword"); isPasswordMode = true; setPasswordCharacter('•') }
        val new = TextField("", ui.skin).apply { messageText = tr("settings.newPassword"); isPasswordMode = true; setPasswordCharacter('•') }
        content.add(old).height(70f).row()
        content.add(new).height(70f).row()
        content.add(ui.button(tr("settings.changePassword"), "secondary") {
            game.client.command({ changePassword(PasswordChangeRequest(old.text, new.text)) }) {
                if (it is bayern.kickner.ruinborn.client.net.ApiResult.Ok) { old.text = ""; new.text = ""; game.client.onToast(tr("settings.passwordChanged")) }
            }
        }).height(66f).row()
        content.add(ui.button(tr("settings.logout"), "danger") {
            ConfirmDialog(game, tr("settings.logout"), tr("settings.logoutConfirm")) { close(); game.client.logout() }.show(stage)
        }).height(70f).padTop(20f).row()
    }
}
