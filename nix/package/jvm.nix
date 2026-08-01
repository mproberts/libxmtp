# Host-platform (desktop JVM) build of the xmtpv3 uniffi cdylib + Kotlin bindings.
#
# Mirrors ./android.nix but builds for the *host* platform (macOS/Linux) instead
# of cross-compiling for Android ABIs, and links against the normal nix libraries
# (openssl/sqlite/zstd) rather than an NDK sysroot. This is what lets the pure-JVM
# engine module run libxmtp on a desktop JVM via JNA.
{
  gnused,
  xmtp,
  stdenv,
  ...
}:
let
  inherit (xmtp) craneLib base;
  rust-toolchain = p: xmtp.mkToolchain p [ stdenv.hostPlatform.rust.rustcTarget ] [ ];
  rust = craneLib.overrideToolchain rust-toolchain;
  version = xmtp.mkVersion rust;
  inherit (base) bindingsFileset commonArgs;

  # Native host build: use the default commonArgs buildInputs (openssl/sqlite/zstd),
  # unlike android.nix which forces buildInputs=[] to link against the NDK sysroot.
  cargoArtifacts = xmtp.base.mkCargoArtifacts rust false { pname = "xmtpv3-jvm-deps"; };

  ext = if stdenv.isDarwin then "dylib" else "so";
  dylib = rust.buildPackage (
    commonArgs
    // {
      inherit cargoArtifacts version;
      pname = "xmtpv3-jvm-${stdenv.hostPlatform.rust.rustcTarget}";
      doInstallCargoArtifacts = false;
      src = bindingsFileset;
      cargoExtraArgs = "-p xmtpv3";
      # Rename the built cdylib to the exact name JNA looks up
      # (Native.load("uniffi_xmtpv3") -> libuniffi_xmtpv3.{dylib,so}), matching Android.
      postFixup = ''
        cp $out/lib/libxmtpv3.${ext} $out/libuniffi_xmtpv3.${ext}
        rm -rf $out/lib
      '';
    }
  );

  # Kotlin bindings are host-generated and platform-agnostic; reuse the identical
  # sed post-processing as android.nix so the same xmtpv3.kt works on desktop JVM.
  kotlin-bindings = rust.uniffiGenerate {
    inherit version;
    pname = "xmtpv3-jvm-kotlin";
    language = "kotlin";
    dylibPath = "${dylib}/libuniffi_xmtpv3.${ext}";
    nativeBuildInputs = [ gnused ];
    doInstallCargoArtifacts = false;
    postFixup = ''
      sed -i \
        -e 's/return "xmtpv3"/return "uniffi_xmtpv3"/' \
        -e 's/value\.forEach { (k, v) ->/value.iterator().forEach { (k, v) ->/g' \
        -e 's/@file:Suppress("NAME_SHADOWING")/@file:Suppress("NAME_SHADOWING", "NewApi")/' \
        "$out/kotlin/uniffi/xmtpv3/xmtpv3.kt"

      echo "Version: ${version}" > $out/libxmtp-version.txt
      echo "Date: $(date -u +%Y-%m-%d)" >> $out/libxmtp-version.txt
    '';
  };
in
{
  inherit kotlin-bindings dylib;
}
