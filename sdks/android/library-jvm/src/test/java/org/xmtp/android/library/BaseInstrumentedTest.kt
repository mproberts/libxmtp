package org.xmtp.android.library

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.xmtp.android.library.messages.PrivateKeyBuilder
import java.io.File
import java.security.SecureRandom

/**
 * Base class for the pure-JVM E2E suite (migrated from the Android instrumented
 * suite). Provides client tracking + cleanup and per-client temp DB directories.
 * Requires the local docker backend (`just backend up`) reachable on localhost.
 */
abstract class BaseInstrumentedTest {
    private val createdClients = mutableListOf<JvmClient>()
    private val dbFolders = mutableListOf<String>()

    @get:Rule
    val testDbDir = TemporaryFolder()
    protected val dbEncryptionKey: ByteArray = SecureRandom().generateSeed(32)

    /** LOCAL environment pointed at localhost (the docker backend is published on the host). */
    protected fun localApi(): JvmApi =
        JvmApi(env = XMTPEnvironment.LOCAL.withValue("localhost"))

    @Before
    open fun setUp() {
        testDbDir.create()
    }

    @After
    open fun tearDown() {
        runBlocking {
            createdClients.forEach { client ->
                try {
                    client.dropLocalDatabaseConnection()
                } catch (e: Exception) {
                    println("Warning: Failed to close database for client: ${e.message}")
                }
            }
        }
        createdClients.clear()
        dbFolders.forEach {
            try {
                File(it).deleteRecursively()
            } catch (e: Exception) {
            }
        }
        dbFolders.clear()
        System.gc()
    }

    protected suspend fun createClient(
        account: SigningKey,
        api: ApiOptions = localApi(),
        deviceSyncEnabled: Boolean = true,
    ): JvmClient {
        val options = createClientOptions(api, deviceSyncEnabled = deviceSyncEnabled)
        val client = JvmClient.create(account = account, options = options)
        createdClients.add(client)
        return client
    }

    protected suspend fun createFixtures(
        api: ApiOptions = localApi(),
    ): TestFixtures {
        val alixAccount = PrivateKeyBuilder()
        val boAccount = PrivateKeyBuilder()
        val caroAccount = PrivateKeyBuilder()

        val (alixClient, boClient, caroClient) =
            coroutineScope {
                val alixDeferred = async { createClient(alixAccount, api) }
                val boDeferred = async { createClient(boAccount, api) }
                val caroDeferred = async { createClient(caroAccount, api) }
                Triple(alixDeferred.await(), boDeferred.await(), caroDeferred.await())
            }

        return TestFixtures(
            alixAccount = alixAccount,
            alix = alixAccount.getPrivateKey(),
            alixClient = alixClient,
            boAccount = boAccount,
            bo = boAccount.getPrivateKey(),
            boClient = boClient,
            caroAccount = caroAccount,
            caro = caroAccount.getPrivateKey(),
            caroClient = caroClient,
        )
    }

    private fun randomSubfolder(): String {
        val clientDbDir = testDbDir.newFolder()
        clientDbDir.mkdirs()
        return clientDbDir.absolutePath
    }

    protected fun createClientOptions(
        api: ApiOptions = localApi(),
        dbDirectory: String? = null,
        deviceSyncEnabled: Boolean = true,
    ): JvmClientOptions {
        val finalDbDirectory = dbDirectory ?: randomSubfolder()
        dbFolders.add(finalDbDirectory)
        return JvmClientOptions(
            dbEncryptionKey = dbEncryptionKey,
            apiOptions = api,
            dbDirectory = finalDbDirectory,
            deviceSyncEnabled = deviceSyncEnabled,
        )
    }

    protected fun createWallet(): PrivateKeyBuilder = PrivateKeyBuilder()
}

data class TestFixtures(
    val alixAccount: PrivateKeyBuilder,
    val alix: org.xmtp.android.library.messages.PrivateKey,
    val alixClient: JvmClient,
    val boAccount: PrivateKeyBuilder,
    val bo: org.xmtp.android.library.messages.PrivateKey,
    val boClient: JvmClient,
    val caroAccount: PrivateKeyBuilder,
    val caro: org.xmtp.android.library.messages.PrivateKey,
    val caroClient: JvmClient,
)
