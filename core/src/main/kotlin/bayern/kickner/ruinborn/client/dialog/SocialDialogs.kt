package bayern.kickner.ruinborn.client.dialog

import bayern.kickner.ruinborn.client.RuinbornGame
import bayern.kickner.ruinborn.client.net.ApiResult
import bayern.kickner.ruinborn.client.render.SpriteMap
import bayern.kickner.ruinborn.client.render.gfx.IconSprite
import bayern.kickner.ruinborn.client.state.GameState
import bayern.kickner.ruinborn.client.ui.Fmt
import bayern.kickner.ruinborn.client.ui.Palette
import bayern.kickner.ruinborn.client.ui.label
import bayern.kickner.ruinborn.client.ui.tr
import bayern.kickner.ruinborn.shared.balance.Reward
import bayern.kickner.ruinborn.shared.dto.AllianceCreateRequest
import bayern.kickner.ruinborn.shared.dto.AllianceDetailDto
import bayern.kickner.ruinborn.shared.dto.AllianceRequestDto
import bayern.kickner.ruinborn.shared.dto.AllianceSettingsRequest
import bayern.kickner.ruinborn.shared.dto.AllianceSummaryDto
import bayern.kickner.ruinborn.shared.dto.BattleParticipant
import bayern.kickner.ruinborn.shared.dto.BattleReport
import bayern.kickner.ruinborn.shared.dto.ChatMessageDto
import bayern.kickner.ruinborn.shared.dto.CosmeticEquipRequest
import bayern.kickner.ruinborn.shared.dto.GatherReport
import bayern.kickner.ruinborn.shared.dto.GiftDto
import bayern.kickner.ruinborn.shared.dto.HelpRequestDto
import bayern.kickner.ruinborn.shared.dto.PlayerProfileDto
import bayern.kickner.ruinborn.shared.dto.RallyDto
import bayern.kickner.ruinborn.shared.dto.RankingDto
import bayern.kickner.ruinborn.shared.dto.ReportDto
import bayern.kickner.ruinborn.shared.dto.ScoutReport
import bayern.kickner.ruinborn.shared.dto.SystemReport
import bayern.kickner.ruinborn.shared.model.AllianceRank
import bayern.kickner.ruinborn.shared.model.Cosmetic
import bayern.kickner.ruinborn.shared.model.JoinMode
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.RallyState
import bayern.kickner.ruinborn.shared.model.Role
import bayern.kickner.ruinborn.shared.rules.Validation
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import kotlinx.coroutines.launch
import ktx.actors.onChange
import ktx.actors.onClick

/** Tab bar. [onSelect] is called when the tab changes. */
fun GameDialog.tabs(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): Table {
    val t = Table()
    val group = ButtonGroup<TextButton>().apply { setMaxCheckCount(1); setMinCheckCount(1) }
    // Two rows from four tabs on, so everything fits on the portrait screen.
    val perRow = if (options.size > 3) (options.size + 1) / 2 else options.size
    options.forEachIndexed { i, (key, text) ->
        val b = TextButton(text, ui.skin, "tab")
        group.add(b)
        b.isChecked = key == selected
        b.onChange { if (isChecked && key != selected) onSelect(key) }
        t.add(b).growX().uniformX().height(60f).padRight(4f).padBottom(4f)
        if ((i + 1) % perRow == 0) t.row()
    }
    return t
}

fun rewardText(r: Reward): String = when {
    r.item != null -> (if (r.count > 1) "${r.count} × " else "") + r.item!!.label()
    r.chest != null -> tr("reward.chest", r.chest!!.label()) + (r.chestSize?.let { " (${it.name})" } ?: "")
    r.cosmetic != null -> r.cosmetic!!.label()
    else -> ""
}

// ---------------------------------------------------------------- Alliance

class AllianceDialog(game: RuinbornGame) : GameDialog(game, tr("alliance.title")) {
    private var tab = "overview"
    private var detail: AllianceDetailDto? = null
    private var requests: List<AllianceRequestDto> = emptyList()
    private var helps: List<HelpRequestDto> = emptyList()
    private var rallies: List<RallyDto> = emptyList()
    private var gifts: List<GiftDto> = emptyList()
    private var search: List<AllianceSummaryDto> = emptyList()
    private var query = ""
    private var version = -1

