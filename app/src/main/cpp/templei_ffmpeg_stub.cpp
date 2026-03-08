#include <jni.h>
#include <android/log.h>
#include <string>
#include <atomic>
#include <dlfcn.h>

namespace {
constexpr const char* kTag = "TempleI-FfmpegStub";

std::atomic<bool> g_prepared{false};
std::atomic<bool> g_started{false};
std::atomic<long long> g_videoAuCount{0};
std::atomic<long long> g_audioAuCount{0};
std::atomic<long long> g_packetEmitCount{0};
std::atomic<long long> g_packetEmitBytes{0};
std::atomic<long long> g_videoLastPtsUs{0};
std::atomic<long long> g_audioLastPtsUs{0};
std::atomic<long long> g_ptsFixupCount{0};
std::atomic<long long> g_ptsOutOfOrderCount{0};
std::atomic<bool> g_videoEnabled{false};
std::atomic<bool> g_audioEnabled{false};
std::atomic<long long> g_videoFirstPtsUs{-1};
std::atomic<long long> g_audioFirstPtsUs{-1};
std::atomic<long long> g_avDeltaUs{0};
std::atomic<long long> g_avDeltaMaxAbsUs{0};
std::string g_last_error;
std::string g_runtime_info = "ffmpeg JNI stub loaded (runtime probe pending)";

struct RuntimeProbeState {
    bool ffmpegLibrariesLoaded = false;
    bool ffmpegSymbolsLoaded = false;
    std::string details = "not probed";
};

RuntimeProbeState g_probe_state;

void* g_avformatHandle = nullptr;
void* g_avcodecHandle = nullptr;
void* g_avutilHandle = nullptr;

using AvformatNetworkInitFn = int (*)();
using AvformatAllocOutputContext2Fn = int (*)(void**, void*, const char*, const char*);
using AvioOpen2Fn = int (*)(void**, const char*, int, void*, void*);
using AvformatWriteHeaderFn = int (*)(void*, void*);
using AvInterleavedWriteFrameFn = int (*)(void*, void*);
using AvWriteTrailerFn = int (*)(void*);

AvformatNetworkInitFn g_avformatNetworkInitFn = nullptr;
AvformatAllocOutputContext2Fn g_avformatAllocOutputContext2Fn = nullptr;
AvioOpen2Fn g_avioOpen2Fn = nullptr;
AvformatWriteHeaderFn g_avformatWriteHeaderFn = nullptr;
AvInterleavedWriteFrameFn g_avInterleavedWriteFrameFn = nullptr;
AvWriteTrailerFn g_avWriteTrailerFn = nullptr;

void set_error(const std::string& message) {
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

std::string probeRuntimeSymbols() {
    g_probe_state = RuntimeProbeState{};

    g_avformatHandle = dlopen("libavformat.so", RTLD_NOW);
    g_avcodecHandle = dlopen("libavcodec.so", RTLD_NOW);
    g_avutilHandle = dlopen("libavutil.so", RTLD_NOW);
    g_probe_state.ffmpegLibrariesLoaded =
        g_avformatHandle != nullptr && g_avcodecHandle != nullptr && g_avutilHandle != nullptr;

    if (!g_probe_state.ffmpegLibrariesLoaded) {
        const char* dlErr = dlerror();
        g_probe_state.details = std::string("ffmpeg runtime libs unavailable: ") + (dlErr == nullptr ? "unknown dlopen error" : dlErr);
        return g_probe_state.details;
    }

    g_avformatNetworkInitFn = reinterpret_cast<AvformatNetworkInitFn>(dlsym(g_avformatHandle, "avformat_network_init"));
    g_avformatAllocOutputContext2Fn = reinterpret_cast<AvformatAllocOutputContext2Fn>(dlsym(g_avformatHandle, "avformat_alloc_output_context2"));
    g_avioOpen2Fn = reinterpret_cast<AvioOpen2Fn>(dlsym(g_avformatHandle, "avio_open2"));
    g_avformatWriteHeaderFn = reinterpret_cast<AvformatWriteHeaderFn>(dlsym(g_avformatHandle, "avformat_write_header"));
    g_avInterleavedWriteFrameFn = reinterpret_cast<AvInterleavedWriteFrameFn>(dlsym(g_avformatHandle, "av_interleaved_write_frame"));
    g_avWriteTrailerFn = reinterpret_cast<AvWriteTrailerFn>(dlsym(g_avformatHandle, "av_write_trailer"));

    g_probe_state.ffmpegSymbolsLoaded =
        g_avformatNetworkInitFn != nullptr &&
        g_avformatAllocOutputContext2Fn != nullptr &&
        g_avioOpen2Fn != nullptr &&
        g_avformatWriteHeaderFn != nullptr &&
        g_avInterleavedWriteFrameFn != nullptr &&
        g_avWriteTrailerFn != nullptr;

    if (!g_probe_state.ffmpegSymbolsLoaded) {
        const char* dlErr = dlerror();
        g_probe_state.details = std::string("ffmpeg symbols missing: ") + (dlErr == nullptr ? "required dlsym lookup failed" : dlErr);
        return g_probe_state.details;
    }

    g_probe_state.details = "ffmpeg symbols resolved (PR D av-clock scaffold; mux/send wiring pending)";
    return g_probe_state.details;
}

void refreshRuntimeInfo() {
    g_runtime_info = probeRuntimeSymbols();
    __android_log_print(ANDROID_LOG_INFO, kTag, "runtime-info: %s", g_runtime_info.c_str());
}

bool runtimeReadyForPrepare() {
    if (!g_probe_state.ffmpegLibrariesLoaded || !g_probe_state.ffmpegSymbolsLoaded) {
        set_error("nativePrepare failed: " + g_runtime_info);
        return false;
    }
    return true;
}

} // namespace


extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeProbeRuntime(JNIEnv*, jobject) {
    refreshRuntimeInfo();
    return (g_probe_state.ffmpegLibrariesLoaded && g_probe_state.ffmpegSymbolsLoaded) ? JNI_TRUE : JNI_FALSE;
}

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

