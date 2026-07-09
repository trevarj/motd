plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
        }
        release {
            isMinifyEnabled = false      // deliberate: zero R8 risk in v1
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }  // Robolectric

    lint {
        warningsAsErrors = true
        // Dependency versions are pinned by policy (plans/01); upgrade nags are intentional noise.
        disable += "GradleDependency"
        // AGP version is pinned by policy (plans/01); the upgrade nag is intentional noise.
        disable += "AndroidGradlePluginVersion"
        // SDK levels are pinned by policy (plans/01). CI runners ship a newer SDK than the
        // pinned platform, so lint flags targetSdk 35 as "old"; that nag is intentional noise.
        disable += "OldTargetApi"
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":irc"))
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