    override fun onState(s: GameState) {
        if (s.allianceVersion != version) {
            version = s.allianceVersion
            reload()
        }
        super.onState(s)
    }

    private fun reload() {
        val a = state.player?.alliance
        if (a == null) {
            game.client.load({ alliances(query) }) { r -> search = r.valueOrNull ?: emptyList(); refresh() }
            return
        }
        game.client.load({ alliance(a.id) }) { r -> detail = r.valueOrNull; refresh() }
        if (a.rank != AllianceRank.MEMBER) game.client.load({ allianceRequests() }) { r -> requests = r.valueOrNull ?: emptyList(); refresh() }
        game.client.load({ helpList() }) { r -> helps = r.valueOrNull ?: emptyList(); refresh() }
        game.client.load({ rallies() }) { r -> rallies = r.valueOrNull ?: emptyList(); refresh() }
        game.client.load({ gifts() }) { r -> gifts = r.valueOrNull ?: emptyList(); refresh() }
    }

    override fun build() {
        if (version < 0) {
            version = state.allianceVersion
            reload()
        }
        val p = state.player ?: return
        val a = p.alliance
        if (a == null) {
            buildNoAlliance(p); return
        }
        titleLabel.setText("[${a.tag}] ${a.name}")
        val opts = mutableListOf("overview" to tr("alliance.tab.overview"), "members" to tr("alliance.tab.members"),
            "help" to tr("alliance.tab.help") + if (helps.isNotEmpty()) " (${helps.size})" else "",
            "rallies" to tr("alliance.tab.rallies") + if (rallies.isNotEmpty()) " (${rallies.size})" else "",
            "gifts" to tr("alliance.tab.gifts") + if (gifts.isNotEmpty()) " (${gifts.size})" else "")
        if (a.rank != AllianceRank.MEMBER) opts.add(2, "requests" to tr("alliance.tab.requests") + if (requests.isNotEmpty()) " (${requests.size})" else "")
        content.add(tabs(opts, tab) { tab = it; refresh() }).row()
        when (tab) {
            "overview" -> overview(a.rank)
            "members" -> members(p.playerId, a.rank)
            "requests" -> requests()
            "help" -> help()
            "rallies" -> rallies(p.playerId)
            "gifts" -> gifts()
        }
    }

    private fun buildNoAlliance(p: bayern.kickner.ruinborn.shared.dto.PlayerState) {
        val rules = state.rules ?: return
        hint(tr("alliance.none"))
        if (p.allianceBlockUntil > game.client.api.serverTime.now()) hint(tr("alliance.blocked", Fmt.dateTime(p.allianceBlockUntil)))
        val field = TextField(query, ui.skin).apply { messageText = tr("alliance.search") }
        val row = Table()
        row.add(field).growX().height(64f).padRight(8f)
        row.add(ui.button(tr("alliance.searchButton"), "secondary") {
            query = field.text.trim()
            game.client.load({ alliances(query) }) { r -> search = r.valueOrNull ?: emptyList(); refresh() }
        }).height(64f)
        content.add(row).row()
        if (search.isEmpty()) empty(tr("alliance.noneFound"))
        search.forEach { s ->
            val r = ui.row()
            val info = Table()
            info.add(ui.label("[${s.tag}] ${s.name}", "bold", wrap = true)).growX().left().row()
            info.add(ui.label(tr("alliance.summary", s.members, rules.balance.alliance.maxMembers, Fmt.short(s.power), s.leaderName, s.joinMode.label()), "small", wrap = true)).growX().left()
            r.add(info).growX()
            val text = if (s.requested) tr("alliance.requested") else if (s.joinMode == JoinMode.OPEN) tr("alliance.join") else tr("alliance.request")
            r.add(ui.button(text, enabled = s.requested.not()) { game.client.command({ joinAlliance(s.id) }) { reload() } }).width(170f).height(62f)
            content.add(r).row()
        }
        section(tr("alliance.create"))
        if (p.hq() < rules.balance.alliance.foundMinHq) {
            hint(tr("alliance.createNeedHq", rules.balance.alliance.foundMinHq)); return
        }
        val name = TextField("", ui.skin).apply { messageText = tr("alliance.name"); maxLength = rules.balance.alliance.nameMaxLength }
        val tag = TextField("", ui.skin).apply { messageText = tr("alliance.tag"); maxLength = rules.balance.alliance.tagLength }
        val desc = TextField("", ui.skin).apply { messageText = tr("alliance.description"); maxLength = rules.balance.alliance.descriptionMaxLength }
        var mode = JoinMode.OPEN
        content.add(name).height(64f).row()
        content.add(tag).height(64f).row()
        content.add(desc).height(64f).row()
        content.add(tabs(JoinMode.entries.map { it.name to it.label() }, mode.name) { mode = JoinMode.valueOf(it) }).row()
        content.add(ui.button(tr("alliance.createButton")) {
            val n = name.text.trim()
            val t = tag.text.trim().uppercase()
            val err = Validation.allianceName(rules.balance, n) ?: Validation.allianceTag(rules.balance, t)
            if (err != null) game.client.onToast(err)
            else game.client.command({ createAlliance(AllianceCreateRequest(n, t, mode, desc.text.trim())) })
        }).height(74f).row()
    }

