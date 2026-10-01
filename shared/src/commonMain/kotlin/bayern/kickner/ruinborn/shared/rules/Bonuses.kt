package bayern.kickner.ruinborn.shared.rules

import bayern.kickner.ruinborn.shared.balance.Balance
import bayern.kickner.ruinborn.shared.model.BonusKind
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.Tech

/**
 * Sum of all bonuses per kind. Bonuses of the same kind are added, never multiplied (concept section 2):
 * research +20 % and hero +10 % result in +30 %.
 */
class Bonuses private constructor(private val values: Map<BonusKind, Double>) {
    operator fun get(kind: BonusKind): Double = values[kind] ?: 0.0

    operator fun plus(other: Bonuses): Bonuses {
        if (other.values.isEmpty()) return this
        if (values.isEmpty()) return other
        val m = values.toMutableMap()
        other.values.forEach { (k, v) -> m[k] = (m[k] ?: 0.0) + v }
        return Bonuses(m)
    }

    fun toMap(): Map<BonusKind, Double> = values

    override fun equals(other: Any?) = other is Bonuses && other.values == values
    override fun hashCode() = values.hashCode()
    override fun toString() = "Bonuses($values)"

    companion object {
        val NONE = Bonuses(emptyMap())

        fun of(map: Map<BonusKind, Double>) = Bonuses(map.filterValues { it != 0.0 })
        fun of(vararg pairs: Pair<BonusKind, Double>) = of(pairs.toMap())

        /** Bonuses from a player's research levels. */
        fun research(balance: Balance, levels: Map<Tech, Int>): Bonuses {
            val m = mutableMapOf<BonusKind, Double>()
            levels.forEach { (tech, level) ->
                if (level <= 0) return@forEach
                balance.research.techs[tech]?.forEach { (k, perLevel) -> m[k] = (m[k] ?: 0.0) + perLevel * level }
            }
            return of(m)
        }

        /** Bonus of a hero at its level. */
        fun hero(balance: Balance, hero: HeroId, level: Int): Bonuses {
            val per = balance.heroes.heroes[hero]?.bonusPerLevel ?: return NONE
            return of(per.mapValues { it.value * level })
        }

        /** Catch-up bonus, if active. */
        fun catchup(balance: Balance, active: Boolean): Bonuses = if (active) of(balance.catchup.bonuses) else NONE
    }
}
