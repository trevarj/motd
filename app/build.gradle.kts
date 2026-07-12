import java.security.MessageDigest
import java.util.Properties
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
}

// The release/debug APKs ship the pinned arm64 native core. Hermetic UI tests exercise plain IRC
// on an x86_64 emulator, so derive an AAR that retains the generated Java API but omits JNI. This
// keeps the E2E build installable without pretending that embedded obfuscation supports x86_64.
val libboxE2eAar by tasks.registering(Zip::class) {
    from(zipTree(layout.projectDirectory.file("libs/libbox.aar")))
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

    @TaskAction
    fun verify() {
        val values = Properties().also { manifest.get().asFile.inputStream().use(it::load) }
        check(values.getProperty("sing-box-version") == expectedVersion.get()) {
            "libbox manifest version must be ${expectedVersion.get()}"
        }
        check(values.getProperty("abis") == "arm64-v8a") {
            "libbox manifest must declare only arm64-v8a"
        }
        check(values.getProperty("libbox-aar-sha256") == expectedSha256.get()) {
            "libbox manifest SHA-256 does not match the pinned value"
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
        check(actualSha256 == expectedSha256.get()) {
            "libbox AAR SHA-256 does not match the pinned value"
        }
    }
}

android {
    namespace = "io.github.trevarj.motd"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.trevarj.motd"
        minSdk = 26
        targetSdk = 35
        // CI derives these from the git tag / run number (see plans/08).
        versionName = System.getenv("MOTD_VERSION_NAME") ?: "0.0.0-dev"
        versionCode = System.getenv("MOTD_VERSION_CODE")?.toIntOrNull() ?: 1
        testInstrumentationRunner = "io.github.trevarj.motd.SmokeTestRunner"
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
    buildFeatures { compose = true }
    testBuildType = "e2e"
    testOptions {
        unitTests { isIncludeAndroidResources = true }  // Robolectric
        managedDevices {
            localDevices {
                create("smokeApi34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp"
                }
            }
        }
    }

    lint {
        warningsAsErrors = true
        // Dependency versions are pinned by policy (plans/01); upgrade nags are intentional noise.
        disable += "GradleDependency"
        // AGP version is pinned by policy (plans/01); the upgrade nag is intentional noise.
        disable += "AndroidGradlePluginVersion"
        // SDK levels are pinned by policy (plans/01). CI runners ship a newer SDK than the
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

val verifyLibboxArtifact by tasks.registering(VerifyLibboxArtifact::class) {
    group = "verification"
    description = "Verifies the tracked libbox AAR against its pinned SHA-256 manifest."
    aar.set(layout.projectDirectory.file("libs/libbox.aar"))
    manifest.set(layout.projectDirectory.file("libs/libbox-v1.13.12.manifest"))
    expectedVersion.set("v1.13.12")
    expectedSha256.set("ef8b4a00eb2e2de7b9a593db18f5190431d1cd311066bde76792bfb1a262a88f")
}

tasks.matching { it.name == "check" || it.name.startsWith("assemble") }.configureEach {
    dependsOn(verifyLibboxArtifact)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":irc"))
    debugImplementation(files("libs/libbox.aar"))
    releaseImplementation(files("libs/libbox.aar"))
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
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Real WebSocket handshake for the WSS transport framing test (plans/19 §3.3).
    testImplementation(libs.okhttp.mockwebserver)
}
