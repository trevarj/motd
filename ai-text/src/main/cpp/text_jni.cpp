#include "text_engine.h"
#include <jni.h>
#include <limits>
#include <mutex>
#include <unordered_map>

namespace {
using namespace motd::text;
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Cancellation>> requests;
Engine& engine() { static Engine instance; return instance; }
std::shared_ptr<Cancellation> request(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto found = requests.find(id);
    if (found == requests.end()) throw Exception(Error::Native);
    return found->second;
}
void throw_code(JNIEnv* env, Error code) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("io/github/trevarj/motd/ai/text/TextException");
    if (!type) return;
    jmethodID constructor = env->GetMethodID(type, "<init>", "(I)V");
    if (constructor) {
        auto failure = static_cast<jthrowable>(env->NewObject(type, constructor, static_cast<jint>(code)));
        if (failure) { env->Throw(failure); env->DeleteLocalRef(failure); }
    }
    env->DeleteLocalRef(type);
}
void failure(JNIEnv* env) {
    try { throw; }
    catch (const Cancelled&) {
        if (env->ExceptionCheck()) return;
        jclass type = env->FindClass("java/util/concurrent/CancellationException");
        if (type) { env->ThrowNew(type, "Local text operation cancelled"); env->DeleteLocalRef(type); }
    }
    catch (const Exception& error) { throw_code(env, error.code); }
    catch (const std::bad_alloc&) { throw_code(env, Error::OutOfMemory); }
    catch (...) { throw_code(env, Error::Native); }
}
std::string bytes(JNIEnv* env, jbyteArray array, size_t maximum) {
    if (!array) throw Exception(Error::InvalidRequest);
    jsize size = env->GetArrayLength(array);
    if (size < 0 || static_cast<size_t>(size) > maximum) throw Exception(Error::InvalidRequest);
    std::string result(static_cast<size_t>(size), '\0');
    if (size) env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw Exception(Error::Native);
    return result;
}
jobject information(JNIEnv* env, const ModelInfo& info) {
    jclass type = env->FindClass("io/github/trevarj/motd/ai/text/TextModelInfo");
    if (!type) return nullptr;
    jmethodID constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;I)V");
    if (!constructor) { env->DeleteLocalRef(type); return nullptr; }
    jstring architecture = env->NewStringUTF(info.architecture.c_str());
    jstring quantization = env->NewStringUTF(info.quantization.c_str());
    jstring template_id = env->NewStringUTF(info.template_id.c_str());
    jobject result = nullptr;
    if (architecture && quantization && template_id) {
        result = env->NewObject(type, constructor, architecture, quantization,
            static_cast<jint>(info.context_tokens), template_id, static_cast<jint>(info.maximum_cpu_threads));
    }
    env->DeleteLocalRef(architecture);
    env->DeleteLocalRef(quantization);
    env->DeleteLocalRef(template_id);
    env->DeleteLocalRef(type);
    return result;
}
}
#define JNI_METHOD(name) Java_io_github_trevarj_motd_ai_text_TextRuntime_##name
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeBegin)(JNIEnv* env, jobject, jlong id) {
    try {
        if (id <= 0) throw Exception(Error::Native);
        auto record = std::make_shared<Cancellation>();
        std::lock_guard<std::mutex> lock(registry_mutex);
        if (!requests.emplace(id, std::move(record)).second) throw Exception(Error::Native);
    } catch (...) { failure(env); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeEnd)(JNIEnv* env, jobject, jlong id) {
    try {
        std::lock_guard<std::mutex> lock(registry_mutex);
        if (requests.erase(id) != 1) throw Exception(Error::Native);
    } catch (...) { failure(env); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeCancel)(JNIEnv* env, jobject, jlong id) {
    try {
        // Never enter the engine/model mutex. A late cancellation after nativeEnd is harmless.
        std::lock_guard<std::mutex> lock(registry_mutex);
        auto found = requests.find(id);
        if (found != requests.end()) found->second->requested.store(true, std::memory_order_relaxed);
    } catch (...) { failure(env); }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(nativeInspect)(JNIEnv* env, jobject, jlong id, jbyteArray path) {
    try {
        auto record = request(id);
        auto file = bytes(env, path, 65536);
        return information(env, engine().inspect(file, *record));
    } catch (...) { failure(env); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(nativeLoad)(JNIEnv* env, jobject, jlong id, jbyteArray path, jint cpu_threads) {
    try {
        auto record = request(id);
        auto file = bytes(env, path, 65536);
        return information(env, engine().load(file, cpu_threads, *record));
    } catch (...) { failure(env); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(nativeTransform)(JNIEnv* env, jobject, jlong id, jint operation,
        jbyteArray text, jbyteArray instruction, jbyteArray target, jint cpu_threads) {
    try {
        auto record = request(id);
        auto source = bytes(env, text, 65536);
        auto style = bytes(env, instruction, 4096);
        auto language = bytes(env, target, 128);
        auto transformed = engine().transform({static_cast<Operation>(operation), source, style, language, cpu_threads}, *record);
        jbyteArray utf8 = env->NewByteArray(static_cast<jsize>(transformed.text.size()));
        if (!utf8) return nullptr;
        env->SetByteArrayRegion(utf8, 0, static_cast<jsize>(transformed.text.size()), reinterpret_cast<const jbyte*>(transformed.text.data()));
        jclass type = env->FindClass("io/github/trevarj/motd/ai/text/NativeTextResult");
        if (!type) { env->DeleteLocalRef(utf8); return nullptr; }
        jmethodID constructor = env->GetMethodID(type, "<init>", "([BI)V");
        jobject result = constructor ? env->NewObject(type, constructor, utf8, static_cast<jint>(transformed.termination)) : nullptr;
        env->DeleteLocalRef(type);
        env->DeleteLocalRef(utf8);
        return result;
    } catch (...) { failure(env); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeUnload)(JNIEnv* env, jobject, jlong id) {
    try { auto record = request(id); engine().unload(); }
    catch (...) { failure(env); }
}
