package bayern.kickner.ruinborn.shared

import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.rules.MS_PER_HOUR
import bayern.kickner.ruinborn.shared.rules.Rates
import bayern.kickner.ruinborn.shared.rules.Stock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourcesTest {
    private val rates = Rates(100.0, 100.0, 50.0)

    @Test
    fun productionStopsAtCapacity() {
        val s = Stock(4_950.0, 0.0, 0.0, 0).materialize(10 * MS_PER_HOUR, rates, 5_000)
        assertEquals(5_000L, s.whole(Resource.FOOD))
        assertEquals(1_000L, s.whole(Resource.WOOD))
        assertEquals(500L, s.whole(Resource.STEEL))
        assertEquals(10 * MS_PER_HOUR, s.at)
    }

    @Test
    fun stockAboveCapacityStaysUnchanged() {
        val s = Stock(8_000.0, 0.0, 0.0, 0).materialize(5 * MS_PER_HOUR, rates, 5_000)
        assertEquals(8_000L, s.whole(Resource.FOOD))
    }

    @Test
    fun frequentMaterializationLosesNothing() {
        // 120 materializations 30 s apart yield the same amount as a single one after 1 h.
        var s = Stock(0.0, 0.0, 0.0, 0)
        repeat(120) { i -> s = s.materialize((i + 1) * 30_000L, rates, 5_000) }
        assertEquals(100L, s.whole(Resource.FOOD))
        assertEquals(50L, s.whole(Resource.STEEL))
    }

    @Test
    fun chestsAndLootMayExceedCapacity() {
        val s = Stock(4_000.0, 0.0, 0.0, 0) + Cost(food = 50_000)
        assertEquals(54_000L, s.whole(Resource.FOOD))
    }

    @Test
    fun affordAndPay() {
        val s = Stock(500.9, 500.0, 250.0, 0)
        assertTrue(s.canAfford(Cost(500, 500, 250)))
        assertFalse(s.canAfford(Cost(501, 0, 0)))
        assertEquals(0L, (s - Cost(500, 500, 250)).whole(Resource.FOOD))
    }

    @Test
    fun plunderableIsStockMinusProtectedAtLeastZero() {
        val s = Stock(3_000.0, 1_000.0, 0.0, 0)
        assertEquals(Cost(1_750, 0, 0), s.plunderable(1_250))
    }

    @Test
    fun materializeIntoPastDoesNothing() {
        val s = Stock(10.0, 10.0, 10.0, 1_000)
        assertEquals(s, s.materialize(500, rates, 5_000))
    }
}