    private fun overview(rank: AllianceRank) {
        val d = detail ?: return hint(tr("common.loading"))
        content.add(ui.label(d.description.ifBlank { tr("alliance.noDescription") }, wrap = true)).row()
        hint(tr("alliance.info", d.summary.members, Fmt.num(d.summary.power), d.summary.joinMode.label(), d.summary.leaderName))
        if (rank != AllianceRank.MEMBER) {
            section(tr("alliance.settings"))
            val desc = TextField(d.description, ui.skin).apply { maxLength = 500 }
            var mode = d.summary.joinMode
            content.add(desc).height(64f).row()
            content.add(tabs(JoinMode.entries.map { it.name to it.label() }, mode.name) { mode = JoinMode.valueOf(it) }).row()
            content.add(ui.button(tr("common.save"), "secondary") { game.client.command({ allianceSettings(AllianceSettingsRequest(mode, desc.text)) }) }).height(64f).row()
        }
        val alone = d.members.size <= 1
        if (rank == AllianceRank.LEADER && alone) {
            content.add(ui.button(tr("alliance.disband"), "danger") {
                ConfirmDialog(game, tr("alliance.disband"), tr("alliance.disbandConfirm")) { game.client.command({ disband() }) }.show(stage)
            }).height(64f).padTop(20f).row()
        } else if (rank != AllianceRank.LEADER || alone) {
            content.add(ui.button(tr("alliance.leave"), "danger") {
                ConfirmDialog(game, tr("alliance.leave"), tr("alliance.leaveConfirm")) { game.client.command({ leaveAlliance() }) }.show(stage)
            }).height(64f).padTop(20f).row()
        } else hint(tr("alliance.leaderLeave"))
    }

    private fun members(me: Long, rank: AllianceRank) {
        val d = detail ?: return hint(tr("common.loading"))
        d.members.forEach { m ->
            val r = ui.row()
            r.add(ui.label("${m.name}", "bold").apply { color = SpriteMap.frameColor(m.frame) }).width(200f).left()
            r.add(ui.label(tr("alliance.member", m.rank.label(), m.hqLevel, Fmt.short(m.power)), "small", wrap = true)).growX().left()
            if (m.playerId != me) {
                val actions = mutableListOf<Pair<String, String>>()
                if (rank == AllianceRank.LEADER) {
                    if (m.rank == AllianceRank.MEMBER) actions += "promote" to tr("alliance.promote")
                    if (m.rank == AllianceRank.OFFICER) actions += "demote" to tr("alliance.demote")
                    actions += "make-leader" to tr("alliance.makeLeader")
                }
                if (rank == AllianceRank.LEADER || (rank == AllianceRank.OFFICER && m.rank == AllianceRank.MEMBER)) actions += "kick" to tr("alliance.kick")
                val col = Table()
                actions.forEach { (a, text) ->
                    col.add(ui.button(text, if (a == "kick") "danger" else "secondary") {
                        ConfirmDialog(game, text, tr("alliance.memberConfirm", text, m.name)) { game.client.command({ manageMember(m.playerId, a) }) { reload() } }.show(stage)
                    }).height(50f).growX().row()
                }
                if (actions.isNotEmpty()) r.add(col).width(190f)
            }
            content.add(r).row()
        }
    }

