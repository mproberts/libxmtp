# XMTP Kotlin Multiplatform Migration

Living plan for turning the XMTP mobile SDKs into a Kotlin Multiplatform (KMP)
SDK backed by the existing `xmtp-android` and `xmtp-ios` implementations.

- **Phase 1 (DONE, branch `xmtp-jvm-refactor`, commit `ed8ad8b11`):** split
  `xmtp-android` into a pure‑Kotlin/JVM engine + a thin Android wrapper.
- **Remaining:** (A) verification of Phase 1, then (B) the KMP work (Phases 2–4).

This document is the source of truth for continuing the effort. It records the
context from Phase 1, then the two forward segments, clearly separated.

---

## 0. The overall sequencing

1. **Phase 1 — `xmtp-android` → pure‑JVM engine + thin Android module.** ✅ done.
   - Vast majority of the implementation + the libxmtp JNI/uniffi bindings live in a
     pure‑Kotlin/JVM module.
   - Tests migrated to pure‑JVM tests.
   - A thin Android module is vended at the **exact** old coordinates
     (`org.xmtp:android`) with a **byte‑identical public interface**, plus Android
     specifics.
2. **Phase 2 — mirror `xmtp-android` as a KMP module** in `sdks/kotlin`
   (targets: Android + iOS).
3. **Phase 3 — back the KMP `androidMain`** with the new `org.xmtp:xmtp-jvm` engine.
4. **Phase 4 — back the KMP `iosMain`** with `xmtp-ios`, bundled so it's a single,
   direct inclusion with no other requirements.

---

## PART A — Context: what Phase 1 delivered

### A.1 Architecture (after Phase 1)

```
              consumers (Android apps)                consumers (pure JVM / future KMP androidMain)
                        │                                              │
                        ▼                                              ▼
        ┌───────────────────────────────┐              ┌──────────────────────────────┐
        │  :library  →  org.xmtp:android │  api project │  :library-jvm → org.xmtp:xmtp-jvm │
        │  (Android AAR, 6 .kt)          │─────────────▶│  (pure Kotlin/JVM engine, 58 .kt) │
        │  thin Client : JvmClient       │              │  JvmClient + Conversations/Group  │
        │  ClientOptions (Context)       │              │  /Dm/Conversation + codecs +      │
        │  log helpers, push, lifecycle  │              │  libxmtp models + FFI (xmtpv3.kt) │
        └───────────────────────────────┘              └──────────────────────────────┘
                        │                                              │
              Android .so via jniLibs + JNA @aar          host .dylib/.so via plain JNA (tests)
```

- **`:library-jvm`** (`org.xmtp:xmtp-jvm`) — `org.jetbrains.kotlin.jvm` + `java-library`.
  Holds the engine and the generated uniffi bindings. No `android.*`/`androidx.*`.
  Loads libxmtp on the host JVM via **JNA** (plain `net.java.dev.jna:jna`, not the `@aar`).
- **`:library`** (`org.xmtp:android`, unchanged coordinates) — `com.android.library`,
  `api project(':library-jvm')`. Only the Android‑specific surface remains.

### A.2 Key design decisions (read before touching this code)

1. **Only `Client` was renamed → `JvmClient`.** It's the sole Context‑carrying type.
   `Conversations`, `Conversation` (sealed, with nested `Group`/`Dm` variants), `Group`,
   and `Dm` keep their names in the engine. Their public `client` property is now typed
   **`JvmClient`** (the runtime object is still the real `Client` subclass on Android).
   **This is the one intentional public‑API residual.**
2. **Why not alias the conversation types?** Kotlin type aliases **cannot** reach nested
   classes — `typealias Conversation = JvmConversation` breaks `Conversation.Group`. So the
   conversation types keep their real names in the engine rather than being aliased from
   `:library`.
3. **`Client : JvmClient` (subclass, not delegation).** The Android `Client` inherits all
   engine methods for free and adds only: the `Context` constructor path, the 3 `Context`
   log helpers, and re‑exposed companion statics (delegating to `JvmClient`). The engine
   exposes a generic factory `JvmClient.initializeV3Client<T>(…, construct)` (and
   `ffiCreateClient<T>(…, construct)`) so `Client.create(...)` builds the `Client` subtype
   through the shared engine creation logic.
