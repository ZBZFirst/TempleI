#include <jni.h>
#include <android/log.h>
#include <string>
#include <atomic>
#include <dlfcn.h>
#include <vector>
#include <chrono>
#include <array>
#include <cstdint>
#include <cstring>

namespace {
constexpr const char* kTag = "TempleI-FfmpegStub";

std::atomic<bool> g_prepared{false};
std::atomic<bool> g_started{false};
std::atomic<long long> g_videoAuCount{0};
std::atomic<long long> g_audioAuCount{0};
std::atomic<long long> g_muxPacketsProduced{0};
std::atomic<long long> g_muxBytesProduced{0};
std::atomic<long long> g_writePacketsSucceeded{0};
std::atomic<long long> g_writeBytesSucceeded{0};
std::atomic<long long> g_writePacketsFailed{0};
std::atomic<long long> g_auAcceptedVideo{0};
std::atomic<long long> g_auAcceptedAudio{0};
std::atomic<long long> g_muxPacketsWritten{0};
std::atomic<long long> g_muxBytesWritten{0};
std::atomic<long long> g_muxWriteFailures{0};
std::atomic<bool> g_firstPacketWritten{false};
std::atomic<long long> g_connectAttempts{0};
std::atomic<long long> g_connectSuccess{0};
std::atomic<long long> g_connectFailures{0};
std::atomic<long long> g_consecutiveWriteFailures{0};
std::atomic<long long> g_lastSuccessfulWriteMs{0};
std::atomic<long long> g_videoLastPtsUs{0};
std::atomic<long long> g_audioLastPtsUs{0};
std::atomic<long long> g_ptsFixupCount{0};
std::atomic<long long> g_videoPtsFixupCount{0};
std::atomic<long long> g_audioPtsFixupCount{0};
std::atomic<long long> g_ptsOutOfOrderCount{0};
std::atomic<bool> g_videoEnabled{false};
std::atomic<bool> g_audioEnabled{false};
std::atomic<long long> g_videoFirstPtsUs{-1};
std::atomic<long long> g_audioFirstPtsUs{-1};
std::atomic<long long> g_avDeltaUs{0};
std::atomic<long long> g_avDeltaMaxAbsUs{0};
std::atomic<bool> g_videoConfigReady{false};
std::atomic<bool> g_audioConfigReady{false};
std::atomic<bool> g_videoSpsSeen{false};
std::atomic<bool> g_videoPpsSeen{false};
std::atomic<bool> g_videoKeyframeSeen{false};
std::atomic<long long> g_videoConfigRejectCount{0};
std::atomic<long long> g_audioConfigRejectCount{0};
std::string g_last_error;
std::string g_runtime_info = "ffmpeg JNI runtime loading (probe pending)";

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
using AvformatNewStreamFn = void* (*)(void*, const void*);
using AvioOpen2Fn = int (*)(void**, const char*, int, void*, void*);
using AvioClosepFn = int (*)(void**);
using AvioWriteFn = void (*)(void*, const unsigned char*, int);
using AvioFlushFn = void (*)(void*);
using AvformatWriteHeaderFn = int (*)(void*, void*);
using AvInterleavedWriteFrameFn = int (*)(void*, void*);
using AvWriteTrailerFn = int (*)(void*);
using AvformatFreeContextFn = void (*)(void*);
using AvPacketAllocFn = void* (*)();
using AvPacketFreeFn = void (*)(void**);
using AvPacketUnrefFn = void (*)(void*);
using AvNewPacketFn = int (*)(void*, int);
using AvRescaleQFn = int64_t (*)(int64_t, void*, void*);
using AvStrerrorFn = int (*)(int, char*, size_t);

AvformatNetworkInitFn g_avformatNetworkInitFn = nullptr;
AvformatAllocOutputContext2Fn g_avformatAllocOutputContext2Fn = nullptr;
AvformatNewStreamFn g_avformatNewStreamFn = nullptr;
AvioOpen2Fn g_avioOpen2Fn = nullptr;
AvioClosepFn g_avioClosepFn = nullptr;
AvioWriteFn g_avioWriteFn = nullptr;
AvioFlushFn g_avioFlushFn = nullptr;
AvformatWriteHeaderFn g_avformatWriteHeaderFn = nullptr;
AvInterleavedWriteFrameFn g_avInterleavedWriteFrameFn = nullptr;
AvWriteTrailerFn g_avWriteTrailerFn = nullptr;
AvformatFreeContextFn g_avformatFreeContextFn = nullptr;
AvPacketAllocFn g_avPacketAllocFn = nullptr;
AvPacketFreeFn g_avPacketFreeFn = nullptr;
AvPacketUnrefFn g_avPacketUnrefFn = nullptr;
AvNewPacketFn g_avNewPacketFn = nullptr;
AvRescaleQFn g_avRescaleQFn = nullptr;
AvStrerrorFn g_avStrerrorFn = nullptr;

void* g_outputFormatContext = nullptr;
void* g_outputIoContext = nullptr;
void* g_videoStream = nullptr;
void* g_audioStream = nullptr;
int g_videoStreamIndex = -1;
int g_audioStreamIndex = -1;
std::string g_output_url;
std::atomic<bool> g_outputOpened{false};
std::atomic<bool> g_headerWritten{false};

struct AvRationalCompat {
    int num;
    int den;
};

struct AvPacketCompat {
    void* buf;
    int64_t pts;
    int64_t dts;
    uint8_t* data;
    int size;
    int stream_index;
    int flags;
    void* side_data;
    int side_data_elems;
    int64_t duration;
    int64_t pos;
    void* opaque;
    void* opaque_ref;
    AvRationalCompat time_base;
};

struct AvCodecParametersCompat {
    int codec_type;
    int codec_id;
    uint32_t codec_tag;
    uint8_t* extradata;
    int extradata_size;
    int format;
    int64_t bit_rate;
    int bits_per_coded_sample;
    int bits_per_raw_sample;
    int profile;
    int level;
    int width;
    int height;
    AvRationalCompat sample_aspect_ratio;
    int field_order;
    AvRationalCompat framerate;
    int color_range;
    int color_primaries;
    int color_trc;
    int color_space;
    int chroma_location;
    int video_delay;
    uint64_t channel_layout;
    int channels;
    int sample_rate;
    int block_align;
    int frame_size;
    int initial_padding;
    int trailing_padding;
    int seek_preroll;
};

struct AvStreamCompat {
    int index;
    int id;
    void* codec;
    void* priv_data;
    AvRationalCompat time_base;
    int64_t start_time;
    int64_t duration;
    int64_t nb_frames;
    int disposition;
    int discard;
    AvRationalCompat sample_aspect_ratio;
    void* metadata;
    AvRationalCompat avg_frame_rate;
    void* attached_pic;
    void* side_data;
    int nb_side_data;
    int event_flags;
    void* r_frame_rate;
    void* recommended_encoder_configuration;
    AvCodecParametersCompat* codecpar;
};

constexpr int kAvMediaTypeVideo = 0;
constexpr int kAvMediaTypeAudio = 1;
constexpr int kAvCodecIdH264 = 27;
constexpr int kAvCodecIdAac = 86018;
constexpr int kAvPktFlagKey = 0x0001;

void set_error(const std::string& message) {
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

long long now_ms();

std::string ffmpeg_error_string(int code) {
    if (g_avStrerrorFn == nullptr) {
        return std::to_string(code);
    }
    std::array<char, 256> buffer{};
    if (g_avStrerrorFn(code, buffer.data(), buffer.size()) == 0) {
        return std::string(buffer.data());
    }
    return std::to_string(code);
}

void log_mux_milestone(const std::string& event, const std::string& detail = "") {
    if (detail.empty()) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "milestone=%s", event.c_str());
    } else {
        __android_log_print(ANDROID_LOG_INFO, kTag, "milestone=%s %s", event.c_str(), detail.c_str());
    }
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
    g_avformatNewStreamFn = reinterpret_cast<AvformatNewStreamFn>(dlsym(g_avformatHandle, "avformat_new_stream"));
    g_avioOpen2Fn = reinterpret_cast<AvioOpen2Fn>(dlsym(g_avformatHandle, "avio_open2"));
    g_avioClosepFn = reinterpret_cast<AvioClosepFn>(dlsym(g_avformatHandle, "avio_closep"));
    g_avioWriteFn = reinterpret_cast<AvioWriteFn>(dlsym(g_avformatHandle, "avio_write"));
    g_avioFlushFn = reinterpret_cast<AvioFlushFn>(dlsym(g_avformatHandle, "avio_flush"));
    g_avformatWriteHeaderFn = reinterpret_cast<AvformatWriteHeaderFn>(dlsym(g_avformatHandle, "avformat_write_header"));
    g_avInterleavedWriteFrameFn = reinterpret_cast<AvInterleavedWriteFrameFn>(dlsym(g_avformatHandle, "av_interleaved_write_frame"));
    g_avWriteTrailerFn = reinterpret_cast<AvWriteTrailerFn>(dlsym(g_avformatHandle, "av_write_trailer"));
    g_avformatFreeContextFn = reinterpret_cast<AvformatFreeContextFn>(dlsym(g_avformatHandle, "avformat_free_context"));
    g_avPacketAllocFn = reinterpret_cast<AvPacketAllocFn>(dlsym(g_avcodecHandle, "av_packet_alloc"));
    g_avPacketFreeFn = reinterpret_cast<AvPacketFreeFn>(dlsym(g_avcodecHandle, "av_packet_free"));
    g_avPacketUnrefFn = reinterpret_cast<AvPacketUnrefFn>(dlsym(g_avcodecHandle, "av_packet_unref"));
    g_avNewPacketFn = reinterpret_cast<AvNewPacketFn>(dlsym(g_avcodecHandle, "av_new_packet"));
    g_avRescaleQFn = reinterpret_cast<AvRescaleQFn>(dlsym(g_avutilHandle, "av_rescale_q"));
    g_avStrerrorFn = reinterpret_cast<AvStrerrorFn>(dlsym(g_avutilHandle, "av_strerror"));

