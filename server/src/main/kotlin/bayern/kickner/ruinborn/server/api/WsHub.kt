package bayern.kickner.ruinborn.server.api

import bayern.kickner.ruinborn.server.engine.Notifier
import bayern.kickner.ruinborn.shared.ApiJson
import bayern.kickner.ruinborn.shared.dto.WsEvent
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Manages the WebSocket connections. The engine calls [send]/[broadcast] only after the commit. Each
 * connection has its own outgoing buffer so the engine never waits for the network.
 */
class WsHub : Notifier {
    class Conn(val playerId: Long, val session: DefaultWebSocketSession) {
        val out = Channel<String>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    private val conns = ConcurrentHashMap<Long, CopyOnWriteArraySet<Conn>>()

    fun register(c: Conn) {
        conns.computeIfAbsent(c.playerId) { CopyOnWriteArraySet() }.add(c)
    }

    fun unregister(c: Conn) {
        conns[c.playerId]?.remove(c)
        c.out.close()
    }

    /** Forwards the outgoing buffer to the connection (runs in the connection's coroutine). */
    fun pump(scope: CoroutineScope, c: Conn) = scope.launch {
        for (text in c.out) {
            if (text === CLOSE) {
                runCatching { c.session.close(CloseReason(CloseReason.Codes.NORMAL, "Sitzung beendet")) }
                return@launch
            }
            runCatching { c.session.send(Frame.Text(text)) }.onFailure { return@launch }
        }
    }

    val connectedPlayers: Set<Long> get() = conns.filterValues { it.isNotEmpty() }.keys

    override fun send(playerId: Long, event: WsEvent) {
        val set = conns[playerId] ?: return
        if (set.isEmpty()) return
        val text = ApiJson.encodeToString(WsEvent.serializer(), event)
        set.forEach { it.out.trySend(text) }
    }

    override fun broadcast(event: WsEvent) {
        val text = ApiJson.encodeToString(WsEvent.serializer(), event)
        conns.values.forEach { set -> set.forEach { it.out.trySend(text) } }
    }

    override fun endSessions(playerId: Long, reason: String) {
        val text = ApiJson.encodeToString(WsEvent.serializer(), WsEvent.SessionEnded(reason))
        conns[playerId]?.forEach {
            it.out.trySend(text)
            it.out.trySend(CLOSE)
        }
    }

    private companion object {
        /** Marker in the outgoing buffer: close the connection after all previous messages (compared by identity). */
        val CLOSE = String(charArrayOf('\u0000'))
    }
}
