package bayern.kickner.ruinborn.client

import bayern.kickner.klogger.KLogger
import bayern.kickner.ruinborn.client.dialog.GameDialog
import bayern.kickner.ruinborn.client.net.ApiClient
import bayern.kickner.ruinborn.client.screen.BaseScreen
import bayern.kickner.ruinborn.client.screen.LoginScreen
import bayern.kickner.ruinborn.client.screen.MapScreen
import bayern.kickner.ruinborn.client.screen.UiScreen
import bayern.kickner.ruinborn.client.ui.Palette
import bayern.kickner.ruinborn.client.ui.Ui
import bayern.kickner.ruinborn.shared.Log
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Preferences
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.async.KtxAsync
import ktx.async.MainDispatcher

/**
 * Launch options, mainly for testing on Linux: separate profile (several clients in parallel), automatic login,
 * start screen/dialog and a screenshot for visual checks.
 */
data class LaunchOptions(
    val serverUrl: String = BuildInfo.SERVER_URL,
    val profile: String = "",
    val autoLogin: Pair<String, String>? = null,
    val autoRegisterInvite: String? = null,
    val startScreen: String? = null,
    val startDialog: String? = null,
    val screenshot: String? = null,
    val screenshotDelaySec: Float = 4f,
    val exitAfterScreenshot: Boolean = true,
)

/** Token in the libGDX preferences `ruinborn` (concept section 12). */
class PrefsTokenStorage(private val prefs: Preferences) : TokenStorage {
    override fun load(): String? = prefs.getString("token", null)?.takeIf { it.isNotBlank() }
    override fun save(token: String?) {
        if (token == null) prefs.remove("token") else prefs.putString("token", token)
        prefs.flush()
    }
}

/**
 * Main class of the client. The client decides nothing: it shows the server state, counts timers down locally
 * and sends commands (concept section 12). The HTTP engine comes from the launcher.
 */
class RuinbornGame(private val engine: () -> HttpClientEngine, val options: LaunchOptions = LaunchOptions()) : KtxGame<KtxScreen>() {
    lateinit var assets: Assets
        private set
    lateinit var ui: Ui
        private set
    lateinit var client: GameClient
        private set

    /** Background work. An unexpected error is logged instead of terminating the app (on Android: the process). */
    val io = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> KLogger.error("Client") { "Fehler im Hintergrund: ${e.stackTraceToString()}" } },
    )
    val openDialogs = mutableListOf<GameDialog>()
    private var elapsed = 0f
    private var shotDone = false

    override fun create() {
        KtxAsync.initiate()
        // Klogger: only logToCustom, so everything ends up in Logcat or on the console (concept section 12).
        KLogger.configure {
            minLevel = KLogger.Level.INFO
            logToCustom { level, tag, msg -> if (level >= KLogger.Level.ERROR) Gdx.app.error(tag, msg) else Gdx.app.log(tag, msg) }
        }
        Log.sink = { l, tag, msg ->
            when (l) {
                Log.Level.DEBUG -> KLogger.debug(tag) { msg }
                Log.Level.INFO -> KLogger.info(tag) { msg }
                Log.Level.WARN -> KLogger.warn(tag) { msg }
                Log.Level.ERROR -> KLogger.error(tag) { msg }
            }
        }
        assets = Assets()
        ui = Ui(assets)
        val prefs = Gdx.app.getPreferences("ruinborn" + options.profile.let { if (it.isBlank()) "" else "-$it" })
        client = GameClient(
            ApiClient(engine(), options.serverUrl, BuildInfo.VERSION_CODE, clock = System::currentTimeMillis),
            io, MainDispatcher, PrefsTokenStorage(prefs),
        )
        client.onToast = { text -> (shownScreen as? UiScreen)?.toast(text) }
        client.onLoggedOut = { reason ->
            openDialogs.toList().forEach { it.close() }
            setScreen<LoginScreen>()
            reason?.let { (shownScreen as? UiScreen)?.toast(it, Palette.bad) }
        }
        client.store.listen { s -> openDialogs.toList().forEach { it.onState(s) } }
        addScreen(LoginScreen(this))
        addScreen(BaseScreen(this))
        addScreen(MapScreen(this))
        setScreen<LoginScreen>()
        io.launch { client.checkVersion() }
        if (options.autoLogin == null && client.hasStoredSession()) {
            client.resumeSession()
            setScreen<BaseScreen>()
        }
    }

    fun showBase() = setScreen<BaseScreen>()
    fun showMap() = setScreen<MapScreen>()
    fun mapScreen(): MapScreen = getScreen()

    override fun render() {
        super.render()
        elapsed += Gdx.graphics.deltaTime
        val shot = options.screenshot
        if (shot != null && shotDone.not() && elapsed >= options.screenshotDelaySec) {
            shotDone = true
            val w = Gdx.graphics.backBufferWidth
            val h = Gdx.graphics.backBufferHeight
            // createFromFrameBuffer already returns the image the right way up.
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, w, h)
            PixmapIO.writePNG(Gdx.files.absolute(shot), pixmap, 0, true)
            pixmap.dispose()
            Gdx.app.log("Ruinborn", "Bildschirmfoto: $shot")
            if (options.exitAfterScreenshot) Gdx.app.exit()
        }
    }

    /** Back from the background (Android): the connection may have dropped silently, so reload state and map. */
    override fun resume() {
        super.resume()
        if (client.state.loggedIn) {
            client.refreshState()
            io.launch { client.reloadMap() }
        }
    }

    override fun dispose() {
        super.dispose()
        client.dispose()
        io.cancel()
        assets.dispose()
    }
}