    g_probe_state.ffmpegSymbolsLoaded =
        g_avformatNetworkInitFn != nullptr &&
        g_avformatAllocOutputContext2Fn != nullptr &&
        g_avformatNewStreamFn != nullptr &&
        g_avioOpen2Fn != nullptr &&
        g_avioClosepFn != nullptr &&
        g_avioWriteFn != nullptr &&
        g_avioFlushFn != nullptr &&
        g_avformatWriteHeaderFn != nullptr &&
        g_avInterleavedWriteFrameFn != nullptr &&
        g_avWriteTrailerFn != nullptr &&
        g_avformatFreeContextFn != nullptr &&
        g_avPacketAllocFn != nullptr &&
        g_avPacketFreeFn != nullptr &&
        g_avPacketUnrefFn != nullptr &&
        g_avNewPacketFn != nullptr &&
        g_avRescaleQFn != nullptr &&
        g_avStrerrorFn != nullptr;

    if (!g_probe_state.ffmpegSymbolsLoaded) {
        const char* dlErr = dlerror();
        g_probe_state.details = std::string("ffmpeg symbols missing: ") + (dlErr == nullptr ? "required dlsym lookup failed" : dlErr);
        return g_probe_state.details;
    }

    g_probe_state.details = "ffmpeg symbols resolved (runtimeMode=active; canonical mux packet bridge enabled)";
    log_mux_milestone("runtime-probe-ok", g_probe_state.details);
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

void reset_mux_runtime_counters() {
    g_auAcceptedVideo.store(0);
    g_auAcceptedAudio.store(0);
    g_muxPacketsWritten.store(0);
    g_muxBytesWritten.store(0);
    g_muxWriteFailures.store(0);
    g_firstPacketWritten.store(false);
}

bool declare_output_streams(bool videoEnabled, bool audioEnabled) {
    g_videoStream = nullptr;
    g_audioStream = nullptr;
    g_videoStreamIndex = -1;
    g_audioStreamIndex = -1;

    if (videoEnabled) {
        g_videoStream = g_avformatNewStreamFn(g_outputFormatContext, nullptr);
        if (g_videoStream == nullptr) {
            set_error("nativePrepare failed: avformat_new_stream(video) returned null");
            return false;
        }
        auto* stream = reinterpret_cast<AvStreamCompat*>(g_videoStream);
        g_videoStreamIndex = stream->index;
        stream->time_base = AvRationalCompat{1, 1'000'000};
        if (stream->codecpar == nullptr) {
            set_error("nativePrepare failed: video codecpar unavailable");
            return false;
        }
        stream->codecpar->codec_type = kAvMediaTypeVideo;
        stream->codecpar->codec_id = kAvCodecIdH264;
        stream->codecpar->width = 1280;
        stream->codecpar->height = 720;
        log_mux_milestone("stream-declared", "type=video codec=h264 tb=1/1000000");
    }

    if (audioEnabled) {
        g_audioStream = g_avformatNewStreamFn(g_outputFormatContext, nullptr);
        if (g_audioStream == nullptr) {
            set_error("nativePrepare failed: avformat_new_stream(audio) returned null");
            return false;
        }
        auto* stream = reinterpret_cast<AvStreamCompat*>(g_audioStream);
        g_audioStreamIndex = stream->index;
        stream->time_base = AvRationalCompat{1, 1'000'000};
        if (stream->codecpar == nullptr) {
            set_error("nativePrepare failed: audio codecpar unavailable");
            return false;
        }
        stream->codecpar->codec_type = kAvMediaTypeAudio;
        stream->codecpar->codec_id = kAvCodecIdAac;
        stream->codecpar->sample_rate = 48'000;
        stream->codecpar->channels = 1;
        log_mux_milestone("stream-declared", "type=audio codec=aac tb=1/1000000");
    }

    return true;
}

bool write_access_unit_packet(
    const std::vector<unsigned char>& payload,
    int streamIndex,
    long long normalizedPtsUs,
    int flags,
    bool isVideo
) {
    if (streamIndex < 0) {
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        g_muxWriteFailures.fetch_add(1);
        set_error("native write failed: stream not declared");
        return false;
    }

    if (!g_started.load() || !g_outputOpened.load() || !g_headerWritten.load() || g_outputFormatContext == nullptr) {
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        g_muxWriteFailures.fetch_add(1);
        set_error("native write failed: output lifecycle not active");
        return false;
    }

    void* packetRaw = g_avPacketAllocFn();
    if (packetRaw == nullptr) {
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        g_muxWriteFailures.fetch_add(1);
        set_error("native write failed: av_packet_alloc returned null");
        return false;
    }

    auto* packet = reinterpret_cast<AvPacketCompat*>(packetRaw);
    const int newPacketResult = g_avNewPacketFn(packetRaw, static_cast<int>(payload.size()));
    if (newPacketResult < 0) {
        g_avPacketFreeFn(&packetRaw);
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        g_muxWriteFailures.fetch_add(1);
        set_error("native write failed: av_new_packet returned " + ffmpeg_error_string(newPacketResult));
        return false;
    }

    std::memcpy(packet->data, payload.data(), payload.size());
    packet->stream_index = streamIndex;
    const AvRationalCompat srcTimeBase{1, 1'000'000};
    const auto* stream = reinterpret_cast<AvStreamCompat*>(isVideo ? g_videoStream : g_audioStream);
    const AvRationalCompat dstTimeBase = stream != nullptr ? stream->time_base : srcTimeBase;
    const int64_t scaledPts = g_avRescaleQFn(normalizedPtsUs, const_cast<AvRationalCompat*>(&srcTimeBase), const_cast<AvRationalCompat*>(&dstTimeBase));
    packet->pts = scaledPts;
    packet->dts = scaledPts;
    packet->duration = g_avRescaleQFn(isVideo ? 33'333 : 21'333, const_cast<AvRationalCompat*>(&srcTimeBase), const_cast<AvRationalCompat*>(&dstTimeBase));
    if ((flags & 1) != 0) {
        packet->flags |= kAvPktFlagKey;
    }

    const int writeResult = g_avInterleavedWriteFrameFn(g_outputFormatContext, packetRaw);
    if (writeResult < 0) {
        g_avPacketUnrefFn(packetRaw);
        g_avPacketFreeFn(&packetRaw);
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        g_muxWriteFailures.fetch_add(1);
        set_error("native write failed: av_interleaved_write_frame returned " + ffmpeg_error_string(writeResult));
        log_mux_milestone("packet-write-failed", "err=" + ffmpeg_error_string(writeResult));
        return false;
    }

    g_muxPacketsWritten.fetch_add(1);
    g_muxBytesWritten.fetch_add(static_cast<long long>(payload.size()));
    g_writePacketsSucceeded.store(g_muxPacketsWritten.load());
    g_writeBytesSucceeded.store(g_muxBytesWritten.load());
    g_consecutiveWriteFailures.store(0);
    g_lastSuccessfulWriteMs.store(now_ms());
    if (!g_firstPacketWritten.exchange(true)) {
        log_mux_milestone("first-packet-written", "streamIndex=" + std::to_string(streamIndex));
    }

    g_avPacketUnrefFn(packetRaw);
    g_avPacketFreeFn(&packetRaw);
    return true;
}

void closeOutputArtifacts() {
    if (g_headerWritten.load() && g_outputFormatContext != nullptr && g_avWriteTrailerFn != nullptr) {
        const int trailerResult = g_avWriteTrailerFn(g_outputFormatContext);
        if (trailerResult < 0) {
            set_error("nativeStop trailer failed: " + ffmpeg_error_string(trailerResult));
        } else {
            log_mux_milestone("trailer-written");
        }
    }
    g_headerWritten.store(false);

    if (g_outputIoContext != nullptr && g_avioClosepFn != nullptr) {
        g_avioClosepFn(&g_outputIoContext);
    }
    g_outputIoContext = nullptr;
    g_outputOpened.store(false);

    if (g_outputFormatContext != nullptr && g_avformatFreeContextFn != nullptr) {
        g_avformatFreeContextFn(g_outputFormatContext);
    }
    g_outputFormatContext = nullptr;
    g_videoStream = nullptr;
    g_audioStream = nullptr;
    g_videoStreamIndex = -1;
    g_audioStreamIndex = -1;
}

long long now_ms() {
    return static_cast<long long>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()
        ).count()
    );
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

