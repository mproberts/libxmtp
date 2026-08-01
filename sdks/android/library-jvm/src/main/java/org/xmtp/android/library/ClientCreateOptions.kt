package org.xmtp.android.library

/**
 * Context-free view of the API connection settings the engine needs.
 *
 * The public [ClientOptions.Api] implements this, so every engine function that
 * used to take `ClientOptions.Api` now takes the [ApiOptions] supertype —
 * source-compatible for callers, but free of the Android-coupled [ClientOptions].
 */
interface ApiOptions {
    val env: XMTPEnvironment
    val appVersion: String?
    val gatewayHost: String?
}

/**
 * Context-free view of the client-creation settings the engine needs.
 *
 * The public, Android-coupled [ClientOptions] (which carries an
 * `android.content.Context`) implements this. The engine resolves the on-disk
 * database location through [resolveDbParentDirectory] instead of reaching for
 * `Context.filesDir`, so the create path has no Android dependency.
 */
interface ClientCreateOptions {
    val apiOptions: ApiOptions
    val preAuthenticateToInboxCallback: PreEventCallback?
    val dbEncryptionKey: ByteArray
    val dbDirectory: String?
    val deviceSyncEnabled: Boolean
    val forkRecoveryOptions: ForkRecoveryOptions?
    val dbPoolOptions: DbPoolOptions?
    val waitForRegistrationVisible: VisibilityConfirmationOptions?

    /**
     * Absolute path of the directory to place the database in when [dbDirectory]
     * is null. On Android this is `<Context.filesDir>/xmtp_db`; a pure-JVM host
     * supplies its own working directory.
     */
    fun resolveDbParentDirectory(): String
}
