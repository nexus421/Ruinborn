package bayern.kickner.ruinborn.server

import bayern.kickner.ruinborn.shared.ApiJson
import bayern.kickner.ruinborn.shared.Headers
import bayern.kickner.ruinborn.shared.dto.ChatPostResponse
import bayern.kickner.ruinborn.shared.dto.ErrorDto
import bayern.kickner.ruinborn.shared.dto.PlayerState
import bayern.kickner.ruinborn.shared.dto.TokenResponse
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.model.ErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiTest {
    private val w = TestWorld(maxPlayers = 3)

    @AfterTest
    fun tearDown() = w.close()

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { w.app.module(this) }
        block()
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null, requestId: String? = null): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            requestId?.let { header(Headers.REQUEST_ID, it) }
        }

    private suspend fun HttpClient.register(name: String, code: String = "code"): HttpResponse =
        postJson("/api/auth/register", """{"username":"$name","password":"geheim123","inviteCode":"$code"}""")

    private suspend fun HttpResponse.error(): ErrorDto = ApiJson.decodeFromString(ErrorDto.serializer(), bodyAsText())
    private suspend fun HttpResponse.token(): String = ApiJson.decodeFromString(TokenResponse.serializer(), bodyAsText()).token

    @Test
    fun publicEndpoints() = api {
        assertEquals("ok", client.get("/api/health").bodyAsText())
        val v = client.get("/api/version")
        assertTrue(v.bodyAsText().contains("\"apkUrl\""))
        assertNotNull(v.headers[Headers.SERVER_TIME])
        assertEquals(HttpStatusCode.NotFound, client.get("/download/ruinborn.apk").status)
    }

    @Test
    fun registrationAndLoginRules() = api {
        val bad = client.register("max", "falsch")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals(ErrorCode.INVALID_INVITE, bad.error().code)
        assertEquals(ErrorCode.VALIDATION, client.register("x!").error().code)
        assertEquals(HttpStatusCode.OK, client.register("max").status)
        assertEquals(ErrorCode.NAME_TAKEN, client.register("MAX").error().code)
        client.register("anna")
        client.register("bert")
        assertEquals(ErrorCode.SERVER_FULL, client.register("carl").error().code)
        val wrong = client.postJson("/api/auth/login", """{"username":"max","password":"falsch123"}""")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("Name oder Passwort falsch.", wrong.error().message)
        val unknown = client.postJson("/api/auth/login", """{"username":"niemand","password":"falsch123"}""")
        assertEquals(wrong.error().message, unknown.error().message, "kein Hinweis, ob Name oder Passwort falsch war")
    }

    @Test
    fun loginRateLimitPerIp() = api {
        repeat(5) { client.postJson("/api/auth/login", """{"username":"a","password":"12345678"}""") }
        assertEquals(HttpStatusCode.TooManyRequests, client.postJson("/api/auth/login", """{"username":"a","password":"12345678"}""").status)
    }

    @Test
    fun stateRequiresTokenAndOutdatedClientGets426() = api {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/state").status)
        val token = client.register("max").token()
        val ok = client.get("/api/state") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, ok.status)
        val s = ApiJson.decodeFromString(PlayerState.serializer(), ok.bodyAsText())
        assertEquals("max", s.name)
        val old = client.get("/api/state") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(Headers.CLIENT_VERSION, "0")
        }
        assertEquals(HttpStatusCode.UpgradeRequired, old.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/health") { header(Headers.CLIENT_VERSION, "0") }.status)
    }

    @Test
    fun duplicateRequestIdExecutesOnce() = api {
        val token = client.register("max").token()
        val r1 = client.postJson("/api/build", """{"plot":"HQ"}""", token, "req-1")
        val r2 = client.postJson("/api/build", """{"plot":"HQ"}""", token, "req-1")
        assertEquals(HttpStatusCode.OK, r1.status)
        assertEquals(r1.bodyAsText(), r2.bodyAsText())
        val s = ApiJson.decodeFromString(PlayerState.serializer(), r2.bodyAsText())
        assertEquals(1, s.timers.size)
        assertEquals(1300L, s.resources.first().amount)
        val r3 = client.postJson("/api/build", """{"plot":"HQ"}""", token, "req-2")
        assertEquals(HttpStatusCode.Conflict, r3.status)
        assertEquals(ErrorCode.QUEUE_FULL, r3.error().code)
    }

    @Test
    fun balanceWithEtag() = api {
        val token = client.register("max").token()
        val r = client.get("/api/balance") { header(HttpHeaders.Authorization, "Bearer $token") }
        val etag = r.headers[HttpHeaders.ETag]!!
        assertTrue(r.bodyAsText().contains("\"buildings\""))
        val again = client.get("/api/balance") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.NotModified, again.status)
    }

    @Test
    fun invalidJsonIsValidationError() = api {
        val token = client.register("max").token()
        val r = client.postJson("/api/build", """{"plot":"NIRGENDS"}""", token)
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals(ErrorCode.VALIDATION, r.error().code)
    }

    @Test
    fun adminCommandsViaChat() = api {
        val admin = client.register("admin").token()
        val player = client.register("max").token()
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/chat/world", """{"text":"/reports"}""", player).status)
        val r = client.postJson("/api/chat/world", """{"text":"/password max neuesPasswort1"}""", admin)
        val resp = ApiJson.decodeFromString(ChatPostResponse.serializer(), r.bodyAsText())
        assertTrue(resp.system!!.contains("Neues Passwort"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/state") { header(HttpHeaders.Authorization, "Bearer $player") }.status)
        val login = client.postJson("/api/auth/login", """{"username":"max","password":"neuesPasswort1"}""")
        assertEquals(HttpStatusCode.OK, login.status)
        val msg = client.postJson("/api/chat/world", """{"text":"Hallo"}""", login.token())
        assertTrue(ApiJson.decodeFromString(ChatPostResponse.serializer(), msg.bodyAsText()).message!!.text == "Hallo")
    }

    @Test
    fun webSocketReceivesStateChanged() = api {
        val token = client.register("max").token()
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/ws", request = { header(HttpHeaders.Authorization, "Bearer $token") }) {
            client.postJson("/api/build", """{"plot":"HQ"}""", token)
            val event = withTimeout(5_000) {
                var e: WsEvent
                do {
                    val frame = incoming.receive()
                    e = ApiJson.decodeFromString(WsEvent.serializer(), (frame as Frame.Text).readText())
                } while (e !is WsEvent.StateChanged)
                e
            }
            assertIs<WsEvent.StateChanged>(event)
        }
    }
}
