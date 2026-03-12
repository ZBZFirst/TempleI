#include <jni.h>
#include <android/log.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavcodec/packet.h>
#include <libavformat/avformat.h>
#include <libavutil/error.h>
#include <libavutil/rational.h>
#include <libavutil/channel_layout.h>
}

#include <atomic>
#include <chrono>
#include <string>
#include <vector>

namespace {
constexpr const char* kStreamTag = "TempleI-Stream";
constexpr const char* kVideoTag = "TempleI-VideoEnc";
constexpr const char* kAudioTag = "TempleI-AudioEnc";
constexpr const char* kMuxTag = "TempleI-Mux";
constexpr const char* kSrtTag = "TempleI-SRT";
constexpr const char* kNetTag = "TempleI-Net";
constexpr const char* kErrorTag = "TempleI-Error";
constexpr AVRational kInputPtsTimebase{1, 1'000'000};

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

AVFormatContext* g_outputFormatContext = nullptr;
AVStream* g_videoStream = nullptr;
AVStream* g_audioStream = nullptr;
std::string g_output_url;
std::atomic<bool> g_outputOpened{false};
std::atomic<bool> g_headerWritten{false};
std::atomic<bool> g_trailerWritten{false};

long long now_ms() {
    return static_cast<long long>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()
        ).count()
    );
}

void set_error(const std::string& message) {
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, kErrorTag, "tsMs=%lld %s", now_ms(), message.c_str());
}

std::string ffmpeg_error_string(int code) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(code, buffer, sizeof(buffer));
    return std::string(buffer);
}

void log_mux_milestone(const std::string& event, const std::string& detail = "") {
    if (detail.empty()) {
        __android_log_print(ANDROID_LOG_INFO, kMuxTag, "tsMs=%lld milestone=%s", now_ms(), event.c_str());
    } else {
        __android_log_print(ANDROID_LOG_INFO, kMuxTag, "tsMs=%lld milestone=%s %s", now_ms(), event.c_str(), detail.c_str());
    }
}

void refresh_runtime_info() {
    const unsigned version = avformat_version();
    g_runtime_info = version > 0
        ? "ffmpeg headers/types active (avformat linked; canonical mux path enabled)"
        : "ffmpeg runtime unavailable";
    if (version > 0) {
        log_mux_milestone("runtime-probe-ok", g_runtime_info);
    }
}

void log_output_protocol_diagnostics() {
    const char* configuration = avformat_configuration();
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kSrtTag,
        "tsMs=%lld ffmpeg avformat_configuration=%s",
        now_ms(),
        configuration == nullptr ? "(null)" : configuration
    );

    void* opaque = nullptr;
    const char* protocol = nullptr;
    bool srtFound = false;
    while ((protocol = avio_enum_protocols(&opaque, 1)) != nullptr) {
        __android_log_print(ANDROID_LOG_DEBUG, kSrtTag, "tsMs=%lld ffmpeg output protocol=%s", now_ms(), protocol);
        if (!srtFound && std::string(protocol) == "srt") {
            srtFound = true;
        }
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        kSrtTag,
        "tsMs=%lld ffmpeg output protocol contains_srt=%d",
        now_ms(),
        srtFound ? 1 : 0
    );
}

void reset_mux_runtime_counters() {
    g_auAcceptedVideo.store(0);
    g_auAcceptedAudio.store(0);
    g_muxPacketsWritten.store(0);
    g_muxBytesWritten.store(0);
    g_muxWriteFailures.store(0);
    g_firstPacketWritten.store(false);
}

void closeOutputArtifacts() {
    if (g_outputFormatContext != nullptr) {
        if (g_headerWritten.load()) {
            const int trailerResult = av_write_trailer(g_outputFormatContext);
            if (trailerResult < 0) {
                set_error("nativeStop trailer failed: " + ffmpeg_error_string(trailerResult));
            } else {
                g_trailerWritten.store(true);
                log_mux_milestone("trailer-written");
            }
        }

        if (g_outputOpened.load() && g_outputFormatContext->pb != nullptr &&
            (g_outputFormatContext->oformat->flags & AVFMT_NOFILE) == 0) {
            avio_closep(&g_outputFormatContext->pb);
        }

        avformat_free_context(g_outputFormatContext);
    }

    g_outputFormatContext = nullptr;
    g_videoStream = nullptr;
    g_audioStream = nullptr;
    g_output_url.clear();
    g_outputOpened.store(false);
    g_headerWritten.store(false);
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

void update_av_delta() {
    const long long videoFirst = g_videoFirstPtsUs.load();
    const long long audioFirst = g_audioFirstPtsUs.load();
    if (videoFirst < 0 || audioFirst < 0) return;

    const long long deltaUs = videoFirst - audioFirst;
    g_avDeltaUs.store(deltaUs);
    const long long absDeltaUs = deltaUs < 0 ? -deltaUs : deltaUs;
    const long long currentMax = g_avDeltaMaxAbsUs.load();
    if (absDeltaUs > currentMax) g_avDeltaMaxAbsUs.store(absDeltaUs);
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
        if (nalIndex >= data.size()) break;

        const int nalType = data[nalIndex] & 0x1F;
        if (nalType == 7) sawSps = true;
        else if (nalType == 8) sawPps = true;
        else if (nalType == 5) sawIdr = true;
        index = nalIndex + 1;
    }

    if (sawSps) g_videoSpsSeen.store(true);
    if (sawPps) g_videoPpsSeen.store(true);
    if (sawIdr) g_videoKeyframeSeen.store(true);

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
    __android_log_print(ANDROID_LOG_INFO, kAudioTag, "tsMs=%lld milestone=AAC config received", now_ms());
    return true;
}

