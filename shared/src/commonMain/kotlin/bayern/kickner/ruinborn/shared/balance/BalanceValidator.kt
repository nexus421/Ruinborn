package bayern.kickner.ruinborn.shared.balance

import bayern.kickner.ruinborn.shared.model.AchievementId
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.DailyTask
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.Tech
import bayern.kickner.ruinborn.shared.model.UnitType
import kotlinx.serialization.json.Json

/** Reading and writing `balance.json`. Unknown keys are an error so typos get noticed. */
object BalanceCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val lenient = Json(json) { ignoreUnknownKeys = true }

    fun decode(text: String): Balance = json.decodeFromString(Balance.serializer(), text)

    /** For the client: newer servers may send additional keys. */
    fun decodeLenient(text: String): Balance = lenient.decodeFromString(Balance.serializer(), text)
    fun encode(balance: Balance): String = json.encodeToString(Balance.serializer(), balance)

    /** Decodes and validates. Returns either the balance or all errors found. */
    fun load(text: String): Pair<Balance?, List<String>> {
        val balance = runCatching { decode(text) }.getOrElse { return null to listOf("balance.json unlesbar: ${it.message}") }
        val problems = balance.validate()
        return (if (problems.isEmpty()) balance else null) to problems
    }

    val default: Balance by lazy { decode(DefaultBalance.JSON) }
}

/**
 * Validates the balance completely: all maps contain every key, values are positive (0 only where
 * the game allows it, e.g. nest count in zone 1). Returns all problems as a list.
 */
