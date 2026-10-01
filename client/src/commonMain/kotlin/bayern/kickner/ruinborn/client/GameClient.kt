package bayern.kickner.ruinborn.client

import bayern.kickner.ruinborn.client.net.ApiClient
import bayern.kickner.ruinborn.client.net.ApiListener
import bayern.kickner.ruinborn.client.net.ApiResult
import bayern.kickner.ruinborn.client.net.GameSocket
import bayern.kickner.ruinborn.client.state.GameState
import bayern.kickner.ruinborn.client.state.GameStore
import bayern.kickner.ruinborn.client.state.MapData
import bayern.kickner.ruinborn.client.state.mergeChat
import bayern.kickner.ruinborn.shared.Log
import bayern.kickner.ruinborn.shared.dto.LoginRequest
import bayern.kickner.ruinborn.shared.dto.PlayerState
import bayern.kickner.ruinborn.shared.dto.RegisterRequest
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.rules.Rules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Storage location of the session token (libGDX preferences `ruinborn`). */
interface TokenStorage {
    fun load(): String?
    fun save(token: String?)
}

/**
 * Drives the client: login, loading state/map/balance/chat, WebSocket events and commands.
 * Network calls run on [io], state changes always on [ui] (render thread).
 */
class GameClient(
    val api: ApiClient,
    private val io: CoroutineScope,
    private val ui: CoroutineDispatcher,
    private val tokens: TokenStorage,
) {
    val store = GameStore()
    val state: GameState get() = store.state

    /** Short toasts (errors, hints) for the UI. */
    var onToast: (String) -> Unit = {}

    /** The server ended the session (logout, ban) or the token is invalid. */
    var onLoggedOut: (reason: String?) -> Unit = {}

    private var balanceEtag: String? = null
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val socket = GameSocket(api, io, ::onEvent, ::onSocketConnected, ::onSocketDisconnected)

    init {
        io.launch {
            for (r in refreshRequests) {
                delay(250)
                refreshStateNow()
            }
        }
        api.listener = object : ApiListener {
            override fun onUnauthorized() {
                io.launch { withContext(ui) { endSession("Bitte erneut anmelden.") } }
            }

            override fun onOutdated() {
                io.launch { withContext(ui) { store.update { it.copy(outdated = true) } } }
            }
        }
    }

    private suspend fun onUi(block: () -> Unit) = withContext(ui) { block() }

    // ---------------------------------------------------------------- Session

    suspend fun checkVersion() {
        val v = api.version().valueOrNull ?: return
        onUi { store.update { it.copy(version = v) } }
    }

    fun hasStoredSession(): Boolean = tokens.load() != null

    fun resumeSession() {
        api.token = tokens.load() ?: return
        start()
    }

    suspend fun login(name: String, password: String): ApiResult<Unit> = authenticate { api.login(LoginRequest(name, password)) }

    suspend fun register(name: String, password: String, invite: String): ApiResult<Unit> =
        authenticate { api.register(RegisterRequest(name, password, invite)) }

    private suspend fun authenticate(call: suspend () -> ApiResult<bayern.kickner.ruinborn.shared.dto.TokenResponse>): ApiResult<Unit> =
        when (val r = call()) {
            is ApiResult.Ok -> {
                api.token = r.value.token
                tokens.save(r.value.token)
                onUi { start() }
                ApiResult.Ok(Unit)
            }
            is ApiResult.Fail -> r
        }

    fun logout() {
        io.launch {
            api.logout()
            withContext(ui) { endSession(null) }
        }
    }

    private fun endSession(reason: String?) {
        if (state.loggedIn.not() && api.token == null) return
        socket.stop()
        api.token = null
        tokens.save(null)
        store.update { GameState(version = it.version, balance = it.balance, rules = it.rules, outdated = it.outdated) }
        onLoggedOut(reason)
    }

    /** After login: load everything and connect the WebSocket. */
    fun start() {
        store.update { it.copy(loggedIn = true) }
        io.launch { reloadAll() }
        socket.start()
    }

    /** Reloads balance (via ETag), state, map and chat. Runs after login and after every reconnect. */
    suspend fun reloadAll() {
        api.balance(balanceEtag).let { r ->
            if (r is ApiResult.Ok && r.value != null) {
                val (b, etag) = r.value
                balanceEtag = etag
                onUi { store.update { it.copy(balance = b, rules = Rules(b, it.player?.gameSpeed ?: 1.0)) } }
            }
        }
        refreshStateNow()
        reloadMap()
        reloadChat("world")
        if (state.player?.alliance != null) reloadChat("alliance")
    }

    suspend fun refreshStateNow() {
        val s = api.state().valueOrNull ?: return
        applyState(s)
    }

    suspend fun applyState(s: PlayerState) = onUi {
        val hadAlliance = state.player?.alliance?.id
        store.update { st ->
            val rules = st.balance?.let { b -> if (st.rules?.gameSpeed == s.gameSpeed) st.rules else Rules(b, s.gameSpeed) }
            st.copy(player = s, rules = rules)
        }
        if (hadAlliance != s.alliance?.id) {
            store.update { it.copy(chat = it.chat - "alliance", allianceVersion = it.allianceVersion + 1) }
            if (s.alliance != null) io.launch { reloadChat("alliance") }
        }
    }

    /**
     * Reloads the state. Several triggers in quick succession are merged. If a trigger arrives while a request is
     * running, exactly one more request follows (otherwise a change could be lost until the next event).
     */
    fun refreshState() {
        refreshRequests.trySend(Unit)
    }

    suspend fun reloadMap() {
        val m = api.map().valueOrNull ?: return
        onUi { store.update { it.copy(map = MapData.of(m)) } }
    }

    suspend fun reloadChat(channel: String) {
        val list = api.chat(channel).valueOrNull ?: return
        onUi { store.update { it.copy(chat = it.chat + (channel to mergeChat(it.chat[channel].orEmpty(), list))) } }
    }

    suspend fun loadOlderChat(channel: String) {
        val oldest = state.chat[channel]?.firstOrNull()?.id ?: return
        val list = api.chat(channel, oldest).valueOrNull ?: return
        onUi { store.update { it.copy(chat = it.chat + (channel to mergeChat(it.chat[channel].orEmpty(), list, keep = 1000))) } }
    }

    // ---------------------------------------------------------------- Commands

    /**
     * Runs a command. A response containing the game state replaces the state. Errors are shown as a toast
     * and passed to [onDone].
     */
    fun <T> command(call: suspend ApiClient.() -> ApiResult<T>, onDone: (ApiResult<T>) -> Unit = {}) {
        io.launch {
            val r = api.call()
            if (r is ApiResult.Ok && r.value is PlayerState) applyState(r.value as PlayerState)
            onUi {
                if (r is ApiResult.Fail && r.status != 401 && r.status != 426) onToast(r.message)
                onDone(r)
            }
        }
    }

    /** Like [command], but without a toast on errors (e.g. for lists that the dialog displays itself). */
    fun <T> load(call: suspend ApiClient.() -> ApiResult<T>, onDone: (ApiResult<T>) -> Unit) {
        io.launch {
            val r = api.call()
            onUi { onDone(r) }
        }
    }

    // ---------------------------------------------------------------- Events

    private fun onEvent(e: WsEvent) {
        io.launch {
            when (e) {
                WsEvent.StateChanged -> refreshState()
                is WsEvent.MapChanged -> onUi { store.update { it.copy(map = it.map.apply(e)) } }
                is WsEvent.Chat -> {
                    val channel = e.message.channel
                    onUi { store.update { it.copy(chat = it.chat + (channel to mergeChat(it.chat[channel].orEmpty(), listOf(e.message)))) } }
                }
                is WsEvent.Report -> {
                    onUi { store.update { it.copy(reportVersion = it.reportVersion + 1) } }
                    refreshState()
                }
                is WsEvent.Incoming -> refreshState()
                WsEvent.AllianceChanged -> onUi { store.update { it.copy(allianceVersion = it.allianceVersion + 1) } }
                is WsEvent.Notice -> onUi { store.update { it.copy(notices = (it.notices + e.text).takeLast(5)) } }
                is WsEvent.SessionEnded -> onUi { endSession(e.reason) }
            }
        }
    }

    private fun onSocketConnected(first: Boolean) {
        io.launch {
            onUi { store.update { it.copy(connected = true) } }
            if (first.not()) reloadAll()
        }
        Log.info("GameClient") { "WebSocket verbunden" }
    }

    private fun onSocketDisconnected() {
        io.launch { onUi { store.update { it.copy(connected = false) } } }
    }

    fun dispose() {
        socket.stop()
        api.close()
    }
}