bool declare_output_streams(bool videoEnabled, bool audioEnabled) {
    g_videoStream = nullptr;
    g_audioStream = nullptr;

    if (videoEnabled) {
        g_videoStream = avformat_new_stream(g_outputFormatContext, nullptr);
        if (g_videoStream == nullptr) {
            set_error("nativePrepare failed: avformat_new_stream(video) returned null");
            return false;
        }
        g_videoStream->time_base = AVRational{1, 1'000'000};
        g_videoStream->codecpar->codec_type = AVMEDIA_TYPE_VIDEO;
        g_videoStream->codecpar->codec_id = AV_CODEC_ID_H264;
        g_videoStream->codecpar->codec_tag = 0;
        g_videoStream->codecpar->width = 1280;
        g_videoStream->codecpar->height = 720;
        g_videoStream->codecpar->format = AV_PIX_FMT_YUV420P;
        log_mux_milestone("stream added", "type=video codec=h264 tb=1/1000000");
    }

    if (audioEnabled) {
        g_audioStream = avformat_new_stream(g_outputFormatContext, nullptr);
        if (g_audioStream == nullptr) {
            set_error("nativePrepare failed: avformat_new_stream(audio) returned null");
            return false;
        }
        g_audioStream->time_base = AVRational{1, 1'000'000};
        g_audioStream->codecpar->codec_type = AVMEDIA_TYPE_AUDIO;
        g_audioStream->codecpar->codec_id = AV_CODEC_ID_AAC;
        g_audioStream->codecpar->codec_tag = 0;
        g_audioStream->codecpar->sample_rate = 48'000;
        g_audioStream->codecpar->channels = 1;
        g_audioStream->codecpar->channel_layout = AV_CH_LAYOUT_MONO;
        g_audioStream->codecpar->format = AV_SAMPLE_FMT_FLTP;
        log_mux_milestone("stream added", "type=audio codec=aac tb=1/1000000");
    }

    return true;
}

