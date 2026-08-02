package org.xmtp.android.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.xmtpv3.getVersionInfo
import java.io.File

/**
 * Android instrumented smoke suite. The bulk E2E now runs on the pure JVM (in
 * :library-jvm); this only re-guards the AAR-specific packaging that the JVM suite
 * cannot exercise:
 *   1. the device `libuniffi_xmtpv3.so` loads from `jniLibs` through the JNA `@aar`
 *      variant, and
 *   2. the `Context`-based DB/log-path conveniences on [ClientOptions]/[Client]
 *      resolve under the app's `filesDir`.
 *
 * Run on an emulator/device: `./gradlew :library:connectedCheck`
 * (or `just android test-integration`).
 */
@RunWith(AndroidJUnit4::class)
class AndroidSmokeTest {

    @Test
    fun nativeLibraryLoadsFromJniLibs() {
        // A synchronous uniffi FFI call with no network dependency, so it isolates the
        // native path on-device: generated xmtpv3.kt -> JNA `@aar` -> jniLibs .so.
        val version = getVersionInfo()
        assertTrue("expected a non-blank libxmtp version, got: '$version'", version.isNotBlank())
    }

    @Test
    fun contextConveniencesResolveUnderFilesDir() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val options =
            ClientOptions(
                api = ClientOptions.Api(env = XMTPEnvironment.LOCAL),
                appContext = ctx,
                dbEncryptionKey = ByteArray(32),
            )

        // The engine resolves the DB parent dir via resolveDbParentDirectory() instead
        // of Context.filesDir; the Android ClientOptions must map it back to filesDir.
        assertEquals(
            File(ctx.filesDir.absolutePath, "xmtp_db").absolutePath,
            options.resolveDbParentDirectory(),
        )

        // Context-based log-path helper must resolve (and not throw) on-device.
        Client.getXMTPLogFilePaths(ctx)
    }
}
