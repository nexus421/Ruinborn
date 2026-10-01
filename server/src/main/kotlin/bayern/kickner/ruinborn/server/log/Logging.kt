package bayern.kickner.ruinborn.server.log

import bayern.kickner.klogger.KLogger
import bayern.kickner.ruinborn.shared.Log

/** Sets up Klogger (console → journald) and connects the commonMain facade [Log] to it. */
object Logging {
    @Volatile
    private var configured = false

    fun setup(level: String) {
        if (configured) return
        configured = true
        KLogger.configure {
            logToConsole()
            minLevel = runCatching { KLogger.Level.valueOf(level.uppercase()) }.getOrDefault(KLogger.Level.INFO)
        }
        Log.sink = { l, tag, msg ->
            when (l) {
                Log.Level.DEBUG -> KLogger.debug(tag) { msg }
                Log.Level.INFO -> KLogger.info(tag) { msg }
                Log.Level.WARN -> KLogger.warn(tag) { msg }
                Log.Level.ERROR -> KLogger.error(tag) { msg }
            }
        }
    }
}
