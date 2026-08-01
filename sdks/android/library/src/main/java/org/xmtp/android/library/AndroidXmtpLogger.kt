package org.xmtp.android.library

import android.util.Log

/**
 * Routes engine logging ([XmtpLog]) to `android.util.Log` (logcat), preserving the
 * SDK's pre-split logging behavior on Android. Installed at process start by
 * [XmtpInitializer].
 */
object AndroidXmtpLogger : XmtpLogger {
    override fun v(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.v(tag, message, throwable) else Log.v(tag, message)
    }

    override fun d(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.d(tag, message, throwable) else Log.d(tag, message)
    }

    override fun i(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.i(tag, message, throwable) else Log.i(tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}