long long normalize_pts(
    long long incomingPtsUs,
    std::atomic<long long>& lastPtsStorage,
    std::atomic<long long>& perTrackFixupCounter
) {
    const long long previousPtsUs = lastPtsStorage.load();
    if (incomingPtsUs <= previousPtsUs) {
        g_ptsOutOfOrderCount.fetch_add(1);
        const long long correctedPtsUs = previousPtsUs + 1;
        lastPtsStorage.store(correctedPtsUs);
        g_ptsFixupCount.fetch_add(1);
        perTrackFixupCounter.fetch_add(1);
        return correctedPtsUs;
    }

    lastPtsStorage.store(incomingPtsUs);
    return incomingPtsUs;
}

int findStartCodePrefixSize(const std::vector<unsigned char>& data, size_t index) {
    if (index + 3 < data.size() && data[index] == 0x00 && data[index + 1] == 0x00 && data[index + 2] == 0x01) {
        return 3;
    }
    if (index + 4 < data.size() && data[index] == 0x00 && data[index + 1] == 0x00 && data[index + 2] == 0x00 && data[index + 3] == 0x01) {
        return 4;
    }
    return 0;
}

bool validateVideoCodecReadiness(const std::vector<unsigned char>& data) {
    bool sawSps = false;
    bool sawPps = false;
    bool sawIdr = false;
    size_t index = 0;
    while (index + 4 < data.size()) {
        const int startCodeLen = findStartCodePrefixSize(data, index);
        if (startCodeLen == 0) {
            index += 1;
            continue;
        }

        const size_t nalIndex = index + static_cast<size_t>(startCodeLen);
        if (nalIndex >= data.size()) {
            break;
        }

        const int nalType = data[nalIndex] & 0x1F;
        if (nalType == 7) {
            sawSps = true;
        } else if (nalType == 8) {
            sawPps = true;
        } else if (nalType == 5) {
            sawIdr = true;
        }
        index = nalIndex + 1;
    }

    if (sawSps) {
        g_videoSpsSeen.store(true);
    }
    if (sawPps) {
        g_videoPpsSeen.store(true);
    }
    if (sawIdr) {
        g_videoKeyframeSeen.store(true);
    }

    const bool readyNow = g_videoSpsSeen.load() && g_videoPpsSeen.load();
    g_videoConfigReady.store(readyNow);
    if (!readyNow) {
        g_videoConfigRejectCount.fetch_add(1);
        set_error("nativePushVideoAccessUnit rejected: codec config incomplete (waiting for SPS/PPS)");
        return false;
    }

    return true;
}

