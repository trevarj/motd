plugins {
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.okio)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}

// Gradle must not reuse a generated-test result produced with a different replay/profile input.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    listOf(
        "MOTD_FUZZ_PROFILE",
        "MOTD_FUZZ_SEED",
        "MOTD_FUZZ_CASE",
        "MOTD_FUZZ_CASES",
        "MOTD_FUZZ_STEPS",
    ).forEach { name -> inputs.property(name, providers.environmentVariable(name).orElse("")) }
}
