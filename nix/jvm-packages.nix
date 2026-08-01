{ self, inputs, ... }:
{
  perSystem =
    {
      pkgs,
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
    in
    {
      packages = {
        inherit jvm-libs-fast;
        jvm-bindings-dylib = dylib;
        jvm-bindings-kotlin = kotlin-bindings;
      };
    };
}
