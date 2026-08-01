package org.xmtp.android.library

import android.content.Context
import androidx.startup.Initializer

/**
 * Wires the Android-specific behavior into the engine at process start, so
 * existing consumers keep identical behavior with zero manual setup:
 *
 *  - routes [XmtpLog] to logcat via [AndroidXmtpLogger], and
 *  - installs the `ProcessLifecycleOwner`-backed [StreamLifecycleManager] as the
 *    engine's [StreamLifecycleController].
 *
 * Registered via `androidx.startup` in the library manifest, so it runs before
 * application code without the app declaring anything.
 */
class XmtpInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        XmtpLog.logger = AndroidXmtpLogger
        JvmClient.streamLifecycleController = StreamLifecycleManager
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
