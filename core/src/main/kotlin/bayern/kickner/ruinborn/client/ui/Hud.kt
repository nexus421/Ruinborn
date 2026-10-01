package bayern.kickner.ruinborn.client.ui

import bayern.kickner.ruinborn.client.BuildInfo
import bayern.kickner.ruinborn.client.RuinbornGame
import bayern.kickner.ruinborn.client.dialog.AllianceDialog
import bayern.kickner.ruinborn.client.dialog.ChatDialog
import bayern.kickner.ruinborn.client.dialog.HeroesDialog
import bayern.kickner.ruinborn.client.dialog.MarchActionDialog
import bayern.kickner.ruinborn.client.dialog.MoreDialog
import bayern.kickner.ruinborn.client.dialog.ReportsDialog
import bayern.kickner.ruinborn.client.dialog.TasksDialog
import bayern.kickner.ruinborn.client.dialog.TimerActionDialog
import bayern.kickner.ruinborn.client.render.SpriteMap
import bayern.kickner.ruinborn.client.render.gfx.IconSprite
import bayern.kickner.ruinborn.client.state.GameState
import bayern.kickner.ruinborn.shared.dto.MarchDto
import bayern.kickner.ruinborn.shared.dto.PlayerState
import bayern.kickner.ruinborn.shared.dto.TimerDto
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.model.TimerKind
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import ktx.actors.onClick

/** Text of a timer for the bar and dialogs. */
fun timerTitle(t: TimerDto): String = when (t.kind) {
    TimerKind.BUILD -> tr("timer.build", t.buildingType?.label() ?: "", t.level ?: 0)
    TimerKind.RESEARCH -> tr("timer.research", t.tech?.label() ?: "", t.level ?: 0)
    TimerKind.TRAIN -> tr("timer.train", Fmt.num(t.count ?: 0), t.unitType?.label() ?: "", t.tier ?: 0)
    TimerKind.HEAL -> tr("timer.heal", Fmt.num(t.units.sumOf { it.count }))
}

fun marchTitle(m: MarchDto): String = when (m.state) {
    MarchState.GATHERING -> tr("march.state.GATHERING")
    MarchState.STATIONED -> tr("march.state.STATIONED")
    MarchState.WAITING -> tr("march.state.WAITING")
    MarchState.RETURNING -> tr("march.state.RETURNING")
    MarchState.OUTBOUND -> m.kind.label() + " → (${m.toX}, ${m.toY})"
}

/**
 * Overlay above base and map (concept section 12): top bar with resources, power, shield and
 * catch-up bonus. Below it "next goal" and banners, the timer bar on the left, navigation at the bottom.
 */
class Hud(private val game: RuinbornGame, private val stage: Stage, private val onMap: Boolean) {
    private val ui = game.ui
    private val root = Table().apply { setFillParent(true); top() }
    private val topBar = Table()
    private val banners = Table()
    private val timersTable = Table().top().left()
    private val bottomBar = Table()
    /** A running countdown: label, target time and text format for the remaining time. */
    private class Countdown(val label: Label, val endsAt: Long, val format: (Long) -> String)

    private val countdowns = mutableListOf<Countdown>()
    private val refreshedFor = mutableSetOf<Long>()
    private var last: GameState? = null

    init {
        root.add(topBar).growX().row()
        root.add(banners).growX().padLeft(12f).padRight(12f).row()
        root.add(timersTable).expand().top().left().padLeft(10f).padTop(6f).row()
        root.add(bottomBar).growX().bottom()
        stage.addActor(root)
        game.client.store.listen { if (stage.root.stage != null) update(it) }
    }

    fun update(s: GameState) {
        if (s == last) return
        last = s
        val p = s.player ?: return
        countdowns.clear()
        buildTop(s, p)
        buildBanners(s, p)
        buildTimers(p)
        buildBottom(p)
    }

