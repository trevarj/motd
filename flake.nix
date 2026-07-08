{
  description = "MOTD dev shell: JDK 17 + Android SDK (CI remains the canonical build env)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
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
      });
}
