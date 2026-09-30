plugins {
    alias(libs.plugins.android.library)
}

fun aiSourcePin(name: String): String =
    rootProject
        .file("third_party/ai/source.lock")
        .readLines()
        .singleOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.takeIf(String::isNotBlank)
        ?: error("third_party/ai/source.lock must pin $name")

android {
    namespace = "io.github.trevarj.motd.ai.text"
    compileSdk = 37
    ndkVersion = aiSourcePin("ANDROID_NDK_VERSION")
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        for (name in listOf("LLAMA_REPOSITORY", "LLAMA_COMMIT", "LLAMA_LICENSE", "LLAMA_LICENSE_FILE", "TEXT_MODEL_REPOSITORY", "TEXT_MODEL_REVISION", "TEXT_MODEL_FILE", "TEXT_MODEL_SHA256", "TEXT_MODEL_LICENSE", "TEXT_TEMPLATE_ID")) {
            buildConfigField("String", name, "\"${aiSourcePin(name)}\"")
        }
        buildConfigField("long", "TEXT_MODEL_BYTES", "${aiSourcePin("TEXT_MODEL_BYTES")}L")
        buildConfigField("String", "TEXT_MODEL_URL", "\"https://huggingface.co/${aiSourcePin("TEXT_MODEL_REPOSITORY")}/resolve/${aiSourcePin("TEXT_MODEL_REVISION")}/${aiSourcePin("TEXT_MODEL_FILE")}\"")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DMOTD_DEBUG_OPTIMIZATION=${aiSourcePin("CMAKE_DEBUG_OPTIMIZATION")}")
                providers.environmentVariable("CMAKE_MAKE_PROGRAM").orNull?.let {
                    arguments += "-DCMAKE_MAKE_PROGRAM=$it"
                }
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = aiSourcePin("CMAKE_VERSION")
        }
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.coroutines.core)
}