4. **Package unchanged.** All engine types stay in `org.xmtp.android.library[.*]` so
   consumer imports are identical. `JvmClient` coexists with the Android `Client` in the
   same package across the two modules (different class names).
5. **Context‑free options seams.** `ApiOptions` + `ClientCreateOptions` (interfaces) live in
   the engine; the Android `ClientOptions`/`ClientOptions.Api` (with `appContext`) implement
   them. Concrete engine impls `JvmApi` / `JvmClientOptions` exist for pure‑JVM/KMP callers
   and tests. The DB directory is resolved via `resolveDbParentDirectory()` instead of
   `Context.filesDir`.
6. **Logging / lifecycle seams.** `XmtpLog` (facade) replaces `android.util.Log`;
   `StreamLifecycleController` replaces the direct androidx‑lifecycle call. On Android an
   `androidx.startup` `XmtpInitializer` installs `AndroidXmtpLogger` + `StreamLifecycleManager`
   at process start, so behavior is identical with zero consumer wiring.
7. **Local backend host.** `XMTPEnvironment.LOCAL` still defaults to `10.0.2.2` (Android
   emulator). JVM tests opt into localhost via `XMTPEnvironment.LOCAL.withValue("localhost")`;
   `getHistorySyncUrl()` was fixed to respect the custom host.
8. **Native lib packaging.** The generated `xmtpv3.kt` compiles into `:library-jvm` (main).
   The host `.dylib`/`.so` is a **test** source‑set resource only (JNA classpath layout
   `<os-arch>/libuniffi_xmtpv3.*`), so the published jar / Android AAR don't carry the
   ~40 MB desktop lib. Android's runtime lib still comes from `:library` `jniLibs` (`.so`) +
   the JNA `@aar`.

### A.3 Files that matter

New / changed (Phase 1):
- Native build: `nix/package/jvm.nix` (host cdylib `libuniffi_xmtpv3` + `xmtpv3.kt`),
  `nix/jvm-packages.nix` (flake output **`jvm-libs-fast`**, JNA resource layout),
  `flake.nix` (import).
- Engine: `sdks/android/library-jvm/build.gradle` (kotlin‑jvm, plain JNA, `xmtp-jvm`
  publishing, dylib in test resources), `library-jvm/src/main/java/org/xmtp/android/library/`
  (`JvmClient.kt`, `Conversations.kt`, `Group.kt`, `Dm.kt`, `Conversation.kt`, seams
  `XmtpLog.kt`/`StreamLifecycleController.kt`/`ClientCreateOptions.kt`, `JvmClientOptions.kt`,
  `CatchUpSummary.kt`, codecs/, libxmtp/, messages/).
- Android wrapper: `library/src/main/java/org/xmtp/android/library/Client.kt` (new thin
  wrapper + `ClientOptions`), `AndroidXmtpLogger.kt`, `XmtpInitializer.kt`,
  `StreamLifecycle.kt` (keeps `StreamLifecycleManager`), `push/`, `AndroidManifest.xml`
  (startup provider), `build.gradle` (`api project(':library-jvm')` + JNA `@aar`).
- Tests: `library-jvm/src/test/java/org/xmtp/android/library/` (25 E2E + ported
  `BaseInstrumentedTest`/`TestHelpers`), `library-jvm/src/test/java/org/xmtp/jvm/FfiSmokeTest.kt`.
- Dev/CI: `sdks/android/dev/jvm-bindings`, `sdks/android/android.just` (`build-jvm`,
  `test-jvm`), `.github/workflows/test-android.yml` (`jvm-e2e-tests` job),
  `sdks/android/settings-jvm.gradle` (isolated engine build without the Android SDK).

### A.4 What is already verified vs not

- **Verified by the compiler:** `:library-jvm` main + test, `:library` main + unit‑test,
  and the **unchanged `:example` app** all compile. `FfiSmokeTest` runs — JNA loads
  `libuniffi_xmtpv3` on the host JVM and `getVersionInfo()` returns.

#### Segment‑1 verification results (2026‑08‑01, local macOS aarch64, no cachix)

