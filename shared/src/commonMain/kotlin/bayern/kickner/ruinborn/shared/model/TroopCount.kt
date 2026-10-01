package bayern.kickner.ruinborn.shared.model

import kotlinx.serialization.Serializable

/** Number of units of one type and tier, e.g. `{"type":"INFANTRY","tier":1,"count":150}`. */
@Serializable
data class TroopCount(val type: UnitType, val tier: Int, val count: Int)

/** Merges equal (type, tier) entries and removes empty ones. Order: type, then tier. */
fun List<TroopCount>.normalized(): List<TroopCount> =
    groupBy { it.type to it.tier }
        .map { (k, v) -> TroopCount(k.first, k.second, v.sumOf { it.count }) }
        .filter { it.count > 0 }
        .sortedWith(compareBy({ it.type.ordinal }, { it.tier }))

val List<TroopCount>.totalUnits: Int get() = sumOf { it.count }
