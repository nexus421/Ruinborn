package bayern.kickner.ruinborn.client.dialog

import bayern.kickner.ruinborn.client.RuinbornGame
import bayern.kickner.ruinborn.client.ui.Fmt
import bayern.kickner.ruinborn.client.ui.label
import bayern.kickner.ruinborn.client.ui.tr
import bayern.kickner.ruinborn.shared.balance.Balance
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.rules.Rules
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table

/** Simple yes/no confirmation. */
class ConfirmDialog(game: RuinbornGame, title: String, private val text: String, private val yes: String = tr("common.yes"), private val onYes: () -> Unit) :
    GameDialog(game, title) {
    override val rebuildOnState = false
    override fun build() {
        content.add(ui.label(text, wrap = true)).row()
        val buttons = Table()
        buttons.add(ui.button(tr("common.no"), "secondary") { close() }).growX().height(70f).padRight(10f)
        buttons.add(ui.button(yes) { close(); onYes() }).growX().height(70f)
        content.add(buttons).row()
    }
}

/**
 * Actions that take longer than 1 h or target players require confirmation (concept section 12).
 */
fun RuinbornGame.confirmIf(stage: Stage, needed: Boolean, text: String, action: () -> Unit) {
    if (needed) ConfirmDialog(this, tr("confirm.title"), text, onYes = action).show(stage) else action()
}

/** Blocking update dialog on HTTP 426 with a download link. */
class UpdateDialog(game: RuinbornGame) : GameDialog(game, tr("update.title")) {
    override val rebuildOnState = false
    override fun build() {
        content.add(ui.label(tr("update.text"), wrap = true)).row()
        val url = state.version?.apkUrl
        if (url != null) content.add(ui.button(tr("update.download")) { Gdx.net.openURI(url) }).height(80f).row()
    }

    override fun close() {
        // Cannot be closed: the server no longer accepts this version.
    }
}

/** Opens a dialog via launch option (visual check via screenshot). */
fun openStartDialog(game: RuinbornGame, stage: Stage, name: String?) {
    val parts = name?.split(":").orEmpty()
    if (parts.size >= 3 && parts[0] == "tile") {
        TileInfoDialog(game, parts[1].toInt(), parts[2].toInt()).show(stage); return
    }
    if (parts.size >= 4 && parts[0] == "march") {
        MarchDialog(game, MarchMode.Normal(bayern.kickner.ruinborn.shared.model.MarchKind.valueOf(parts[3])), parts[1].toInt(), parts[2].toInt()).show(stage); return
    }
    if (name == "lastreport") {
        game.client.load({ reports() }) { r -> r.valueOrNull?.firstOrNull()?.let { ReportDetailDialog(game, it.id) {}.show(stage) } }
        return
    }
    val d: GameDialog = when (name) {
        "research" -> ResearchDialog(game)
        "heroes" -> HeroesDialog(game)
        "inventory" -> InventoryDialog(game)
        "alliance" -> AllianceDialog(game)
        "chat" -> ChatDialog(game)
        "reports" -> ReportsDialog(game)
        "tasks" -> TasksDialog(game)
        "rankings" -> RankingsDialog(game)
        "profile" -> ProfileDialog(game, null)
        "settings" -> SettingsDialog(game)
        "more" -> MoreDialog(game)
        "train" -> TrainDialog(game, BuildingType.BARRACKS)
        "hospital" -> HospitalDialog(game)
        "building" -> BuildingDialog(game, bayern.kickner.ruinborn.shared.model.Plot.HQ)
        "build-r4" -> BuildingDialog(game, bayern.kickner.ruinborn.shared.model.Plot.R4)
        else -> return
    }
    d.show(stage)
}

/** Description of a building's effect at a given level. */
fun effectText(rules: Rules, type: BuildingType, level: Int, research: Map<BonusKind, Double> = emptyMap()): String {
    if (level <= 0) return ""
    val b: Balance = rules.balance
    return when (type) {
        BuildingType.HQ -> tr("effect.HQ", level)
        BuildingType.WALL -> tr("effect.WALL", Fmt.percent(rules.wallDefenseBonus(level)))
        BuildingType.WAREHOUSE -> {
            val cap = rules.storageCapacity(level, research[BonusKind.STORAGE] ?: 0.0)
            tr("effect.WAREHOUSE", Fmt.num(cap), Fmt.num(rules.protectedAmount(cap)))
        }
        BuildingType.FARM, BuildingType.SAWMILL, BuildingType.STEEL_MILL -> {
            val res = rules.producedResource(type)!!
            val bonus = research[BonusKind.prod(res)] ?: 0.0
            tr("effect.PRODUCTION", Fmt.num((rules.productionPerHour(type, level, bonus) * rules.gameSpeed).toLong()), res.label())
        }
        BuildingType.BARRACKS, BuildingType.FACTORY, BuildingType.RANGE ->
            tr("effect.TRAINING", Fmt.num(rules.orderSize(type, level)), rules.maxTierFor(level), type.trains!!.label())
        BuildingType.HOSPITAL -> tr("effect.HOSPITAL", Fmt.num(rules.hospitalCapacity(level, research[BonusKind.HOSPITAL_CAP] ?: 0.0)))
        BuildingType.LAB -> tr("effect.LAB", ((level + 1) / 2).coerceAtMost(b.research.maxLevel))
        BuildingType.RALLY_POINT -> tr("effect.RALLY_POINT", Fmt.num(rules.marchSize(level, research[BonusKind.MARCH_SIZE] ?: 0.0)))
        BuildingType.ALLIANCE_CENTER -> tr("effect.ALLIANCE_CENTER", rules.helpMax(level), Fmt.num(rules.reinforceCapacity(level)))
    }
}

/** Bonus description, e.g. "+5 % Nahrungsproduktion". */
fun bonusText(effects: Map<BonusKind, Double>, factor: Int = 1): String =
    effects.entries.joinToString(", ") { (k, v) -> "+" + Fmt.percent(v * factor) + " " + k.label() }
