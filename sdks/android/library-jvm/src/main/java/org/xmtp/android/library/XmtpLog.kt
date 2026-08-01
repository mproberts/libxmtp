package org.xmtp.android.library

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Pluggable logging sink for the SDK engine.
 *
 * The engine must not depend on `android.util.Log`, so all internal logging goes
 * through [XmtpLog], whose backend can be swapped. On a pure JVM (and by default)
 * this routes to `java.util.logging`; the Android module installs an
 * `android.util.Log`-backed [XmtpLogger] at process start so device logs keep
 * landing in logcat exactly as before.
 */
interface XmtpLogger {
    fun v(tag: String, message: String, throwable: Throwable? = null)

    fun d(tag: String, message: String, throwable: Throwable? = null)

    fun i(tag: String, message: String, throwable: Throwable? = null)

    fun w(tag: String, message: String, throwable: Throwable? = null)

    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Default JVM logger backed by `java.util.logging`. Used until (or unless) a host
 * platform installs its own [XmtpLogger] on [XmtpLog].
 */
object JavaUtilXmtpLogger : XmtpLogger {
    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        val logger = Logger.getLogger("org.xmtp.$tag")
        if (throwable != null) logger.log(level, message, throwable) else logger.log(level, message)
    }

    override fun v(tag: String, message: String, throwable: Throwable?) = log(Level.FINER, tag, message, throwable)

    override fun d(tag: String, message: String, throwable: Throwable?) = log(Level.FINE, tag, message, throwable)

    override fun i(tag: String, message: String, throwable: Throwable?) = log(Level.INFO, tag, message, throwable)

    override fun w(tag: String, message: String, throwable: Throwable?) = log(Level.WARNING, tag, message, throwable)

    override fun e(tag: String, message: String, throwable: Throwable?) = log(Level.SEVERE, tag, message, throwable)
}

/**
 * Engine-wide logging facade. Replaces direct `android.util.Log` calls so the SDK
 * core stays free of Android dependencies. Install a platform logger by assigning
 * [logger].
 */
object XmtpLog {
    @Volatile
    var logger: XmtpLogger = JavaUtilXmtpLogger

    fun v(tag: String, message: String, throwable: Throwable? = null) = logger.v(tag, message, throwable)

    fun d(tag: String, message: String, throwable: Throwable? = null) = logger.d(tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) = logger.i(tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) = logger.w(tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = logger.e(tag, message, throwable)
}
