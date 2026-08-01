package org.xmtp.android.library

import android.content.Context
import org.xmtp.android.library.codecs.ContentCodec
import org.xmtp.android.library.libxmtp.InboxState
import org.xmtp.android.library.libxmtp.PublicIdentity
import org.xmtp.android.library.libxmtp.SignatureRequest
import uniffi.xmtpv3.FfiKeyPackageStatus
import uniffi.xmtpv3.FfiLogLevel
import uniffi.xmtpv3.FfiLogRotation
import uniffi.xmtpv3.FfiProcessType
import uniffi.xmtpv3.FfiXmtpClient
import uniffi.xmtpv3.XmtpApiClient
import java.io.File

/**
 * Android client options. Carries an [android.content.Context] and implements the
 * engine's Context-free [ClientCreateOptions] seam so it can drive creation of the
 * pure-JVM [JvmClient] without the engine depending on Android.
 */
data class ClientOptions(
    val api: Api = Api(),
    override val preAuthenticateToInboxCallback: PreEventCallback? = null,
    val appContext: Context,
    override val dbEncryptionKey: ByteArray,
    override val dbDirectory: String? = null,
    override val deviceSyncEnabled: Boolean = true,
    override val forkRecoveryOptions: ForkRecoveryOptions? = null,
    override val dbPoolOptions: DbPoolOptions? = null,
    override val waitForRegistrationVisible: VisibilityConfirmationOptions? = null,
) : ClientCreateOptions {
    override val apiOptions: ApiOptions get() = api

    override fun resolveDbParentDirectory(): String =
        File(appContext.filesDir.absolutePath, "xmtp_db").absolutePath

    data class Api(
        override val env: XMTPEnvironment = XMTPEnvironment.DEV,
        @Deprecated("isSecure is no longer used and will be removed in a future release")
        val isSecure: Boolean = true,
        override val appVersion: String? = null,
        override val gatewayHost: String? = null,
    ) : ApiOptions
}

/**
 * XMTP client for Android. A thin wrapper over the pure-JVM [JvmClient] engine that
 * preserves the historical `org.xmtp.android.library.Client` API and adds the
 * `Context`-based conveniences. All messaging behavior is inherited from [JvmClient].
 */
