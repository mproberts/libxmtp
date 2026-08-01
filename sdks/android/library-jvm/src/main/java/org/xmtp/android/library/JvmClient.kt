package org.xmtp.android.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmtp.android.library.codecs.ContentCodec
import org.xmtp.android.library.codecs.TextCodec
import org.xmtp.android.library.libxmtp.ArchiveMetadata
import org.xmtp.android.library.libxmtp.ArchiveOptions
import org.xmtp.android.library.libxmtp.AvailableArchive
import org.xmtp.android.library.libxmtp.IdentityKind
import org.xmtp.android.library.libxmtp.InboxState
import org.xmtp.android.library.libxmtp.PublicIdentity
import org.xmtp.android.library.libxmtp.SignatureRequest
import org.xmtp.android.library.libxmtp.toFfi
import uniffi.xmtpv3.DbOptions
import uniffi.xmtpv3.FfiCatchUpOptions
import uniffi.xmtpv3.FfiClientMode
import uniffi.xmtpv3.FfiDeviceSyncMode
import uniffi.xmtpv3.FfiForkRecoveryOpts
import uniffi.xmtpv3.FfiForkRecoveryPolicy
import uniffi.xmtpv3.FfiKeyPackageStatus
import uniffi.xmtpv3.FfiLogLevel
import uniffi.xmtpv3.FfiLogRotation
import uniffi.xmtpv3.FfiMessageMetadata
import uniffi.xmtpv3.FfiProcessType
import uniffi.xmtpv3.FfiVisibilityConfirmationOptions
import uniffi.xmtpv3.FfiXmtpClient
import uniffi.xmtpv3.XmtpApiClient
import uniffi.xmtpv3.applySignatureRequest
import uniffi.xmtpv3.connectToBackend
import uniffi.xmtpv3.createClient
import uniffi.xmtpv3.enterDebugWriter
import uniffi.xmtpv3.exitDebugWriter
import uniffi.xmtpv3.generateInboxId
import uniffi.xmtpv3.getInboxIdForIdentifier
import uniffi.xmtpv3.getNewestMessageMetadata
import uniffi.xmtpv3.getVersionInfo
import uniffi.xmtpv3.inboxStateFromInboxIds
import uniffi.xmtpv3.isConnected
import uniffi.xmtpv3.revokeInstallations
import java.io.File
import uniffi.xmtpv3.setNativeLogLevel as ffiSetNativeLogLevel

typealias PreEventCallback = suspend () -> Unit
typealias ProcessType = FfiProcessType
typealias MessageMetadata = FfiMessageMetadata

// ClientOptions (the Android, Context-carrying options) lives in the :library
// module and implements ClientCreateOptions. The engine only sees the
// Context-free ClientCreateOptions / ApiOptions seams.

enum class ForkRecoveryPolicy {
    None,
    AllowlistedGroups,
    All,
    ;

    fun toFfi(): FfiForkRecoveryPolicy =
        when (this) {
            None -> FfiForkRecoveryPolicy.NONE
            AllowlistedGroups -> FfiForkRecoveryPolicy.ALLOWLISTED_GROUPS
            All -> FfiForkRecoveryPolicy.ALL
        }
}

data class ForkRecoveryOptions(
    val enableRecoveryRequests: ForkRecoveryPolicy,
    val groupsToRequestRecovery: List<String>,
    val disableRecoveryResponses: Boolean? = null,
    val workerIntervalNs: ULong? = null,
) {
    fun toFfi(): FfiForkRecoveryOpts =
        FfiForkRecoveryOpts(
            enableRecoveryRequests = this.enableRecoveryRequests.toFfi(),
            groupsToRequestRecovery = this.groupsToRequestRecovery,
            disableRecoveryResponses = this.disableRecoveryResponses,
            workerIntervalNs = this.workerIntervalNs,
        )
}

data class VisibilityConfirmationOptions(
    val quorumPercentage: Float? = null,
    val quorumAbsolute: ULong? = null,
    val timeoutMs: ULong? = null,
) {
    fun toFfi(): FfiVisibilityConfirmationOptions =
        FfiVisibilityConfirmationOptions(
            quorumPercentage = this.quorumPercentage,
            quorumAbsolute = this.quorumAbsolute,
            timeoutMs = this.timeoutMs,
        )
}

data class DbPoolOptions(
    val maxPoolSize: UInt? = null,
    val minPoolSize: UInt? = null,
)

typealias InboxId = String

