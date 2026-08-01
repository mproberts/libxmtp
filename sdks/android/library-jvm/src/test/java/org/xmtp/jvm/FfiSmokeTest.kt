package org.xmtp.jvm

import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.xmtpv3.getVersionInfo

/**
 * M0 proof-of-life: verifies the libxmtp native library loads on a desktop JVM.
 *
 * [getVersionInfo] is a synchronous uniffi FFI call with no network dependency,
 * so exercising it proves the whole native path works on the pure JVM:
 *   generated xmtpv3.kt (on the compile classpath)
 *     -> JNA `Native.load("uniffi_xmtpv3")`
 *       -> extract libuniffi_xmtpv3.{dylib,so} from the classpath resource
 *         (resources/<os-arch>/, produced by `./dev/jvm-bindings`)
 *
 * Run: `./dev/jvm-bindings && ./gradlew library-jvm:test` (inside the nix shell).
 */
class FfiSmokeTest {
    @Test
    fun `libxmtp native library loads and reports a version`() {
        val version = getVersionInfo()
        assertTrue(
            "expected a non-blank libxmtp version, got: '$version'",
            version.isNotBlank(),
        )
    }
}