    private fun requests() {
        if (requests.isEmpty()) return empty(tr("alliance.noRequests"))
        requests.forEach { q ->
            val r = ui.row()
            r.add(ui.label(tr("alliance.requestRow", q.name, q.hqLevel, Fmt.short(q.power)), "small", wrap = true)).growX().left()
            r.add(ui.button(tr("alliance.accept")) { game.client.command({ answerRequest(q.playerId, true) }) { reload() } }).height(56f).padRight(6f)
            r.add(ui.button(tr("alliance.reject"), "secondary") { game.client.command({ answerRequest(q.playerId, false) }) { reload() } }).height(56f)
            content.add(r).row()
        }
    }

    private fun help() {
        if (helps.isEmpty()) empty(tr("alliance.noHelp"))
        else content.add(ui.button(tr("alliance.helpAll", helps.size)) { game.client.command({ helpAll() }) { reload() } }).height(74f).row()
        helps.forEach { h ->
            val r = ui.row()
            r.add(ui.label(tr("alliance.helpRow", h.playerName, h.kind.label(), h.helpCount, h.helpMax), "small", wrap = true)).growX().left()
            content.add(r).row()
        }
    }

    private fun rallies(me: Long) {
        if (rallies.isEmpty()) return empty(tr("alliance.noRallies"))
        rallies.forEach { r ->
            val row = ui.row()
            val info = Table()
            val target = if (r.targetKind == MapObjectKind.NEST) tr("tile.nest", r.targetLevel) else r.targetName
            info.add(ui.label(tr("rally.row", r.leaderName, target, r.x, r.y), "bold", wrap = true)).growX().left().row()
            val time = if (r.state == RallyState.WAITING) tr("rally.launchAt", Fmt.time(r.launchAt)) else tr("rally.arriveAt", Fmt.time(r.arriveAt ?: 0))
            info.add(ui.label(time + " · " + tr("rally.participants", r.participants.size, 1 + (state.rules?.balance?.rally?.maxJoiners ?: 4), Fmt.num(r.participants.sumOf { it.units })), "small", wrap = true)).growX().left().row()
            row.add(info).growX()
            val mine = r.participants.any { it.playerId == me }
            when {
                r.leaderId == me && r.state == RallyState.WAITING ->
                    row.add(ui.button(tr("rally.cancel"), "danger") { game.client.command({ cancelRally(r.id) }) { reload() } }).height(56f)
                mine.not() && r.state == RallyState.WAITING -> {
                    row.add(ui.button(tr("rally.join")) {
                        game.client.load({ profile(r.leaderId) }) { pr ->
                            val base = (pr as? ApiResult.Ok)?.value?.base ?: return@load
                            MarchDialog(game, MarchMode.RallyJoin(r.id, base.x, base.y), r.x, r.y).show(stage)
                        }
                    }).height(56f)
                }
                else -> {}
            }
            content.add(row).row()
        }
    }

    private fun gifts() {
        if (gifts.isEmpty()) return empty(tr("alliance.noGifts"))
        gifts.forEach { g ->
            val r = ui.row()
            r.add(ui.icon(IconSprite.GIFT, 44f)).size(44f).padRight(10f)
            r.add(ui.label(tr("alliance.gift", g.level, Fmt.dateTime(g.expiresAt)), "small", wrap = true)).growX().left()
            r.add(ui.button(tr("common.claim")) { game.client.command({ claimGift(g.id) }) { reload() } }).height(56f)
            content.add(r).row()
        }
    }
}

// ---------------------------------------------------------------- Chat

class ChatDialog(game: RuinbornGame) : GameDialog(game, tr("chat.title")) {
    private var channel = "world"
    private val input = TextField("", ui.skin).apply { messageText = tr("chat.placeholder"); maxLength = 300 }
    private val systemLines = mutableListOf<String>()
    private var lastCount = -1