class Client internal constructor(
    libXMTPClient: FfiXmtpClient,
    dbPath: String,
    installationId: String,
    inboxId: InboxId,
    environment: XMTPEnvironment,
    publicIdentity: PublicIdentity,
) : JvmClient(libXMTPClient, dbPath, installationId, inboxId, environment, publicIdentity) {
    companion object {
        /** @see JvmClient.manageStreamLifecycle */
        @JvmStatic
        var manageStreamLifecycle: Boolean
            get() = JvmClient.manageStreamLifecycle
            set(value) {
                JvmClient.manageStreamLifecycle = value
            }

        const val IN_MEMORY_DB_PATH: String = JvmClient.IN_MEMORY_DB_PATH

        var codecRegistry: CodecRegistry
            get() = JvmClient.codecRegistry
            set(value) {
                JvmClient.codecRegistry = value
            }

        fun register(codec: ContentCodec<*>) = JvmClient.register(codec)

        // --- Context-based logging conveniences (resolve xmtp_logs under filesDir) ---

        private fun logDir(appContext: Context): String =
            File(appContext.filesDir, "xmtp_logs").path

        fun activatePersistentLibXMTPLogWriter(
            appContext: Context,
            logLevel: FfiLogLevel,
            rotationSchedule: FfiLogRotation,
            maxFiles: Int,
            processType: ProcessType = FfiProcessType.MAIN,
        ) = JvmClient.activatePersistentLibXMTPLogWriter(
            logDir(appContext),
            logLevel,
            rotationSchedule,
            maxFiles,
            processType,
        )

        fun deactivatePersistentLibXMTPLogWriter() = JvmClient.deactivatePersistentLibXMTPLogWriter()

        fun setLibXMTPNativeLogLevel(logLevel: FfiLogLevel) = JvmClient.setLibXMTPNativeLogLevel(logLevel)

        fun getXMTPLogFilePaths(appContext: Context): List<String> =
            JvmClient.getXMTPLogFilePaths(logDir(appContext))

        fun clearXMTPLogs(appContext: Context): Int = JvmClient.clearXMTPLogs(logDir(appContext))

        // --- Static API delegated to the engine (Context-free) ---

        suspend fun connectToApiBackend(api: ClientOptions.Api): XmtpApiClient =
            JvmClient.connectToApiBackend(api)

        suspend fun getOrCreateInboxId(api: ClientOptions.Api, publicIdentity: PublicIdentity): InboxId =
            JvmClient.getOrCreateInboxId(api, publicIdentity)

        suspend fun revokeInstallations(
            api: ClientOptions.Api,
            signingKey: SigningKey,
            inboxId: InboxId,
            installationIds: List<String>,
        ) = JvmClient.revokeInstallations(api, signingKey, inboxId, installationIds)

        @DelicateApi("Prefer revokeInstallations() unless managing the signature flow independently.")
        suspend fun ffiRevokeInstallations(
            api: ClientOptions.Api,
            publicIdentity: PublicIdentity,
            inboxId: InboxId,
            installationIds: List<String>,
        ): SignatureRequest = JvmClient.ffiRevokeInstallations(api, publicIdentity, inboxId, installationIds)

        @DelicateApi("Prefer revokeInstallations() unless managing the signature flow independently.")
        suspend fun ffiApplySignatureRequest(api: ClientOptions.Api, signatureRequest: SignatureRequest) =
            JvmClient.ffiApplySignatureRequest(api, signatureRequest)

        suspend fun inboxStatesForInboxIds(inboxIds: List<InboxId>, api: ClientOptions.Api): List<InboxState> =
            JvmClient.inboxStatesForInboxIds(inboxIds, api)

        suspend fun getNewestMessageMetadata(
            groupIds: List<String>,
            api: ClientOptions.Api,
        ): Map<String, MessageMetadata> = JvmClient.getNewestMessageMetadata(groupIds, api)

        suspend fun keyPackageStatusesForInstallationIds(
            installationIds: List<String>,
            api: ClientOptions.Api,
        ): Map<String, FfiKeyPackageStatus> =
            JvmClient.keyPackageStatusesForInstallationIds(installationIds, api)

        suspend fun canMessage(identities: List<PublicIdentity>, api: ClientOptions.Api): Map<String, Boolean> =
            JvmClient.canMessage(identities, api)

        // --- Client creation (produces the Android Client subtype via the engine factory) ---

        suspend fun create(account: SigningKey, options: ClientOptions): Client =
            try {
                JvmClient.initializeV3Client(account.publicIdentity, options, account, construct = ::Client)
            } catch (e: Exception) {
                throw XMTPException("Error creating V3 client: ${e.message}", e)
            }

        suspend fun createInMemory(account: SigningKey, options: ClientOptions): Client =
            try {
                JvmClient.initializeV3Client(
                    account.publicIdentity,
                    options,
                    account,
                    inMemory = true,
                    construct = ::Client,
                )
            } catch (e: Exception) {
                throw XMTPException("Error creating in-memory V3 client: ${e.message}", e)
            }

        suspend fun build(
            publicIdentity: PublicIdentity,
            options: ClientOptions,
            inboxId: InboxId? = null,
        ): Client =
            try {
                JvmClient.initializeV3Client(
                    publicIdentity,
                    options,
                    inboxId = inboxId,
                    buildOffline = inboxId != null,
                    construct = ::Client,
                )
            } catch (e: Exception) {
                throw XMTPException("Error creating V3 client: ${e.message}", e)
            }

        @DelicateApi(
            "This function is delicate and should be used with caution. Creating an FfiClient without signing or registering will create a broken experience use `create()` instead",
        )
        suspend fun ffiCreateClient(publicIdentity: PublicIdentity, clientOptions: ClientOptions): Client =
            JvmClient.ffiCreateClient(publicIdentity, clientOptions, ::Client)
    }
}
