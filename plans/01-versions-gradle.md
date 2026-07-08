# 01 — Pinned versions & Gradle setup

The version matrix below is a known-coexisting set. **Do not bump, downgrade, or add
dependencies.** Kotlin 2.x provides the Compose compiler via the
`org.jetbrains.kotlin.plugin.compose` plugin — there is no `composeOptions` block anywhere.
Hilt and Room both go through KSP; kapt is forbidden.

## `gradle/libs.versions.toml` (verbatim)

```toml
[versions]
agp = "8.9.2"
kotlin = "2.1.20"
ksp = "2.1.20-1.0.32"
hilt = "2.56.2"
hiltNavigationCompose = "1.2.0"
composeBom = "2025.04.01"
activityCompose = "1.10.1"
navigationCompose = "2.8.9"
lifecycle = "2.8.7"
coreKtx = "1.16.0"
room = "2.7.1"
paging = "3.3.6"
datastore = "1.1.4"
coil = "2.7.0"
okio = "3.9.1"
coroutines = "1.10.1"
serialization = "1.8.0"
unifiedpush = "2.5.0"
junit = "4.13.2"
turbine = "1.2.0"
robolectric = "4.14.1"
androidxTestCore = "1.6.1"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons = { group = "androidx.compose.material", name = "material-icons-extended" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-service = { group = "androidx.lifecycle", name = "lifecycle-service", version.ref = "lifecycle" }
core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-paging = { group = "androidx.room", name = "room-paging", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
paging-runtime = { group = "androidx.paging", name = "paging-runtime", version.ref = "paging" }
paging-compose = { group = "androidx.paging", name = "paging-compose", version.ref = "paging" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
okio = { group = "com.squareup.okio", name = "okio", version.ref = "okio" }
coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
unifiedpush-connector = { group = "org.unifiedpush.android", name = "connector", version.ref = "unifiedpush" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

## SDK / toolchain

- Gradle wrapper **8.13** (`gradle-8.13-bin.zip` in `gradle-wrapper.properties`)
- compileSdk **35**, targetSdk **35**, minSdk **26**
- JDK **17** via `kotlin { jvmToolchain(17) }` in BOTH modules

## `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "motd"
include(":irc", ":app")
```

## Root `build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

## `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
kotlin.code.style=official
```

## `irc/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.okio)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
```

## `app/build.gradle.kts`

```kotlin
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
        release {
            isMinifyEnabled = false      // deliberate: zero R8 risk in v1
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }  // Robolectric
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
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.unifiedpush.connector)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
```

## Local dev environment: Nix flake

The host machine is a Guix System, but Kotlin/Android tooling comes from **Nix** (`flake.nix`
at repo root, already present — do not rewrite it). It provides JDK 17 and an Android SDK
matching the pins above (platform 35, build-tools 35.0.0), plus the aapt2 override AGP needs on
non-FHS systems. direnv loads it via `.envrc` (`use flake`).

```sh
nix develop -c ./gradlew :irc:test           # pure-JVM protocol tests
nix develop -c ./gradlew :app:assembleDebug  # full APK
```

CI (GitHub Actions, `ubuntu-latest`) is the canonical build environment and does NOT use Nix —
it uses `setup-java` + the preinstalled Android SDK (see `08-ci-release.md`).

## Wrapper bootstrap note (WP1)

The implementation environment may not have a `gradle` binary to generate the wrapper. WP1
must commit the standard Gradle 8.13 wrapper: `gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.properties` (distributionUrl
`https\://services.gradle.org/distributions/gradle-8.13-bin.zip`) and
`gradle/wrapper/gradle-wrapper.jar` fetched from
`https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar`.
Mark `gradlew` executable.
