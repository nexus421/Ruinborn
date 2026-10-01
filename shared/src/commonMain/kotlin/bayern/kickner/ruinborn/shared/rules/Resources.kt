package bayern.kickner.ruinborn.shared.rules

import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.model.Resource
import kotlin.math.max
import kotlin.math.min

/** Production rates per hour per resource (including bonuses and gameSpeed). */
data class Rates(val food: Double = 0.0, val wood: Double = 0.0, val steel: Double = 0.0) {
    operator fun get(r: Resource): Double = when (r) {
        Resource.FOOD -> food
        Resource.WOOD -> wood
        Resource.STEEL -> steel
    }

    companion object {
        val ZERO = Rates()
    }
}

/**
 * Stored amount B₀ per resource at time [at] (ms UTC).
 *
 * Deviation from the concept (see docs/DECISIONS.md): amounts are decimals. If fractions were dropped on
 * every materialization, frequent actions would lose the entire production
 * (100 per hour and one action every 30 s would yield 0). Values shown outside are always rounded down.
 */
data class Stock(val food: Double, val wood: Double, val steel: Double, val at: Long) {
    operator fun get(r: Resource): Double = when (r) {
        Resource.FOOD -> food
        Resource.WOOD -> wood
        Resource.STEEL -> steel
    }

    /** Integer (rounded down) amount per resource. */
    fun whole(r: Resource): Long = floorSafe(get(r))

    fun wholeCost(): Cost = Cost(whole(Resource.FOOD), whole(Resource.WOOD), whole(Resource.STEEL))

    /**
     * Amount at time [now]:
     * B(t) = B₀ if B₀ ≥ K, otherwise min(K, B₀ + r · Δt) with Δt in hours.
     * If [now] is before [at] (must not happen), the amount stays unchanged.
     */
    fun materialize(now: Long, rates: Rates, capacity: Long): Stock {
        if (now <= at) return this
        val hours = (now - at).toDouble() / MS_PER_HOUR
        fun one(b0: Double, r: Double): Double = if (b0 >= capacity) b0 else min(capacity.toDouble(), b0 + r * hours)
        return Stock(one(food, rates.food), one(wood, rates.wood), one(steel, rates.steel), now)
    }

    fun canAfford(cost: Cost): Boolean = Resource.entries.all { whole(it) >= cost[it] }

    /** Deducts costs (check [canAfford] first). */
    operator fun minus(cost: Cost): Stock = Stock(food - cost.food, wood - cost.wood, steel - cost.steel, at)

    /** Credit. May exceed the capacity (loot, rewards, chests). */
    operator fun plus(gain: Cost): Stock = Stock(food + gain.food, wood + gain.wood, steel + gain.steel, at)

    /** Lootable amount per resource: amount minus protected amount, at least 0. */
    fun plunderable(protectedAmount: Long): Cost =
        Cost(
            max(0L, whole(Resource.FOOD) - protectedAmount),
            max(0L, whole(Resource.WOOD) - protectedAmount),
            max(0L, whole(Resource.STEEL) - protectedAmount),
        )
}
