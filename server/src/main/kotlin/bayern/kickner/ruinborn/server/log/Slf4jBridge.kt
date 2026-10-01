package bayern.kickner.ruinborn.server.log

import bayern.kickner.klogger.KLogger
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Forwards Ktor's internal SLF4J messages to Klogger (concept section 13), so no additional logging library
 * is needed. TRACE is dropped.
 */
class KloggerSlf4jProvider : SLF4JServiceProvider {
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()
    private val factory = ILoggerFactory { name -> loggers.getOrPut(name) { KloggerSlf4jLogger(name) } }
    private val loggers = ConcurrentHashMap<String, Logger>()

    override fun getLoggerFactory(): ILoggerFactory = factory
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = mdc
    override fun getRequestedApiVersion(): String = "2.0.99"
    override fun initialize() {}
}

private class KloggerSlf4jLogger(private val loggerName: String) : AbstractLogger() {
    private val tag = loggerName.substringAfterLast('.')

    override fun getName(): String = loggerName
    override fun isTraceEnabled() = false
    override fun isTraceEnabled(marker: Marker?) = false
    override fun isDebugEnabled() = true
    override fun isDebugEnabled(marker: Marker?) = true
    override fun isInfoEnabled() = true
    override fun isInfoEnabled(marker: Marker?) = true
    override fun isWarnEnabled() = true
    override fun isWarnEnabled(marker: Marker?) = true
    override fun isErrorEnabled() = true
    override fun isErrorEnabled(marker: Marker?) = true
    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(level: Level, marker: Marker?, pattern: String?, args: Array<out Any?>?, t: Throwable?) {
        val text = MessageFormatter.basicArrayFormat(pattern, args) + (t?.let { "\n" + it.stackTraceToString() } ?: "")
        when (level) {
            Level.TRACE -> {}
            Level.DEBUG -> KLogger.debug(tag) { text }
            Level.INFO -> KLogger.info(tag) { text }
            Level.WARN -> KLogger.warn(tag) { text }
            Level.ERROR -> KLogger.error(tag) { text }
        }
    }
}