fun Balance.validate(): List<String> {
    val p = mutableListOf<String>()
    fun pos(name: String, v: Double) { if (v.isNaN() || v <= 0.0) p += "$name muss > 0 sein (ist $v)" }
    fun pos(name: String, v: Long) { if (v <= 0L) p += "$name muss > 0 sein (ist $v)" }
    fun pos(name: String, v: Int) { if (v <= 0) p += "$name muss > 0 sein (ist $v)" }
    fun nonNeg(name: String, v: Double) { if (v.isNaN() || v < 0.0) p += "$name muss ≥ 0 sein (ist $v)" }
    fun share(name: String, v: Double) { if (v.isNaN() || v < 0.0 || v > 1.0) p += "$name muss zwischen 0 und 1 liegen (ist $v)" }
    fun cost(name: String, l: List<Long>) {
        if (l.size != 3) p += "$name braucht genau 3 Werte [N, H, S]" else l.forEachIndexed { i, v -> if (v < 0) p += "$name[$i] negativ" }
    }
    fun <K> complete(name: String, keys: Set<K>, all: Collection<K>) {
        val missing = all.filterNot { it in keys }
        if (missing.isNotEmpty()) p += "$name: fehlende Schlüssel $missing"
    }
    fun need(name: String, v: Any?) { if (v == null) p += "$name fehlt" }

    pos("version", version)

    // start
    start.buildings.forEach { (plot, lvl) -> pos("start.buildings.$plot", lvl) }
    start.resourceBuildings.forEach { (plot, t) ->
        if (plot.isResourcePlot.not() || t.isResource.not()) p += "start.resourceBuildings.$plot=$t ungültig"
    }
    nonNeg("start.food", start.food.toDouble()); nonNeg("start.wood", start.wood.toDouble()); nonNeg("start.steel", start.steel.toDouble())
    if (start.heroes.isEmpty()) p += "start.heroes darf nicht leer sein"
    if (start.spawnZone !in 1..3) p += "start.spawnZone muss 1 bis 3 sein"
    nonNeg("start.minBaseDistance", start.minBaseDistance)

    // buildings
    complete("buildings", buildings.keys, BuildingType.entries)
    buildings.forEach { (t, b) ->
        cost("buildings.$t.baseCost", b.baseCost)
        pos("buildings.$t.baseTimeSec", b.baseTimeSec); pos("buildings.$t.costGrowth", b.costGrowth)
        pos("buildings.$t.timeGrowth", b.timeGrowth); pos("buildings.$t.maxLevel", b.maxLevel); pos("buildings.$t.unlockHq", b.unlockHq)
        when (t) {
            BuildingType.FARM, BuildingType.SAWMILL, BuildingType.STEEL_MILL -> {
                need("buildings.$t.prodPerHour", b.prodPerHour); need("buildings.$t.prodGrowth", b.prodGrowth)
            }
            BuildingType.WAREHOUSE -> {
                need("buildings.$t.capacityBase", b.capacityBase); need("buildings.$t.capacityGrowth", b.capacityGrowth)
                need("buildings.$t.protectedShare", b.protectedShare)
                b.protectedShare?.let { share("buildings.$t.protectedShare", it) }
            }
            BuildingType.HOSPITAL -> {
                need("buildings.$t.capacityBase", b.capacityBase); need("buildings.$t.capacityGrowth", b.capacityGrowth)
            }
            BuildingType.WALL -> need("buildings.$t.defensePerLevel", b.defensePerLevel)
            BuildingType.BARRACKS, BuildingType.FACTORY, BuildingType.RANGE -> {
                need("buildings.$t.orderBase", b.orderBase); need("buildings.$t.orderPerLevel", b.orderPerLevel)
            }
            BuildingType.RALLY_POINT -> {
                need("buildings.$t.marchBase", b.marchBase); need("buildings.$t.marchPerLevel", b.marchPerLevel)
            }
            BuildingType.ALLIANCE_CENTER -> {
                need("buildings.$t.helpBase", b.helpBase); need("buildings.$t.helpLevelDivisor", b.helpLevelDivisor)
                need("buildings.$t.reinforceBase", b.reinforceBase); need("buildings.$t.reinforcePerLevel", b.reinforcePerLevel)
                b.helpLevelDivisor?.let { pos("buildings.$t.helpLevelDivisor", it) }
            }
            BuildingType.LAB -> {
                need("buildings.$t.labPerResearchLevel", b.labPerResearchLevel); need("buildings.$t.labOffset", b.labOffset)
            }
            BuildingType.HQ -> {}
        }
        listOfNotNull(b.prodPerHour, b.prodGrowth, b.capacityBase, b.capacityGrowth, b.defensePerLevel).forEach { pos("buildings.$t.effekt", it) }
    }
    complete("resourcePlots", resourcePlots.keys, Plot.entries.filter { it.isResourcePlot })
    resourcePlots.forEach { (plot, hq) -> pos("resourcePlots.$plot", hq); if (plot.isResourcePlot.not()) p += "resourcePlots.$plot ist kein Ressourcenplatz" }

    pos("timers.buildQueues", timers.buildQueues); share("timers.cancelRefundShare", timers.cancelRefundShare)

    // research
    cost("research.baseCost", research.baseCost)
    pos("research.costGrowth", research.costGrowth); pos("research.baseTimeSec", research.baseTimeSec)
    pos("research.timeGrowth", research.timeGrowth); pos("research.maxLevel", research.maxLevel); pos("research.unlockHq", research.unlockHq)
    complete("research.techs", research.techs.keys, Tech.entries)
    research.techs.forEach { (t, eff) -> if (eff.isEmpty()) p += "research.techs.$t ohne Effekt"; eff.forEach { (k, v) -> pos("research.techs.$t.$k", v) } }

    // units, tiers, hospital
    complete("units", units.keys, UnitType.entries)
    units.forEach { (t, u) ->
        pos("units.$t.atk", u.atk); pos("units.$t.def", u.def); pos("units.$t.hp", u.hp); pos("units.$t.load", u.load)
        pos("units.$t.secPerTile", u.secPerTile); pos("units.$t.trainSec", u.trainSec); cost("units.$t.cost", u.cost)
    }
    pos("tiers.maxTier", tiers.maxTier); pos("tiers.statGrowth", tiers.statGrowth); pos("tiers.loadGrowth", tiers.loadGrowth)
    pos("tiers.trainGrowth", tiers.trainGrowth)
    if (tiers.unlockLevels.size != tiers.maxTier) p += "tiers.unlockLevels braucht ${tiers.maxTier} Einträge"
    tiers.unlockLevels.forEachIndexed { i, v -> pos("tiers.unlockLevels[$i]", v) }
    share("hospital.healCostShare", hospital.healCostShare); share("hospital.healTimeShare", hospital.healTimeShare)
    pos("hospital.healTimeShare", hospital.healTimeShare)

    // heroes
    pos("heroes.maxLevel", heroes.maxLevel); pos("heroes.xpBase", heroes.xpBase); pos("heroes.xpGrowth", heroes.xpGrowth)
    pos("heroes.pvpXpFactor", heroes.pvpXpFactor)
    complete("heroes.heroes", heroes.heroes.keys, HeroId.entries)
    heroes.heroes.forEach { (h, hb) -> pos("heroes.$h.unlockHq", hb.unlockHq); hb.bonusPerLevel.forEach { (k, v) -> pos("heroes.$h.$k", v) } }

    // map
    pos("map.width", map.width); pos("map.height", map.height); pos("map.zone3MaxR", map.zone3MaxR); pos("map.zone2MaxR", map.zone2MaxR)
    if (map.zone3MaxR >= map.zone2MaxR) p += "map.zone3MaxR muss kleiner als map.zone2MaxR sein"
    if (map.centerX !in 0 until map.width || map.centerY !in 0 until map.height) p += "map.center außerhalb der Karte"

    // zombies, nests, fields
    with(zombies) {
        pos("zombies.minLevel", minLevel); if (maxLevel < minLevel) p += "zombies.maxLevel < minLevel"
        listOf(countBase, countGrowth, atkBase, defBase, hpBase, statGrowth, rewardBase, rewardGrowth, xpBase, xpGrowth).forEach { pos("zombies.wert", it) }
        share("zombies.steelShare", steelShare); share("zombies.dropChance", dropChance)
        if (drops.isEmpty() || drops.maxOf { it.maxLevel } < maxLevel) p += "zombies.drops muss alle Stufen bis $maxLevel abdecken"
    }
    with(nests) {
        pos("nests.minLevel", minLevel); if (maxLevel < minLevel) p += "nests.maxLevel < minLevel"
        listOf(countBase, countGrowth, atkBase, defBase, hpBase, statGrowth, rewardFoodWoodBase, rewardSteelBase, rewardGrowth, xpBase, xpGrowth)
            .forEach { pos("nests.wert", it) }
        pos("nests.speedupsPerLevel", speedupsPerLevel)
    }
    with(fields) {
        pos("fields.minLevel", minLevel); if (maxLevel < minLevel) p += "fields.maxLevel < minLevel"
        pos("fields.stockBase", stockBase); pos("fields.stockGrowth", stockGrowth); share("fields.steelShare", steelShare)
        pos("fields.steelShare", steelShare); pos("fields.rateBase", rateBase); nonNeg("fields.ratePerLevel", ratePerLevel)
    }

    // spawn
    pos("spawn.intervalSec", spawn.intervalSec); pos("spawn.maxAttempts", spawn.maxAttempts)
    complete("spawn.zones", spawn.zones.map { it.zone }.toSet(), listOf(1, 2, 3))
    spawn.zones.forEach { z ->
        fun range(name: String, r: SpawnRange, min: Int, max: Int) {
            if (r.count < 0) p += "spawn.zone${z.zone}.$name.count negativ"
            if (r.count > 0 && (r.minLevel < min || r.maxLevel > max || r.minLevel > r.maxLevel)) p += "spawn.zone${z.zone}.$name Stufenbereich ungültig"
        }
        range("zombies", z.zombies, zombies.minLevel, zombies.maxLevel)
        range("nests", z.nests, nests.minLevel, nests.maxLevel)
        range("fields", z.fields, fields.minLevel, fields.maxLevel)
    }
    complete("relocation.minHqByZone", relocation.minHqByZone.keys, listOf(1, 2, 3))

    pos("marches.maxScouts", marches.maxScouts); pos("marches.minUnits", marches.minUnits)

    // combat
    pos("combat.maxRounds", combat.maxRounds); pos("combat.counterMultiplier", combat.counterMultiplier)
    pos("combat.defenseConstant", combat.defenseConstant)
    complete("combat.counters", combat.counters.keys, UnitType.entries)
    if (combat.hospitalOrder.toSet() != UnitType.entries.toSet()) p += "combat.hospitalOrder muss jeden Truppentyp genau einmal enthalten"

    pos("scouting.foodCost", scouting.foodCost); pos("scouting.secPerTile", scouting.secPerTile)

    with(protection) {
        pos("protection.newbieHours", newbieHours); pos("protection.newbieEndsAtHq", newbieEndsAtHq)
        pos("protection.recoveryLosses", recoveryLosses); pos("protection.recoveryWindowHours", recoveryWindowHours)
        pos("protection.recoveryShieldHours", recoveryShieldHours); pos("protection.inactivityDays", inactivityDays)
    }
    pos("catchup.activeDays", catchup.activeDays); pos("catchup.levelsBelowMedian", catchup.levelsBelowMedian)
    catchup.bonuses.forEach { (k, v) -> pos("catchup.$k", v) }

    with(alliance) {
        listOf(maxMembers, foundMinHq, joinMinHq, nameMinLength, nameMaxLength, tagLength, descriptionMaxLength, helpsWithoutCenter)
            .forEach { pos("alliance.wert", it) }
        listOf(requestExpiryDays, joinBlockHours, leaderInactiveDays, helpMinSec, helpShare).forEach { pos("alliance.wert", it) }
        if (nameMinLength > nameMaxLength) p += "alliance.nameMinLength > nameMaxLength"
    }
    if (rally.waitMinutes.isEmpty()) p += "rally.waitMinutes leer"
    rally.waitMinutes.forEach { pos("rally.waitMinutes", it) }
    pos("rally.maxJoiners", rally.maxJoiners)
    pos("gifts.foodWoodPerLevel", gifts.foodWoodPerLevel); pos("gifts.itemCount", gifts.itemCount); pos("gifts.expiryDays", gifts.expiryDays)

    // items
    complete("items.speedups", items.speedups.keys, ItemId.entries.filter { it.isSpeedup })
    complete("items.chests", items.chests.keys, ItemId.entries.filter { it.isChest })
    complete("items.shields", items.shields.keys, ItemId.entries.filter { it.isShield })
    complete("items.heroBooks", items.heroBooks.keys, ItemId.entries.filter { it.isHeroBook })
    items.speedups.forEach { (k, v) -> pos("items.speedups.$k", v) }
    items.chests.forEach { (k, v) -> pos("items.chests.$k", v.amount) }
    items.shields.forEach { (k, v) -> pos("items.shields.$k", v) }
    items.heroBooks.forEach { (k, v) -> pos("items.heroBooks.$k", v) }

    // daily
    complete("daily.tasks", daily.tasks.keys, DailyTask.entries)
    daily.tasks.forEach { (k, v) -> pos("daily.tasks.$k.target", v.target); if (v.rewards.isEmpty()) p += "daily.tasks.$k ohne Belohnung" }
    daily.tasks[DailyTask.BONUS]?.let { if (it.target > DailyTask.entries.size - 1) p += "daily.tasks.BONUS.target zu groß" }
    if (daily.chestSizes.isEmpty() || daily.chestSizes.maxOf { it.maxHq } < (buildings[BuildingType.HQ]?.maxLevel ?: 0)) {
        p += "daily.chestSizes muss alle HQ-Stufen abdecken"
    }

    // achievements
    complete("achievements", achievements.map { it.id }.toSet(), AchievementId.entries)
    if (achievements.map { it.id }.toSet().size != achievements.size) p += "achievements enthält doppelte IDs"
    achievements.forEach { a -> pos("achievements.${a.id}.target", a.target); if (a.rewards.isEmpty()) p += "achievements.${a.id} ohne Belohnung" }

    // rewards
    (daily.tasks.values.flatMap { it.rewards } + achievements.flatMap { it.rewards }).forEach { r ->
        val kinds = listOfNotNull(r.item, r.chest, r.cosmetic).size
        if (kinds != 1) p += "Belohnung $r muss genau eines von item, chest, cosmetic haben"
        if (r.chestSize != null && r.chest == null) p += "Belohnung $r: chestSize ohne chest"
        pos("Belohnung.count", r.count)
    }

    if (cosmetics.defaultSkin.isSkin.not()) p += "cosmetics.defaultSkin ist kein Skin"
    if (cosmetics.defaultFrame.isSkin) p += "cosmetics.defaultFrame ist kein Rahmen"
    pos("chat.maxLength", chat.maxLength); pos("chat.minIntervalMs", chat.minIntervalMs); pos("chat.historyDays", chat.historyDays)
    pos("chat.pageSize", chat.pageSize)
    pos("reports.retentionDays", reports.retentionDays); pos("reports.pageSize", reports.pageSize)
    pos("rankings.topN", rankings.topN)
    listOf(rankings.powerPerBuildingLevel, rankings.powerPerResearchLevel, rankings.powerPerHeroLevel).forEach { nonNeg("rankings.wert", it.toDouble()) }

    return p
}