open class JvmClient(
    libXMTPClient: FfiXmtpClient,
    val dbPath: String,
    val installationId: String,
    val inboxId: InboxId,
    val environment: XMTPEnvironment,
    val publicIdentity: PublicIdentity,
) {
    val preferences: PrivatePreferences =
        PrivatePreferences(client = this, ffiClient = libXMTPClient)
    val conversations: Conversations =
        Conversations(
            client = this,
            ffiConversations = libXMTPClient.conversations(),
            ffiClient = libXMTPClient,
        )
    val debugInformation: XMTPDebugInformation =
        XMTPDebugInformation(ffiClient = libXMTPClient)
    val libXMTPVersion: String = getVersionInfo()
    private val ffiClient: FfiXmtpClient = libXMTPClient

    /**
     * `true` when this client is backed by an in-memory database. In that case
     * [deleteLocalDatabase], [dropLocalDatabaseConnection] and
     * [reconnectLocalDatabase] are no-ops and the underlying state is
     * discarded when the client is garbage collected.
     */
    val isInMemory: Boolean
        get() = dbPath == IN_MEMORY_DB_PATH

    companion object {
        private const val TAG = "JvmClient"

        /**
         * Process-wide control for automatic stream-lifecycle management. When
         * true (the default), the first [JvmClient] created registers a
         * [androidx.lifecycle.ProcessLifecycleOwner] observer that parks the
         * shared streaming wire while the app is backgrounded and revives it on
         * foreground. The streaming wire is shared across every client in the
         * process, so this is a **process-global** setting, not per-client: set
         * it to false **before creating your first client** to opt out (e.g. to
         * manage the lifecycle yourself).
         */
        @JvmStatic
        var manageStreamLifecycle: Boolean = true

        /**
         * The controller that keeps the shared streaming wire in step with the
         * host's lifecycle. Defaults to a no-op (pure-JVM / KMP hosts); the
         * Android module installs a [androidx.lifecycle.ProcessLifecycleOwner]-backed
         * controller at process start.
         */
        @JvmStatic
        var streamLifecycleController: StreamLifecycleController = StreamLifecycleController.NoOp

        /**
         * Sentinel value assigned to [JvmClient.dbPath] when the client was created
         * via [createInMemory]. No file exists at this path.
         */
        const val IN_MEMORY_DB_PATH: String = ":memory:"

        var codecRegistry =
            run {
                val registry = CodecRegistry()
                registry.register(codec = TextCodec())
                registry
            }

        private fun ApiOptions.toCacheKey(): String =
            "${env.getUrl()}|${appVersion ?: "nil"}|${gatewayHost ?: "nil"}"

        private val apiClientCache = mutableMapOf<String, XmtpApiClient>()
        private val cacheLock = Mutex()

        fun activatePersistentLibXMTPLogWriter(
            logDirectory: String,
            logLevel: FfiLogLevel,
            rotationSchedule: FfiLogRotation,
            maxFiles: Int,
            processType: ProcessType = FfiProcessType.MAIN,
        ) {
            val dir = File(logDirectory)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            enterDebugWriter(
                dir.toString(),
                logLevel,
                rotationSchedule,
                maxFiles.toUInt(),
                processType,
            )
        }

        fun deactivatePersistentLibXMTPLogWriter() {
            exitDebugWriter()
        }

        /**
         * Sets the log level for the native log layer (logcat on Android). Use
         * `FfiLogLevel.TRACE` to capture span/activity events. Independent of the
         * persistent file log writer.
         */
        fun setLibXMTPNativeLogLevel(logLevel: FfiLogLevel) {
            ffiSetNativeLogLevel(logLevel)
        }

        fun getXMTPLogFilePaths(logDirectory: String): List<String> {
            val dir = File(logDirectory)
            if (!dir.exists()) {
                return emptyList()
            }

            return dir
                .listFiles()
                ?.filter { it.isFile }
                ?.map { it.absolutePath }
                ?: emptyList()
        }

        fun clearXMTPLogs(logDirectory: String): Int {
            val dir = File(logDirectory)
            if (!dir.exists()) {
                return 0
            }

            try {
                deactivatePersistentLibXMTPLogWriter()
            } catch (e: Exception) {
                // Log writer might not be active, continue with deletion
            }

            var deletedCount = 0
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.delete()) {
                    deletedCount++
                }
            }

            return deletedCount
        }

        suspend fun connectToApiBackend(api: ApiOptions): XmtpApiClient {
            val cacheKey = api.toCacheKey()
            return cacheLock.withLock {
                val cached = apiClientCache[cacheKey]

                if (cached != null && isConnected(cached)) {
                    return cached
                }

                // If not cached or not connected, create a fresh client
                val newClient =
                    connectToBackend(
                        api.env.getUrl(),
                        api.gatewayHost,
                        FfiClientMode.DEFAULT,
                        api.appVersion,
                        null,
                        null,
                    )
                apiClientCache[cacheKey] = newClient
                return@withLock newClient
            }
        }

        suspend fun getOrCreateInboxId(
            api: ApiOptions,
            publicIdentity: PublicIdentity,
        ): InboxId =
            withContext(Dispatchers.IO) {
                val rootIdentity = publicIdentity.ffiPrivate
                var inboxId =
                    getInboxIdForIdentifier(
                        api = connectToApiBackend(api),
                        accountIdentifier = rootIdentity,
                    )
                if (inboxId.isNullOrBlank()) {
                    inboxId = generateInboxId(rootIdentity, 0.toULong())
                }
                inboxId
            }

        suspend fun revokeInstallations(
            api: ApiOptions,
            signingKey: SigningKey,
            inboxId: InboxId,
            installationIds: List<String>,
        ) = withContext(Dispatchers.IO) {
            val apiClient = connectToApiBackend(api)
            val rootIdentity = signingKey.publicIdentity.ffiPrivate
            val ids = installationIds.map { it.hexToByteArray() }
            val signatureRequest = revokeInstallations(apiClient, rootIdentity, inboxId, ids)
            handleSignature(SignatureRequest(signatureRequest), signingKey)
            applySignatureRequest(apiClient, signatureRequest)
        }

        @DelicateApi(
            "This function is delicate and should be used with caution. Should only be used if trying to manage the signature flow independently otherwise use `revokeInstallations()` instead",
        )
        suspend fun ffiRevokeInstallations(
            api: ApiOptions,
            publicIdentity: PublicIdentity,
            inboxId: InboxId,
            installationIds: List<String>,
        ): SignatureRequest =
            withContext(Dispatchers.IO) {
                val apiClient = connectToApiBackend(api)
                val rootIdentity = publicIdentity.ffiPrivate
                val ids = installationIds.map { it.hexToByteArray() }
                val signatureRequest = revokeInstallations(apiClient, rootIdentity, inboxId, ids)
                SignatureRequest(signatureRequest)
            }

        @DelicateApi(
            "This function is delicate and should be used with caution. Should only be used if trying to manage the signature flow independently otherwise use `revokeInstallations()` instead",
        )
        suspend fun ffiApplySignatureRequest(
            api: ApiOptions,
            signatureRequest: SignatureRequest,
        ) = withContext(Dispatchers.IO) {
            val apiClient = connectToApiBackend(api)
            applySignatureRequest(apiClient, signatureRequest.ffiSignatureRequest)
        }

        fun register(codec: ContentCodec<*>) {
            codecRegistry.register(codec = codec)
        }

        private suspend fun <T> withFfiClient(
            api: ApiOptions,
            useClient: suspend (ffiClient: FfiXmtpClient) -> T,
        ): T =
            withContext(Dispatchers.IO) {
                val publicIdentity =
                    PublicIdentity(
                        IdentityKind.ETHEREUM,
                        "0x0000000000000000000000000000000000000000",
                    )
                val inboxId = getOrCreateInboxId(api, publicIdentity)

                val ffiClient =
                    createClient(
                        api = connectToApiBackend(api),
                        db =
                            DbOptions(
                                db = null,
                                encryptionKey = null,
                                maxDbPoolSize = null,
                                minDbPoolSize = null,
                            ),
                        accountIdentifier = publicIdentity.ffiPrivate,
                        inboxId = inboxId,
                        nonce = 0.toULong(),
                        legacySignedPrivateKeyProto = null,
                        deviceSyncMode = null,
                        allowOffline = false,
                        forkRecoveryOpts = null,
                        workerConfig = null,
                    )

                useClient(ffiClient)
            }

        suspend fun inboxStatesForInboxIds(
            inboxIds: List<InboxId>,
            api: ApiOptions,
        ): List<InboxState> =
            withContext(Dispatchers.IO) {
                val apiClient = connectToApiBackend(api)
                inboxStateFromInboxIds(apiClient, inboxIds).map { InboxState(it) }
            }

        suspend fun getNewestMessageMetadata(
            groupIds: List<String>,
            api: ApiOptions,
        ): Map<String, MessageMetadata> =
            withContext(Dispatchers.IO) {
                val apiClient = connectToApiBackend(api)
                val groupIdBytes = groupIds.map { it.hexToByteArray() }
                val result = getNewestMessageMetadata(apiClient, groupIdBytes)
                result.entries.associate { (byteArrayKey, metadata) ->
                    byteArrayKey.toHex() to metadata
                }
            }

        suspend fun keyPackageStatusesForInstallationIds(
            installationIds: List<String>,
            api: ApiOptions,
        ): Map<String, FfiKeyPackageStatus> =
            withContext(Dispatchers.IO) {
                withFfiClient(api) { ffiClient ->
                    val byteArrays = installationIds.map { it.hexToByteArray() }
                    val result = ffiClient.getKeyPackageStatusesForInstallationIds(byteArrays)
                    result.entries.associate { (byteArrayKey, status) ->
                        byteArrayKey.toHex() to status
                    }
                }
            }

        suspend fun canMessage(
            identities: List<PublicIdentity>,
            api: ApiOptions,
        ): Map<String, Boolean> =
            withContext(Dispatchers.IO) {
                withFfiClient(api) { ffiClient ->
                    val ffiIdentifiers = identities.map { it.ffiPrivate }
                    val result = ffiClient.canMessage(ffiIdentifiers)

                    result.mapKeys { (ffiIdentifier, _) ->
                        ffiIdentifier.identifier
                    }
                }
            }

        /**
         * Builds a client, delegating final instantiation to [construct] so a host
         * SDK can produce its own [JvmClient] subtype (e.g. the Android `Client`,
         * which adds `Context` conveniences). Most callers use [create], [build]
         * or [createInMemory].
         */
        suspend fun <T : JvmClient> initializeV3Client(
            publicIdentity: PublicIdentity,
            clientOptions: ClientCreateOptions,
            signingKey: SigningKey? = null,
            inboxId: InboxId? = null,
            buildOffline: Boolean = false,
            inMemory: Boolean = false,
            construct: (FfiXmtpClient, String, String, InboxId, XMTPEnvironment, PublicIdentity) -> T,
        ): T =
            withContext(Dispatchers.IO) {
                val recoveredInboxId =
                    inboxId ?: getOrCreateInboxId(clientOptions.apiOptions, publicIdentity)

                val (ffiClient, dbPath) =
                    createFfiClient(
                        publicIdentity,
                        recoveredInboxId,
                        clientOptions,
                        buildOffline,
                        inMemory,
                    )
                clientOptions.preAuthenticateToInboxCallback?.let {
                    runBlocking {
                        it.invoke()
                    }
                }
                ffiClient.signatureRequest()?.let { signatureRequest ->
                    signingKey?.let {
                        handleSignature(SignatureRequest(signatureRequest), it)
                    } ?: run {
                        XmtpLog.d("XMTP", "No signer provided. Logging DB context...")
                        XmtpLog.d("XMTP", "dbPath: $dbPath")

                        if (clientOptions.dbDirectory != null) {
                            XmtpLog.d("XMTP", "dbDirectory: ${clientOptions.dbDirectory}")

                            val dbDirFile = File(clientOptions.dbDirectory)
                            val fileCount = dbDirFile.listFiles()?.size ?: 0

                            XmtpLog.d("XMTP", "Files in dbDirectory: $fileCount")
                        }
                        throw XMTPException("No signer passed but signer was required.")
                    }

                    ffiClient.registerIdentity(
                        signatureRequest,
                        clientOptions.waitForRegistrationVisible?.toFfi(),
                    )
                }

                val client =
                    construct(
                        ffiClient,
                        dbPath,
                        ffiClient.installationId().toHex(),
                        ffiClient.inboxId(),
                        clientOptions.apiOptions.env,
                        publicIdentity,
                    )

                // Keep the shared streaming wire in step with app
                // foreground/background. Process-global and idempotent — the
                // first managed client registers it for every client.
                if (manageStreamLifecycle) {
                    streamLifecycleController.enableIfNeeded()
                }

                client
            }

        // Function to create a client with a signing key
        suspend fun create(
            account: SigningKey,
            options: ClientCreateOptions,
        ): JvmClient =
            withContext(Dispatchers.IO) {
                try {
                    initializeV3Client(account.publicIdentity, options, account, construct = ::JvmClient)
                } catch (e: Exception) {
                    throw XMTPException("Error creating V3 client: ${e.message}", e)
                }
            }

        /**
         * Creates a JvmClient backed by an in-memory SQLCipher database.
         *
         * Bypasses all on-disk database management — no `.db3` file is created
         * and no directory is touched. The returned client has [JvmClient.dbPath]
         * equal to [IN_MEMORY_DB_PATH] (`":memory:"`) and [JvmClient.isInMemory]
         * returns `true`.
         *
         * On in-memory clients, [JvmClient.deleteLocalDatabase],
         * [JvmClient.dropLocalDatabaseConnection] and
         * [JvmClient.reconnectLocalDatabase] are no-ops — state lives only in the
         * FFI pool and is discarded when the client is garbage collected.
         *
         * Intended for tests and other ephemeral flows where a real client is
         * needed but persistence is not. [ClientOptions.dbDirectory] and
         * [ClientOptions.dbEncryptionKey] are ignored — libxmtp manages the
         * in-memory store itself.
         */
        suspend fun createInMemory(
            account: SigningKey,
            options: ClientCreateOptions,
        ): JvmClient =
            withContext(Dispatchers.IO) {
                try {
                    initializeV3Client(
                        account.publicIdentity,
                        options,
                        account,
                        inMemory = true,
                        construct = ::JvmClient,
                    )
                } catch (e: Exception) {
                    throw XMTPException("Error creating in-memory V3 client: ${e.message}", e)
                }
            }

        // Function to build a client from a address
        suspend fun build(
            publicIdentity: PublicIdentity,
            options: ClientCreateOptions,
            inboxId: InboxId? = null,
        ): JvmClient =
            withContext(Dispatchers.IO) {
                try {
                    initializeV3Client(
                        publicIdentity,
                        options,
                        inboxId = inboxId,
                        buildOffline = inboxId != null,
                        construct = ::JvmClient,
                    )
                } catch (e: Exception) {
                    throw XMTPException("Error creating V3 client: ${e.message}", e)
                }
            }

        private suspend fun createFfiClient(
            publicIdentity: PublicIdentity,
            inboxId: InboxId,
            options: ClientCreateOptions,
            buildOffline: Boolean = false,
            inMemory: Boolean = false,
        ): Pair<FfiXmtpClient, String> =
            withContext(Dispatchers.IO) {
                if (inMemory) {
                    val ffiClient =
                        createClient(
                            api = connectToApiBackend(options.apiOptions),
                            db =
                                DbOptions(
                                    db = null,
                                    encryptionKey = null,
                                    maxDbPoolSize = options.dbPoolOptions?.maxPoolSize,
                                    minDbPoolSize = options.dbPoolOptions?.minPoolSize,
                                ),
                            accountIdentifier = publicIdentity.ffiPrivate,
                            inboxId = inboxId,
                            nonce = 0.toULong(),
                            legacySignedPrivateKeyProto = null,
                            deviceSyncMode =
                                if (!options.deviceSyncEnabled) {
                                    FfiDeviceSyncMode.DISABLED
                                } else {
                                    FfiDeviceSyncMode.ENABLED
                                },
                            allowOffline = buildOffline,
                            forkRecoveryOpts = options.forkRecoveryOptions?.toFfi(),
                            workerConfig = null,
                        )
                    return@withContext Pair(ffiClient, IN_MEMORY_DB_PATH)
                }

                val alias = "xmtp-${options.apiOptions.env}-$inboxId"

                val mlsDbDirectory = options.dbDirectory
                val directoryFile =
                    if (mlsDbDirectory != null) {
                        File(mlsDbDirectory)
                    } else {
                        File(options.resolveDbParentDirectory())
                    }

                if (!directoryFile.exists()) {
                    val created = directoryFile.mkdirs()
                    if (!created) {
                        throw XMTPException("Failed to create directory for database at ${directoryFile.absolutePath}")
                    }
                }
                val dbPath = directoryFile.absolutePath + "/$alias.db3"

                val ffiClient =
                    createClient(
                        api = connectToApiBackend(options.apiOptions),
                        db =
                            DbOptions(
                                db = dbPath,
                                encryptionKey = options.dbEncryptionKey,
                                maxDbPoolSize = options.dbPoolOptions?.maxPoolSize,
                                minDbPoolSize = options.dbPoolOptions?.minPoolSize,
                            ),
                        accountIdentifier = publicIdentity.ffiPrivate,
                        inboxId = inboxId,
                        nonce = 0.toULong(),
                        legacySignedPrivateKeyProto = null,
                        deviceSyncMode =
                            if (!options.deviceSyncEnabled) {
                                FfiDeviceSyncMode.DISABLED
                            } else {
                                FfiDeviceSyncMode.ENABLED
                            },
                        allowOffline = buildOffline,
                        forkRecoveryOpts = options.forkRecoveryOptions?.toFfi(),
                        workerConfig = null,
                    )
                Pair(ffiClient, dbPath)
            }

        private suspend fun handleSignature(
            signatureRequest: SignatureRequest,
            signingKey: SigningKey,
        ) {
            val signedData = signingKey.sign(signatureRequest.signatureText())

            when (signingKey.type) {
                SignerType.SCW -> {
                    val chainId =
                        signingKey.chainId ?: throw XMTPException("ChainId is required for SCW")
                    signatureRequest.addScwSignature(
                        signedData.rawData,
                        signingKey.publicIdentity.identifier,
                        chainId.toULong(),
                        signingKey.blockNumber?.toULong(),
                    )
                }

                else -> {
                    signatureRequest.addEcdsaSignature(signedData.rawData)
                }
            }
        }

        @DelicateApi(
            "This function is delicate and should be used with caution. Creating an FfiClient without signing or registering will create a broken experience use `create()` instead",
        )
        suspend fun ffiCreateClient(
            publicIdentity: PublicIdentity,
            clientOptions: ClientCreateOptions,
        ): JvmClient = ffiCreateClient(publicIdentity, clientOptions, ::JvmClient)

        @DelicateApi(
            "This function is delicate and should be used with caution. Creating an FfiClient without signing or registering will create a broken experience use `create()` instead",
        )
        suspend fun <T : JvmClient> ffiCreateClient(
            publicIdentity: PublicIdentity,
            clientOptions: ClientCreateOptions,
            construct: (FfiXmtpClient, String, String, InboxId, XMTPEnvironment, PublicIdentity) -> T,
        ): T =
            withContext(Dispatchers.IO) {
                val recoveredInboxId = getOrCreateInboxId(clientOptions.apiOptions, publicIdentity)

                val (ffiClient, dbPath) =
                    createFfiClient(
                        publicIdentity,
                        recoveredInboxId,
                        clientOptions,
                    )
                val client =
                    construct(
                        ffiClient,
                        dbPath,
                        ffiClient.installationId().toHex(),
                        ffiClient.inboxId(),
                        clientOptions.apiOptions.env,
                        publicIdentity,
                    )
                if (manageStreamLifecycle) {
                    streamLifecycleController.enableIfNeeded()
                }
                client
            }
    }

    suspend fun revokeInstallations(
        signingKey: SigningKey,
        installationIds: List<String>,
    ) = withContext(Dispatchers.IO) {
        val ids = installationIds.map { it.hexToByteArray() }
        val signatureRequest = ffiRevokeInstallations(ids)
        handleSignature(signatureRequest, signingKey)
        ffiApplySignatureRequest(signatureRequest)
    }

    suspend fun revokeAllOtherInstallations(signingKey: SigningKey) =
        withContext(Dispatchers.IO) {
            ffiRevokeAllOtherInstallations()?.let {
                handleSignature(it, signingKey)
                ffiApplySignatureRequest(it)
            }
        }

    @DelicateApi(
        "This function is delicate and should be used with caution. Adding a identity already associated with an inboxId will cause the identity to lose access to that inbox. See: inboxIdFromIdentity(publicIdentity)",
    )
    suspend fun addAccount(
        newAccount: SigningKey,
        allowReassignInboxId: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val signatureRequest = ffiAddIdentity(newAccount.publicIdentity, allowReassignInboxId)
        handleSignature(signatureRequest, newAccount)
        ffiApplySignatureRequest(signatureRequest)
    }

    suspend fun removeAccount(
        recoverAccount: SigningKey,
        publicIdentityToRemove: PublicIdentity,
    ) = withContext(Dispatchers.IO) {
        val signatureRequest = ffiRevokeIdentity(publicIdentityToRemove)
        handleSignature(signatureRequest, recoverAccount)
        ffiApplySignatureRequest(signatureRequest)
    }

    fun signWithInstallationKey(message: String): ByteArray = ffiClient.signWithInstallationKey(message)

    fun verifySignature(
        message: String,
        signature: ByteArray,
    ): Boolean =
        try {
            ffiClient.verifySignedWithInstallationKey(message, signature)
            true
        } catch (e: Exception) {
            false
        }

    fun verifySignatureWithInstallationId(
        message: String,
        signature: ByteArray,
        installationId: String,
    ): Boolean =
        try {
            ffiClient.verifySignedWithPublicKey(message, signature, installationId.hexToByteArray())
            true
        } catch (e: Exception) {
            false
        }

    suspend fun canMessage(identities: List<PublicIdentity>): Map<String, Boolean> =
        withContext(Dispatchers.IO) {
            val ffiIdentifiers = identities.map { it.ffiPrivate }
            val result = ffiClient.canMessage(ffiIdentifiers)

            result.mapKeys { (ffiIdentifier, _) ->
                ffiIdentifier.identifier
            }
        }

    suspend fun inboxIdFromIdentity(publicIdentity: PublicIdentity): InboxId? =
        withContext(Dispatchers.IO) {
            ffiClient.findInboxId(publicIdentity.ffiPrivate)
        }

    suspend fun deleteLocalDatabase() =
        withContext(Dispatchers.IO) {
            // In-memory clients have no on-disk file; nothing to drop or remove.
            if (isInMemory) return@withContext
            dropLocalDatabaseConnection()
            File(dbPath).delete()
        }

    @DelicateApi(
        "This function is delicate and should be used with caution. App will error if database not properly reconnected. See: reconnectLocalDatabase()",
    )
    suspend fun dropLocalDatabaseConnection() =
        withContext(Dispatchers.IO) {
            // In-memory clients hold their state in the FFI pool; releasing the
            // connection would discard data that cannot be restored on reconnect.
            if (isInMemory) return@withContext
            ffiClient.releaseDbConnection()
        }

    suspend fun reconnectLocalDatabase() =
        withContext(Dispatchers.IO) {
            if (isInMemory) return@withContext
            ffiClient.dbReconnect()
        }

    /**
     * Bring the local store current with the network, then stop — for background
     * fetch and cold start, where holding a live stream is wasted because the
     * wire is about to go away.
     *
     * Pass [timeoutMs] to bound the run against a background budget (a
     * WorkManager job, an FCM handler); null runs to the live edge. A negative
     * value is treated as 0 (return immediately). Cutting it short is safe:
     * everything processed is persisted and a later call resumes from durable
     * state.
     *
     * Check [CatchUpSummary.completed] before treating the counts as the whole
     * story: on the deadline path it is false, whatever was processed before
     * the cut is already stored (the counts may undercount it), and a later
     * call resumes from there.
     */
    suspend fun catchUpToLive(timeoutMs: Long? = null): CatchUpSummary =
        withContext(Dispatchers.IO) {
            CatchUpSummary(
                ffiClient.catchUpToLive(
                    FfiCatchUpOptions(timeoutMs = timeoutMs?.coerceAtLeast(0)?.toULong()),
                ),
            )
        }

    suspend fun inboxStatesForInboxIds(
        refreshFromNetwork: Boolean,
        inboxIds: List<InboxId>,
    ): List<InboxState> =
        withContext(Dispatchers.IO) {
            ffiClient
                .addressesFromInboxId(refreshFromNetwork, inboxIds)
                .map { InboxState(it) }
        }

    suspend fun inboxState(refreshFromNetwork: Boolean): InboxState =
        withContext(Dispatchers.IO) {
            InboxState(ffiClient.inboxState(refreshFromNetwork))
        }

    /**
     * Manually trigger a device sync request to sync records from another active device on this account.
     */
    suspend fun sendSyncRequest(
        opts: ArchiveOptions = ArchiveOptions(),
        serverUrl: String = environment.getHistorySyncUrl(),
    ) = withContext(Dispatchers.IO) {
        ffiClient.sendSyncRequest(opts.toFfi(), serverUrl)
    }

    /**
     * Manually send a sync archive to the sync group.
     * The pin will be later used as a reference when importing.
     */
    suspend fun sendSyncArchive(
        opts: ArchiveOptions = ArchiveOptions(),
        serverUrl: String = environment.getHistorySyncUrl(),
        pin: String,
    ) = withContext(Dispatchers.IO) {
        ffiClient.sendSyncArchive(opts.toFfi(), serverUrl, pin)
    }

    /**
     * Manually process a sync archive that matches the pin given.
     * If no pin is given, then it will process the last archive sent.
     */
    suspend fun processSyncArchive(archivePin: String? = null) =
        withContext(Dispatchers.IO) {
            ffiClient.processSyncArchive(archivePin)
        }

    /**
     * List the archives available for import in the sync group.
     * You may need to manually sync the sync group before calling
     * this function to see recently uploaded archives.
     */
    suspend fun listAvailableArchives(daysCutoff: Long): List<AvailableArchive> =
        withContext(Dispatchers.IO) {
            ffiClient.listAvailableArchives(daysCutoff).map { AvailableArchive(it) }
        }

    /**
     * Manually sync all device sync groups.
     */
    suspend fun syncAllDeviceSyncGroups(): GroupSyncSummary =
        withContext(Dispatchers.IO) {
            GroupSyncSummary.fromFfi(ffiClient.syncAllDeviceSyncGroups())
        }

    suspend fun createArchive(
        path: String,
        encryptionKey: ByteArray,
        opts: ArchiveOptions = ArchiveOptions(),
    ) = withContext(Dispatchers.IO) {
        ffiClient.createArchive(path, opts.toFfi(), encryptionKey)
    }

    suspend fun importArchive(
        path: String,
        encryptionKey: ByteArray,
    ) = withContext(Dispatchers.IO) {
        ffiClient.importArchive(path, encryptionKey)
    }

    suspend fun archiveMetadata(
        path: String,
        encryptionKey: ByteArray,
    ): ArchiveMetadata =
        withContext(Dispatchers.IO) {
            ArchiveMetadata(ffiClient.archiveMetadata(path, encryptionKey))
        }

    @DelicateApi(
        "This function is delicate and should be used with caution. Should only be used if trying to manage the signature flow independently otherwise use `addAccount(), removeAccount(), or revoke()` instead",
    )
    suspend fun ffiApplySignatureRequest(signatureRequest: SignatureRequest) {
        ffiClient.applySignatureRequest(signatureRequest.ffiSignatureRequest)
    }

    @DelicateApi(
        "This function is delicate and should be used with caution. Should only be used if trying to manage the signature flow independently otherwise use `revokeInstallations()` instead",
    )
    suspend fun ffiRevokeInstallations(ids: List<ByteArray>): SignatureRequest =
        SignatureRequest(ffiClient.revokeInstallations(ids))

    @DelicateApi(
        "This function is delicate and should be used with caution. Should only be used if trying to manage the signature flow independently otherwise use `revokeAllOtherInstallations()` instead",
    )
    suspend fun ffiRevokeAllOtherInstallations(): SignatureRequest? =
        ffiClient.revokeAllOtherInstallationsSignatureRequest()?.let { SignatureRequest(it) }

    @DelicateApi(
        "This function is delicate and should be used with caution. Should only be used if trying to manage the signature flow independently otherwise use `removeAccount()` instead",
    )
    suspend fun ffiRevokeIdentity(publicIdentityToRemove: PublicIdentity): SignatureRequest =
        SignatureRequest(ffiClient.revokeIdentity(publicIdentityToRemove.ffiPrivate))

    @DelicateApi(
        "This function is delicate and should be used with caution. Should only be used if trying to manage the create and register flow independently otherwise use `addAccount()` instead",
    )
    suspend fun ffiAddIdentity(
        publicIdentityToAdd: PublicIdentity,
        allowReassignInboxId: Boolean = false,
    ): SignatureRequest {
        val inboxId: InboxId? =
            if (!allowReassignInboxId) {
                inboxIdFromIdentity(
                    PublicIdentity(
                        publicIdentityToAdd.kind,
                        publicIdentityToAdd.identifier,
                    ),
                )
            } else {
                null
            }

        if (allowReassignInboxId || inboxId.isNullOrBlank()) {
            return SignatureRequest(ffiClient.addIdentity(publicIdentityToAdd.ffiPrivate))
        } else {
            throw XMTPException("This identity is already associated with inbox $inboxId")
        }
    }

    @DelicateApi(
        "This function is delicate and should be used with caution. Should only be used if trying to manage the signature flow independently otherwise use `create()` instead",
    )
    fun ffiSignatureRequest(): SignatureRequest? = ffiClient.signatureRequest()?.let { SignatureRequest(it) }

    @DelicateApi(
        "This function is delicate and should be used with caution. Should only be used if trying to manage the create and register flow independently otherwise use `create()` instead",
    )
    suspend fun ffiRegisterIdentity(
        signatureRequest: SignatureRequest,
        visibilityConfirmationOptions: VisibilityConfirmationOptions? = null,
    ) {
        ffiClient.registerIdentity(
            signatureRequest.ffiSignatureRequest,
            visibilityConfirmationOptions?.toFfi(),
        )
    }
}