- **B.1 E2E — PASS.** Backend brought up from pulled `ghcr.io/xmtp/*:main` images (no Nix;
  validation image is pullable despite `pull_policy: never`). Host bindings staged via the
  §B.5 cargo fallback. `:library-jvm:test` → **199/202 passed**, 2 skipped. The 3 failures
  (`ConversationsTest.testCanStreamAllMessagesFilterConsent`,
  `GroupTest.testSyncsAllGroupsInParallel`, `HistorySyncTest.testStreamPreferenceUpdates`)
  are **pre‑existing flaky streaming/timing assertions** (exact async‑stream counts synced
  only by `Thread.sleep`), in the engine‑FFI streaming path the migration never touched.
  Non‑deterministic across runs; on a fresh backend 2/3 pass. **Not migration regressions.**
- **B.3 engine — PASS.** `org.xmtp:xmtp-jvm:4.12.0-dev` → mavenLocal (jar/sources/javadoc/
  module/pom); POM lists `proto-kotlin` + `protobuf-kotlin-lite` as compile‑scope (api).
  Fixed: both modules' `signing {}` now guarded with `if (signingKey)` so keyless local
  `publishToMavenLocal` no longer requires `.asc` artifacts (`-x sign…` was insufficient —
  `sign publications.release` registers the sigs as required artifacts).
- **B.6.3 done.** Moved the 3 engine‑clean unit tests (`CryptoTest`, `DbPoolOptionsTest`,
  `VisibilityConfirmationOptionsTest`) into `library-jvm/src/test` (compile+pass verified).
  `ClientCacheKeyTest` + `RemoteAttachmentTest` + `TestHelpers` stay in `:library` — they
  reference the Android `Client`/`ClientOptions`/`Fixtures` surface (same‑package, module‑level).
