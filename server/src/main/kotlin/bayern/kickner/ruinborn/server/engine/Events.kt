package bayern.kickner.ruinborn.server.engine

import kotlinx.serialization.Serializable
import java.util.PriorityQueue

/** Types of scheduled events (concept section 13). SHIELD_END was added (system report "shield ends"). */
enum class EventType(val recurring: Boolean = false) {
    MARCH_ARRIVE, MARCH_HOME, GATHER_END, RALLY_LAUNCH, SHIELD_END,
    SPAWN(true), DAILY_RESET(true), BACKUP(true), CLEANUP(true),
}

@Serializable
data class EventPayload(val marchId: Long? = null, val rallyId: Long? = null, val playerId: Long? = null)

data class ScheduledEvent(val id: Long, val dueAt: Long, val type: EventType, val payload: EventPayload) {
    /** Simultaneous marches are processed in order of march ID. */
    val sortKey: Long get() = payload.marchId ?: payload.rallyId ?: 0L
}

/** In-memory mirror of the `scheduled_event` table, sorted by due time, then march ID, then ID. */
class EventQueue {
    private val queue = PriorityQueue(compareBy<ScheduledEvent>({ it.dueAt }, { it.sortKey }, { it.id }))

    fun add(e: ScheduledEvent) {
        queue.add(e)
    }

    fun remove(id: Long) {
        queue.removeIf { it.id == id }
    }

    fun peek(): ScheduledEvent? = queue.peek()
    fun poll(): ScheduledEvent? = queue.poll()
    fun clear() = queue.clear()
    val size: Int get() = queue.size
    fun all(): List<ScheduledEvent> = queue.toList().sortedWith(compareBy({ it.dueAt }, { it.sortKey }, { it.id }))
}