bool validateAudioCodecReadiness(const std::vector<unsigned char>& data) {
    if (data.size() < 7) {
        g_audioConfigRejectCount.fetch_add(1);
        set_error("nativePushAudioAccessUnit rejected: ADTS header too short");
        return false;
    }

    const bool adtsSyncOk = data[0] == 0xFF && (data[1] & 0xF0) == 0xF0;
    if (!adtsSyncOk) {
        g_audioConfigRejectCount.fetch_add(1);
        set_error("nativePushAudioAccessUnit rejected: ADTS sync word missing");
        return false;
    }

    g_audioConfigReady.store(true);
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
    if (!(mode_value == "caller" || mode_value == "listener")) {
        set_error("nativePrepare rejected invalid mode (expected caller/listener)");
        return JNI_FALSE;
    }

    refreshRuntimeInfo();
    if (!runtimeReadyForPrepare()) {
        return JNI_FALSE;
    }

    closeOutputArtifacts();
    if (g_avformatNetworkInitFn() < 0) {
        set_error("nativePrepare failed: avformat_network_init returned error");
        return JNI_FALSE;
    }

    g_output_url = std::string("srt://") + host_value + ":" + std::to_string(static_cast<int>(port)) +
        "?mode=" + mode_value + "&latency=" + std::to_string(static_cast<int>(latency_ms));
    void* formatContext = nullptr;
    const int allocResult = g_avformatAllocOutputContext2Fn(&formatContext, nullptr, "mpegts", g_output_url.c_str());
    if (allocResult < 0 || formatContext == nullptr) {
        set_error("nativePrepare failed: avformat_alloc_output_context2 returned " + ffmpeg_error_string(allocResult));
        closeOutputArtifacts();
        return JNI_FALSE;
    }
    g_outputFormatContext = formatContext;
    log_mux_milestone("output-context-allocated", "format=mpegts");

    if (!declare_output_streams(video_enabled == JNI_TRUE, audio_enabled == JNI_TRUE)) {
        closeOutputArtifacts();
        return JNI_FALSE;
    }

    g_prepared.store(true);
    g_started.store(false);
    g_videoAuCount.store(0);
    g_audioAuCount.store(0);
    g_muxPacketsProduced.store(0);
    g_muxBytesProduced.store(0);
    g_writePacketsSucceeded.store(0);
    g_writeBytesSucceeded.store(0);
    g_writePacketsFailed.store(0);
    reset_mux_runtime_counters();
    g_connectAttempts.store(0);
    g_connectSuccess.store(0);
    g_connectFailures.store(0);
    g_consecutiveWriteFailures.store(0);
    g_lastSuccessfulWriteMs.store(0);
    g_videoLastPtsUs.store(0);
    g_audioLastPtsUs.store(0);
    g_ptsFixupCount.store(0);
    g_videoPtsFixupCount.store(0);
    g_audioPtsFixupCount.store(0);
    g_ptsOutOfOrderCount.store(0);
    g_videoEnabled.store(video_enabled == JNI_TRUE);
    g_audioEnabled.store(audio_enabled == JNI_TRUE);
    g_videoFirstPtsUs.store(-1);
    g_audioFirstPtsUs.store(-1);
    g_avDeltaUs.store(0);
    g_avDeltaMaxAbsUs.store(0);
    g_videoConfigReady.store(false);
    g_audioConfigReady.store(false);
    g_videoSpsSeen.store(false);
    g_videoPpsSeen.store(false);
    g_videoKeyframeSeen.store(false);
    g_videoConfigRejectCount.store(0);
    g_audioConfigRejectCount.store(0);
    g_outputOpened.store(false);
    g_headerWritten.store(false);
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
    log_mux_milestone("prepare-complete", std::string("url=") + g_output_url);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeStart(JNIEnv*, jobject) {
    g_connectAttempts.fetch_add(1);
    if (!g_prepared.load()) {
        g_connectFailures.fetch_add(1);
        set_error("nativeStart failed: backend not prepared");
        return JNI_FALSE;
    }
    if (!g_probe_state.ffmpegLibrariesLoaded || !g_probe_state.ffmpegSymbolsLoaded) {
        g_connectFailures.fetch_add(1);
        set_error("nativeStart failed: runtime probe incomplete");
        return JNI_FALSE;
    }
    if (g_outputFormatContext == nullptr) {
        g_connectFailures.fetch_add(1);
        set_error("nativeStart failed: output context not prepared");
        return JNI_FALSE;
    }

    void* ioContext = nullptr;
    constexpr int kAvioFlagWrite = 2;
    const int openResult = g_avioOpen2Fn(&ioContext, g_output_url.c_str(), kAvioFlagWrite, nullptr, nullptr);
    if (openResult < 0 || ioContext == nullptr) {
        g_connectFailures.fetch_add(1);
        set_error("nativeStart failed: avio_open2 returned " + ffmpeg_error_string(openResult));
        closeOutputArtifacts();
        return JNI_FALSE;
    }
    g_outputIoContext = ioContext;
    g_outputOpened.store(true);
    log_mux_milestone("output-opened", std::string("url=") + g_output_url);

    const int headerResult = g_avformatWriteHeaderFn(g_outputFormatContext, nullptr);
    if (headerResult < 0) {
        g_connectFailures.fetch_add(1);
        set_error("nativeStart failed: avformat_write_header returned " + ffmpeg_error_string(headerResult));
        closeOutputArtifacts();
        return JNI_FALSE;
    }
    g_headerWritten.store(true);
    log_mux_milestone("header-written");

    g_connectSuccess.fetch_add(1);
    g_started.store(true);
    __android_log_print(ANDROID_LOG_INFO, kTag, "nativeStart ok outputUrl=%s", g_output_url.c_str());
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
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        set_error("nativePushVideoAccessUnit failed: backend not started");
        return JNI_FALSE;
    }
    if (!g_videoEnabled.load()) {
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        set_error("nativePushVideoAccessUnit rejected: video path disabled by stream mode");
        return JNI_FALSE;
    }

    const jsize size = data == nullptr ? 0 : env->GetArrayLength(data);
    if (size <= 0) {
        return JNI_TRUE;
    }

    std::vector<unsigned char> payload(static_cast<size_t>(size));
    env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte*>(payload.data()));
    if (!validateVideoCodecReadiness(payload)) {
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        return JNI_FALSE;
    }

    const long long normalizedPtsUs = normalize_pts(
        static_cast<long long>(presentation_time_us),
        g_videoLastPtsUs,
        g_videoPtsFixupCount
    );
    if (g_videoFirstPtsUs.load() < 0) {
        g_videoFirstPtsUs.store(normalizedPtsUs);
        update_av_delta();
    }

    const long long count = g_videoAuCount.fetch_add(1) + 1;
    g_auAcceptedVideo.fetch_add(1);
    g_muxPacketsProduced.fetch_add(1);
    g_muxBytesProduced.fetch_add(size);
    if (!write_access_unit_packet(payload, g_videoStreamIndex, normalizedPtsUs, flags, true)) {
        return JNI_FALSE;
    }
    if (count <= 5 || (count % 120) == 0) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "videoAU count=%lld size=%d ptsUs=%lld flags=%d cfgReady=%d keySeen=%d",
            count,
            static_cast<int>(size),
            normalizedPtsUs,
            static_cast<int>(flags),
            g_videoConfigReady.load() ? 1 : 0,
            g_videoKeyframeSeen.load() ? 1 : 0
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
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        set_error("nativePushAudioAccessUnit failed: backend not started");
        return JNI_FALSE;
    }
    if (!g_audioEnabled.load()) {
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        set_error("nativePushAudioAccessUnit rejected: audio path disabled by stream mode");
        return JNI_FALSE;
    }

    const jsize size = data == nullptr ? 0 : env->GetArrayLength(data);
    if (size <= 0) {
        return JNI_TRUE;
    }

    std::vector<unsigned char> payload(static_cast<size_t>(size));
    env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte*>(payload.data()));
    if (!validateAudioCodecReadiness(payload)) {
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        return JNI_FALSE;
    }

    const long long normalizedPtsUs = normalize_pts(
        static_cast<long long>(presentation_time_us),
        g_audioLastPtsUs,
        g_audioPtsFixupCount
    );
    if (g_audioFirstPtsUs.load() < 0) {
        g_audioFirstPtsUs.store(normalizedPtsUs);
        update_av_delta();
    }

    const long long count = g_audioAuCount.fetch_add(1) + 1;
    g_auAcceptedAudio.fetch_add(1);
    g_muxPacketsProduced.fetch_add(1);
    g_muxBytesProduced.fetch_add(size);
    if (!write_access_unit_packet(payload, g_audioStreamIndex, normalizedPtsUs, flags, false)) {
        return JNI_FALSE;
    }
    if (count <= 5 || (count % 120) == 0) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "audioAU count=%lld size=%d ptsUs=%lld flags=%d cfgReady=%d",
            count,
            static_cast<int>(size),
            normalizedPtsUs,
            static_cast<int>(flags),
            g_audioConfigReady.load() ? 1 : 0
        );
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeStop(JNIEnv*, jobject) {
    closeOutputArtifacts();
    g_started.store(false);
    g_prepared.store(false);
    g_videoConfigReady.store(false);
    g_audioConfigReady.store(false);
    reset_mux_runtime_counters();
    log_mux_milestone("shutdown-complete");
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
    const std::string runtimeMode = (g_probe_state.ffmpegLibrariesLoaded && g_probe_state.ffmpegSymbolsLoaded) ? "active" : "stub";
    const std::string snapshot =
        std::string("runtimeMode=") + runtimeMode +
        " prepared=" + (g_prepared.load() ? "true" : "false") +
        " started=" + (g_started.load() ? "true" : "false") +
        " outputOpened=" + (g_outputOpened.load() ? "true" : "false") +
        " headerWritten=" + (g_headerWritten.load() ? "true" : "false") +
        " outputUrl=" + (g_output_url.empty() ? std::string("none") : g_output_url) +
        " videoAu=" + std::to_string(g_videoAuCount.load()) +
        " audioAu=" + std::to_string(g_audioAuCount.load()) +
        " auAcceptedVideo=" + std::to_string(g_auAcceptedVideo.load()) +
        " auAcceptedAudio=" + std::to_string(g_auAcceptedAudio.load()) +
        " muxPacketsProduced=" + std::to_string(g_muxPacketsProduced.load()) +
        " muxBytesProduced=" + std::to_string(g_muxBytesProduced.load()) +
        " muxPacketsWritten=" + std::to_string(g_muxPacketsWritten.load()) +
        " muxBytesWritten=" + std::to_string(g_muxBytesWritten.load()) +
        " muxWriteFailures=" + std::to_string(g_muxWriteFailures.load()) +
        " firstPacketWritten=" + (g_firstPacketWritten.load() ? "true" : "false") +
        " writePacketsSucceeded=" + std::to_string(g_writePacketsSucceeded.load()) +
        " writeBytesSucceeded=" + std::to_string(g_writeBytesSucceeded.load()) +
        " writePacketsFailed=" + std::to_string(g_writePacketsFailed.load()) +
        // Deprecated aliases retained for one migration cycle so Kotlin parsers
        // can prefer canonical write fields while older readers remain stable.
        " packets=" + std::to_string(g_writePacketsSucceeded.load()) +
        " packetsWritten=" + std::to_string(g_writePacketsSucceeded.load()) +
        " bytes=" + std::to_string(g_writeBytesSucceeded.load()) +
        " bytesWritten=" + std::to_string(g_writeBytesSucceeded.load()) +
        " connectAttempts=" + std::to_string(g_connectAttempts.load()) +
        " connectSuccess=" + std::to_string(g_connectSuccess.load()) +
        " connectFailures=" + std::to_string(g_connectFailures.load()) +
        " consecutiveWriteFailures=" + std::to_string(g_consecutiveWriteFailures.load()) +
        " lastSuccessfulWriteMs=" + std::to_string(g_lastSuccessfulWriteMs.load()) +
        " videoPtsUs=" + std::to_string(g_videoLastPtsUs.load()) +
        " audioPtsUs=" + std::to_string(g_audioLastPtsUs.load()) +
        " ptsFixups=" + std::to_string(g_ptsFixupCount.load()) +
        " videoPtsFixups=" + std::to_string(g_videoPtsFixupCount.load()) +
        " audioPtsFixups=" + std::to_string(g_audioPtsFixupCount.load()) +
        " ptsOutOfOrder=" + std::to_string(g_ptsOutOfOrderCount.load()) +
        " videoEnabled=" + (g_videoEnabled.load() ? "true" : "false") +
        " audioEnabled=" + (g_audioEnabled.load() ? "true" : "false") +
        " videoCfgReady=" + (g_videoConfigReady.load() ? "true" : "false") +
        " audioCfgReady=" + (g_audioConfigReady.load() ? "true" : "false") +
        " videoSpsSeen=" + (g_videoSpsSeen.load() ? "true" : "false") +
        " videoPpsSeen=" + (g_videoPpsSeen.load() ? "true" : "false") +
        " videoKeyframeSeen=" + (g_videoKeyframeSeen.load() ? "true" : "false") +
        " videoCfgRejects=" + std::to_string(g_videoConfigRejectCount.load()) +
        " audioCfgRejects=" + std::to_string(g_audioConfigRejectCount.load()) +
        " avDeltaUs=" + std::to_string(g_avDeltaUs.load()) +
        " avDeltaMaxAbsUs=" + std::to_string(g_avDeltaMaxAbsUs.load());
    return env->NewStringUTF(snapshot.c_str());
}