    refreshRuntimeInfo();
    if (!runtimeReadyForPrepare()) {
        return JNI_FALSE;
    }

    g_prepared.store(true);
    g_started.store(false);
    g_videoAuCount.store(0);
    g_audioAuCount.store(0);
    g_packetEmitCount.store(0);
    g_packetEmitBytes.store(0);
    g_videoLastPtsUs.store(0);
    g_audioLastPtsUs.store(0);
    g_ptsFixupCount.store(0);
    g_ptsOutOfOrderCount.store(0);
    g_videoEnabled.store(video_enabled == JNI_TRUE);
    g_audioEnabled.store(audio_enabled == JNI_TRUE);
    g_videoFirstPtsUs.store(-1);
    g_audioFirstPtsUs.store(-1);
    g_avDeltaUs.store(0);
    g_avDeltaMaxAbsUs.store(0);
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
    if (!g_probe_state.ffmpegLibrariesLoaded || !g_probe_state.ffmpegSymbolsLoaded) {
        set_error("nativeStart failed: runtime probe incomplete");
        return JNI_FALSE;
    }

    g_started.store(true);
    __android_log_print(ANDROID_LOG_INFO, kTag, "nativeStart ok");
    return JNI_TRUE;
}



void update_av_delta() {
    const long long videoFirst = g_videoFirstPtsUs.load();
    const long long audioFirst = g_audioFirstPtsUs.load();
    if (videoFirst < 0 || audioFirst < 0) {
        return;
    }

    const long long deltaUs = videoFirst - audioFirst;
    g_avDeltaUs.store(deltaUs);
    const long long absDeltaUs = deltaUs < 0 ? -deltaUs : deltaUs;
    const long long currentMax = g_avDeltaMaxAbsUs.load();
    if (absDeltaUs > currentMax) {
        g_avDeltaMaxAbsUs.store(absDeltaUs);
    }
}

long long normalize_pts(long long incomingPtsUs, std::atomic<long long>& lastPtsStorage) {
    const long long previousPtsUs = lastPtsStorage.load();
    if (incomingPtsUs <= previousPtsUs) {
        g_ptsOutOfOrderCount.fetch_add(1);
        const long long correctedPtsUs = previousPtsUs + 1;
        lastPtsStorage.store(correctedPtsUs);
        g_ptsFixupCount.fetch_add(1);
        return correctedPtsUs;
    }

    lastPtsStorage.store(incomingPtsUs);
    return incomingPtsUs;
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
    if (!g_videoEnabled.load()) {
        set_error("nativePushVideoAccessUnit rejected: video path disabled by stream mode");
        return JNI_FALSE;
    }

    const jsize size = data == nullptr ? 0 : env->GetArrayLength(data);
    if (size <= 0) {
        return JNI_TRUE;
    }

    const long long normalizedPtsUs = normalize_pts(static_cast<long long>(presentation_time_us), g_videoLastPtsUs);
    if (g_videoFirstPtsUs.load() < 0) {
        g_videoFirstPtsUs.store(normalizedPtsUs);
        update_av_delta();
    }
    const long long count = g_videoAuCount.fetch_add(1) + 1;
    if (count <= 5 || (count % 120) == 0) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "videoAU count=%lld size=%d ptsUs=%lld flags=%d",
            count,
            static_cast<int>(size),
            normalizedPtsUs,
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
    if (!g_audioEnabled.load()) {
        set_error("nativePushAudioAccessUnit rejected: audio path disabled by stream mode");
        return JNI_FALSE;
    }

    const jsize size = data == nullptr ? 0 : env->GetArrayLength(data);
    if (size <= 0) {
        return JNI_TRUE;
    }

    const long long normalizedPtsUs = normalize_pts(static_cast<long long>(presentation_time_us), g_audioLastPtsUs);
    if (g_audioFirstPtsUs.load() < 0) {
        g_audioFirstPtsUs.store(normalizedPtsUs);
        update_av_delta();
    }
    const long long count = g_audioAuCount.fetch_add(1) + 1;
    if (count <= 5 || (count % 120) == 0) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "audioAU count=%lld size=%d ptsUs=%lld flags=%d",
            count,
            static_cast<int>(size),
            normalizedPtsUs,
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
        " audioAu=" + std::to_string(g_audioAuCount.load()) +
        " packets=" + std::to_string(g_packetEmitCount.load()) +
        " bytes=" + std::to_string(g_packetEmitBytes.load()) +
        " videoPtsUs=" + std::to_string(g_videoLastPtsUs.load()) +
        " audioPtsUs=" + std::to_string(g_audioLastPtsUs.load()) +
        " ptsFixups=" + std::to_string(g_ptsFixupCount.load()) +
        " ptsOutOfOrder=" + std::to_string(g_ptsOutOfOrderCount.load()) +
        " videoEnabled=" + (g_videoEnabled.load() ? "true" : "false") +
        " audioEnabled=" + (g_audioEnabled.load() ? "true" : "false") +
        " avDeltaUs=" + std::to_string(g_avDeltaUs.load()) +
        " avDeltaMaxAbsUs=" + std::to_string(g_avDeltaMaxAbsUs.load());
    return env->NewStringUTF(snapshot.c_str());
}
