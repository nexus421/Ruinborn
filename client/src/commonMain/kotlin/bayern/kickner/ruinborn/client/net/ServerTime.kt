package bayern.kickner.ruinborn.client.net

/**
 * Offset between server time (header `X-Server-Time`) and the local clock. Countdowns use [now] so a wrong
 * device clock does not matter (concept section 12).
 */
class ServerTime(private val localClock: () -> Long) {
    @kotlin.concurrent.Volatile
    var offsetMs: Long = 0
        private set

    @kotlin.concurrent.Volatile
    var synced: Boolean = false
        private set

    fun update(serverMs: Long) {
        offsetMs = serverMs - localClock()
        synced = true
    }

    fun now(): Long = localClock() + offsetMs
}

/** Retry after 1, 2, 5 and 10 s, then every 30 s (concept section 12). */
object Backoff {
    private val steps = longArrayOf(1_000, 2_000, 5_000, 10_000)
    const val STEADY_MS = 30_000L

    fun delayFor(attempt: Int): Long = if (attempt < steps.size) steps[attempt] else STEADY_MS
}
