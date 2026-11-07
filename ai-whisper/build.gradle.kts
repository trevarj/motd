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

val supportedAiAbis = listOf("arm64-v8a", "x86_64")
val aiAbis =
    providers
        .gradleProperty("motdAiAbis")
        .map { value -> value.split(',').map(String::trim) }
        .getOrElse(supportedAiAbis)
require(aiAbis.all { it in supportedAiAbis }) {
    "motdAiAbis must be a non-empty comma-separated list of arm64-v8a and/or x86_64"
}

android {
    namespace = "io.github.trevarj.motd.ai.whisper"
    compileSdk = 37
    ndkVersion = aiSourcePin("ANDROID_NDK_VERSION")

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += aiAbis }
        externalNativeBuild {
            cmake {
                arguments +=
                    listOf(
                        "-DANDROID_STL=c++_static",
                        "-DMOTD_DEBUG_OPTIMIZATION=${aiSourcePin("CMAKE_DEBUG_OPTIMIZATION")}",
                        "-DBUILD_SHARED_LIBS=OFF",
                        "-DGGML_NATIVE=OFF",
                        "-DGGML_OPENMP=OFF",
                        "-DGGML_VULKAN=OFF",
                        "-DGGML_OPENCL=OFF",
                        "-DGGML_OPENCL_EMBED_KERNELS=OFF",
                        "-DGGML_CUDA=OFF",
                        "-DGGML_SYCL=OFF",
                        "-DGGML_BLAS=OFF",
                        "-DGGML_RPC=OFF",
                    )
                providers.environmentVariable("CMAKE_MAKE_PROGRAM").orNull?.let {
                    arguments += "-DCMAKE_MAKE_PROGRAM=$it"
                }
                // AGP invokes Ninja directly, bypassing cmake --build's environment cap.
                providers.environmentVariable("CMAKE_BUILD_PARALLEL_LEVEL").orNull?.let { value ->
                    val jobs = value.toIntOrNull()
                    require(jobs != null && jobs > 0) { "CMAKE_BUILD_PARALLEL_LEVEL must be a positive integer" }
                    arguments +=
                        listOf(
                            "-DCMAKE_JOB_POOLS=motd_build=$jobs",
                            "-DCMAKE_JOB_POOL_COMPILE=motd_build",
                            "-DCMAKE_JOB_POOL_LINK=motd_build",
                        )
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
