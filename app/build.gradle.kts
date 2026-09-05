import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Opt in for release-only characterization without slowing routine builds or checking generated
// reports into source control: ./gradlew :app:compileReleaseKotlin -PmotdComposeMetrics=true
if (providers.gradleProperty("motdComposeMetrics").map(String::toBoolean).getOrElse(false)) {
    composeCompiler {
        reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
        metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
    }
}

val libboxSourceBuild = providers.gradleProperty("motdLibboxSource").orNull?.toBoolean() ?: false
val libboxAar =
    providers.gradleProperty("motdLibboxAar").orNull?.let(::file)
        ?: file("libs/libbox.aar")
val libboxManifest =
    providers.gradleProperty("motdLibboxManifest").orNull?.let(::file)
        ?: file("libs/libbox-v1.13.12.manifest")
val libboxNdkVersion =
    rootProject
        .file("third_party/sing-box/source.lock")
        .readLines()
        .singleOrNull { it.startsWith("ANDROID_NDK_VERSION=") }
        ?.substringAfter('=')
        ?.takeIf(String::isNotBlank)
        ?: error("third_party/sing-box/source.lock must pin ANDROID_NDK_VERSION")

// The release/debug APKs ship the pinned arm64 native core. Hermetic UI tests exercise plain IRC
// on an x86_64 emulator, so derive an AAR that retains the generated Java API but omits JNI. This
// keeps the E2E build installable without pretending that embedded obfuscation supports x86_64.
val libboxE2eAar =
    tasks.register<Zip>("libboxE2eAar") {
        from(zipTree(libboxAar))
        exclude("jni/**")
        archiveFileName.set("libbox-e2e-no-jni.aar")
        destinationDirectory.set(layout.buildDirectory.dir("generated/e2e-libs"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

abstract class VerifyLibboxArtifact : DefaultTask() {
    @get:InputFile abstract val aar: RegularFileProperty

    @get:InputFile abstract val manifest: RegularFileProperty

    @get:Input abstract val expectedVersion: Property<String>

    @get:Input abstract val expectedNdkVersion: Property<String>

    @get:Input abstract val expectedSha256: Property<String>

    @get:Input abstract val enforcePinnedSha256: Property<Boolean>

    @TaskAction
    fun verify() {
        check(aar.get().asFile.isFile) { "libbox AAR does not exist: ${aar.get().asFile}" }
        check(manifest.get().asFile.isFile) {
            "libbox manifest does not exist: ${manifest.get().asFile}"
        }
        val values =
            Properties().also {
                manifest
                    .get()
                    .asFile
                    .inputStream()
                    .use(it::load)
            }
        check(values.getProperty("sing-box-version") == expectedVersion.get()) {
            "libbox manifest version must be ${expectedVersion.get()}"
        }
        check(values.getProperty("android-ndk-version") == expectedNdkVersion.get()) {
            "libbox manifest NDK must be ${expectedNdkVersion.get()}"
        }
        check(values.getProperty("abis") == "arm64-v8a") {
            "libbox manifest must declare only arm64-v8a"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        aar.get().asFile.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        check(values.getProperty("libbox-aar-sha256") == actualSha256) {
            "libbox manifest SHA-256 does not match the generated AAR"
        }
        if (enforcePinnedSha256.get()) {
            check(actualSha256 == expectedSha256.get()) {
                "libbox AAR SHA-256 does not match the pinned value"
            }
        }
        ZipFile(aar.get().asFile).use { archive ->
            val nativeEntries =
                archive
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .filter { it.startsWith("jni/") && !it.endsWith("/") }
                    .sorted()
                    .toList()
            check(nativeEntries == listOf("jni/arm64-v8a/libbox.so")) {
                "libbox AAR must contain only jni/arm64-v8a/libbox.so, found $nativeEntries"
            }
        }
    }
}

abstract class VerifyAiNativeArtifacts : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val whisperAar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val debugApk: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val e2eApk: RegularFileProperty

    @TaskAction
    fun verify() {
        verifyAar(whisperAar.get().asFile, "ai-whisper debug AAR", "libmotd_whisper.so")
        verifyApk(debugApk.get().asFile, "app debug APK", "arm64-v8a", rejectLibbox = false)
        verifyApk(e2eApk.get().asFile, "app e2e APK", "x86_64", rejectLibbox = true)
    }

    private fun verifyAar(
        archive: File,
        label: String,
        libraryName: String,
    ) {
        val entries = readEntries(archive, label)
        val expected =
            listOf(
                "jni/arm64-v8a/$libraryName",
                "jni/x86_64/$libraryName",
            )
        val aiLibraries = entries.filter(::isMotdAiLibrary)
        check(aiLibraries == expected) {
            "$label must contain exactly $expected; found AI JNI entries $aiLibraries"
        }
        val jniEntries = entries.filter { it.startsWith("jni/") }
        check(jniEntries == expected) {
            "$label must contain no JNI files except $expected; found $jniEntries"
        }
        rejectLeakedRuntimeLibraries(label, entries)
        rejectModelWeights(label, entries)
    }

    private fun verifyApk(
        archive: File,
        label: String,
        abi: String,
        rejectLibbox: Boolean,
    ) {
        val entries = readEntries(archive, label)
        val expectedAiLibraries =
            listOf(
                "lib/$abi/libmotd_whisper.so",
            )
        val aiLibraries = entries.filter(::isMotdAiLibrary)
        check(aiLibraries == expectedAiLibraries) {
            "$label must contain exactly $expectedAiLibraries; found AI JNI entries $aiLibraries"
        }
        val unexpectedAbiEntries =
            entries.filter {
                it.startsWith("lib/") &&
                    it.substringAfter("lib/").substringBefore('/') != abi
            }
        check(unexpectedAbiEntries.isEmpty()) {
            "$label must contain native files only for $abi; found $unexpectedAbiEntries"
        }
        if (rejectLibbox) {
            val libboxEntries = entries.filter { it.substringAfterLast('/') == "libbox.so" }
            check(libboxEntries.isEmpty()) {
                "$label must not package libbox.so; found $libboxEntries"
            }
        }
        rejectLeakedRuntimeLibraries(label, entries)
        rejectModelWeights(label, entries)
    }

    private fun readEntries(
        archive: File,
        label: String,
    ): List<String> {
        check(archive.isFile) { "$label does not exist: $archive" }
        return try {
            ZipFile(archive).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name }
                    .sorted()
                    .toList()
            }
        } catch (failure: IOException) {
            throw GradleException("$label is not a readable ZIP archive: $archive", failure)
        }
    }

    private fun rejectLeakedRuntimeLibraries(
        label: String,
        entries: List<String>,
    ) {
        val leaked =
            entries.filter {
                val name = it.substringAfterLast('/').lowercase()
                name.endsWith(".so") &&
                    (
                        name.startsWith("libggml") ||
                            name.startsWith("libllama") ||
                            name.startsWith("libwhisper")
                    )
            }
        check(leaked.isEmpty()) {
            "$label must statically link GGML and whisper; found leaked shared libraries $leaked"
        }
    }

    private fun rejectModelWeights(
        label: String,
        entries: List<String>,
    ) {
        val weights = entries.filter(::isModelWeight)
        check(weights.isEmpty()) {
            "$label must not package model weights under assets/resources/JNI; found $weights"
        }
    }

    private fun isMotdAiLibrary(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.startsWith("libmotd_") && name.endsWith(".so")
    }

    private fun isModelWeight(path: String): Boolean {
        val normalized = path.lowercase()
        if (
            !normalized.startsWith("assets/") &&
            !normalized.startsWith("res/") &&
            !normalized.startsWith("resources/") &&
            !normalized.startsWith("jni/") &&
            !normalized.startsWith("lib/")
        ) {
            return false
        }
        return when (normalized.substringAfterLast('.', "")) {
            "bin",
            "ckpt",
            "ggml",
            "gguf",
            "model",
            "onnx",
            "pt",
            "pth",
            "safetensors",
            "tflite",
            "weights",
            -> true

            else -> false
        }
    }
}

fun quotedBuildConfigValue(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val configuredVersionName =
    System
        .getenv("MOTD_VERSION_NAME")
        ?.takeIf(String::isNotBlank)
        ?: providers.gradleProperty("motdVersionName").orNull?.takeIf(String::isNotBlank)
        ?: "0.10.2"
val configuredVersionCode =
    System.getenv("MOTD_VERSION_CODE")?.toIntOrNull()
        ?: providers.gradleProperty("motdVersionCode").orNull?.toIntOrNull()
        ?: 10002
val sourceCommit =
    System
        .getenv("MOTD_SOURCE_COMMIT")
        ?.takeIf(String::isNotBlank)
        ?: providers.gradleProperty("motdSourceCommit").orNull?.takeIf(String::isNotBlank)
        ?: "unknown"

android {
    namespace = "io.github.trevarj.motd"
    compileSdk = 37
    // The same pinned NDK builds libbox and performs final native packaging.
    ndkVersion = libboxNdkVersion

    // F-Droid rejects AGP's dependency metadata APK signing block. Dependency provenance is
    // pinned and published separately through the fdroiddata recipe and release source bundle.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "io.github.trevarj.motd"
        minSdk = 26
        targetSdk = 36
        // Release CI and F-Droid supply these explicitly; the checked-in Gradle properties provide
        // a deterministic fallback for source builds outside either service.
        versionName = configuredVersionName
        versionCode = configuredVersionCode
        buildConfigField("String", "MOTD_SOURCE_COMMIT", quotedBuildConfigValue(sourceCommit))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    // Signing only when CI secrets are present; local/debug builds never fail on this.
    val keystorePath = System.getenv("MOTD_KEYSTORE_PATH")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MOTD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MOTD_KEY_ALIAS")
                keyPassword = System.getenv("MOTD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Distinct applicationId so a debug build can coexist with the released APK
            // (they carry different signing keys; same id + different key = install failure).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            ndk { abiFilters += "arm64-v8a" }
        }
        release {
            isMinifyEnabled = false // deliberate: zero R8 risk in v1
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
            ndk { abiFilters += "arm64-v8a" }
        }
        create("e2e") {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
            ndk { abiFilters += "x86_64" }
        }
    }
    // Production APKs remain arm64-only while this is the only packaged libbox artifact. The
    // debuggable E2E variant is deliberately x86_64 and contains no libbox JNI (see above).
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testBuildType = "e2e"
    testOptions {
        unitTests { isIncludeAndroidResources = true } // Robolectric
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
    sourceSets {
        getByName("test").resources.directories.add("$projectDir/schemas")
    }

    lint {
        warningsAsErrors = true
        // Dependency versions are catalog-pinned; upgrade nags are intentional noise.
        disable += "GradleDependency"
        // The AGP version is catalog-pinned; the upgrade nag is intentional noise.
        disable += "AndroidGradlePluginVersion"
        // compileSdk 37 / targetSdk 36 is an intentional split: compile against the pinned SDK
        // while targeting the latest stable Android behavior. The resulting nag is noise.
        disable += "OldTargetApi"
        // The Doze battery-exemption request is a justified core use: an always-on IRC/bouncer
        // connection is the app's primary function. Distributed outside the Play Store.
        disable += "BatteryLife"
        // The pinned libbox artifact is arm64-only; ChromeOS x86_64 translation support is outside
        // the current APK contract.
        disable += "ChromeOsAbiSupport"
    }
}

val verifyLibboxArtifact =
    tasks.register<VerifyLibboxArtifact>("verifyLibboxArtifact") {
        group = "verification"
        description = "Verifies the libbox AAR against its manifest and pinned source contract."
        aar.set(libboxAar)
        manifest.set(libboxManifest)
        expectedVersion.set("v1.13.12")
        expectedNdkVersion.set(libboxNdkVersion)
        expectedSha256.set("3fdbd30eba2450935389c100efd88475721d44870bbab870340533ee4ba84977")
        enforcePinnedSha256.set(!libboxSourceBuild)
    }

tasks.register<VerifyAiNativeArtifacts>("verifyAiNativeArtifacts") {
    group = "verification"
    description = "Verifies AI runtime AAR and app APK native packaging contracts."
    dependsOn(
        ":ai-whisper:bundleDebugAar",
        "assembleDebug",
        "assembleE2e",
    )
    whisperAar.set(
        rootProject.layout.projectDirectory.file(
            "ai-whisper/build/outputs/aar/ai-whisper-debug.aar",
        ),
    )
    debugApk.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    e2eApk.set(layout.buildDirectory.file("outputs/apk/e2e/app-e2e.apk"))
}

tasks.matching { it.name == "check" || it.name.startsWith("assemble") }.configureEach {
    dependsOn(verifyLibboxArtifact)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":irc"))
    implementation(project(":ai-whisper"))
    debugImplementation(files(libboxAar))
    releaseImplementation(files(libboxAar))
    add("e2eImplementation", files(libboxE2eAar))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material3.adaptive.navigation)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.process)
    implementation(libs.core.ktx)
    implementation(libs.emoji2.emojipicker)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.datastore.preferences)
    // Three hand-authored bodymovin assets in res/raw carry motion that Compose specs cannot draw
    // (stroke trim draw-on). Runtime-only: no new networking, and colors stay theme-driven.
    implementation(libs.lottie.compose)
    // QR invite generation/decoding stays entirely on-device. CameraX supplies only preview/frame capture.
    implementation(libs.zxing.core)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)
    implementation(libs.telephoto.zoomable.image.coil)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)
    implementation(libs.media3.ui)
    // Explicit for the IRC-over-WebSocket transport; already present transitively
    // via Coil, pinned to the same resolved version in libs.versions.toml so nothing new resolves.
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.unifiedpush.connector)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Robolectric-driven Compose UI regression tests (chat-list scroll/placement behavior).
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    add("e2eImplementation", libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestUtil(libs.androidx.test.orchestrator)
    // Real WebSocket handshake for the WSS transport framing test.
    testImplementation(libs.okhttp.mockwebserver)
}

// Generated JUnit cases are deterministic only for the selected profile/seed/replay inputs.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Native Compose state degrades paging within ten shared Robolectric classes.
    if (name == "testDebugUnitTest") forkEvery = 5
    listOf(
        "MOTD_FUZZ_PROFILE",
        "MOTD_FUZZ_SEED",
        "MOTD_FUZZ_CASE",
        "MOTD_FUZZ_CASES",
        "MOTD_FUZZ_STEPS",
        "MOTD_FUZZ_SHARD",
    ).forEach { name -> inputs.property(name, providers.environmentVariable(name).orElse("")) }
}
