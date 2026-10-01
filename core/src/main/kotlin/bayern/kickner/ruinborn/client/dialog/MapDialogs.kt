package bayern.kickner.ruinborn.client.dialog

import bayern.kickner.ruinborn.client.RuinbornGame
import bayern.kickner.ruinborn.client.net.ApiResult
import bayern.kickner.ruinborn.client.render.Relation
import bayern.kickner.ruinborn.client.render.SpriteMap
import bayern.kickner.ruinborn.client.render.relationOf
import bayern.kickner.ruinborn.client.ui.Fmt
import bayern.kickner.ruinborn.client.ui.label
import bayern.kickner.ruinborn.client.ui.marchTitle
import bayern.kickner.ruinborn.client.ui.tr
import bayern.kickner.ruinborn.shared.dto.ItemUseRequest
import bayern.kickner.ruinborn.shared.dto.MarchRequest
import bayern.kickner.ruinborn.shared.dto.PlayerState
import bayern.kickner.ruinborn.shared.dto.RallyJoinRequest
import bayern.kickner.ruinborn.shared.dto.RallyRequest
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.model.TroopCount
import bayern.kickner.ruinborn.shared.model.UnitType
import bayern.kickner.ruinborn.shared.rules.Bonuses
import bayern.kickner.ruinborn.shared.rules.Rules
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import ktx.actors.onChange

// ---------------------------------------------------------------- Tile info

/** Info panel of a map tile with the available actions (concept section 12). */
class TileInfoDialog(game: RuinbornGame, private val x: Int, private val y: Int) : GameDialog(game, "($x, $y)") {
    override fun build() {
        val p = state.player ?: return
        val rules = state.rules ?: return
        val o = state.map.byTile[x to y]
        val zone = rules.zoneOf(x, y)
        val dist = rules.distance(p.base.x, p.base.y, x, y)
        hint(tr("tile.zone", zone, Fmt.num(dist.toLong())))
        val actions = Table()
        actions.defaults().growX().height(72f).padBottom(8f)
        fun action(text: String, style: String = "default", onClick: () -> Unit) = actions.add(ui.button(text, style) { onClick() }).row()
        val inAlliance = p.alliance != null
        when {
            o == null -> {
                content.add(ui.label(tr("tile.empty"), "bold")).row()
                if (p.items.any { it.item == ItemId.RELOCATE }) {
                    val need = rules.balance.relocation.minHqByZone.getValue(zone)
                    if (p.hq() >= need) action(tr("tile.relocate")) {
                        ConfirmDialog(game, tr("tile.relocate"), tr("tile.relocateConfirm", x, y)) {
                            game.client.command({ useItem(ItemUseRequest(ItemId.RELOCATE, 1, x = x, y = y)) }) { if (it is ApiResult.Ok) close() }
                        }.show(stage)
                    } else hint(tr("tile.relocateNeedHq", need))
                }
            }
            o.kind == MapObjectKind.ZOMBIE -> {
                val z = rules.zombie(o.level)
                val r = rules.zombieReward(o.level)
                content.add(ui.label(tr("tile.zombies", o.level), "bold")).row()
                content.add(ui.label(tr("tile.zombieStats", Fmt.num(z.count), Fmt.num(z.atk.toLong()), Fmt.num(z.def.toLong()), Fmt.num(z.hp.toLong())), "small", wrap = true)).row()
                content.add(ui.label(tr("tile.rewards", Fmt.num(r.food), Fmt.num(r.wood), Fmt.num(r.steel), Fmt.num(r.heroXp)), "small", wrap = true)).row()
                if (o.level > p.limits.maxZombieAttackLevel) hint(tr("tile.zombieLocked", p.limits.maxZombieAttackLevel))
                else action(tr("tile.attack")) { MarchDialog(game, MarchMode.Normal(MarchKind.ATTACK), x, y).show(stage) }
            }
            o.kind == MapObjectKind.NEST -> {
                val n = rules.nest(o.level)
                content.add(ui.label(tr("tile.nest", o.level), "bold")).row()
                content.add(ui.label(tr("tile.zombieStats", Fmt.num(n.count), Fmt.num(n.atk.toLong()), Fmt.num(n.def.toLong()), Fmt.num(n.hp.toLong())), "small", wrap = true)).row()
                hint(tr("tile.nestHint"))
                if (inAlliance) action(tr("tile.rally")) { MarchDialog(game, MarchMode.RallyCreate, x, y).show(stage) }
                else hint(tr("tile.needAlliance"))
            }
            o.kind == MapObjectKind.FIELD -> {
                content.add(ui.label(tr("tile.field", o.resType!!.label(), o.level), "bold", wrap = true)).row()
                content.add(ui.label(tr("tile.fieldStock", Fmt.num(o.amount ?: 0), Fmt.num(rules.gatherRatePerHour(o.level, o.resType!!).toLong())), "small", wrap = true)).row()
                val occupant = o.occupiedByMarchId?.let { id -> state.allMarches.firstOrNull { it.id == id } }
                when {
                    occupant == null -> action(tr("tile.gather")) { MarchDialog(game, MarchMode.Normal(MarchKind.GATHER), x, y).show(stage) }
                    occupant.playerId == p.playerId -> hint(tr("tile.ownGatherer"))
                    else -> {
                        hint(tr("tile.gatheredBy", occupant.playerName ?: "?"))
                        val rel = relationOf(occupant.playerId, occupant.allianceId, p.playerId, p.alliance?.id)
                        if (rel == Relation.ENEMY) action(tr("tile.attackGatherer"), "danger") { MarchDialog(game, MarchMode.Normal(MarchKind.ATTACK), x, y).show(stage) }
                    }
                }
            }
            else -> {
                val rel = relationOf(o.playerId, o.allianceId, p.playerId, p.alliance?.id)
                content.add(ui.label((o.allianceTag?.let { "[$it] " } ?: "") + (o.playerName ?: "?"), "bold", wrap = true)).row()
                if (o.shielded) hint(tr("tile.shielded"))
                when (rel) {
                    Relation.OWN -> action(tr("tile.enterBase")) { close(); game.showBase() }
                    Relation.ALLY -> {
                        action(tr("tile.reinforce")) { MarchDialog(game, MarchMode.Normal(MarchKind.REINFORCE), x, y).show(stage) }
                    }
                    else -> if (o.shielded.not()) {
                        action(tr("tile.attack"), "danger") { MarchDialog(game, MarchMode.Normal(MarchKind.ATTACK), x, y).show(stage) }
                        action(tr("tile.scout"), "secondary") { MarchDialog(game, MarchMode.Normal(MarchKind.SCOUT), x, y).show(stage) }
                        if (inAlliance) action(tr("tile.rally"), "secondary") { MarchDialog(game, MarchMode.RallyCreate, x, y).show(stage) }
                    }
                }
                o.playerId?.let { pid -> if (rel != Relation.OWN) action(tr("tile.profile"), "secondary") { ProfileDialog(game, pid).show(stage) } }
            }
        }
        if (actions.hasChildren()) content.add(actions).row()
    }
}

