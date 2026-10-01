plugins {
    alias(libs.plugins.android.library)
}

abstract class PrepareTtsSources : Exec() {
    @get:OutputDirectory
    abstract val kotlinOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val licensesOutput: DirectoryProperty
}

abstract class BuildTtsNative : Exec() {
    @get:OutputDirectory
    abstract val jniOutput: DirectoryProperty
}

fun ttsSourcePin(name: String): String =
    rootProject
        .file("third_party/ai/source.lock")
        .readLines()
        .singleOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.takeIf(String::isNotBlank)
        ?: error("third_party/ai/source.lock must pin $name")

val generatedKotlin = layout.buildDirectory.dir("generated/ttsKotlin")
val generatedJni = layout.buildDirectory.dir("generated/jniLibs")
val generatedLicenses = layout.buildDirectory.dir("generated/ttsLicenses")
val nativeTools = rootProject.file("third_party/ai")
val prepareTtsSources =
    tasks.register<PrepareTtsSources>("prepareTtsSources") {
        kotlinOutput.set(generatedKotlin)
        licensesOutput.set(generatedLicenses)
        // Keep verifying source/archive and license digests before using the generated binding.
        outputs.upToDateWhen { false }
        workingDir(rootProject.projectDir)
        commandLine(
            "python3",
            File(nativeTools, "prepare-tts-sources.py"),
            "--fetch",
            "--kotlin-output",
            generatedKotlin.get().asFile,
            "--licenses-output",
            generatedLicenses.get().asFile,
        )
    }

val buildTtsNative =
    tasks.register<BuildTtsNative>("buildTtsNative") {
        jniOutput.set(generatedJni)
        dependsOn(prepareTtsSources)
        workingDir(rootProject.projectDir)
        inputs.files(
            File(nativeTools, "source.lock"),
            File(nativeTools, "tts-sources.lock.json"),
            File(nativeTools, "tts-licenses.lock.json"),
            File(nativeTools, "patches/ort-android-soname.cmake"),
            File(nativeTools, "patches/sherpa-tts-jni-errors.patch"),
            File(nativeTools, "prepare-tts-sources.py"),
            File(nativeTools, "build-tts.py"),
            File(nativeTools, "verify-tts-native.py"),
        )
        commandLine(
            "python3",
            File(nativeTools, "build-tts.py"),
            "android",
            "--output",
            generatedJni.get().asFile,
        )
    }

android {
    namespace = "io.github.trevarj.motd.ai.tts"
    compileSdk = 37
    ndkVersion = ttsSourcePin("ANDROID_NDK_VERSION")
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin { jvmToolchain(21) }

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(prepareTtsSources) { it.kotlinOutput }
        variant.sources.resources?.addGeneratedSourceDirectory(prepareTtsSources) { it.licensesOutput }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildTtsNative) { it.jniOutput }
    }
}

dependencies {
    testImplementation(libs.junit)
}
