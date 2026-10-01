package bayern.kickner.ruinborn.android

import android.os.Bundle
import bayern.kickner.ruinborn.BuildConfig
import bayern.kickner.ruinborn.client.LaunchOptions
import bayern.kickner.ruinborn.client.RuinbornGame
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import io.ktor.client.engine.okhttp.OkHttp

/** Android entry point: portrait, fullscreen, HTTP engine OkHttp (concept section 12). */
class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            useAccelerometer = false
            useCompass = false
            useGyroscope = false
        }
        initialize(RuinbornGame({ OkHttp.create() }, LaunchOptions(serverUrl = BuildConfig.SERVER_URL)), config)
    }
}
