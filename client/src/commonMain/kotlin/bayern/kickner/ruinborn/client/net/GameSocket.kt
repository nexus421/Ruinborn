package bayern.kickner.ruinborn.client.net

import bayern.kickner.ruinborn.shared.ApiJson
import bayern.kickner.ruinborn.shared.Log
import bayern.kickner.ruinborn.shared.dto.WsEvent
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * WebSocket connection for server notifications (concept section 15). Reconnects with [Backoff] after a
 * disconnect. [onConnected] reports every successful (re)connect so the app reloads state, map and balance.
 */
class GameSocket(
    private val api: ApiClient,
    private val scope: CoroutineScope,
    private val onEvent: (WsEvent) -> Unit,
    private val onConnected: (first: Boolean) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        job = scope.launch {
            var attempt = 0
            var first = true
            while (isActive) {
                val url = api.baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/ws"
                runCatching {
                    api.http.webSocket(url, request = { api.token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }) {
                        attempt = 0
                        onConnected(first)
                        first = false
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val event = runCatching { ApiJson.decodeFromString(WsEvent.serializer(), frame.readText()) }.getOrNull()
                                if (event != null) onEvent(event)
                            }
                        }
                    }
                }.onFailure { if (it is CancellationException) throw it else Log.debug("GameSocket") { "Verbindung fehlgeschlagen: ${it.message}" } }
                if (isActive.not()) break
                onDisconnected()
                delay(Backoff.delayFor(attempt++))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
