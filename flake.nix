{
  description = "MOTD dev shell: JDK 17 + Android SDK (CI remains the canonical build env)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";
    # libbox v1.13.12 requires Go >= 1.24.7 and Android NDK 28.0.13004108.
    # Keep this build-only toolchain separate from the app's stable SDK shell.
    nixpkgs-libbox.url = "github:NixOS/nixpkgs/767b0d3ec98a143ad9ed7dfc0d5553510ac27133";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, nixpkgs-libbox, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };
        libboxPkgs = import nixpkgs-libbox {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };
        # Match compileSdk/buildTools pinned in gradle/libs.versions.toml (plans/01).
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "35" ];
          buildToolsVersions = [ "35.0.0" ];
          platformToolsVersion = "35.0.2";
          includeEmulator = false;
          includeSystemImages = false;
        };
        androidSdk = androidComposition.androidsdk;
        sdkRoot = "${androidSdk}/libexec/android-sdk";
      in {
        devShells.default = pkgs.mkShell {
          packages = [ pkgs.jdk17 androidSdk ];
          JAVA_HOME = pkgs.jdk17.home;
          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          # AGP downloads a dynamically-linked aapt2 that won't run outside FHS;
          # point Gradle at the Nix-provided one instead.
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/35.0.0/aapt2";
        };
        devShells.libbox = libboxPkgs.mkShell {
          # Deliberately omit the NDK: Nix's Android SDK composition fetches a
          # 690 MiB archive before the shell can start. build-libbox.sh accepts
          # a verified local r28 archive (or LIBBOX_NDK_HOME) instead.
          # Google's prebuilt NDK host tools use the FHS interpreter
          # /lib64/ld-linux-x86-64.so.2.  Guix does not provide that path, so
          # build-libbox.sh patches the verified *extracted cache* (never the
          # downloaded archive) to use these pinned Nix runtime paths.
          packages = [ libboxPkgs.go_1_25 libboxPkgs.git libboxPkgs.gnumake libboxPkgs.unzip libboxPkgs.jdk17 libboxPkgs.patchelf libboxPkgs.zlib ];
          JAVA_HOME = libboxPkgs.jdk17.home;
          LIBBOX_NDK_HOST_LOADER = libboxPkgs.stdenv.cc.bintools.dynamicLinker;
          LIBBOX_NDK_HOST_RPATH = "${libboxPkgs.zlib}/lib:${libboxPkgs.stdenv.cc.cc.lib}/lib";
        };
      });
}
