#include <jni.h>
#include <android/log.h>
#include <string>
#include <atomic>

namespace {
constexpr const char* kTag = "TempleI-FfmpegStub";
std::atomic<bool> g_prepared{false};
std::atomic<bool> g_started{false};
std::atomic<long long> g_videoAuCount{0};
std::atomic<long long> g_audioAuCount{0};
std::string g_last_error;
std::string g_runtime_info = "ffmpeg JNI stub loaded (PR D av-ingest bring-up)";

void set_error(const std::string& message) {
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativePrepare(
    JNIEnv* env,
    jobject,
    jstring host,
    jint port,
    jint latency_ms,
    jstring mode,
    jboolean video_enabled,
    jboolean audio_enabled
) {
    const char* host_chars = env->GetStringUTFChars(host, nullptr);
    const char* mode_chars = env->GetStringUTFChars(mode, nullptr);
    const std::string host_value = host_chars == nullptr ? "" : host_chars;
    const std::string mode_value = mode_chars == nullptr ? "" : mode_chars;
    if (host_chars != nullptr) {
        env->ReleaseStringUTFChars(host, host_chars);
    }
    if (mode_chars != nullptr) {
        env->ReleaseStringUTFChars(mode, mode_chars);
    }

    if (host_value.empty() || port <= 0 || port > 65535) {
        set_error("nativePrepare rejected invalid endpoint args");
        return JNI_FALSE;
    }

    g_prepared.store(true);
    g_started.store(false);
    g_videoAuCount.store(0);
    g_audioAuCount.store(0);
    g_last_error.clear();
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "nativePrepare host=%s port=%d latency=%d mode=%s video=%d audio=%d",
        host_value.c_str(),
        static_cast<int>(port),
        static_cast<int>(latency_ms),
        mode_value.c_str(),
        video_enabled ? 1 : 0,
        audio_enabled ? 1 : 0
    );
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeStart(JNIEnv*, jobject) {
    if (!g_prepared.load()) {
        set_error("nativeStart failed: backend not prepared");
        return JNI_FALSE;
    }
    g_started.store(true);
    __android_log_print(ANDROID_LOG_INFO, kTag, "nativeStart ok");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativePushVideoAccessUnit(
    JNIEnv* env,
    jobject,
    jbyteArray data,
    jlong presentation_time_us,
    jint flags
) {
    if (!g_started.load()) {
        set_error("nativePushVideoAccessUnit failed: backend not started");
        return JNI_FALSE;
    }

    const jsize size = data == nullptr ? 0 : env->GetArrayLength(data);
    if (size <= 0) {
        return JNI_TRUE;
    }

    const long long count = g_videoAuCount.fetch_add(1) + 1;
    if (count <= 5 || (count % 120) == 0) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "videoAU count=%lld size=%d ptsUs=%lld flags=%d",
            count,
            static_cast<int>(size),
            static_cast<long long>(presentation_time_us),
            static_cast<int>(flags)
        );
    }
    return JNI_TRUE;
}


extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativePushAudioAccessUnit(
    JNIEnv* env,
    jobject,
    jbyteArray data,
    jlong presentation_time_us,
    jint flags
) {
    if (!g_started.load()) {
        set_error("nativePushAudioAccessUnit failed: backend not started");
        return JNI_FALSE;
    }

    const jsize size = data == nullptr ? 0 : env->GetArrayLength(data);
    if (size <= 0) {
        return JNI_TRUE;
    }

    const long long count = g_audioAuCount.fetch_add(1) + 1;
    if (count <= 5 || (count % 120) == 0) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "audioAU count=%lld size=%d ptsUs=%lld flags=%d",
            count,
            static_cast<int>(size),
            static_cast<long long>(presentation_time_us),
            static_cast<int>(flags)
        );
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeStop(JNIEnv*, jobject) {
    g_started.store(false);
    g_prepared.store(false);
    __android_log_print(ANDROID_LOG_INFO, kTag, "nativeStop");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeRuntimeInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_runtime_info.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeStatsSnapshot(JNIEnv* env, jobject) {
    const std::string snapshot =
        std::string("prepared=") + (g_prepared.load() ? "true" : "false") +
        " started=" + (g_started.load() ? "true" : "false") +
        " videoAu=" + std::to_string(g_videoAuCount.load()) +
        " audioAu=" + std::to_string(g_audioAuCount.load());
    return env->NewStringUTF(snapshot.c_str());
}
