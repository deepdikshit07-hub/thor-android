#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <string>
#include <vector>
#include <thread>

extern "C" JNIEXPORT jlong JNICALL
Java_com_thor_assistant_ThorWhisperNative_nativeCreate(JNIEnv *env, jobject, jstring model_path) {
    if (model_path == nullptr) return 0;
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;
    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    whisper_context *context = whisper_init_from_file_with_params(path, context_params);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_thor_assistant_ThorWhisperNative_nativeTranscribe(JNIEnv *env, jobject, jlong handle, jfloatArray audio, jstring language) {
    auto *context = reinterpret_cast<whisper_context *>(handle);
    if (context == nullptr || audio == nullptr || language == nullptr) return env->NewStringUTF("");
    const jsize count = env->GetArrayLength(audio);
    if (count <= 0) return env->NewStringUTF("");
    std::vector<float> samples(static_cast<size_t>(count));
    env->GetFloatArrayRegion(audio, 0, count, samples.data());
    if (env->ExceptionCheck()) return env->NewStringUTF("");
    const char *language_chars = env->GetStringUTFChars(language, nullptr);
    if (language_chars == nullptr) return env->NewStringUTF("");
    const std::string language_code(language_chars);
    env->ReleaseStringUTFChars(language, language_chars);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const unsigned int cores = std::max(1u, std::thread::hardware_concurrency());
    params.n_threads = static_cast<int>(std::min(4u, cores));
    params.language = language_code.c_str();
    params.translate = false;
    params.no_context = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    const int status = whisper_full(context, params, samples.data(), static_cast<int>(samples.size()));
    if (status != 0) return env->NewStringUTF("");
    std::string result;
    const int segments = whisper_full_n_segments(context);
    for (int i = 0; i < segments; ++i) {
        const char *segment = whisper_full_get_segment_text(context, i);
        if (segment != nullptr) result += segment;
    }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_thor_assistant_ThorWhisperNative_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto *context = reinterpret_cast<whisper_context *>(handle);
    if (context != nullptr) whisper_free(context);
}
