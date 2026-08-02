package org.xmtp.android.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.xmtp.android.library.JvmClient.Companion.ffiApplySignatureRequest
import org.xmtp.android.library.JvmClient.Companion.ffiRevokeInstallations
import org.xmtp.android.library.libxmtp.IdentityKind
import org.xmtp.android.library.libxmtp.PublicIdentity
import org.xmtp.android.library.messages.PrivateKeyBuilder
import org.xmtp.android.library.messages.walletAddress
import uniffi.xmtpv3.FfiException
import uniffi.xmtpv3.FfiLogLevel
import uniffi.xmtpv3.FfiLogRotation
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ClientTest : BaseInstrumentedTest() {
    @Test
    fun testCanBeCreatedWithBundle() {
        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")
        val fakeWallet = PrivateKeyBuilder()
        val options =
            JvmClientOptions(
                apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                dbEncryptionKey = key,
            )
        val client = runBlocking { JvmClient.create(account = fakeWallet, options = options) }

        val clientIdentity = fakeWallet.publicIdentity
        runBlocking {
            client.canMessage(listOf(clientIdentity))[clientIdentity.identifier]?.let { assert(it) }
        }

        val fromBundle = runBlocking { JvmClient.build(clientIdentity, options = options) }
        assertEquals(client.inboxId, fromBundle.inboxId)

        runBlocking {
            fromBundle.canMessage(listOf(clientIdentity))[clientIdentity.identifier]?.let {
                assert(it)
            }
        }
    }

    @Test
    fun testCanBeBuiltOffline() =
        runBlocking {
            val fixtures = createFixtures()
            val wallet = createWallet()
            val client = createClient(wallet)

            client.debugInformation.clearAllStatistics()
            println(client.debugInformation.aggregateStatistics)

            val dbDir = File(client.dbPath).parent
            val builtClient =
                JvmClient.build(
                    client.publicIdentity,
                    JvmClientOptions(
                        apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                        dbEncryptionKey = dbEncryptionKey,
                        dbDirectory = dbDir,
                    ),
                    client.inboxId,
                )
            println(client.debugInformation.aggregateStatistics)
            assertEquals(client.inboxId, builtClient.inboxId)

            val group = builtClient.conversations.newGroup(listOf(fixtures.alixClient.inboxId))
            group.send("howdy")
            val alixDm = fixtures.alixClient.conversations.newConversation(builtClient.inboxId)
            alixDm.send("howdy")
            val boGroup =
                fixtures.boClient.conversations.newGroupWithIdentities(
                    listOf(builtClient.publicIdentity),
                )
            boGroup.send("howdy")
            builtClient.conversations.syncAllConversations()
            val convos = builtClient.conversations.list()

            assertEquals(convos.size, 3)
        }

    @Test
    fun testCreatesAClient() {
        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")
        val fakeWallet = PrivateKeyBuilder()
        val options =
            JvmClientOptions(
                apiOptions = JvmApi(XMTPEnvironment.LOCAL, appVersion = "Testing/0.0.0"),
                dbEncryptionKey = key,
            )
        val clientIdentity = fakeWallet.publicIdentity

        val inboxId = runBlocking { JvmClient.getOrCreateInboxId(options.apiOptions, clientIdentity) }
        val client = runBlocking { JvmClient.create(account = fakeWallet, options = options) }
        runBlocking {
            client.canMessage(listOf(clientIdentity))[clientIdentity.identifier]?.let { assert(it) }
        }
        assert(client.installationId.isNotEmpty())
        assertEquals(inboxId, client.inboxId)
        assertEquals(fakeWallet.publicIdentity.identifier, client.publicIdentity.identifier)
    }

    @Test
    fun testStaticCanMessage() {
        val fixtures = runBlocking { createFixtures() }
        val notOnNetwork = PrivateKeyBuilder()
        val alixPublicIdentity = PublicIdentity(IdentityKind.ETHEREUM, fixtures.alix.walletAddress)
        val boPublicIdentity = PublicIdentity(IdentityKind.ETHEREUM, fixtures.bo.walletAddress)
        val notOnNetworkPublicIdentity =
            PublicIdentity(IdentityKind.ETHEREUM, notOnNetwork.getPrivateKey().walletAddress)

        val canMessageList =
            runBlocking {
                JvmClient.canMessage(
                    listOf(alixPublicIdentity, notOnNetworkPublicIdentity, boPublicIdentity),
                    JvmApi(XMTPEnvironment.LOCAL),
                )
            }

        val expectedResults =
            mapOf(
                alixPublicIdentity to true,
                notOnNetworkPublicIdentity to false,
                boPublicIdentity to true,
            )

        expectedResults.forEach { (id, expected) ->
            assertEquals(expected, canMessageList[id.identifier])
        }
    }

    @Test
    fun testStaticInboxIds() {
        val fixtures = runBlocking { createFixtures() }
        val states =
            runBlocking {
                JvmClient.inboxStatesForInboxIds(
                    listOf(fixtures.boClient.inboxId, fixtures.caroClient.inboxId),
                    JvmApi(XMTPEnvironment.LOCAL),
                )
            }
        assertEquals(
            states.first().recoveryPublicIdentity.identifier,
            fixtures.boAccount.publicIdentity.identifier,
        )
        assertEquals(
            states.last().recoveryPublicIdentity.identifier,
            fixtures.caroAccount.publicIdentity.identifier,
        )
    }

    @Test
    fun testCanDeleteDatabase() {
        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")
        val fakeWallet = PrivateKeyBuilder()
        val fakeWallet2 = PrivateKeyBuilder()
        var client =
            runBlocking {
                JvmClient.create(
                    account = fakeWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )
            }
        val client2 =
            runBlocking {
                JvmClient.create(
                    account = fakeWallet2,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )
            }

        runBlocking {
            client.conversations.newGroup(listOf(client2.inboxId))
            client.conversations.sync()
            assertEquals(client.conversations.listGroups().size, 1)
        }

        assert(client.dbPath.isNotEmpty())
        runBlocking { client.deleteLocalDatabase() }

        client =
            runBlocking {
                JvmClient.create(
                    account = fakeWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )
            }
        runBlocking {
            client.conversations.sync()
            assertEquals(client.conversations.listGroups().size, 0)
        }
    }

    @Test
    fun testCreatesADevClient() {
        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")
        val fakeWallet = PrivateKeyBuilder()
        val client =
            runBlocking {
                JvmClient.create(
                    account = fakeWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.DEV),
                            dbEncryptionKey = key,
                        ),
                )
            }
        val clientIdentity = fakeWallet.publicIdentity
        runBlocking {
            client.canMessage(listOf(clientIdentity))[clientIdentity.identifier]?.let { assert(it) }
        }
    }

    @Test
    fun testCreatesAProductionClient() {
        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")
        val fakeWallet = PrivateKeyBuilder()
        val client =
            runBlocking {
                JvmClient.create(
                    account = fakeWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.PRODUCTION),
                            dbEncryptionKey = key,
                        ),
                )
            }
        val clientIdentity = fakeWallet.publicIdentity
        runBlocking {
            client.canMessage(listOf(clientIdentity))[clientIdentity.identifier]?.let { assert(it) }
        }
    }

    @Test
    fun testPreAuthenticateToInboxCallback() {
        val fakeWallet = PrivateKeyBuilder()
        val expectation = CompletableFuture<Unit>()
        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")

        val preAuthenticateToInboxCallback: suspend () -> Unit = { expectation.complete(Unit) }

        val opts =
            JvmClientOptions(
                apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                preAuthenticateToInboxCallback = preAuthenticateToInboxCallback,
                dbEncryptionKey = key,
            )

        try {
            runBlocking { JvmClient.create(account = fakeWallet, options = opts) }
            expectation.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            fail("Error: $e")
        }
    }

    @Test
    fun testCanDropReconnectDatabase() {
        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")
        val fakeWallet = PrivateKeyBuilder()
        val fakeWallet2 = PrivateKeyBuilder()
        val boClient =
            runBlocking {
                JvmClient.create(
                    account = fakeWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )
            }
        val alixClient =
            runBlocking {
                JvmClient.create(
                    account = fakeWallet2,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )
            }

        runBlocking {
            boClient.conversations.newGroup(listOf(alixClient.inboxId))
            boClient.conversations.sync()
        }

        runBlocking { assertEquals(boClient.conversations.listGroups().size, 1) }

        runBlocking { boClient.dropLocalDatabaseConnection() }

        assertThrows(
            "JvmClient error: storage error: Pool needs to  reconnect before use",
            FfiException::class.java,
        ) { runBlocking { boClient.conversations.listGroups() } }

        runBlocking { boClient.reconnectLocalDatabase() }

        runBlocking { assertEquals(boClient.conversations.listGroups().size, 1) }
    }

    @Test
    fun testCanGetAnInboxIdFromAddress() {
        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")
        val alixWallet = PrivateKeyBuilder()
        val boWallet = PrivateKeyBuilder()
        val alixClient =
            runBlocking {
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )
            }
        val boClient =
            runBlocking {
                JvmClient.create(
                    account = boWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )
            }
        val boInboxId =
            runBlocking {
                alixClient.inboxIdFromIdentity(
                    PublicIdentity(IdentityKind.ETHEREUM, boWallet.getPrivateKey().walletAddress),
                )
            }
        assertEquals(boClient.inboxId, boInboxId)
    }

    @Test
    fun testRevokesInstallations() {
        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")
        val alixWallet = PrivateKeyBuilder()

        val alixClient =
            runBlocking {
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )
            }

        val alixClient2 =
            runBlocking {
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                            dbDirectory = filesDir.absolutePath.toString(),
                        ),
                )
            }

        val alixClient3 =
            runBlocking {
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                            dbDirectory =
                                File(filesDir.absolutePath, "xmtp_db3")
                                    .toPath()
                                    .toString(),
                        ),
                )
            }

        var state = runBlocking { alixClient3.inboxState(true) }
        assertEquals(state.installations.size, 3)

        runBlocking {
            alixClient3.revokeInstallations(alixWallet, listOf(alixClient2.installationId))
        }

        state = runBlocking { alixClient3.inboxState(true) }
        assertEquals(state.installations.size, 2)
    }

    @Test
    fun testRevokesAllOtherInstallations() {
        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")
        val alixWallet = PrivateKeyBuilder()
        runBlocking {
            val alixClient =
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )

            val alixClient2 =
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                            dbDirectory = filesDir.absolutePath.toString(),
                        ),
                )
        }

        val alixClient3 =
            runBlocking {
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                            dbDirectory =
                                File(filesDir.absolutePath, "xmtp_db3")
                                    .toPath()
                                    .toString(),
                        ),
                )
            }

        var state = runBlocking { alixClient3.inboxState(true) }
        assertEquals(state.installations.size, 3)
        assert(state.installations.first().createdAt != null)

        runBlocking { alixClient3.revokeAllOtherInstallations(alixWallet) }

        state = runBlocking { alixClient3.inboxState(true) }
        assertEquals(state.installations.size, 1)
    }

    @Test
    fun testsCanFindOthersInboxStates() {
        val fixtures = runBlocking { createFixtures() }
        val states =
            runBlocking {
                fixtures.alixClient.inboxStatesForInboxIds(
                    true,
                    listOf(fixtures.boClient.inboxId, fixtures.caroClient.inboxId),
                )
            }
        assertEquals(states.first().recoveryPublicIdentity.identifier, fixtures.bo.walletAddress)
        assertEquals(states.last().recoveryPublicIdentity.identifier, fixtures.caro.walletAddress)
    }

    @Test
    fun testsCanSeeKeyPackageStatus() {
        val fixtures = runBlocking { createFixtures() }
        runBlocking { JvmClient.connectToApiBackend(JvmApi(XMTPEnvironment.LOCAL)) }
        val inboxState =
            runBlocking {
                JvmClient
                    .inboxStatesForInboxIds(
                        listOf(fixtures.alixClient.inboxId),
                        JvmApi(XMTPEnvironment.LOCAL),
                    ).first()
            }
        val installationIds = inboxState.installations.map { it.installationId }
        val keyPackageStatus =
            runBlocking {
                JvmClient.keyPackageStatusesForInstallationIds(
                    installationIds,
                    JvmApi(XMTPEnvironment.LOCAL),
                )
            }
        for (installationId: String in keyPackageStatus.keys) {
            val thisKPStatus = keyPackageStatus.get(installationId)!!
            val notBeforeDate =
                thisKPStatus.lifetime?.notBefore?.let {
                    java.time.Instant
                        .ofEpochSecond(it.toLong())
                        .toString()
                }
                    ?: "null"
            val notAfterDate =
                thisKPStatus.lifetime?.notAfter?.let {
                    java.time.Instant
                        .ofEpochSecond(it.toLong())
                        .toString()
                }
                    ?: "null"
            println(
                "inst: " +
                    installationId +
                    " - valid from: " +
                    notBeforeDate +
                    " to: " +
                    notAfterDate,
            )
            println("error code: " + thisKPStatus.validationError)
            val notBefore = thisKPStatus.lifetime?.notBefore
            val notAfter = thisKPStatus.lifetime?.notAfter
            if (notBefore != null && notAfter != null) {
                assertEquals((3600 * 24 * 28 * 3 + 3600).toULong(), notAfter - notBefore)
            }
        }
    }

    //    @Test
    //    fun testsCanSeeInvalidKeyPackageStatusOnDev() {
    //        runBlocking {
    //            JvmClient.connectToApiBackend(
    //                JvmApi(
    //                    XMTPEnvironment.DEV,
    //                    true
    //                )
    //            )
    //        }
    //        val inboxState = runBlocking {
    //            JvmClient.inboxStatesForInboxIds(
    //                listOf("f87420435131ea1b911ad66fbe4b626b107f81955da023d049f8aef6636b8e1b"),
    //                JvmApi(XMTPEnvironment.DEV)
    //            ).first()
    //        }
    //        val installationIds = inboxState.installations.map { it.installationId }
    //        val keyPackageStatus = runBlocking {
    //            JvmClient.keyPackageStatusesForInstallationIds(
    //                installationIds,
    //                JvmApi(XMTPEnvironment.DEV)
    //            )
    //        }
    //        for (installationId: String in keyPackageStatus.keys) {
    //            val thisKPStatus = keyPackageStatus.get(installationId)!!
    //            val notBeforeDate = thisKPStatus.lifetime?.notBefore?.let {
    //                java.time.Instant.ofEpochSecond(it.toLong()).toString()
    //            } ?: "null"
    //            val notAfterDate = thisKPStatus.lifetime?.notAfter?.let {
    //                java.time.Instant.ofEpochSecond(it.toLong()).toString()
    //            } ?: "null"
    //            println("inst: " + installationId + " - valid from: " + notBeforeDate + " to: " +
    // notAfterDate)
    //            println("error code: " + thisKPStatus.validationError)
    //        }
    //    }

    @Test
    fun testsSignatures() {
        val fixtures = runBlocking { createFixtures() }
        val signature = fixtures.alixClient.signWithInstallationKey("Testing")
        assertEquals(fixtures.alixClient.verifySignature("Testing", signature), true)
        assertEquals(fixtures.alixClient.verifySignature("Not Testing", signature), false)

        val alixInstallationId = fixtures.alixClient.installationId
        assertEquals(
            fixtures.alixClient.verifySignatureWithInstallationId(
                "Testing",
                signature,
                alixInstallationId,
            ),
            true,
        )
        assertEquals(
            fixtures.alixClient.verifySignatureWithInstallationId(
                "Not Testing",
                signature,
                alixInstallationId,
            ),
            false,
        )
        assertEquals(
            fixtures.alixClient.verifySignatureWithInstallationId(
                "Testing",
                signature,
                fixtures.boClient.installationId,
            ),
            false,
        )
        assertEquals(
            fixtures.boClient.verifySignatureWithInstallationId(
                "Testing",
                signature,
                alixInstallationId,
            ),
            true,
        )
        runBlocking { fixtures.alixClient.deleteLocalDatabase() }

        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")
        val alixClient2 =
            runBlocking {
                JvmClient.create(
                    account = fixtures.alixAccount,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )
            }

        assertEquals(
            alixClient2.verifySignatureWithInstallationId(
                "Testing",
                signature,
                alixInstallationId,
            ),
            true,
        )
        assertEquals(
            alixClient2.verifySignatureWithInstallationId(
                "Testing2",
                signature,
                alixInstallationId,
            ),
            false,
        )
    }

    @OptIn(DelicateApi::class)
    @Test
    fun testAddAccounts() {
        val fixtures = runBlocking { createFixtures() }
        val alix2Wallet = PrivateKeyBuilder()
        val alix3Wallet = PrivateKeyBuilder()
        runBlocking { fixtures.alixClient.addAccount(alix2Wallet) }
        runBlocking { fixtures.alixClient.addAccount(alix3Wallet) }

        val state = runBlocking { fixtures.alixClient.inboxState(true) }
        assertEquals(state.installations.size, 1)
        assertEquals(state.identities.size, 3)
        assertEquals(
            state.recoveryPublicIdentity.identifier,
            fixtures.alixAccount.publicIdentity.identifier,
        )
        assertEquals(
            state.identities.map { it.identifier }.sorted(),
            listOf(
                alix2Wallet.publicIdentity.identifier,
                alix3Wallet.publicIdentity.identifier,
                fixtures.alix.walletAddress,
            ).sorted(),
        )
    }

    @OptIn(DelicateApi::class)
    @Test
    fun testAddAccountsWithExistingInboxIds() {
        val fixtures = runBlocking { createFixtures() }

        assertThrows(
            "This wallet is already associated with inbox ${fixtures.boClient.inboxId}",
            XMTPException::class.java,
        ) { runBlocking { fixtures.alixClient.addAccount(fixtures.boAccount) } }

        assert(fixtures.boClient.inboxId != fixtures.alixClient.inboxId)
        runBlocking { fixtures.alixClient.addAccount(fixtures.boAccount, true) }

        val state = runBlocking { fixtures.alixClient.inboxState(true) }
        assertEquals(state.identities.size, 2)

        val inboxId =
            runBlocking {
                fixtures.alixClient.inboxIdFromIdentity(
                    PublicIdentity(IdentityKind.ETHEREUM, fixtures.bo.walletAddress),
                )
            }
        assertEquals(inboxId, fixtures.alixClient.inboxId)
    }

    @OptIn(DelicateApi::class)
    @Test
    fun testRemovingAccounts() {
        val fixtures = runBlocking { createFixtures() }
        val alix2Wallet = PrivateKeyBuilder()
        val alix3Wallet = PrivateKeyBuilder()
        runBlocking { fixtures.alixClient.addAccount(alix2Wallet) }
        runBlocking { fixtures.alixClient.addAccount(alix3Wallet) }

        var state = runBlocking { fixtures.alixClient.inboxState(true) }
        assertEquals(state.identities.size, 3)
        assertEquals(
            state.recoveryPublicIdentity.identifier,
            fixtures.alixAccount.publicIdentity.identifier,
        )

        runBlocking {
            fixtures.alixClient.removeAccount(
                fixtures.alixAccount,
                PublicIdentity(IdentityKind.ETHEREUM, alix2Wallet.getPrivateKey().walletAddress),
            )
        }
        state = runBlocking { fixtures.alixClient.inboxState(true) }
        assertEquals(state.identities.size, 2)
        assertEquals(state.recoveryPublicIdentity.identifier, fixtures.alix.walletAddress)
        assertEquals(
            state.identities.map { it.identifier }.sorted(),
            listOf(
                alix3Wallet.getPrivateKey().walletAddress,
                fixtures.alixAccount.publicIdentity.identifier,
            ).sorted(),
        )
        assertEquals(state.installations.size, 1)

        // Cannot remove the recovery address
        assertThrows("JvmClient error: Unknown Signer", FfiException::class.java) {
            runBlocking {
                fixtures.alixClient.removeAccount(alix3Wallet, fixtures.alixAccount.publicIdentity)
            }
        }
    }

    @Test
    fun testErrorsIfDbEncryptionKeyIsLost() {
        val key = SecureRandom().generateSeed(32)
        val badKey = SecureRandom().generateSeed(32)

        val filesDir = createTempDir("xmtp")
        val alixWallet = PrivateKeyBuilder()

        val alixClient =
            runBlocking {
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )
            }

        assertThrows(
            "Error creating V3 client: Storage error: PRAGMA key or salt has incorrect value",
            XMTPException::class.java,
        ) {
            runBlocking {
                JvmClient.build(
                    publicIdentity =
                        PublicIdentity(
                            IdentityKind.ETHEREUM,
                            alixWallet.getPrivateKey().walletAddress,
                        ),
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = badKey,
                        ),
                )
            }
        }

        assertThrows(
            "Error creating V3 client: Storage error: PRAGMA key or salt has incorrect value",
            XMTPException::class.java,
        ) {
            runBlocking {
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = badKey,
                        ),
                )
            }
        }
    }

    @Test
    fun testCreatesAClientManually() {
        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")
        val fakeWallet = PrivateKeyBuilder()
        val options =
            JvmClientOptions(
                apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                dbEncryptionKey = key,
            )
        val inboxId =
            runBlocking {
                JvmClient.getOrCreateInboxId(options.apiOptions, fakeWallet.publicIdentity)
            }
        val client = runBlocking { JvmClient.ffiCreateClient(fakeWallet.publicIdentity, options) }
        runBlocking {
            val sigRequest = client.ffiSignatureRequest()
            sigRequest?.let { signatureRequest ->
                signatureRequest.addEcdsaSignature(
                    fakeWallet.sign(signatureRequest.signatureText()).rawData,
                )
                client.ffiRegisterIdentity(signatureRequest)
            }
        }
        runBlocking {
            client
                .canMessage(listOf(fakeWallet.publicIdentity))[
                fakeWallet.publicIdentity.identifier,
            ]?.let { assert(it) }
        }
        assert(client.installationId.isNotEmpty())
        assertEquals(inboxId, client.inboxId)
    }

    @Test
    fun testCanManageAddRemoveManually() =
        runBlocking {
            val key = SecureRandom().generateSeed(32)
            val filesDir = createTempDir("xmtp")
            val alixWallet = PrivateKeyBuilder()
            val boWallet = PrivateKeyBuilder()

            val options =
                JvmClientOptions(
                    apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                    dbEncryptionKey = key,
                )

            val alix = JvmClient.create(alixWallet, options)

            var inboxState = alix.inboxState(true)
            assertEquals(1, inboxState.identities.size)

            val sigRequest = alix.ffiAddIdentity(boWallet.publicIdentity)
            val signedMessage = boWallet.sign(sigRequest.signatureText()).rawData

            sigRequest.addEcdsaSignature(signedMessage)
            alix.ffiApplySignatureRequest(sigRequest)

            inboxState = alix.inboxState(true)
            assertEquals(2, inboxState.identities.size)

            val sigRequest2 = alix.ffiRevokeIdentity(boWallet.publicIdentity)
            val signedMessage2 = alixWallet.sign(sigRequest2.signatureText()).rawData

            sigRequest2.addEcdsaSignature(signedMessage2)
            alix.ffiApplySignatureRequest(sigRequest2)

            inboxState = alix.inboxState(true)
            assertEquals(1, inboxState.identities.size)
        }

    @Test
    fun testCanManageRevokeManually() =
        runBlocking {
            val key = SecureRandom().generateSeed(32)
            val filesDir = createTempDir("xmtp")
            val alixWallet = PrivateKeyBuilder()
            val alix =
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )

            val alix2 =
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                            dbDirectory = filesDir.absolutePath.toString(),
                        ),
                )
            val alix3 =
                runBlocking {
                    JvmClient.create(
                        account = alixWallet,
                        options =
                            JvmClientOptions(
                                apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                                dbEncryptionKey = key,
                                dbDirectory =
                                    File(filesDir.absolutePath, "xmtp_db3")
                                        .toPath()
                                        .toString(),
                            ),
                    )
                }

            var inboxState = alix3.inboxState(true)
            assertEquals(inboxState.installations.size, 3)

            val sigText = alix.ffiRevokeInstallations(listOf(alix2.installationId.hexToByteArray()))
            val signedMessage = alixWallet.sign(sigText.signatureText()).rawData

            sigText.addEcdsaSignature(signedMessage)
            alix.ffiApplySignatureRequest(sigText)

            inboxState = alix.inboxState(true)
            assertEquals(2, inboxState.installations.size)

            val sigText2 = alix.ffiRevokeAllOtherInstallations()
            val signedMessage2 = alixWallet.sign(sigText2!!.signatureText()).rawData

            sigText2.addEcdsaSignature(signedMessage2)
            alix.ffiApplySignatureRequest(sigText2)

            inboxState = alix.inboxState(true)
            assertEquals(1, inboxState.installations.size)
        }

    @Test
    fun testPersistentLogging() {
        val key = SecureRandom().generateSeed(32)
        val filesDir = createTempDir("xmtp")
        JvmClient.clearXMTPLogs(File(filesDir, "xmtp_logs").path)
        val fakeWallet = PrivateKeyBuilder()

        // Create a specific log directory for this test
        val logDirectory = File(filesDir, "xmtp_test_logs")
        if (logDirectory.exists()) {
            logDirectory.deleteRecursively()
        }
        logDirectory.mkdirs()

        try {
            // Activate persistent logging with a small number of log files
            JvmClient.activatePersistentLibXMTPLogWriter(
                File(filesDir, "xmtp_logs").path,
                FfiLogLevel.TRACE,
                FfiLogRotation.HOURLY,
                3,
            )

            // Log the actual log directory path
            val actualLogDir = File(filesDir, "xmtp_logs")
            println("Log directory path: ${actualLogDir.absolutePath}")

            // Create a client
            val client =
                runBlocking {
                    JvmClient.create(
                        account = fakeWallet,
                        options =
                            JvmClientOptions(
                                apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                                dbEncryptionKey = key,
                            ),
                    )
                }

            // Create a group with only the client as a member
            runBlocking {
                client.conversations.newGroup(emptyList())
                client.conversations.sync()
            }

            // Verify the group was created
            val groups = runBlocking { client.conversations.listGroups() }
            assertEquals(1, groups.size)

            // Deactivate logging
            JvmClient.deactivatePersistentLibXMTPLogWriter()

            // Print log files content to console
            val logFiles = File(filesDir, "xmtp_logs").listFiles()
            println("Found ${logFiles?.size ?: 0} log files:")

            logFiles?.forEach { file ->
                println("\n--- Log file: ${file.absolutePath} (${file.length()} bytes) ---")
                try {
                    val content = file.readText()
                    // Print first 1000 chars to avoid overwhelming the console
                    println(
                        content.take(1000) +
                            (if (content.length > 1000) "...(truncated)" else ""),
                    )
                } catch (e: Exception) {
                    println("Error reading log file: ${e.message}")
                }
            }
        } finally {
            // Make sure logging is deactivated
            JvmClient.deactivatePersistentLibXMTPLogWriter()
        }
        val logFiles = JvmClient.getXMTPLogFilePaths(File(filesDir, "xmtp_logs").path)
        assertEquals(logFiles.size, 1)
        println(logFiles.get(0))
        JvmClient.clearXMTPLogs(File(filesDir, "xmtp_logs").path)
        val logFiles2 = JvmClient.getXMTPLogFilePaths(File(filesDir, "xmtp_logs").path)
        assertEquals(logFiles2.size, 0)
    }

    @Test
    fun testNetworkDebugInformation() =
        runBlocking {
            val key = SecureRandom().generateSeed(32)
            val filesDir = createTempDir("xmtp")
            val alixWallet = PrivateKeyBuilder()
            val alix =
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = key,
                        ),
                )
            alix.debugInformation.clearAllStatistics()

            val job =
                CoroutineScope(Dispatchers.IO).launch {
                    alix.conversations.streamAllMessages().collect {}
                }
            val group = alix.conversations.newGroup(emptyList())
            group.send("hi")

            delay(4000)

            val aggregateStats2 = alix.debugInformation.aggregateStatistics
            println("Aggregate Stats Create:\n$aggregateStats2")

            val apiStats2 = alix.debugInformation.apiStatistics
            assertEquals(0, apiStats2.fetchKeyPackage)
            assertEquals(3, apiStats2.sendGroupMessages)
            assertEquals(0, apiStats2.sendWelcomeMessages)
            assertEquals(1, apiStats2.queryWelcomeMessages)
            assertEquals(1, apiStats2.subscribeWelcomes)

            val identityStats2 = alix.debugInformation.identityStatistics
            assertEquals(0, identityStats2.publishIdentityUpdate)
            // Collapsing the two gRPC connections into one (#3721) routes the
            // group-creation identity-update fetches through the single API
            // client, so they're now counted here (was 0 under the
            // two-connection setup).
            assertEquals(2, identityStats2.getIdentityUpdatesV2)
            assertEquals(0, identityStats2.getInboxIds)
            assertEquals(0, identityStats2.verifySmartContractWalletSignature)
            job.cancel()
        }

    @Test
    fun testCannotCreateMoreThan10Installations() {
        val filesDir = createTempDir("xmtp")
        val encryptionKey = SecureRandom().generateSeed(32)
        val wallet = PrivateKeyBuilder()

        val clients = mutableListOf<JvmClient>()

        repeat(10) { i ->
            val client =
                runBlocking {
                    JvmClient.create(
                        account = wallet,
                        options =
                            JvmClientOptions(
                                apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                                dbEncryptionKey = encryptionKey,
                                dbDirectory =
                                    File(filesDir, "xmtp_db_$i").absolutePath,
                            ),
                    )
                }
            clients.add(client)
        }

        val state = runBlocking { clients.first().inboxState(true) }
        assertEquals(10, state.installations.size)

        // Attempt to create a 6th installation, should fail
        assertThrows(
            "Error creating V3 client: JvmClient builder error: Cannot register a new installation because the InboxID ${clients[0].inboxId} has already registered 10/10 installations. Please revoke existing installations first.",
            XMTPException::class.java,
        ) {
            runBlocking {
                JvmClient.create(
                    account = wallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = encryptionKey,
                            dbDirectory =
                                File(filesDir, "xmtp_db_10").absolutePath,
                        ),
                )
            }
        }

        val boWallet = PrivateKeyBuilder()
        val boClient =
            runBlocking {
                JvmClient.create(
                    account = boWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                            dbEncryptionKey = SecureRandom().generateSeed(32),
                            dbDirectory = File(filesDir, "xmtp_bo").absolutePath,
                        ),
                )
            }

        val group = runBlocking { boClient.conversations.newGroup(listOf(clients[2].inboxId)) }

        val members = runBlocking { group.members() }
        val alixMember = members.find { it.inboxId == clients.first().inboxId }
        assertNotNull(alixMember)
        val inboxState =
            runBlocking {
                boClient.inboxStatesForInboxIds(true, listOf(alixMember!!.inboxId))
            }
        assertEquals(10, inboxState.first().installations.size)

        runBlocking {
            clients.first().revokeInstallations(wallet, listOf(clients[9].installationId))
        }

        val stateAfterRevoke = runBlocking { clients.first().inboxState(true) }
        assertEquals(9, stateAfterRevoke.installations.size)

        runBlocking {
            JvmClient.create(
                account = wallet,
                options =
                    JvmClientOptions(
                        apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                        dbEncryptionKey = encryptionKey,
                        dbDirectory = File(filesDir, "xmtp_db_11").absolutePath,
                    ),
            )
        }
        val updatedState = runBlocking { clients.first().inboxState(true) }
        assertEquals(10, updatedState.installations.size)
    }

    @Test
    fun testStaticRevokeOneOfFiveInstallations() {
        val filesDir = createTempDir("xmtp")
        val wallet = PrivateKeyBuilder()
        val encryptionKey = SecureRandom().generateSeed(32)

        val clients = mutableListOf<JvmClient>()
        repeat(5) { i ->
            val client =
                runBlocking {
                    JvmClient.create(
                        account = wallet,
                        options =
                            JvmClientOptions(
                                apiOptions = JvmApi(XMTPEnvironment.LOCAL),
                                dbEncryptionKey = encryptionKey,
                                dbDirectory =
                                    File(filesDir, "xmtp_db_$i").absolutePath,
                            ),
                    )
                }
            clients.add(client)
        }

        var state = runBlocking { clients.last().inboxState(true) }
        assertEquals(5, state.installations.size)

        val toRevokeId = clients[1].installationId
        runBlocking {
            JvmClient.revokeInstallations(
                JvmApi(XMTPEnvironment.LOCAL),
                wallet,
                clients.first().inboxId,
                listOf(toRevokeId),
            )
        }

        state = runBlocking { clients.last().inboxState(true) }
        assertEquals(4, state.installations.size)
        val remainingIds = state.installations.map { it.installationId }
        assertFalse(remainingIds.contains(toRevokeId))
    }

    @Test
    fun testStaticRevokeInstallationsManually() =
        runBlocking {
            val key = SecureRandom().generateSeed(32)
            val filesDir = createTempDir("xmtp")
            val alixWallet = PrivateKeyBuilder()
            val apiOptions = JvmApi(XMTPEnvironment.LOCAL)
            val alix =
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = apiOptions,
                            dbEncryptionKey = key,
                        ),
                )

            val alix2 =
                JvmClient.create(
                    account = alixWallet,
                    options =
                        JvmClientOptions(
                            apiOptions = apiOptions,
                            dbEncryptionKey = key,
                            dbDirectory = filesDir.absolutePath.toString(),
                        ),
                )
            val alix3 =
                runBlocking {
                    JvmClient.create(
                        account = alixWallet,
                        options =
                            JvmClientOptions(
                                apiOptions = apiOptions,
                                dbEncryptionKey = key,
                                dbDirectory =
                                    File(filesDir.absolutePath, "xmtp_db3")
                                        .toPath()
                                        .toString(),
                            ),
                    )
                }

            var inboxState = alix3.inboxState(true)
            assertEquals(inboxState.installations.size, 3)

            val sigText =
                ffiRevokeInstallations(
                    apiOptions,
                    alixWallet.publicIdentity,
                    alix.inboxId,
                    listOf(alix2.installationId),
                )
            val signedMessage = alixWallet.sign(sigText.signatureText()).rawData

            sigText.addEcdsaSignature(signedMessage)
            ffiApplySignatureRequest(apiOptions, sigText)

            inboxState = alix.inboxState(true)
            assertEquals(2, inboxState.installations.size)
        }
}
