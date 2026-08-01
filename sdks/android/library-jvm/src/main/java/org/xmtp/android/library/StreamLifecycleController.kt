package org.xmtp.android.library

/**
 * Seam for app-lifecycle-driven stream suspend/resume.
 *
 * The streaming wire is shared across every client in the process; on Android it
 * is parked while backgrounded and revived on foreground via
 * `androidx.lifecycle.ProcessLifecycleOwner`. The engine has no Android lifecycle,
 * so it depends only on this interface and defaults to [NoOp]. The Android module
 * installs a `ProcessLifecycleOwner`-backed controller (see `StreamLifecycleManager`)
 * at process start; other hosts may plug their own.
 */
interface StreamLifecycleController {
    /** Idempotently register lifecycle observation. Safe to call on every client creation. */
    fun enableIfNeeded()

    /** Does nothing — the default for pure-JVM / KMP hosts with no process lifecycle. */
    object NoOp : StreamLifecycleController {
        override fun enableIfNeeded() {}
    }
}