- **B.6.4 done.** The `conversations.client : JvmClient` residual is safe for the in‑repo
  `:example` (it only uses `ClientManager.client` which is a `Client`; nothing assigns a
  conversation's `.client` to a `Client`). Doc‑note only for external consumers.
- **B.4 — PASS.** After repairing the local Nix store (`sudo nix-store --verify
  --check-contents --repair` — the old `/nix/var/nix/db` had survived an earlier
  incomplete uninstall, leaving `bootstrap-tools.drv` DB‑registered but gone, failing
  *all* `nix build`s incl. `nixpkgs.hello`), `nix build .#jvm-libs-fast` produces the
  correct layout (`resources/darwin-aarch64/libuniffi_xmtpv3.dylib` + `java/…/xmtpv3.kt`
  + version). The nix `xmtpv3.kt` differs from the cargo one only in ktlint formatting
  (raw bindgen vs ktlint‑formatted — semantically identical; `xmtpv3.kt` is spotless‑
  excluded). Staged into `:library-jvm` and `FfiSmokeTest` passes → nix output compiles
  + loads.
- **B.2 — PASS (2 real bugs fixed).** `nix build .#android-libs` → 4 ABIs; `:library:
  assembleDebug`/`assembleRelease` → AARs; `:example:assembleDebug` → APK; `spotlessCheck`
  green; `:library` unit tests green (ClientCacheKeyTest, RemoteAttachmentTest).
    - **Bug 1 (Tink/protobuf dex collision).** The Phase‑1 switch to JVM `tink` pulled
      full `protobuf-java`, which dex‑collides with XMTP's `protobuf-javalite` on Android
      (and `tink` vs WalletConnect's `tink-android`). Fix: `:library` now excludes the
      engine's JVM `tink` and substitutes `tink-android` (javalite‑based) — mirroring the
      JNA `@aar` pattern. Would have broken **every** Android consumer's app build.
    - **Bug 2 (spotless).** Pre‑existing ktlint‑1.8.0 formatting violations across
      `:library` + `:library-jvm` (Phase 1 never ran spotless). Fixed via `spotlessApply`.
    - `just lint` (rust/config/md) skipped: the migration changed no Rust; the Kotlin gate
      (spotless) is green. (Follow‑up: `:library-jvm` had no spotless task surfaced until a
      full check — now covered.)
- **B.3 `:library` — PASS.** `org.xmtp:android:4.12.0-dev` → mavenLocal; its POM lists
  `org.xmtp:xmtp-jvm` as **compile‑scope (api)** with `tink` + `jna` exclusions carried
  through, plus `tink-android`/`jna@aar` substituted at runtime. Downstream graph correct.
- **B.6.1 — PASS.** New `library/src/androidTest/…/AndroidSmokeTest.kt` (2 tests) run on
  the `Pixel_9a_2` emulator via `:library:connectedDebugAndroidTest` → 2/2 pass. Guards the
  AAR‑only packaging the JVM suite can't: (1) device `libuniffi_xmtpv3.so` loads from
  `jniLibs` through the JNA `@aar` variant (`getVersionInfo()`), (2) `ClientOptions` Context
  DB/log‑path conveniences resolve under `filesDir`.
- **B.6.2 — done.** (1) Added a fat **`jvm-libs`** flake output in `nix/jvm-packages.nix`
  (host‑OS‑filtered desktop targets via `mkCrossPkgs`, JNA `resources/<os-arch>/…` layout,
  mirroring `android-libs`/`node-packages`) — built on macOS → `darwin-aarch64` slice; the
  full multi‑OS artifact is the CI union of per‑runner slices (macOS can't cross to Linux).
  (2) Added a **gated** `org.xmtp:xmtp-jvm-native` publication (Jar of the JNA‑layout native
  libs, `-PxmtpJvmNativeDir=<jvm-libs result>`); verified the `-natives.jar` carries
  `darwin-aarch64/libuniffi_xmtpv3.dylib` at the jar root. Standalone desktop‑JVM consumers
  only — Android/KMP don't use it.

### Segment‑1 (Part B) COMPLETE ✅ — all gates + all B.6 close‑out done

Two real migration bugs found and fixed by the gates, plus pre‑existing lint debt:
1. **Tink/protobuf dex collision** (B.2) — would have broken every Android consumer's app
   build. `:library` now excludes the engine's JVM `tink` and substitutes `tink-android`.
2. **Keyless publish** (B.3) — `signing {}` now guards on `if (signingKey)`.
3. **spotless** — pre‑existing ktlint‑1.8.0 violations across both modules, auto‑formatted.

Files changed (uncommitted, for review): `library/build.gradle` (tink exclude+substitute,
signing guard), `library-jvm/build.gradle` (signing guard, gated native publication),
`example/build.gradle` (no net change), `nix/jvm-packages.nix` (fat `jvm-libs`),
`library/src/androidTest/…/AndroidSmokeTest.kt` (new), 3 unit tests moved to
`library-jvm/src/test`, + spotlessApply formatting across `:library`/`:library-jvm`.
Segment 2 (KMP, Part C) is unblocked.

---

## PART B — Segment 1: Verification & hardening of Phase 1

Do these before starting the KMP work. None of them are blocked by the KMP design.

### B.1 Run the E2E suite (needs the docker backend)

The backend runs **without Nix** — every service is a pullable `ghcr.io/xmtp/*:main`
image (validation service included; anvil builds from a tiny Dockerfile):

```bash
# 1) bring up docker (Colima or Docker Desktop)
colima start --cpu 4 --memory 8      # or: open -a Docker
# 2) start the backend
docker compose -f dev/docker/docker-compose.yml up -d --wait
# 3) build host bindings + run the JVM E2E suite
cd sdks/android
./dev/jvm-bindings 2>/dev/null || true   # if Nix is available; otherwise stage bindings via cargo (see B.5)
./gradlew -c settings-jvm.gradle :library-jvm:test
```

Expected: the migrated `ClientTest`, `ConversationsTest`, `GroupTest`, `DmTest`,
`HistorySyncTest`, `SmartContractWalletTest`, etc. pass against `localhost`.

**Watch for runtime issues the compiler couldn't catch:**
- A few `ClientTest`/`ArchiveTest` DB/log‑path tests were mechanically ported to per‑test
  temp dirs (`createTempDir("xmtp")`). Confirm each test still asserts against the *same*
  directory it wrote to (the migration kept a stable `filesDir` per test, but audit the
  path‑sensitive ones).
- SCW tests hit anvil at `http://localhost:8545`; history‑sync at `http://localhost:5558`.

### B.2 Full Android + lint gates

```bash
cd sdks/android
export ANDROID_SDK_ROOT=$HOME/Library/Android/sdk   # or your SDK path
./gradlew :library:build :example:assembleDebug     # AAR + sample app
./gradlew spotlessCheck                              # excludes xmtpv3.kt already
just lint                                            # from repo root (rust + config + md)
```

The 6 pre‑existing pure‑JVM unit tests in `library/src/test` still compile; decide whether to
leave them in `:library` or move them into `:library-jvm/src/test` next to the engine.

### B.3 Publish dry‑run

```bash
cd sdks/android
./gradlew -c settings-jvm.gradle :library-jvm:publishToMavenLocal   # org.xmtp:xmtp-jvm
./gradlew :library:publishToMavenLocal                              # org.xmtp:android
```

Confirm the `org.xmtp:android` POM lists `org.xmtp:xmtp-jvm` as an `api` dependency, and that
a scratch consumer project resolving `org.xmtp:android` compiles unchanged.

### B.4 Validate the Nix path

Prove `nix/package/jvm.nix` + `nix/jvm-packages.nix` build under Nix (only cargo‑equivalence
was verified in Phase 1):

```bash
nix build .#jvm-libs-fast --print-build-logs
ls result/   # expect resources/<os-arch>/libuniffi_xmtpv3.* + java/uniffi/xmtpv3/xmtpv3.kt
```

### B.5 Staging bindings without Nix (fallback / local dev)

If Nix isn't available, reproduce what `jvm-libs-fast` produces (matches `jvm.nix` exactly):

```bash
cd <repo root>
rustup run 1.97.1 cargo build --release -p xmtpv3           # -> target/release/libxmtpv3.dylib
rustup run 1.97.1 cargo run --release --features "uniffi/cli" \
  --bin ffi-uniffi-bindgen -- generate \
  --library target/release/libxmtpv3.dylib --language kotlin --out-dir /tmp/gen
# apply the 3 sed fixes from nix/package/jvm.nix, then stage:
sed -i '' -e 's/return "xmtpv3"/return "uniffi_xmtpv3"/' \
  -e 's/value\.forEach { (k, v) ->/value.iterator().forEach { (k, v) ->/g' \
  -e 's/@file:Suppress("NAME_SHADOWING")/@file:Suppress("NAME_SHADOWING", "NewApi")/' \
  /tmp/gen/uniffi/xmtpv3/xmtpv3.kt
S=sdks/android/.build/jvm-bindings
mkdir -p $S/java/uniffi/xmtpv3 $S/resources/darwin-aarch64
cp /tmp/gen/uniffi/xmtpv3/xmtpv3.kt $S/java/uniffi/xmtpv3/xmtpv3.kt
cp target/release/libxmtpv3.dylib  $S/resources/darwin-aarch64/libuniffi_xmtpv3.dylib
```

Isolated engine build/test (no Android SDK needed): `./gradlew -c settings-jvm.gradle
:library-jvm:test`.

### B.6 Known follow‑ups to close out Phase 1

- [ ] **Android instrumented smoke suite** (1–3 tests) in `:library` to guard the AAR path:
      device `.so` load from `jniLibs`, the `@aar` JNA variant, and the `Context`
      DB/log‑path conveniences. The bulk E2E now lives on the JVM; this only re‑guards the
      Android‑specific packaging.
- [ ] **Desktop‑JVM native artifact.** The published `xmtp-jvm` jar omits the native lib (to
      keep the AAR slim). Standalone desktop‑JVM consumers need the lib on their classpath —
      publish a separate multi‑slice native artifact assembled from a `jvm-libs` (fat) flake
      output: macOS `darwin-aarch64`/`darwin-x86-64`, Linux `linux-x86-64`/`linux-aarch64`,
      each under `<os-arch>/libuniffi_xmtpv3.*` (JNA `RESOURCE_PREFIX`). Consider static
      linking so it doesn't depend on `/nix/store` paths. **Android does not need this**
      (uses `jniLibs`), and the KMP `androidMain` won't either.
- [ ] **`jvm-libs` (fat) flake output** — union of per‑runner `jvm-libs-fast` slices, built via
      `mkCrossPkgs` (macOS + Linux), for the artifact above.
- [ ] Decide the home of the 6 `library/src/test` unit tests (leave vs. move to engine).
- [ ] Sanity‑check the `conversations.client : JvmClient` residual against real downstream
      consumers; if any depend on `x: Client = someGroup.client`, provide a documented cast
      or reconsider wrapping the conversation types.

---

## PART C — Segment 2: Kotlin Multiplatform (Phases 2–4)

Goal: a KMP module in `sdks/kotlin` exposing **one** XMTP API to both Android and iOS,
backed by `org.xmtp:xmtp-jvm` on Android and `xmtp-ios` (Swift) on iOS.

### C.1 Proposed module layout

```
sdks/kotlin/
  build.gradle(.kts)          # kotlin("multiplatform"), androidTarget(), iosArm64()/iosSimulatorArm64()
  src/
    commonMain/kotlin/org/xmtp/…   # the public KMP API + `expect` declarations
    commonTest/kotlin/…
    androidMain/kotlin/…           # `actual` → delegates to org.xmtp:xmtp-jvm (JvmClient …)
    iosMain/kotlin/…               # `actual` → delegates to xmtp-ios via interop (see C.4)
```

Publish as a KMP artifact (Android AAR + iOS klibs / an XCFramework for Swift consumers).

### C.2 Common API shape

Two viable styles for `commonMain`:

- **(preferred) A hand‑written common API + `expect`/`actual` factories.** Define
  platform‑neutral interfaces (`XmtpClient`, `Conversation`, `Group`, `Dm`, `Message`,
  content codecs, options) in `commonMain`. Each platform provides `actual` implementations
  that wrap its native SDK type. This keeps the KMP surface clean and lets the two backends
  differ internally.
- **(alternative) Generate common bindings directly from libxmtp** with
  `uniffi-bindgen-kotlin-multiplatform`, skipping `xmtp-android`/`xmtp-ios` entirely on the
  hot path. Cleaner FFI story, but **contradicts the chosen approach** of reusing the
  existing SDKs and their higher‑level ergonomics. Keep as a fallback if Swift interop (C.4)
  proves too costly.

Reconcile the public naming: the engine currently uses `org.xmtp.android.library.*`. The KMP
module should expose a platform‑neutral package (e.g. `org.xmtp` / `org.xmtp.kotlin`). Map
engine types into the neutral package in `androidMain` (thin adapters or `typealias`, minding
the nested‑class alias limitation from A.2).

### C.3 Phase 3 — `androidMain` backed by `org.xmtp:xmtp-jvm`

- `androidMain` is a JVM/Android source set, so it can depend on **`org.xmtp:xmtp-jvm`**
  directly and call `JvmClient`, `Conversations`, codecs, etc. — no `Context` needed for the
  core, exactly why the engine was extracted.
- Where a KMP consumer needs `Context` conveniences, either (a) accept a working‑directory /
  path in the common API (map to `JvmClientOptions.workingDirectory`), or (b) offer an
  Android‑only extension that takes a `Context` (mirroring `:library`'s `ClientOptions`).
- The native lib on Android comes from `jniLibs` (`.so`) — reuse the existing Nix Android
  bindings (`android-libs`) and the JNA `@aar`. The KMP `androidMain` should pull these the
  same way `:library` does.
- Decision: does `androidMain` depend on **`org.xmtp:xmtp-jvm`** (engine, cleanest) or on
  **`org.xmtp:android`** (to get the Context conveniences + startup wiring)? Prefer the
  engine and re‑implement the small Context conveniences in `androidMain` to avoid dragging
  the whole AAR.

### C.4 Phase 4 — `iosMain` backed by `xmtp-ios` (the hard part)

Kotlin/Native interops with **C and Objective‑C**, not Swift directly. `xmtp-ios` is a Swift
package. Options, roughly in order of reuse vs. effort:

1. **Objective‑C bridge over `xmtp-ios`.** Write a thin `@objc`‑annotated Swift/ObjC facade
   exposing the needed XMTP surface, expose it as an ObjC framework, and `cinterop` to it from
   `iosMain`. Maximizes reuse of `xmtp-ios`; costs a hand‑written bridge that must track the
   Swift API. Swift `async` maps to completion handlers for ObjC.
2. **Kotlin/Native cinterop straight to libxmtp's uniffi FFI** (the `libxmtpv3.a` static lib +
   `xmtpv3FFI.h`), i.e. generate Kotlin/Native bindings for the same C ABI the Swift bindings
   use. Bypasses `xmtp-ios` Swift wrappers but reuses the *same* Rust core. This is close to
   the `uniffi-bindgen-kotlin-multiplatform` route in C.2 and is often the pragmatic path for
   the iOS target.
3. **Full `xmtp-ios` reuse via SKIE / Swift‑export** tooling — evolving; evaluate maturity.

**Bundling requirement (from the plan):** the iOS artifact must be a *single, direct
inclusion with no other requirements.* That means vendoring `LibXMTPSwiftFFI.xcframework`
(built by `sdks/ios/dev/bindings` → `nix build .#ios-xcframeworks`) and any bridge into the
KMP‑produced XCFramework / Swift package, so a Swift app adds one dependency. Reuse the iOS
Nix packages (`ios-libs`, `ios-xcframeworks`) already in the repo.

**Native lib per iOS target:** device `aarch64-apple-ios`, simulator
`aarch64-apple-ios-sim`, (macOS `aarch64-apple-darwin` if desktop). Link the static
`libxmtpv3.a` for the chosen interop, or vendor the xcframework for the bridge route.

### C.5 Build & packaging (KMP specifics)

- Gradle: `kotlin("multiplatform")` with `androidTarget { … }` and iOS targets; for iOS
  Swift consumers, produce an **XCFramework** (`XCFramework()` / `kotlin.native.cocoapods`
  plugin, or `swift-create-xcframework`).
- Everything stays inside the Nix devshells (`nix develop .#android` / `.#ios`) and `just`
  recipes; add `sdks/kotlin/*.just` mirroring the android/ios modules.
- Publishing: Android AAR + `-kotlin-multiplatform` metadata to Maven; iOS as an
  XCFramework/Swift package (SPM) and/or CocoaPods, matching how `xmtp-ios` ships today
  (root `Package.swift` + `XMTP.podspec`).

### C.6 Open decisions to make before Phase 2

1. **iOS interop route (C.4):** ObjC bridge over `xmtp-ios` vs. direct Kotlin/Native cinterop
   to libxmtp's uniffi FFI vs. uniffi‑KMP common bindings. This is the single biggest fork —
   it determines whether `xmtp-ios` is reused at the Swift level or only at the Rust‑core
   level.
2. **Common API package name** and how much of the existing `org.xmtp.android.library` surface
   to expose vs. redesign for KMP ergonomics.
3. **`androidMain` dependency:** `org.xmtp:xmtp-jvm` (engine) vs. `org.xmtp:android` (wrapper).
4. **Async model:** Kotlin `suspend` in `commonMain` mapping to Swift `async` on the consumer
   side (via the XCFramework) — validate the generated Swift ergonomics.
5. **Codec/content‑type extensibility** across platforms (the codec registry is currently a
   JVM static; design the common equivalent).

### C.7 Suggested phased execution

- **P2.0** Scaffold `sdks/kotlin` KMP module; empty `commonMain` API + `expect`; wire targets
  and Nix/just recipes; get an empty KMP build green on both platforms.
- **P3.0** Implement `androidMain` `actual`s over `org.xmtp:xmtp-jvm`; port a slice of the JVM
  E2E tests to `commonTest`/`androidUnitTest` to validate parity.
- **P4.0** Spike the chosen iOS interop (C.6.1) on one flow (create client + send/list a
  message) end‑to‑end before committing to the full surface.
- **P4.1** Fill out `iosMain`; bundle the xcframework; ship a one‑include iOS artifact.
- **P5** Cross‑platform test suite in `commonTest`; publish the KMP artifact.

---

## Appendix — environment notes (from Phase 1 setup)

- Sudo‑free toolchain used to build/verify locally without Nix: Homebrew `rustup`
  (toolchain **1.97.1**, pinned by `rust-toolchain.toml`; invoke as `rustup run 1.97.1 …`),
  `pkg-config`, `cmake`, `go`; `openssl@3`/`sqlite`/`zstd` via Homebrew. The project uses
  **rustls + ring** (no OpenSSL‑TLS), so host native builds are painless.
- Android SDK installed at `~/Library/Android/sdk` (platform‑35, build‑tools 35.0.0);
  `sdks/android/local.properties` points at it (gitignored).
- Docker was **not** available in the Phase‑1 environment (Colima wouldn't boot in the
  sandbox), which is why the E2E run and publish dry‑run are deferred to Segment 1 (B.1/B.3).
- Isolated engine builds use `sdks/android/settings-jvm.gradle` (includes only `:library-jvm`,
  so no Android SDK is required to compile/test the engine).