    override fun build() {
        val p = state.player ?: return
        val opts = mutableListOf("world" to tr("chat.world"))
        if (p.alliance != null) opts += "alliance" to tr("chat.alliance")
        if (channel == "alliance" && p.alliance == null) channel = "world"
        content.add(tabs(opts, channel) { channel = it; lastCount = -1; refresh() }).row()
        val messages = state.chat[channel].orEmpty()
        content.add(ui.button(tr("chat.older"), "link") { game.io.launch { game.client.loadOlderChat(channel) } }).left().row()
        if (messages.isEmpty()) empty(tr("chat.empty"))
        messages.forEach { m -> content.add(message(m, p.role)).row() }
        systemLines.forEach { content.add(ui.label(it, "accent", wrap = true)).row() }
        val row = Table()
        row.add(input).growX().height(66f).padRight(8f)
        row.add(ui.button(tr("chat.send")) { send() }).height(66f)
        content.add(row).row()
        input.setTextFieldListener { _, c -> if (c == '\r' || c == '\n') send() }
        if (messages.size != lastCount) {
            lastCount = messages.size
            // Scroll to the newest message
            com.badlogic.gdx.Gdx.app.postRunnable { scrollToEnd() }
        }
        stage?.keyboardFocus = input
    }

    private fun message(m: ChatMessageDto, role: Role): Table {
        val t = ui.row(8f)
        val head = Table()
        head.add(ui.label(m.senderName, "small").apply { color = if (m.system) Palette.accent else SpriteMap.frameColor(m.frame) }).left()
        head.add(ui.label(Fmt.time(m.createdAt), "muted")).expandX().right()
        t.add(head).growX().row()
        t.add(ui.label(m.text, if (m.deleted) "muted" else "default", wrap = true)).growX().left()
        if (m.system.not() && m.deleted.not()) {
            t.touchable = Touchable.enabled
            t.onClick { ChatMessageMenu(game, m, role).show(stage) }
        }
        return t
    }

    private fun send() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        input.text = ""
        game.client.command({ postChat(channel, text) }) { r ->
            (r as? ApiResult.Ok)?.value?.system?.let { systemLines += it; refresh() }
        }
    }

    private fun scrollToEnd() {
        val sp = content.parent as? com.badlogic.gdx.scenes.scene2d.ui.ScrollPane ?: return
        sp.layout()
        sp.scrollPercentY = 1f
    }
}

/** Long press or tap on a message: report, for admins also delete. */
class ChatMessageMenu(game: RuinbornGame, private val m: ChatMessageDto, private val role: Role) : GameDialog(game, m.senderName) {
    override val rebuildOnState = false
    override fun build() {
        content.add(ui.label(m.text, wrap = true)).row()
        content.add(ui.button(tr("chat.report"), "secondary") { game.client.command({ reportMessage(m.id) }) { close(); game.client.onToast(tr("chat.reported")) } }).height(64f).row()
        if (role == Role.ADMIN) content.add(ui.button(tr("chat.delete"), "danger") { game.client.command({ deleteMessage(m.id) }) { close() } }).height(64f).row()
        m.senderId?.let { id -> content.add(ui.button(tr("tile.profile"), "secondary") { val s = stage; close(); ProfileDialog(game, id).show(s) }).height(64f).row() }
    }
}

// ---------------------------------------------------------------- Reports

class ReportsDialog(game: RuinbornGame) : GameDialog(game, tr("reports.title")) {
    private var list: List<ReportDto>? = null
    private var version = -1

    override fun onState(s: GameState) {
        if (s.reportVersion != version) {
            version = s.reportVersion
            load()
        }
        super.onState(s)
    }

    private fun load() = game.client.load({ reports() }) { r -> list = r.valueOrNull ?: list; refresh() }

    override fun build() {
        if (version < 0) {
            version = state.reportVersion
            load()
        }
        val l = list ?: return hint(tr("common.loading"))
        if (l.isEmpty()) return empty(tr("reports.empty"))
        if (l.any { it.read.not() }) content.add(ui.button(tr("reports.readAll"), "secondary") {
            game.client.command({ readAllReports() }) { load() }
        }).height(56f).row()
        l.forEach { r ->
            val row = ui.row()
            row.add(ui.icon(reportIcon(r), 40f)).size(40f).padRight(10f)
            val info = Table()
            info.add(ui.label(r.title, if (r.read) "small" else "bold", wrap = true)).growX().left().row()
            info.add(ui.label(Fmt.dateTime(r.createdAt), "muted")).left()
            row.add(info).growX()
            row.touchable = Touchable.enabled
            row.onClick { ReportDetailDialog(game, r.id) { load() }.show(stage) }
            content.add(row).row()
        }
        content.add(ui.button(tr("reports.more"), "link") {
            val oldest = l.lastOrNull()?.id ?: return@button
            game.client.load({ reports(oldest) }) { res -> list = l + (res.valueOrNull ?: emptyList()); refresh() }
        }).row()
    }

