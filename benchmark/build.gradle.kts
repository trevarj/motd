plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "io.github.trevarj.motd.macrobenchmark" // distinct from the app's .benchmark id
    compileSdk = 37

    defaultConfig {
        minSdk = 29 // Perfetto-backed frame and async trace-section metrics.
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Pairs with :app's profileable `benchmark` build type; only this test APK is debuggable.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants { it.enable = it.buildType == "benchmark" }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.junit)
}
