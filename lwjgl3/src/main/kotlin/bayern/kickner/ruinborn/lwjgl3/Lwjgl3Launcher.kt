package bayern.kickner.ruinborn.lwjgl3

import bayern.kickner.ruinborn.client.BuildInfo
import bayern.kickner.ruinborn.client.LaunchOptions
import bayern.kickner.ruinborn.client.RuinbornGame
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import io.ktor.client.engine.cio.CIO

/**
 * Desktop launcher (LWJGL3) in portrait 540 × 960 for testing on Linux (concept section 12). HTTP engine: CIO.
 *
 * Options (all optional):
 * `--server URL`, `--profile NAME` (own token, for several clients), `--login NAME:PASSWORD`,
 * `--register INVITE_CODE` (with `--login`: register, otherwise log in), `--screen map`, `--dialog NAME`,
 * `--screenshot FILE.png` (after `--shot-delay` seconds, default 4, then exit), `--size WxH`.
 */
fun main(args: Array<String>) {
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val options = LaunchOptions(
        serverUrl = arg("--server") ?: BuildInfo.SERVER_URL,
        profile = arg("--profile") ?: "",
        autoLogin = arg("--login")?.split(":", limit = 2)?.takeIf { it.size == 2 }?.let { it[0] to it[1] },
        autoRegisterInvite = arg("--register"),
        startScreen = arg("--screen"),
        startDialog = arg("--dialog"),
        screenshot = arg("--screenshot"),
        screenshotDelaySec = arg("--shot-delay")?.toFloatOrNull() ?: 4f,
    )
    val (w, h) = arg("--size")?.split("x")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (540 to 960)
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Ruinborn" + if (options.profile.isNotBlank()) " – ${options.profile}" else "")
        setWindowedMode(w, h)
        useVsync(true)
        setForegroundFPS(60)
        setResizable(true)
    }
    Lwjgl3Application(RuinbornGame({ CIO.create() }, options), config)
}
