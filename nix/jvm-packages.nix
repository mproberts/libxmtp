{ self, inputs, ... }:
{
  perSystem =
    {
      pkgs,
      system,
      lib,
      ...
    }:
    let
      mkJvmBindings = p: p.callPackage ./package/jvm.nix { };
      inherit (mkJvmBindings pkgs) dylib kotlin-bindings;

      hp = pkgs.stdenv.hostPlatform;
      ext = if pkgs.stdenv.isDarwin then "dylib" else "so";

      # Map the host platform to JNA's Platform.RESOURCE_PREFIX. JNA extracts a
      # bundled native lib from the classpath at "<prefix>/" + mapLibraryName(name).
      jnaPrefix =
        if hp.isDarwin && hp.isAarch64 then
          "darwin-aarch64"
        else if hp.isDarwin && hp.isx86_64 then
          "darwin-x86-64"
        else if hp.isLinux && hp.isx86_64 then
          "linux-x86-64"
        else if hp.isLinux && hp.isAarch64 then
          "linux-aarch64"
        else
          throw "Unsupported host platform for jvm-libs: ${hp.system}";

      # Host-only slice: enough for dev + CI, since JVM tests always run on the host.
      jvm-libs-fast = pkgs.linkFarm "xmtpv3-jvm-fast" [
        {
          name = "resources/${jnaPrefix}/libuniffi_xmtpv3.${ext}";
          path = "${dylib}/libuniffi_xmtpv3.${ext}";
        }
        {
          name = "java/uniffi/xmtpv3/xmtpv3.kt";
          path = "${kotlin-bindings}/kotlin/uniffi/xmtpv3/xmtpv3.kt";
        }
        {
          name = "libxmtp-version.txt";
          path = "${kotlin-bindings}/libxmtp-version.txt";
        }
      ];

      # Fat slice: every desktop-JVM native target buildable on the current host,
      # laid out for JNA (resources/<os-arch>/libuniffi_xmtpv3.{dylib,so}) alongside
      # the shared generated bindings. Split by host availability exactly like
      # nix/node-packages.nix: gnu cross-compilation is broken on macOS (Apple SDK
      # only builds darwin), so each runner emits its own OS's slices and the
      # published multi-OS native artifact is the union of the per-runner outputs.
      # Desktop-JVM consumers only — Android uses jniLibs and the KMP androidMain won't
      # need this (both get the lib from the platform bindings, not the published jar).
      configToJna = {
        "aarch64-apple-darwin" = "darwin-aarch64";
        "x86_64-apple-darwin" = "darwin-x86-64";
        "x86_64-unknown-linux-gnu" = "linux-x86-64";
        "aarch64-unknown-linux-gnu" = "linux-aarch64";
      };
      jvmTargets =
        if hp.isDarwin then
          [ "aarch64-apple-darwin" ]
        else
          [
            "x86_64-unknown-linux-gnu"
            "aarch64-unknown-linux-gnu"
          ];

      # Cross pkgsets keyed by target config; build one dylib per target.
      jvmCrossPkgs = self.lib.mkCrossPkgs system jvmTargets;
      jvmDylibs = lib.mapAttrs (_: p: (mkJvmBindings p).dylib) jvmCrossPkgs;

      jvm-libs = pkgs.linkFarm "xmtpv3-jvm" (
        lib.mapAttrsToList (config: d: {
          name = "resources/${configToJna.${config}}/libuniffi_xmtpv3.${ext}";
          path = "${d}/libuniffi_xmtpv3.${ext}";
        }) jvmDylibs
        ++ [
          {
            name = "java/uniffi/xmtpv3/xmtpv3.kt";
            path = "${kotlin-bindings}/kotlin/uniffi/xmtpv3/xmtpv3.kt";
          }
          {
            name = "libxmtp-version.txt";
            path = "${kotlin-bindings}/libxmtp-version.txt";
          }
        ]
      );
    in
    {
      packages = {
        inherit jvm-libs jvm-libs-fast;
        jvm-bindings-dylib = dylib;
        jvm-bindings-kotlin = kotlin-bindings;
      };
    };
}