    private fun reportIcon(r: ReportDto): IconSprite = when (r.kind) {
        bayern.kickner.ruinborn.shared.model.ReportKind.BATTLE -> IconSprite.SWORD
        bayern.kickner.ruinborn.shared.model.ReportKind.SCOUT -> IconSprite.SCOUT
        bayern.kickner.ruinborn.shared.model.ReportKind.GATHER -> IconSprite.CRATE
        bayern.kickner.ruinborn.shared.model.ReportKind.SYSTEM -> IconSprite.REPORT
    }
}

class ReportDetailDialog(game: RuinbornGame, private val id: Long, private val onChanged: () -> Unit) : GameDialog(game, tr("reports.title")) {
    override val rebuildOnState = false
    private var report: ReportDto? = null

    override fun build() {
        if (report == null) {
            game.client.load({ report(id) }) { r -> report = r.valueOrNull; if (report != null) { refresh(); onChanged() } }
            return hint(tr("common.loading"))
        }
        val r = report!!
        titleLabel.setText(r.title)
        hint(Fmt.dateTime(r.createdAt))
        when (val pl = r.payload) {
            is BattleReport -> battle(pl)
            is ScoutReport -> scout(pl)
            is GatherReport -> content.add(ui.label(tr("report.gather", Fmt.num(pl.amount), pl.resource.label(), pl.x, pl.y), wrap = true)).row()
            is SystemReport -> content.add(ui.label(pl.text, wrap = true)).row()
            null -> {}
        }
        val buttons = Table()
        val coords = when (val pl = r.payload) {
            is BattleReport -> pl.x to pl.y
            is ScoutReport -> pl.x to pl.y
            is GatherReport -> pl.x to pl.y
            else -> null
        }
        if (coords != null) buttons.add(ui.button(tr("march.showOnMap"), "secondary") {
            game.openDialogs.toList().forEach { it.close() }
            game.showMap()
            game.mapScreen().focus(coords.first, coords.second)
        }).growX().height(62f).padRight(8f)
        buttons.add(ui.button(tr("reports.delete"), "danger") { game.client.command({ deleteReport(id) }) { close(); onChanged() } }).growX().height(62f)
        content.add(buttons).row()
    }

    private fun battle(b: BattleReport) {
        content.add(ui.label(if (b.won) tr("report.won") else tr("report.lost"), "title").apply { color = if (b.won) Palette.good else Palette.bad }).row()
        hint(tr("report.battleInfo", b.x, b.y, b.rounds, if (b.fought) "" else tr("report.noFight")))
        section(tr("report.attackers"))
        b.attackers.forEach { participant(it) }
        section(tr("report.defenders"))
        b.defenders.forEach { participant(it) }
        if (b.loot.total > 0) {
            section(tr("report.loot")); content.add(ui.cost(b.loot)).left().row()
        }
        if (b.rewards.total > 0) {
            section(tr("report.rewards")); content.add(ui.cost(b.rewards)).left().row()
        }
        if (b.drops.isNotEmpty()) hint(tr("report.drops", b.drops.joinToString { "${it.count} × ${it.item.label()}" }))
    }

    private fun participant(p: BattleParticipant) {
        val t = ui.row(8f)
        t.add(ui.label(p.name + (p.hero?.let { " · ${it.label()} ${p.heroLevel}" } ?: "") + if (p.reinforcement) " (${tr("report.reinforcement")})" else "", "bold", wrap = true)).colspan(5).growX().left().row()
        if (p.stacks.isEmpty()) t.add(ui.label(tr("report.noTroops"), "muted")).colspan(5).left().row()
        else {
            listOf(tr("report.col.unit"), tr("report.col.before"), tr("report.col.wounded"), tr("report.col.dead"), tr("report.col.survived")).forEach {
                t.add(ui.label(it, "muted")).left().padRight(8f)
            }
            t.row()
            p.stacks.forEach { s ->
                t.add(ui.label(s.type?.let { "${it.label()} T${s.tier}" } ?: tr("report.zombieStack", s.tier), "small")).left().padRight(8f)
                t.add(ui.label(Fmt.num(s.before), "small")).left()
                t.add(ui.label(Fmt.num(s.wounded), "small")).left()
                t.add(ui.label(Fmt.num(s.dead), "bad")).left()
                t.add(ui.label(Fmt.num(s.survived), "good")).left().row()
            }
        }
        if (p.heroXp > 0) t.add(ui.label(tr("report.heroXp", Fmt.num(p.heroXp)), "accent")).colspan(5).left().row()
        content.add(t).row()
    }

