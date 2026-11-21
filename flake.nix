{
  description = "motd dev shell: JDK 21 + Android SDK (CI remains the canonical build env)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/767b0d3ec98a143ad9ed7dfc0d5553510ac27133";
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
        nativeNdkVersion = pkgs.lib.removePrefix "ANDROID_NDK_VERSION="
          (builtins.head (pkgs.lib.filter
            (line: pkgs.lib.hasPrefix "ANDROID_NDK_VERSION=" line)
            (pkgs.lib.splitString "\n" (builtins.readFile ./third_party/sing-box/source.lock))));
        # Must match third_party/ai/source.lock. Gradle requests the same exact SDK package.
        nativeCmakeVersion = "3.31.6";
        # Match compileSdk/buildTools used by the current Gradle configuration.
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "37.0" ];
          buildToolsVersions = [ "36.0.0" ];
          platformToolsVersion = "35.0.2";
          # App builds compile the AI libraries, so both ordinary and native shells need these.
          includeNDK = true;
          ndkVersions = [ nativeNdkVersion ];
          includeCmake = true;
          cmakeVersions = [ nativeCmakeVersion ];
          includeEmulator = false;
          includeSystemImages = false;
        };
        # Nix exposes the side-by-side NDK through both ndk/<version> and legacy
        # ndk-bundle. AGP diagnoses that duplicate package location, so present
        # the same immutable SDK without the legacy alias.
        androidSdk = pkgs.runCommand "motd-android-sdk" {} ''
          mkdir -p "$out/libexec"
          ln -s "${androidComposition.androidsdk}/bin" "$out/bin"
          cp -rs "${androidComposition.androidsdk}/libexec/android-sdk" "$out/libexec/"
          chmod u+w "$out/libexec/android-sdk"
          rm "$out/libexec/android-sdk/ndk-bundle"
        '';
        sdkRoot = "${androidSdk}/libexec/android-sdk";
        # Local i5-10210U (4 cores / 8 threads, 46 GiB RAM): leave desktop headroom.
        # Explicit daemon arguments prevent reusing uncapped Gradle/Kotlin JVMs.
        localGradleOpts = pkgs.lib.concatStringsSep " " [
          "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/36.0.0/aapt2"
          "-Dorg.gradle.workers.max=2"
          "-Dorg.gradle.parallel=false"
          "\"-Dorg.gradle.jvmargs=-Xmx4g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8\""
          "\"-Dorg.gradle.project.kotlin.daemon.jvmargs=-Xmx4g -XX:ActiveProcessorCount=2\""
        ];
        # Opt-in, large emulator closure for the local headless E2E loop. Keeping this separate
        # avoids making every ordinary build fetch an API image and emulator runtime.
        # This shell only runs emulator/avdmanager/adb; headless.sh shells back out to the
        # default shell for Gradle, so no platform or build-tools beyond the AVD's API level.
        emulatorComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "34" ];
          buildToolsVersions = [ ];
          platformToolsVersion = "35.0.2";
          includeCmake = false;
          includeEmulator = true;
          includeSystemImages = true;
          systemImageTypes = [ "default" ];
          abiVersions = [ "x86_64" ];
        };
        emulatorSdk = emulatorComposition.androidsdk;
        emulatorSdkRoot = "${emulatorSdk}/libexec/android-sdk";
        # Pinned, opt-in agent tooling; the ordinary Android shell does not fetch this JAR.
        gradleMcpJar = pkgs.fetchurl {
          url = "https://repo.maven.apache.org/maven2/dev/rnett/gradle-mcp/gradle-mcp/0.0.15/gradle-mcp-0.0.15.jar";
          sha256 = "51ea00fda38d795045954c1fcaa2f9db4b04487fc1ee55af6fe6778386f84772";
        };
        gradleMcp = pkgs.writeShellApplication {
          name = "gradle-mcp";
          runtimeInputs = [ pkgs.jdk21 ];
          text = ''
            export GRADLE_MCP_PROJECT_ROOT="''${GRADLE_MCP_PROJECT_ROOT:-$PWD}"
            exec java -jar ${gradleMcpJar} "$@"
          '';
        };
      in {
        devShells.default = pkgs.mkShell {
          # imagemagick: test/e2e/showcase-composite.sh merges the light/dark
          # showcase captures into the tracked diagonal-split screenshots.
          packages = [ pkgs.jdk21 pkgs.ninja pkgs.python3 pkgs.kotlin-language-server pkgs.nodejs_22 pkgs.imagemagick pkgs.actionlint pkgs.stdenv.cc pkgs.curl androidSdk ];
          JAVA_HOME = pkgs.jdk21.home;
          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          ANDROID_NDK_HOME = "${sdkRoot}/ndk/${nativeNdkVersion}";
          ANDROID_NDK_ROOT = "${sdkRoot}/ndk/${nativeNdkVersion}";
          CMAKE_MAKE_PROGRAM = "${pkgs.ninja}/bin/ninja";
          # AGP downloads a dynamically-linked aapt2 that won't run outside FHS;
          # point Gradle at the Nix-provided one instead.
          GRADLE_OPTS = localGradleOpts;
          # Also bound processor-sized pools in test and other child JVMs.
          JAVA_TOOL_OPTIONS = "-XX:ActiveProcessorCount=2";
          # Native modules translate this into Ninja compile/link job pools.
          CMAKE_BUILD_PARALLEL_LEVEL = "2";
        };
        devShells.mcp = self.devShells.${system}.default.overrideAttrs (previous: {
          nativeBuildInputs = previous.nativeBuildInputs ++ [ gradleMcp ];
        });
        devShells.native = pkgs.mkShell {
          packages = [ pkgs.jdk21 pkgs.ninja pkgs.stdenv.cc pkgs.curl androidSdk ];
          JAVA_HOME = pkgs.jdk21.home;
          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          ANDROID_NDK_HOME = "${sdkRoot}/ndk/${nativeNdkVersion}";
          ANDROID_NDK_ROOT = "${sdkRoot}/ndk/${nativeNdkVersion}";
          CMAKE_MAKE_PROGRAM = "${pkgs.ninja}/bin/ninja";
          GRADLE_OPTS = localGradleOpts;
          JAVA_TOOL_OPTIONS = "-XX:ActiveProcessorCount=2";
          CMAKE_BUILD_PARALLEL_LEVEL = "2";
        };
        # Metadata authoring only: pinned shaping tools, no fonts or Android SDK.
        devShells.emoji-authoring = pkgs.mkShell {
          packages = [ pkgs.python3 pkgs.harfbuzz ];
        };
        # Standalone raster studies and browser verification, without the Android SDK.
        devShells.sprite-studies = pkgs.mkShell {
          packages = [ pkgs.nodejs_22 pkgs.imagemagick pkgs.ffmpeg pkgs.chromium ];
          FONTCONFIG_FILE = pkgs.makeFontsConf { fontDirectories = [ pkgs.dejavu_fonts ]; };
        };
        devShells.sound-authoring = let
          release = "https://raw.githubusercontent.com/sgossner/VCSL/c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e";
          clip = name: path: hash: {
            name = "${name}.wav";
            path = pkgs.fetchurl {
              name = "vcsl-${name}.wav";
              url = "${release}/${path}";
              inherit hash;
            };
          };
          recordings = pkgs.linkFarm "motd-vcsl-recordings" [
            (clip "woodblock-soft" "Idiophones/Struck%20Idiophones/Woodblock/wood_click_pp_rr1.wav" "sha256-LCGl3m893VHOhRMlumyqShh0IsUSmdp7L9xPUa5enuE=")
            (clip "woodblock-answer" "Idiophones/Struck%20Idiophones/Woodblock/wood_click3_vl1.wav" "sha256-s4oG8DfecLss+Y/YnwavjRBNQSN3PS5iVpEYmmdJW+U=")
            (clip "zither-low" "Chordophones/Zithers/Psaltery%2C%20Bowed%20and%20Plucked/Pluck/BowedPsaltery_E4_Main_Pluck_rr2.wav" "sha256-C9lWUxDYUcoe7qeUlhuf9wGoObUGT+Qld18Xo7vSBKo=")
            (clip "zither-high" "Chordophones/Zithers/Psaltery%2C%20Bowed%20and%20Plucked/Pluck/BowedPsaltery_C5_Main_Pluck_rr1.wav" "sha256-0+3/S2gI4fExk1bNURXFdAhhs0NVFKvjLEal2YMg/xQ=")
            (clip "vibes-low" "Idiophones/Struck%20Idiophones/Vibraphone/Soft%20Mallets/Vibes_soft_F4_v1_rr1_Main.wav" "sha256-lbfW7rcdd8zmB8DFKwpt/SPQ7u3C6zk/uabFpXaPSqU=")
            (clip "vibes-high" "Idiophones/Struck%20Idiophones/Vibraphone/Soft%20Mallets/Vibes_soft_A4_v2_rr1_Main.wav" "sha256-+1B8XLLyAjKJ/fE4QJLzxeE/A6K1scOfZ5LMV6p31Y0=")
            (clip "xylo-low" "Idiophones/Struck%20Idiophones/Xylophone/Soft%20Mallets/Xylo_Soft_G4_pp_01_far.wav" "sha256-ubhsNuPLOTHxX9gAYA+jaNl9iVbfmPkuClICx8whKqo=")
            (clip "xylo-high" "Idiophones/Struck%20Idiophones/Xylophone/Soft%20Mallets/Xylo_Soft_C5_pp_01_far.wav" "sha256-buZ2IITvbfOocuYVNvkh3Qx7A5iO2FEiwXRh3m9vNlk=")
          ];
        in pkgs.mkShell {
          packages = [ pkgs.nodejs_22 pkgs.ffmpeg pkgs.fluidsynth ];
          FLUID_R3_GM = "${pkgs.soundfont-fluid}/share/soundfonts/FluidR3_GM2-2.sf2";
          VCSL_AUDIO = "${recordings}";
        };
        devShells.emulator = pkgs.mkShell {
          packages = [ pkgs.jdk21 emulatorSdk ];
          JAVA_HOME = pkgs.jdk21.home;
          ANDROID_HOME = emulatorSdkRoot;
          ANDROID_SDK_ROOT = emulatorSdkRoot;
          LANG = "C.UTF-8";
          LC_ALL = "C.UTF-8";
        };
        # Lockfile-backed native fixture shell. Keep registry lookups out of E2E scripts so Soju,
        # Ergo, ZNC, and both REALITY implementations are reproducible from flake.lock.
        devShells.e2e-stack = pkgs.mkShell {
          packages = with pkgs; [
            ergochat
            soju
            netcat-openbsd
            openssl
            python3
            sing-box
            xray
            znc
          ];
          MOTD_E2E_STACK_SHELL = "1";
          LANG = "C.UTF-8";
          LC_ALL = "C.UTF-8";
        };
        devShells.libbox = pkgs.mkShell {
          # Deliberately omit the NDK: Nix's Android SDK composition fetches a
          # 690 MiB archive before the shell can start. build-libbox.sh accepts
          # a verified local r28 archive (or LIBBOX_NDK_HOME) instead.
          # Google's prebuilt NDK host tools use the FHS interpreter
          # /lib64/ld-linux-x86-64.so.2.  Guix does not provide that path, so
          # build-libbox.sh patches the verified *extracted cache* (never the
          # downloaded archive) to use these pinned Nix runtime paths.
          packages = [ pkgs.go_1_25 pkgs.git pkgs.gnumake pkgs.unzip pkgs.jdk21 pkgs.patchelf pkgs.zlib ];
          JAVA_HOME = pkgs.jdk21.home;
          LIBBOX_NDK_HOST_LOADER = pkgs.stdenv.cc.bintools.dynamicLinker;
          LIBBOX_NDK_HOST_RPATH = "${pkgs.zlib}/lib:${pkgs.stdenv.cc.cc.lib}/lib";
        };
      });
}