// ---------------------------------------------------------------- March

sealed class MarchMode {
    data class Normal(val kind: MarchKind) : MarchMode()
    data object RallyCreate : MarchMode()
    data class RallyJoin(val rallyId: Long, val leaderX: Int, val leaderY: Int) : MarchMode()
}

/**
 * March dialog: free hero, one slider per type and tier, "maximum" (highest tier first), display of
 * units and march size, combat power, load and travel time (concept section 12).
 */
class MarchDialog(game: RuinbornGame, private val mode: MarchMode, private val x: Int, private val y: Int) :
    GameDialog(game, when (mode) {
        is MarchMode.Normal -> mode.kind.label()
        MarchMode.RallyCreate -> tr("rally.create")
        is MarchMode.RallyJoin -> tr("rally.join")
    }) {
    override val rebuildOnState = false
    private val counts = mutableMapOf<Pair<UnitType, Int>, Int>()
    private var hero: HeroId? = null
    private var waitMinutes = 5
    private val summary = ui.label("", "small", wrap = true)

    private val kind: MarchKind get() = (mode as? MarchMode.Normal)?.kind ?: MarchKind.RALLY

    override fun build() {
        val p = state.player ?: return
        val rules = state.rules ?: return
        val targetX = (mode as? MarchMode.RallyJoin)?.leaderX ?: x
        val targetY = (mode as? MarchMode.RallyJoin)?.leaderY ?: y
        val dist = rules.distance(p.base.x, p.base.y, targetX, targetY)
        val bon = p.researchBonuses(rules)
        hint(tr("march.target", x, y, Fmt.num(dist.toLong())))
        if (kind == MarchKind.SCOUT) {
            val t = rules.marchTimeMs(dist, rules.balance.scouting.secPerTile, bon[BonusKind.MARCH_SPEED])
            content.add(ui.label(tr("march.scoutInfo", Fmt.num(rules.balance.scouting.foodCost), Fmt.duration(t)), wrap = true)).row()
            content.add(ui.button(tr("march.send")) { send() }).height(78f).row()
            return
        }
        // Hero
        section(tr("march.hero"))
        val free = p.heroes.filter { it.unlocked && it.marchId == null }
        if (free.isEmpty()) {
            hint(tr("march.noHero")); return
        }
        if (hero == null || free.none { it.hero == hero }) hero = free.maxBy { it.level }.hero
        val heroes = Table().left()
        val group = ButtonGroup<TextButton>().apply { setMaxCheckCount(1); setMinCheckCount(1) }
        free.forEach { h ->
            val b = TextButton("${h.hero.label()} ${h.level}", ui.skin, "tab")
            group.add(b)
            b.isChecked = h.hero == hero
            b.onChange { if (isChecked) { hero = h.hero; updateSummary(p, rules, dist) } }
            heroes.add(b).height(58f).padRight(6f)
        }
        content.add(heroes).left().row()
        if (mode == MarchMode.RallyCreate) {
            section(tr("rally.wait"))
            val waits = Table().left()
            val wg = ButtonGroup<TextButton>().apply { setMaxCheckCount(1); setMinCheckCount(1) }
            rules.balance.rally.waitMinutes.forEach { m ->
                val b = TextButton(tr("rally.minutes", m), ui.skin, "tab")
                wg.add(b)
                b.isChecked = m == waitMinutes
                b.onChange { if (isChecked) waitMinutes = m }
                waits.add(b).height(58f).padRight(6f)
            }
            content.add(waits).left().row()
        }
        // Troops
        section(tr("march.troops"))
        val home = p.troops.filter { it.home > 0 }.sortedWith(compareByDescending<bayern.kickner.ruinborn.shared.dto.TroopDto> { it.tier }.thenBy { it.type.ordinal })
        if (home.isEmpty()) hint(tr("march.noTroops"))
        val sliders = mutableListOf<Pair<Pair<UnitType, Int>, Slider>>()
        home.forEach { t ->
            val key = t.type to t.tier
            counts.putIfAbsent(key, 0)
            val row = ui.row(6f)
            row.add(ui.icon(SpriteMap.buildingIcon(bayern.kickner.ruinborn.shared.model.BuildingType.trainerOf(t.type)), 32f)).size(32f).padRight(8f)
            row.add(ui.label("${t.type.label()} T${t.tier}", "small")).width(200f).left()
            val amount = ui.label(Fmt.num(counts.getValue(key)), "small")
            val slider = Slider(0f, t.home.toFloat(), 1f, false, ui.skin).apply { value = counts.getValue(key).toFloat() }
            slider.onChange {
                counts[key] = value.toInt()
                amount.setText(Fmt.num(value.toInt()))
                updateSummary(p, rules, dist)
            }
            sliders += key to slider
            row.add(slider).growX().padRight(8f)
            row.add(amount).width(90f)
            content.add(row).row()
        }
        val buttons = Table()
        buttons.add(ui.button(tr("march.max"), "secondary") {
            var left = p.limits.marchSize
            home.forEach { t ->
                val n = minOf(left, t.home.toLong()).toInt()
                counts[t.type to t.tier] = n
                left -= n
            }
            sliders.forEach { (k, s) -> s.value = counts.getValue(k).toFloat() }
            updateSummary(p, rules, dist)
        }).growX().height(62f).padRight(8f)
        buttons.add(ui.button(tr("march.clear"), "secondary") {
            counts.keys.toList().forEach { counts[it] = 0 }
            sliders.forEach { (_, s) -> s.value = 0f }
            updateSummary(p, rules, dist)
        }).growX().height(62f)
        content.add(buttons).row()
        content.add(summary).row()
        updateSummary(p, rules, dist)
        content.add(ui.button(tr("march.send")) { send() }).height(80f).row()
    }

    private fun troops(): List<TroopCount> = counts.filterValues { it > 0 }.map { (k, v) -> TroopCount(k.first, k.second, v) }

    private fun updateSummary(p: PlayerState, rules: Rules, dist: Double) {
        val t = troops()
        val units = t.sumOf { it.count }
        val heroRow = p.heroes.firstOrNull { it.hero == hero }
        val bon = p.researchBonuses(rules) + (heroRow?.let { Bonuses.hero(rules.balance, it.hero, it.level) } ?: Bonuses.NONE)
        val time = if (t.isEmpty()) 0L else rules.marchTimeMs(dist, rules.slowestSecPerTile(t), bon[BonusKind.MARCH_SPEED])
        summary.setText(
            tr("march.summary", Fmt.num(units), Fmt.num(p.limits.marchSize), Fmt.num(rules.power(t)), Fmt.num(rules.load(t, bon[BonusKind.LOAD])),
                if (t.isEmpty()) "–" else Fmt.duration(time)),
        )
        summary.color = if (units > p.limits.marchSize) bayern.kickner.ruinborn.client.ui.Palette.bad else bayern.kickner.ruinborn.client.ui.Palette.text
    }

    private fun send() {
        val t = troops()
        val pvp = kind == MarchKind.SCOUT || (kind == MarchKind.ATTACK && state.map.byTile[x to y]?.kind != MapObjectKind.ZOMBIE) ||
            (mode == MarchMode.RallyCreate && state.map.byTile[x to y]?.kind == MapObjectKind.BASE)
        val go = {
            val onDone: (ApiResult<PlayerState>) -> Unit = { if (it is ApiResult.Ok) { closeAll() } }
            when (val m = mode) {
                is MarchMode.Normal -> game.client.command({ march(MarchRequest(m.kind, hero, t, x, y)) }, onDone)
                MarchMode.RallyCreate -> game.client.command({ rally(RallyRequest(x, y, waitMinutes, hero!!, t)) }, onDone)
                is MarchMode.RallyJoin -> game.client.command({ joinRally(m.rallyId, RallyJoinRequest(hero!!, t)) }, onDone)
            }
        }
        game.confirmIf(stage, pvp, tr("confirm.pvp")) { go() }
    }

    private fun closeAll() {
        game.openDialogs.filter { it is TileInfoDialog || it is MarchDialog || it is AllianceDialog }.forEach { it.close() }
    }
}