    private fun scout(s: ScoutReport) {
        content.add(ui.label(tr("report.scoutOf", s.targetName, s.x, s.y), "bold", wrap = true)).row()
        s.resources.forEach { r ->
            val row = Table()
            row.add(ui.resourceIcon(r.resource, 28f)).size(28f).padRight(8f)
            row.add(ui.label(tr("report.scoutResource", Fmt.num(r.amount), Fmt.num(r.plunderable)), "small")).left()
            content.add(row).left().row()
        }
        section(tr("report.troopsHome"))
        if (s.troopsHome.isEmpty()) hint(tr("report.noTroops"))
        s.troopsHome.forEach { t -> content.add(ui.label("${Fmt.num(t.count)} ${t.type.label()} T${t.tier}", "small")).left().row() }
        hint(tr("report.scoutMisc", s.wallLevel, Fmt.num(s.reinforcementsTotal), s.defenseHero?.let { "${it.label()} ${s.defenseHeroLevel}" } ?: "–"))
    }
}

// ---------------------------------------------------------------- Tasks and achievements

class TasksDialog(game: RuinbornGame) : GameDialog(game, tr("tasks.title")) {
    private var tab = "daily"

    override fun build() {
        val p = state.player ?: return
        val rules = state.rules ?: return
        content.add(tabs(listOf("daily" to tr("tasks.daily"), "achievements" to tr("tasks.achievements")), tab) { tab = it; refresh() }).row()
        if (tab == "daily") {
            hint(tr("tasks.reset", Fmt.dateTime(p.nextDailyResetAt)))
            p.daily.forEach { d ->
                val row = ui.row()
                val info = Table()
                info.add(ui.label(tr("daily.${d.task.name}"), "bold")).left().row()
                info.add(ui.label(tr("daily.desc.${d.task.name}", Fmt.num(d.target)), "small", wrap = true)).growX().left().row()
                info.add(ui.bar({ d.progress.toFloat() / d.target.coerceAtLeast(1) })).growX().height(10f).row()
                info.add(ui.label("${Fmt.num(d.progress)} / ${Fmt.num(d.target)} · " + rules.balance.daily.tasks.getValue(d.task).rewards.joinToString { rewardText(it) }, "muted", wrap = true)).growX().left()
                row.add(info).growX()
                val ready = d.claimed.not() && d.progress >= d.target
                row.add(ui.button(if (d.claimed) tr("common.claimed") else tr("common.claim"), enabled = ready) {
                    game.client.command({ claimDaily(d.task) })
                }).width(160f).height(60f)
                content.add(row).row()
            }
        } else {
            p.achievements.forEach { a ->
                val cfg = rules.balance.achievements.first { it.id == a.id }
                val row = ui.row()
                val info = Table()
                info.add(ui.label(tr("achievement.${a.id.name}"), if (a.completed) "bold" else "default", wrap = true)).growX().left().row()
                info.add(ui.label(cfg.rewards.joinToString { rewardText(it) }, "muted", wrap = true)).growX().left().row()
                if (a.completed.not()) info.add(ui.bar({ a.progress.toFloat() / a.target.coerceAtLeast(1) }, Palette.info)).growX().height(8f).row()
                row.add(info).growX()
                row.add(ui.button(if (a.claimed) tr("common.claimed") else tr("common.claim"), enabled = a.completed && a.claimed.not()) {
                    game.client.command({ claimAchievement(a.id) })
                }).width(160f).height(60f)
                content.add(row).row()
            }
        }
    }
}

// ---------------------------------------------------------------- Rankings

class RankingsDialog(game: RuinbornGame) : GameDialog(game, tr("rankings.title")) {
    override val rebuildOnState = false
    private var kind = "player-power"
    private var data: RankingDto? = null

