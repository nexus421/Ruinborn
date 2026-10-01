package bayern.kickner.ruinborn.client.state

import bayern.kickner.ruinborn.shared.balance.Balance
import bayern.kickner.ruinborn.shared.dto.ChatMessageDto
import bayern.kickner.ruinborn.shared.dto.MapObjectDto
import bayern.kickner.ruinborn.shared.dto.MapSnapshot
import bayern.kickner.ruinborn.shared.dto.MarchDto
import bayern.kickner.ruinborn.shared.dto.PlayerState
import bayern.kickner.ruinborn.shared.dto.VersionInfo
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.rules.Rules

/** Map data on the client, quick to update by ID. */
data class MapData(
    val width: Int = 100,
    val height: Int = 100,
    val objects: Map<Long, MapObjectDto> = emptyMap(),
    val marches: Map<Long, MarchDto> = emptyMap(),
) {
    /** Object on a tile (each tile holds at most one object). */
    val byTile: Map<Pair<Int, Int>, MapObjectDto> by lazy { objects.values.associateBy { it.x to it.y } }

    fun apply(e: WsEvent.MapChanged): MapData = copy(
        objects = (objects - e.removedObjectIds.toSet()) + e.objects.associateBy { it.id },
        marches = (marches - e.removedMarchIds.toSet()) + e.marches.associateBy { it.id },
    )

    companion object {
        fun of(s: MapSnapshot) = MapData(s.width, s.height, s.objects.associateBy { it.id }, s.marches.associateBy { it.id })
    }
}

/**
 * Immutable app state. Only replaced on the render thread (concept section 12).
 */
data class GameState(
    val loggedIn: Boolean = false,
    val connected: Boolean = false,
    val player: PlayerState? = null,
    val map: MapData = MapData(),
    val balance: Balance? = null,
    val rules: Rules? = null,
    val chat: Map<String, List<ChatMessageDto>> = emptyMap(),
    val version: VersionInfo? = null,
    val outdated: Boolean = false,
    /** Counts alliance changes. Open alliance dialogs reload when it changes. */
    val allianceVersion: Int = 0,
    val reportVersion: Int = 0,
    val notices: List<String> = emptyList(),
) {
    /** Own marches come completely from the game state, other players' marches from the map. */
    val allMarches: List<MarchDto>
        get() {
            val own = player?.marches.orEmpty()
            val ownIds = own.map { it.id }.toSet()
            return own + map.marches.values.filter { it.id !in ownIds }
        }
}

/** Holds the state and notifies observers. Not thread-safe: use only from the render thread. */
class GameStore {
    var state: GameState = GameState()
        private set

    private val listeners = mutableListOf<(GameState) -> Unit>()

    fun update(change: (GameState) -> GameState) {
        val next = change(state)
        if (next == state) return
        state = next
        listeners.toList().forEach { it(next) }
    }

    fun listen(l: (GameState) -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }
}

/** Merges chat history: append new messages, replace deletions, sort by ID. */
fun mergeChat(existing: List<ChatMessageDto>, incoming: List<ChatMessageDto>, keep: Int = 200): List<ChatMessageDto> =
    (existing.associateBy { it.id } + incoming.associateBy { it.id }).values.sortedBy { it.id }.takeLast(keep)
