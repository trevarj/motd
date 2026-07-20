import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
// reports into source control: ./gradlew :app:compileFossReleaseKotlin -PmotdComposeMetrics=true
if (providers.gradleProperty("motdComposeMetrics").map(String::toBoolean).getOrElse(false)) {
    composeCompiler {
        reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
        metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
    }
}

val libboxSourceBuild = providers.gradleProperty("motdLibboxSource").orNull?.toBoolean() ?: false
val libboxAar = providers.gradleProperty("motdLibboxAar").orNull?.let(::file)
    ?: file("libs/libbox.aar")
val libboxManifest = providers.gradleProperty("motdLibboxManifest").orNull?.let(::file)
    ?: file("libs/libbox-v1.13.12.manifest")

// The release/debug APKs ship the pinned arm64 native core. Hermetic UI tests exercise plain IRC
// on an x86_64 emulator, so derive an AAR that retains the generated Java API but omits JNI. This
// keeps the E2E build installable without pretending that embedded obfuscation supports x86_64.
val libboxE2eAar by tasks.registering(Zip::class) {
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
    @get:Input abstract val expectedSha256: Property<String>
    @get:Input abstract val enforcePinnedSha256: Property<Boolean>

    @TaskAction
    fun verify() {
        check(aar.get().asFile.isFile) { "libbox AAR does not exist: ${aar.get().asFile}" }
        check(manifest.get().asFile.isFile) {
            "libbox manifest does not exist: ${manifest.get().asFile}"
        }
        val values = Properties().also { manifest.get().asFile.inputStream().use(it::load) }
        check(values.getProperty("sing-box-version") == expectedVersion.get()) {
            "libbox manifest version must be ${expectedVersion.get()}"
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
            val nativeEntries = archive.entries().asSequence()
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

fun quotedBuildConfigValue(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val configuredVersionName = System.getenv("MOTD_VERSION_NAME")
    ?.takeIf(String::isNotBlank)
    ?: providers.gradleProperty("motdVersionName").orNull?.takeIf(String::isNotBlank)
    ?: "0.10.2"
val configuredVersionCode = System.getenv("MOTD_VERSION_CODE")?.toIntOrNull()
    ?: providers.gradleProperty("motdVersionCode").orNull?.toIntOrNull()
    ?: 10002
val sourceCommit = System.getenv("MOTD_SOURCE_COMMIT")
    ?.takeIf(String::isNotBlank)
    ?: providers.gradleProperty("motdSourceCommit").orNull?.takeIf(String::isNotBlank)
    ?: "unknown"

android {
    namespace = "io.github.trevarj.motd"
    compileSdk = 35

    // F-Droid rejects AGP's dependency metadata APK signing block. Dependency provenance is
    // pinned and published separately through the fdroiddata recipe and release source bundle.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "io.github.trevarj.motd"
        minSdk = 26
        targetSdk = 35
        // Release CI and F-Droid supply these explicitly; the checked-in Gradle properties provide
        // a deterministic fallback for source builds outside either service.
        versionName = configuredVersionName
        versionCode = configuredVersionCode
        buildConfigField("String", "MOTD_SOURCE_COMMIT", quotedBuildConfigValue(sourceCommit))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            buildConfigField("boolean", "FCM_AVAILABLE", "false")
        }
        create("google") {
            dimension = "distribution"
            val firebaseApiKey = System.getenv("MOTD_FIREBASE_API_KEY").orEmpty()
            val firebaseAppId = System.getenv("MOTD_FIREBASE_APP_ID").orEmpty()
            val firebaseProjectId = System.getenv("MOTD_FIREBASE_PROJECT_ID").orEmpty()
            val firebaseSenderId = System.getenv("MOTD_FIREBASE_SENDER_ID").orEmpty()
            val relayUrl = System.getenv("MOTD_FCM_RELAY_URL").orEmpty().trimEnd('/')
            val fcmConfigured = listOf(
                firebaseApiKey, firebaseAppId, firebaseProjectId, firebaseSenderId, relayUrl,
            ).all { it.isNotBlank() }
            fun quoted(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            buildConfigField("boolean", "FCM_AVAILABLE", fcmConfigured.toString())
            buildConfigField("String", "FIREBASE_API_KEY", quoted(firebaseApiKey))
            buildConfigField("String", "FIREBASE_APP_ID", quoted(firebaseAppId))
            buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(firebaseProjectId))
            buildConfigField("String", "FIREBASE_SENDER_ID", quoted(firebaseSenderId))
            buildConfigField("String", "FCM_RELAY_URL", quoted(relayUrl))
        }
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
            isMinifyEnabled = false      // deliberate: zero R8 risk in v1
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
        unitTests { isIncludeAndroidResources = true }  // Robolectric
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        managedDevices {
            localDevices {
                create("headlessApi34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp"
                }
            }
        }
    }
    sourceSets {
        getByName("test").resources.srcDir("$projectDir/schemas")
    }

    lint {
        warningsAsErrors = true
        // Dependency versions are catalog-pinned; upgrade nags are intentional noise.
        disable += "GradleDependency"
        // The AGP version is catalog-pinned; the upgrade nag is intentional noise.
        disable += "AndroidGradlePluginVersion"
        // SDK levels are deliberately pinned. CI runners ship a newer SDK than the
        // pinned platform, so lint flags targetSdk 35 as "old"; that nag is intentional noise.
        disable += "OldTargetApi"
        // The Doze battery-exemption request is a justified core use: an always-on IRC/bouncer
        // connection is the app's primary function. Distributed outside the Play Store.
        disable += "BatteryLife"
        // The pinned libbox artifact is arm64-only; ChromeOS x86_64 translation support is outside
        // the current APK contract.
        disable += "ChromeOsAbiSupport"
    }
}

androidComponents {
    // Hermetic E2E uses an AOSP image with no Google Play services. Building a Google E2E APK
    // adds a large, unusable variant and needlessly doubles the emulator-test compilation graph.
    beforeVariants(selector().withBuildType("e2e")) { variant ->
        if (variant.productFlavors.contains("distribution" to "google")) variant.enable = false
    }
}

val verifyLibboxArtifact by tasks.registering(VerifyLibboxArtifact::class) {
    group = "verification"
    description = "Verifies the libbox AAR against its manifest and pinned source contract."
    aar.set(libboxAar)
    manifest.set(libboxManifest)
    expectedVersion.set("v1.13.12")
    expectedSha256.set("cdb8eef80c3792df860094759ab0f8b8ecd73d595cec4c80f4526c1cae8ebdae")
    enforcePinnedSha256.set(!libboxSourceBuild)
}

tasks.matching { it.name == "check" || it.name.startsWith("assemble") }.configureEach {
    dependsOn(verifyLibboxArtifact)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":irc"))
    debugImplementation(files(libboxAar))
    releaseImplementation(files(libboxAar))
    add("e2eImplementation", files(libboxE2eAar))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
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
    implementation(libs.hilt.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    // Explicit for the IRC-over-WebSocket transport (plans/19 §3.3); already present transitively
    // via Coil, pinned to the same resolved version in libs.versions.toml so nothing new resolves.
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.unifiedpush.connector)
    "googleImplementation"(platform(libs.firebase.bom))
    "googleImplementation"(libs.firebase.messaging)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestUtil(libs.androidx.test.orchestrator)
    // Real WebSocket handshake for the WSS transport framing test (plans/19 §3.3).
    testImplementation(libs.okhttp.mockwebserver)
}

// Generated JUnit cases are deterministic only for the selected profile/seed/replay inputs.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    listOf(
        "MOTD_FUZZ_PROFILE",
        "MOTD_FUZZ_SEED",
        "MOTD_FUZZ_CASE",
        "MOTD_FUZZ_CASES",
        "MOTD_FUZZ_STEPS",
    ).forEach { name -> inputs.property(name, providers.environmentVariable(name).orElse("")) }
}
