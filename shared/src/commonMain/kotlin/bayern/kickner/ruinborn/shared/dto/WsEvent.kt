package bayern.kickner.ruinborn.shared.dto

import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.ReportKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WebSocket events from server to client (concept section 15). Each message is a JSON object
 * with `type` and the event's fields, e.g.
 * `{"type":"incoming","marchId":12,"attacker":"Max","kind":"ATTACK","arriveAt":1790000000000}`.
 */
@Serializable
sealed class WsEvent {
    @Serializable
    @SerialName("state_changed")
    data object StateChanged : WsEvent()

    @Serializable
    @SerialName("map_changed")
    data class MapChanged(
        val objects: List<MapObjectDto> = emptyList(),
        val marches: List<MarchDto> = emptyList(),
        val removedObjectIds: List<Long> = emptyList(),
        val removedMarchIds: List<Long> = emptyList(),
    ) : WsEvent()

    @Serializable
    @SerialName("chat")
    data class Chat(val message: ChatMessageDto) : WsEvent()

    @Serializable
    @SerialName("report")
    data class Report(val id: Long, val kind: ReportKind) : WsEvent()

    @Serializable
    @SerialName("incoming")
    data class Incoming(val marchId: Long, val attacker: String, val kind: MarchKind, val arriveAt: Long) : WsEvent()

    @Serializable
    @SerialName("alliance_changed")
    data object AllianceChanged : WsEvent()

    @Serializable
    @SerialName("notice")
    data class Notice(val text: String) : WsEvent()

    @Serializable
    @SerialName("session_ended")
    data class SessionEnded(val reason: String) : WsEvent()
}
