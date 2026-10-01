package bayern.kickner.ruinborn.shared

/**
 * Small platform-neutral logging facade for commonMain code. Klogger is a JVM-only library and therefore
 * cannot be used directly in commonMain. Each app connects [sink] to Klogger (server, desktop)
 * or to another logger.
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    /** Target of all messages. Without a target nothing is written. */
    var sink: ((level: Level, tag: String, message: String) -> Unit)? = null

    fun debug(tag: String, msg: () -> String) = emit(Level.DEBUG, tag, msg)
    fun info(tag: String, msg: () -> String) = emit(Level.INFO, tag, msg)
    fun warn(tag: String, msg: () -> String) = emit(Level.WARN, tag, msg)
    fun error(tag: String, msg: () -> String) = emit(Level.ERROR, tag, msg)

    private inline fun emit(level: Level, tag: String, msg: () -> String) {
        val s = sink ?: return
        runCatching { s(level, tag, msg()) }
    }
}
