package bayern.kickner.ruinborn.server.api

import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory token bucket (concept section 13): [capacity] requests, refilled with
 * [capacity] per [periodMs]. Old entries are removed occasionally on access.
 */
class RateLimiter(private val capacity: Int, private val periodMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private class Bucket(var tokens: Double, var at: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private var lastSweep = 0L

    fun tryAcquire(key: String): Boolean {
        val t = now()
        sweep(t)
        val b = buckets.computeIfAbsent(key) { Bucket(capacity.toDouble(), t) }
        synchronized(b) {
            val refill = (t - b.at).coerceAtLeast(0) * capacity.toDouble() / periodMs
            b.tokens = minOf(capacity.toDouble(), b.tokens + refill)
            b.at = t
            if (b.tokens < 1.0) return false
            b.tokens -= 1.0
            return true
        }
    }

    private fun sweep(t: Long) {
        if (t - lastSweep < 60_000) return
        lastSweep = t
        buckets.entries.removeIf { t - it.value.at > periodMs * 10 }
    }
}