    private fun buildTop(s: GameState, p: PlayerState) {
        topBar.clearChildren()
        topBar.background = ui.theme.panel
        topBar.pad(10f, 12f, 10f, 12f)
        val res = Table()
        p.resources.forEach { r ->
            val cell = Table()
            cell.add(ui.resourceIcon(r.resource, 34f)).size(34f).padRight(6f)
            val col = Table()
            val full = r.amount >= r.capacity
            col.add(ui.label(Fmt.short(r.amount), if (full) "accent" else "bold")).left().row()
            col.add(ui.label("+" + Fmt.short(r.perHour) + tr("hud.perHour"), "muted")).left()
            cell.add(col)
            res.add(cell).expandX().left().padRight(10f)
        }
        topBar.add(res).growX()
        val right = Table()
        right.add(ui.icon(IconSprite.POWER, 30f)).size(30f).padRight(4f)
        right.add(ui.label(Fmt.short(p.power), "bold")).padRight(10f)
        val now = game.client.api.serverTime.now()
        val protectedUntil = maxOf(p.protectionUntil, p.shieldUntil)
        if (protectedUntil > now) {
            right.add(ui.icon(IconSprite.SHIELD, 30f).apply { onClick { ui.toast(stage, tr("hud.protected", Fmt.dateTime(protectedUntil))) } }).size(30f).padRight(6f)
        }
        if (p.catchupUntil > now) {
            right.add(ui.icon(IconSprite.STAR, 30f).apply { onClick { ui.toast(stage, tr("hud.catchup")) } }).size(30f)
        }
        topBar.add(right).right()
    }

    private fun banner(text: String, drawable: com.badlogic.gdx.scenes.scene2d.utils.Drawable, onTap: (() -> Unit)? = null): Table {
        val t = Table()
        t.background = drawable
        t.pad(8f, 14f, 8f, 14f)
        val l = ui.label(text, "small", wrap = true)
        t.add(l).growX()
        if (onTap != null) {
            t.touchable = Touchable.enabled
            t.onClick { onTap() }
        }
        return t
    }

    private fun buildBanners(s: GameState, p: PlayerState) {
        banners.clearChildren()
        banners.defaults().growX().padTop(6f)
        // Next goal: first achievement not yet completed
        p.achievements.firstOrNull { it.completed.not() }?.let { a ->
            val claimable = p.achievements.count { it.completed && it.claimed.not() }
            val text = tr("hud.nextGoal", tr("achievement.${a.id.name}")) + if (claimable > 0) "  ·  " + tr("hud.claimable", claimable) else ""
            banners.add(banner(text, ui.theme.panelLight) { TasksDialog(game).show(stage) }).row()
        }
        if (s.connected.not()) banners.add(banner(tr("hud.reconnecting"), ui.theme.bannerYellow)).row()
        p.incoming.forEach { inc ->
            val row = banner("", ui.theme.bannerRed)
            val l = row.cells.first().actor as Label
            countdowns += Countdown(l, inc.arriveAt) { left -> tr("hud.incoming", inc.kind.label(), inc.attacker, Fmt.countdown(left)) }
            banners.add(row).row()
        }
        s.notices.lastOrNull()?.let { n ->
            banners.add(banner(tr("hud.notice", n), ui.theme.bannerBlue) {
                game.client.store.update { it.copy(notices = emptyList()) }
            }).row()
        }
        val latest = s.version?.latestClientVersion ?: 0
        if (latest > BuildInfo.VERSION_CODE) banners.add(banner(tr("hud.updateAvailable"), ui.theme.bannerBlue)).row()
    }

