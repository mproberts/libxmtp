export NIX_DEVSHELL := env("NIX_DEVSHELL", "default")
set shell := ["../../dev/nix-shell"]

# Verify Android SDK builds (bindings + Gradle)
check:
  ./dev/bindings && ./gradlew -p . build

# Run Spotless + Android Lint
lint:
  ./gradlew -p . spotlessCheck --continue && ./gradlew -p . :library:lintDebug

# Format Kotlin code
format:
  ./gradlew -p . spotlessApply

# Run unit tests
test:
  ./dev/bindings && ./gradlew -p . library:testDebug

# Run integration tests (requires emulator)
test-integration: build
  run-test-emulator && ./gradlew -p . connectedCheck --continue

# Run pure-JVM tests (host libxmtp via JNA; needs `just backend up`, no emulator)
test-jvm:
  ./dev/jvm-bindings && ./gradlew -p . library-jvm:test

# Build native bindings
build:
  ./dev/bindings

# Build host-platform (desktop JVM) native bindings
build-jvm:
  ./dev/jvm-bindings
