package bayern.kickner.ruinborn.server

import bayern.kickner.ruinborn.client.GameClient
import bayern.kickner.ruinborn.client.TokenStorage
import bayern.kickner.ruinborn.client.net.ApiClient
import bayern.kickner.ruinborn.client.net.ApiResult
import bayern.kickner.ruinborn.shared.dto.BuildRequest
import bayern.kickner.ruinborn.shared.model.Plot
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real client (module `client`) against a real server over HTTP and WebSocket. */
class EndToEndTest {
    @Test
    fun registerLoadBuildAndReceiveEvents() {
        val w = TestWorld()
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(io.ktor.server.cio.CIO, port = port, host = "127.0.0.1") { w.app.module(this) }.start(wait = false)
        val uiThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val stored = mutableListOf<String?>()
        val client = GameClient(
            ApiClient(CIO.create(), "http://127.0.0.1:$port", 1, clock = { w.clock.now() }),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            uiThread,
            object : TokenStorage {
                override fun load() = stored.lastOrNull()
                override fun save(token: String?) { stored += token }
            },
        )
        try {
            runBlocking {
                client.checkVersion()
                assertNotNull(client.state.version)
                val bad = client.register("max", "geheim123", "falsch")
                assertIs<ApiResult.Fail>(bad)
                assertIs<ApiResult.Ok<Unit>>(client.register("max", "geheim123", "code"))
                withTimeout(10_000) {
                    while (client.state.player == null || client.state.balance == null || client.state.map.objects.isEmpty() || client.state.connected.not()) delay(50)
                }
                assertEquals("max", client.state.player!!.name)
                assertTrue(client.state.map.objects.size > 800)
                assertNotNull(stored.last())
                var done: ApiResult<*>? = null
                client.command({ build(BuildRequest(Plot.HQ)) }) { done = it }
                withTimeout(5_000) { while (done == null) delay(20) }
                assertEquals(1, client.state.player!!.timers.size)
                // Fast-forward: timer done → the client reloads and sees HQ 2
                w.advance(156_000)
                client.refreshStateNow()
                assertEquals(2, client.state.player!!.buildings.single { it.plot == Plot.HQ }.level)
                // Chat over WebSocket
                client.command({ postChat("world", "Hallo") })
                withTimeout(5_000) { while (client.state.chat["world"].orEmpty().none { it.text == "Hallo" }) delay(20) }
            }
        } finally {
            client.dispose()
            server.stop(100, 500)
            w.close()
            uiThread.close()
        }
    }
}