bool write_access_unit_packet(
    const std::vector<unsigned char>& payload,
    AVStream* stream,
    long long normalizedPtsUs,
    int flags,
    bool isVideo
) {
    if (stream == nullptr) {
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

    AVPacket* packet = av_packet_alloc();
    if (packet == nullptr) {
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        g_muxWriteFailures.fetch_add(1);
        set_error("native write failed: av_packet_alloc returned null");
        return false;
    }

    const int newPacketResult = av_new_packet(packet, static_cast<int>(payload.size()));
    if (newPacketResult < 0) {
        av_packet_free(&packet);
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        g_muxWriteFailures.fetch_add(1);
        set_error("native write failed: av_new_packet returned " + ffmpeg_error_string(newPacketResult));
        return false;
    }

    std::memcpy(packet->data, payload.data(), payload.size());
    packet->stream_index = stream->index;
    packet->pos = -1;
    packet->pts = av_rescale_q(normalizedPtsUs, kInputPtsTimebase, stream->time_base);
    packet->dts = packet->pts;
    packet->duration = av_rescale_q(isVideo ? 33'333LL : 21'333LL, kInputPtsTimebase, stream->time_base);
    if ((flags & 1) != 0) {
        packet->flags |= AV_PKT_FLAG_KEY;
    }

    const int writeResult = av_interleaved_write_frame(g_outputFormatContext, packet);
    if (writeResult < 0) {
        av_packet_free(&packet);
        g_writePacketsFailed.fetch_add(1);
        g_consecutiveWriteFailures.fetch_add(1);
        g_muxWriteFailures.fetch_add(1);
        set_error("native write failed: av_interleaved_write_frame returned " + ffmpeg_error_string(writeResult));
        __android_log_print(ANDROID_LOG_ERROR, kErrorTag, "tsMs=%lld packet write failure code=%d error=%s", now_ms(), writeResult, ffmpeg_error_string(writeResult).c_str());
        log_mux_milestone("packet-write-failed", "err=" + ffmpeg_error_string(writeResult));
        return false;
    }

    av_packet_free(&packet);

    g_muxPacketsWritten.fetch_add(1);
    g_muxBytesWritten.fetch_add(static_cast<long long>(payload.size()));
    g_writePacketsSucceeded.store(g_muxPacketsWritten.load());
    g_writeBytesSucceeded.store(g_muxBytesWritten.load());
    g_consecutiveWriteFailures.store(0);
    g_lastSuccessfulWriteMs.store(now_ms());
    if (!g_firstPacketWritten.exchange(true)) {
        log_mux_milestone("first-packet-written", "streamIndex=" + std::to_string(stream->index));
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeProbeRuntime(JNIEnv*, jobject) {
    __android_log_print(ANDROID_LOG_DEBUG, kSrtTag, "TempleI-SRT runtime probe invoked");
    refresh_runtime_info();
    return avformat_version() > 0 ? JNI_TRUE : JNI_FALSE;
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
    g_prepared.store(false);
    g_started.store(false);
    g_outputOpened.store(false);
    g_headerWritten.store(false);
    g_trailerWritten.store(false);

    const char* host_chars = env->GetStringUTFChars(host, nullptr);
    const char* mode_chars = env->GetStringUTFChars(mode, nullptr);
    const std::string host_value = host_chars == nullptr ? "" : host_chars;
    const std::string mode_value = mode_chars == nullptr ? "" : mode_chars;
    if (host_chars != nullptr) env->ReleaseStringUTFChars(host, host_chars);
    if (mode_chars != nullptr) env->ReleaseStringUTFChars(mode, mode_chars);

    if (host_value.empty() || port <= 0 || port > 65535) {
        set_error("nativePrepare rejected invalid endpoint args");
        return JNI_FALSE;
    }
    if (!(mode_value == "caller" || mode_value == "listener")) {
        set_error("nativePrepare rejected invalid mode (expected caller/listener)");
        return JNI_FALSE;
    }

    refresh_runtime_info();
    if (avformat_version() == 0) {
        set_error("nativePrepare failed: ffmpeg runtime unavailable");
        return JNI_FALSE;
    }

    closeOutputArtifacts();
    const int netInit = avformat_network_init();
    if (netInit < 0) {
        set_error("nativePrepare failed: avformat_network_init returned " + ffmpeg_error_string(netInit));
        return JNI_FALSE;
    }

    g_output_url = std::string("srt://") + host_value + ":" + std::to_string(static_cast<int>(port)) +
        "?mode=" + mode_value + "&latency=" + std::to_string(static_cast<int>(latency_ms));

    AVFormatContext* formatContext = nullptr;
    const int allocResult = avformat_alloc_output_context2(&formatContext, nullptr, "mpegts", g_output_url.c_str());
    if (allocResult < 0 || formatContext == nullptr) {
        set_error("nativePrepare failed: avformat_alloc_output_context2 returned " + ffmpeg_error_string(allocResult));
        closeOutputArtifacts();
        return JNI_FALSE;
    }
    g_outputFormatContext = formatContext;
    log_mux_milestone("avformat_alloc_output_context2 success", "format=mpegts");

    if (!declare_output_streams(video_enabled == JNI_TRUE, audio_enabled == JNI_TRUE)) {
        closeOutputArtifacts();
        return JNI_FALSE;
    }

    av_dump_format(g_outputFormatContext, 0, g_output_url.c_str(), 1);

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
    g_trailerWritten.store(false);
    g_last_error.clear();

    __android_log_print(
        ANDROID_LOG_INFO,
        kNetTag,
        "tsMs=%lld network host=%s port=%d latency=%d mode=%s video=%d audio=%d",
        now_ms(),
        host_value.c_str(),
        static_cast<int>(port),
        static_cast<int>(latency_ms),
        mode_value.c_str(),
        video_enabled ? 1 : 0,
        audio_enabled ? 1 : 0
    );
    __android_log_print(ANDROID_LOG_INFO, kSrtTag, "tsMs=%lld milestone=URL used url=%s", now_ms(), g_output_url.c_str());
    log_mux_milestone("prepare-complete", std::string("url=") + g_output_url);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeStart(JNIEnv*, jobject) {
    const long long attempt = g_connectAttempts.fetch_add(1) + 1;
    __android_log_print(ANDROID_LOG_INFO, kSrtTag, "tsMs=%lld milestone=avio_open2 attempt=%lld url=%s", now_ms(), attempt, g_output_url.c_str());
    if (!g_prepared.load()) {
        g_connectFailures.fetch_add(1);
        set_error("nativeStart failed: backend not prepared");
        return JNI_FALSE;
    }
    if (g_outputFormatContext == nullptr) {
        g_connectFailures.fetch_add(1);
        set_error("nativeStart failed: output context not prepared");
        return JNI_FALSE;
    }

    log_output_protocol_diagnostics();

    if ((g_outputFormatContext->oformat->flags & AVFMT_NOFILE) == 0) {
        const int openResult = avio_open2(&g_outputFormatContext->pb, g_output_url.c_str(), AVIO_FLAG_WRITE, nullptr, nullptr);
        __android_log_print(ANDROID_LOG_INFO, kSrtTag, "tsMs=%lld milestone=avio_open2 result code=%d", now_ms(), openResult);
        __android_log_print(ANDROID_LOG_INFO, kSrtTag, "tsMs=%lld av_strerror=%s", now_ms(), ffmpeg_error_string(openResult).c_str());
        if (openResult < 0 || g_outputFormatContext->pb == nullptr) {
            g_connectFailures.fetch_add(1);
            set_error("nativeStart failed: avio_open2 returned " + ffmpeg_error_string(openResult));
            closeOutputArtifacts();
            return JNI_FALSE;
        }
    }

    g_outputOpened.store(true);
    g_trailerWritten.store(false);
    log_mux_milestone("output-opened", std::string("url=") + g_output_url);

    const int headerResult = avformat_write_header(g_outputFormatContext, nullptr);
    __android_log_print(ANDROID_LOG_INFO, kMuxTag, "tsMs=%lld milestone=avformat_write_header result code=%d", now_ms(), headerResult);
    if (headerResult < 0) {
        g_connectFailures.fetch_add(1);
        set_error("nativeStart failed: avformat_write_header returned " + ffmpeg_error_string(headerResult));
        closeOutputArtifacts();
        return JNI_FALSE;
    }

    g_headerWritten.store(true);
    log_mux_milestone("avformat_write_header success");

    g_connectSuccess.fetch_add(1);
    g_started.store(true);
    __android_log_print(ANDROID_LOG_INFO, kStreamTag, "tsMs=%lld milestone=stream started outputUrl=%s", now_ms(), g_output_url.c_str());
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
    if (size <= 0) return JNI_TRUE;

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

    if ((flags & 1) != 0) {
        __android_log_print(ANDROID_LOG_INFO, kVideoTag, "tsMs=%lld milestone=keyframe detected frame=%lld ptsUs=%lld", now_ms(), count, normalizedPtsUs);
    }

    if (!write_access_unit_packet(payload, g_videoStream, normalizedPtsUs, flags, true)) {
        return JNI_FALSE;
    }

    if (count <= 5 || (count % 120) == 0) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kVideoTag,
            "tsMs=%lld milestone=encoded frame size frame=%lld bytes=%d ptsUs=%lld flags=%d cfgReady=%d keySeen=%d",
            now_ms(),
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
    if (size <= 0) return JNI_TRUE;

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

    if (!write_access_unit_packet(payload, g_audioStream, normalizedPtsUs, flags, false)) {
        return JNI_FALSE;
    }

    if (count <= 5 || (count % 120) == 0) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kAudioTag,
            "tsMs=%lld milestone=encoded frame size frame=%lld bytes=%d ptsUs=%lld flags=%d cfgReady=%d",
            now_ms(),
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
    g_trailerWritten.store(false);
    reset_mux_runtime_counters();
    log_mux_milestone("shutdown-complete");
    __android_log_print(ANDROID_LOG_INFO, kStreamTag, "tsMs=%lld milestone=stream stopped", now_ms());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeRuntimeInfo(JNIEnv* env, jobject thiz) {
    __android_log_print(ANDROID_LOG_DEBUG, kSrtTag, "TempleI-SRT nativeRuntimeInfo JNI resolved");
    return env->NewStringUTF(g_runtime_info.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_FfmpegNativeBridge_nativeStatsSnapshot(JNIEnv* env, jobject) {
    const std::string runtimeMode = avformat_version() > 0 ? "active" : "stub";
    const std::string snapshot =
        std::string("runtimeMode=") + runtimeMode +
        " prepared=" + (g_prepared.load() ? "true" : "false") +
        " started=" + (g_started.load() ? "true" : "false") +
        " outputOpened=" + (g_outputOpened.load() ? "true" : "false") +
        " headerWritten=" + (g_headerWritten.load() ? "true" : "false") +
        " trailerWritten=" + (g_trailerWritten.load() ? "true" : "false") +
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
