package org.xmtp.android.library

import java.io.File

/**
 * Concrete [ApiOptions] for pure-JVM / KMP hosts (the Android SDK uses
 * `ClientOptions.Api` instead).
 */
data class JvmApi(
    override val env: XMTPEnvironment = XMTPEnvironment.DEV,
    override val appVersion: String? = null,
    override val gatewayHost: String? = null,
) : ApiOptions

/**
 * Concrete [ClientCreateOptions] for pure-JVM / KMP hosts.
 *
 * The Android SDK ships its own Context-carrying `ClientOptions`; this is the
 * Context-free equivalent used by desktop-JVM consumers and the engine's own
 * tests. When [dbDirectory] is null the database is placed under
 * [workingDirectory]`/xmtp_db`.
 */
data class JvmClientOptions(
    override val dbEncryptionKey: ByteArray,
    override val apiOptions: ApiOptions = JvmApi(),
    override val preAuthenticateToInboxCallback: PreEventCallback? = null,
    override val dbDirectory: String? = null,
    override val deviceSyncEnabled: Boolean = true,
    override val forkRecoveryOptions: ForkRecoveryOptions? = null,
    override val dbPoolOptions: DbPoolOptions? = null,
    override val waitForRegistrationVisible: VisibilityConfirmationOptions? = null,
    val workingDirectory: String = System.getProperty("java.io.tmpdir") ?: ".",
) : ClientCreateOptions {
    override fun resolveDbParentDirectory(): String =
        File(workingDirectory, "xmtp_db").absolutePath
}
