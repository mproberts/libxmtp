package org.xmtp.android.library

import uniffi.xmtpv3.FfiCatchUpSummary

/**
 * Counts of what a [JvmClient.catchUpToLive] run brought into the local store, plus
 * whether it reached the live edge.
 */
data class CatchUpSummary(
    val messages: Long,
    val conversations: Long,
    val completed: Boolean,
) {
    internal constructor(ffi: FfiCatchUpSummary) : this(
        messages = ffi.messages.toLong(),
        conversations = ffi.conversations.toLong(),
        completed = ffi.completed,
    )
}