    private fun buildTimers(p: PlayerState) {
        timersTable.clearChildren()
        timersTable.defaults().left().padBottom(6f)
        p.timers.sortedBy { it.endsAt }.forEach { t ->
            val row = entry(iconFor(t), timerTitle(t), t.endsAt)
            row.onClick { TimerActionDialog(game, t.id).show(stage) }
            timersTable.add(row).width(330f).row()
        }
        p.marches.sortedBy { it.arriveAt }.forEach { m ->
            val end = if (m.state == MarchState.GATHERING) m.gatherEndAt ?: m.arriveAt else m.arriveAt
            val showCountdown = m.state == MarchState.OUTBOUND || m.state == MarchState.RETURNING || m.state == MarchState.GATHERING
            val row = entry(marchIcon(m), marchTitle(m), if (showCountdown) end else null)
            row.onClick { MarchActionDialog(game, m.id).show(stage) }
            timersTable.add(row).width(330f).row()
        }
        val freeQueues = p.limits.buildQueues - p.timers.count { it.kind == TimerKind.BUILD }
        if (freeQueues > 0 && onMap.not()) {
            timersTable.add(ui.label(tr("hud.freeQueues", freeQueues), "muted")).padLeft(6f).row()
        }
    }

    private fun entry(icon: IconSprite, text: String, endsAt: Long?): Table {
        val row = Table()
        row.background = ui.theme.panel
        row.pad(4f, 8f, 4f, 10f)
        row.touchable = Touchable.enabled
        row.add(ui.icon(icon, 30f)).size(30f).padRight(8f)
        val col = Table()
        col.add(ui.label(text, "small").apply { setEllipsis(true) }).width(230f).left().row()
        if (endsAt != null) {
            val cd = ui.label("", "accent")
            countdowns += Countdown(cd, endsAt) { left -> if (left > 0) Fmt.countdown(left) else tr("hud.done") }
            col.add(cd).left()
        }
        row.add(col).growX()
        return row
    }

    private fun iconFor(t: TimerDto): IconSprite = when (t.kind) {
        TimerKind.BUILD -> t.buildingType?.let { SpriteMap.buildingIcon(it) } ?: IconSprite.BASE
        TimerKind.RESEARCH -> IconSprite.RESEARCH
        TimerKind.TRAIN -> IconSprite.TROOPS
        TimerKind.HEAL -> IconSprite.HOSPITAL
    }

    private fun marchIcon(m: MarchDto): IconSprite = when (m.kind) {
        MarchKind.ATTACK -> IconSprite.SWORD
        MarchKind.GATHER -> IconSprite.CRATE
        MarchKind.SCOUT -> IconSprite.SCOUT
        MarchKind.REINFORCE -> IconSprite.SHIELD
        MarchKind.RALLY -> IconSprite.ALLIANCE
    }

    private fun buildBottom(p: PlayerState) {
        bottomBar.clearChildren()
        bottomBar.background = ui.theme.panel
        bottomBar.pad(8f, 6f, 12f, 6f)
        bottomBar.defaults().expandX().uniformX()
        fun add(b: Button) = bottomBar.add(b)
        if (onMap) add(ui.iconButton(IconSprite.BASE, tr("nav.base")) { game.showBase() })
        else add(ui.iconButton(IconSprite.MAP, tr("nav.map")) { game.showMap() })
        add(ui.iconButton(IconSprite.HERO_RHEA, tr("nav.heroes")) { HeroesDialog(game).show(stage) })
        add(ui.iconButton(IconSprite.ALLIANCE, tr("nav.alliance"), p.openAllianceHelps + p.openGifts) { AllianceDialog(game).show(stage) })
        add(ui.iconButton(IconSprite.CHAT, tr("nav.chat")) { ChatDialog(game).show(stage) })
        add(ui.iconButton(IconSprite.REPORT, tr("nav.reports"), p.unreadReports) { ReportsDialog(game).show(stage) })
        val claimable = p.daily.count { it.claimed.not() && it.progress >= it.target } + p.achievements.count { it.completed && it.claimed.not() }
        add(ui.iconButton(IconSprite.GEAR, tr("nav.more"), claimable) { MoreDialog(game).show(stage) })
    }

    /** Updates countdowns every frame. Expired timers trigger one reload. */
    fun act() {
        val now = game.client.api.serverTime.now()
        countdowns.forEach { c ->
            val left = c.endsAt - now
            c.label.setText(c.format(left))
            if (left <= 0 && refreshedFor.add(c.endsAt)) game.client.refreshState()
        }
    }

    fun setVisible(v: Boolean) {
        root.isVisible = v
    }
}