// ---------------------------------------------------------------- March actions

class MarchActionDialog(game: RuinbornGame, private val marchId: Long) : GameDialog(game, tr("march.title")) {
    override fun build() {
        val p = state.player ?: return
        val m = p.marches.firstOrNull { it.id == marchId }
        if (m == null) {
            hint(tr("march.gone")); return
        }
        section(marchTitle(m))
        content.add(ui.label(tr("march.details", m.kind.label(), m.state.label(), Fmt.num(m.troopCount), m.heroId?.label() ?: "–"), "small", wrap = true)).row()
        m.troops?.let { list -> content.add(ui.label(list.joinToString(", ") { "${Fmt.num(it.count)} ${it.type.label()} T${it.tier}" }, "muted", wrap = true)).row() }
        m.cargo?.takeIf { it.total > 0 }?.let { content.add(ui.cost(it)).left().row() }
        if (m.state == MarchState.GATHERING) {
            content.add(ui.label(tr("march.gathering", Fmt.num(m.load ?: 0), Fmt.num((m.gatherRatePerHour ?: 0.0).toLong())), "small", wrap = true)).row()
        }
        val canRecall = when (m.state) {
            MarchState.RETURNING -> false
            MarchState.OUTBOUND -> m.kind != MarchKind.RALLY || m.hostId != null
            else -> true
        }
        val buttons = Table()
        buttons.defaults().growX().height(70f).padBottom(8f)
        buttons.add(ui.button(tr("march.showOnMap"), "secondary") {
            close()
            game.showMap()
            game.mapScreen().focus(m.toX, m.toY)
        }).row()
        if (canRecall) buttons.add(ui.button(tr("march.recall")) { game.client.command({ recall(m.id) }) { close() } }).row()
        content.add(buttons).row()
    }
}