    override fun build() {
        content.add(tabs(listOf("player-power" to tr("rankings.player"), "alliance-power" to tr("rankings.alliance"), "zombies" to tr("rankings.zombies")), kind) {
            kind = it; data = null; refresh()
        }).row()
        val d = data
        if (d == null || d.kind != kind) {
            game.client.load({ ranking(kind) }) { r -> data = r.valueOrNull; refresh() }
            return hint(tr("common.loading"))
        }
        d.own?.let { content.add(ui.label(tr("rankings.own", it.rank, Fmt.num(it.value)), "accent")).row() }
        d.entries.forEach { e ->
            val row = ui.row(8f)
            row.add(ui.label("${e.rank}.", "bold")).width(70f).left()
            row.add(ui.label((e.allianceTag?.takeIf { kind != "alliance-power" }?.let { "[$it] " } ?: "") + e.name, wrap = true).apply {
                e.frame?.let { color = SpriteMap.frameColor(it) }
            }).growX().left()
            row.add(ui.label(Fmt.num(e.value), "bold")).right()
            if (kind != "alliance-power") {
                row.touchable = Touchable.enabled
                row.onClick { ProfileDialog(game, e.id).show(stage) }
            }
            content.add(row).row()
        }
    }
}

// ---------------------------------------------------------------- Profile and cosmetics

class ProfileDialog(game: RuinbornGame, private val playerId: Long?) : GameDialog(game, tr("profile.title")) {
    private var profile: PlayerProfileDto? = null

    override fun build() {
        val p = state.player ?: return
        val id = playerId ?: p.playerId
        if (profile?.id != id) {
            game.client.load({ profile(id) }) { r -> profile = r.valueOrNull; refresh() }
        }
        val pr = profile
        if (pr != null) {
            val head = ui.row()
            head.add(ui.icon(IconSprite.HERO_RHEA, 72f).apply { color = SpriteMap.frameColor(pr.frame) }).size(72f).padRight(12f)
            val info = Table()
            info.add(ui.label(pr.name, "large").apply { color = SpriteMap.frameColor(pr.frame) }).left().row()
            info.add(ui.label(tr("profile.info", pr.allianceTag?.let { "[$it] ${pr.allianceName}" } ?: tr("profile.noAlliance"), pr.hqLevel, Fmt.num(pr.power), Fmt.num(pr.zombiesDefeated)), "small", wrap = true)).growX().left()
            head.add(info).growX()
            content.add(head).row()
            pr.base?.let { b ->
                content.add(ui.button(tr("march.showOnMap"), "secondary") {
                    game.openDialogs.toList().forEach { it.close() }
                    game.showMap(); game.mapScreen().focus(b.x, b.y)
                }).height(60f).row()
            }
        } else hint(tr("common.loading"))
        if (id != p.playerId) return
        val unlocked = p.cosmetics.unlocked.toSet()
        section(tr("profile.skins"))
        cosmeticList(Cosmetic.entries.filter { it.isSkin }, unlocked, p.cosmetics.skin) { CosmeticEquipRequest(skin = it) }
        section(tr("profile.frames"))
        cosmeticList(Cosmetic.entries.filter { it.isSkin.not() }, unlocked, p.cosmetics.frame) { CosmeticEquipRequest(frame = it) }
    }

    private fun cosmeticList(list: List<Cosmetic>, unlocked: Set<Cosmetic>, current: Cosmetic, req: (Cosmetic) -> CosmeticEquipRequest) {
        val rules = state.rules ?: return
        list.forEach { c ->
            val row = ui.row(8f)
            row.add(ui.label(c.label(), "bold").apply { if (c.isSkin.not()) color = SpriteMap.frameColor(c) }).width(250f).left()
            when {
                c == current -> row.add(ui.label(tr("profile.equipped"), "good")).expandX().right()
                c in unlocked -> row.add(ui.button(tr("profile.equip"), "secondary") { game.client.command({ equip(req(c)) }) }).expandX().right().height(54f)
                else -> {
                    val source = rules.balance.achievements.firstOrNull { a -> a.rewards.any { it.cosmetic == c } }
                    row.add(ui.label(source?.let { tr("profile.unlockBy", tr("achievement.${it.id.name}")) } ?: tr("profile.locked"), "muted", wrap = true)).growX()
                }
            }
            content.add(row).row()
        }
    }
}
