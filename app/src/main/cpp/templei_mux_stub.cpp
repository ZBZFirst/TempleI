#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace {

struct MuxRuntimeState {
    bool prepared = false;
    bool started = false;
    std::string lastError;
    std::deque<std::vector<uint8_t>> packetQueue;
    std::map<uint16_t, uint8_t> continuityCounter;
};

std::mutex gMutex;
MuxRuntimeState gState;

constexpr uint16_t kPidPat = 0x0000;
constexpr uint16_t kPidPmt = 0x0100;
constexpr uint16_t kPidVideo = 0x0101;
constexpr uint16_t kPidAudio = 0x0102;

constexpr uint8_t kStreamTypeH264 = 0x1B;
constexpr uint8_t kStreamTypeAac = 0x0F;
constexpr const char* kLogTag = "TempleI-MuxStub";

int gPatPmtLogCount = 0;
bool gFirstVideoPesLogged = false;
bool gFirstAudioPesLogged = false;
std::map<uint16_t, uint64_t> gPidPacketCount;

void logPidPacket(uint16_t pid) {
    const auto count = ++gPidPacketCount[pid];
    if (count <= 5 || (count % 500) == 0) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "pid=0x%04X tsPacketCount=%llu", pid, static_cast<unsigned long long>(count));
    }
}

std::vector<uint8_t> buildTsPacket(
        uint16_t pid,
        bool payloadUnitStart,
        const uint8_t* payload,
        size_t payloadLen,
        uint8_t continuityCounter) {
    std::vector<uint8_t> packet(188, 0xFF);
    packet[0] = 0x47;
    packet[1] = static_cast<uint8_t>(((payloadUnitStart ? 0x40 : 0x00) | ((pid >> 8) & 0x1F)));
    packet[2] = static_cast<uint8_t>(pid & 0xFF);
    const size_t copyLen = std::min(payloadLen, static_cast<size_t>(184));
    const size_t stuffingLen = 184 - copyLen;

    if (stuffingLen == 0) {
        packet[3] = static_cast<uint8_t>(0x10 | (continuityCounter & 0x0F));
        if (copyLen > 0) {
            std::memcpy(packet.data() + 4, payload, copyLen);
        }
        return packet;
    }

    packet[3] = static_cast<uint8_t>(0x30 | (continuityCounter & 0x0F));
    packet[4] = static_cast<uint8_t>(stuffingLen - 1);
    if (stuffingLen > 1) {
        packet[5] = 0x00;
        if (stuffingLen > 2) {
            std::memset(packet.data() + 6, 0xFF, stuffingLen - 2);
        }
    }

    if (copyLen > 0) {
        const size_t payloadOffset = 4 + stuffingLen;
        std::memcpy(packet.data() + payloadOffset, payload, copyLen);
    }
    return packet;
}

uint32_t crc32Mpeg(const std::vector<uint8_t>& data, size_t begin, size_t end) {
    uint32_t crc = 0xFFFFFFFF;
    for (size_t i = begin; i < end; ++i) {
        crc ^= static_cast<uint32_t>(data[i]) << 24;
        for (int b = 0; b < 8; ++b) {
            if (crc & 0x80000000) {
                crc = (crc << 1) ^ 0x04C11DB7;
            } else {
                crc <<= 1;
            }
        }
    }
    return crc;
}

void pushSectionAsTsPackets(uint16_t pid, const std::vector<uint8_t>& sectionBytes) {
    std::vector<uint8_t> payload;
    payload.reserve(sectionBytes.size() + 1);
    payload.push_back(0x00); // pointer_field
    payload.insert(payload.end(), sectionBytes.begin(), sectionBytes.end());

    size_t offset = 0;
    bool first = true;
    while (offset < payload.size()) {
        auto& cc = gState.continuityCounter[pid];
        const size_t chunkLen = std::min(static_cast<size_t>(184), payload.size() - offset);
        auto packet = buildTsPacket(pid, first, payload.data() + offset, chunkLen, cc++);
        gState.packetQueue.push_back(std::move(packet));
        logPidPacket(pid);
        offset += chunkLen;
        first = false;
    }
}

void emitPatAndPmt() {
    // PAT section.
    std::vector<uint8_t> pat;
    pat.push_back(0x00);              // table_id
    pat.push_back(0xB0);              // section_syntax_indicator + section_length hi bits
    pat.push_back(0x0D);              // section_length low bits
    pat.push_back(0x00); pat.push_back(0x01); // transport_stream_id
    pat.push_back(0xC1);              // version=0,current_next=1
    pat.push_back(0x00);              // section_number
    pat.push_back(0x00);              // last_section_number
    pat.push_back(0x00); pat.push_back(0x01); // program_number
    pat.push_back(static_cast<uint8_t>(0xE0 | ((kPidPmt >> 8) & 0x1F)));
    pat.push_back(static_cast<uint8_t>(kPidPmt & 0xFF));
    const uint32_t patCrc = crc32Mpeg(pat, 0, pat.size());
    pat.push_back(static_cast<uint8_t>((patCrc >> 24) & 0xFF));
    pat.push_back(static_cast<uint8_t>((patCrc >> 16) & 0xFF));
    pat.push_back(static_cast<uint8_t>((patCrc >> 8) & 0xFF));
    pat.push_back(static_cast<uint8_t>(patCrc & 0xFF));
    pushSectionAsTsPackets(kPidPat, pat);

    // PMT section.
    std::vector<uint8_t> pmt;
    pmt.push_back(0x02); // table_id
    pmt.push_back(0xB0);
    pmt.push_back(0x17); // section_length (23)
    pmt.push_back(0x00); pmt.push_back(0x01); // program_number
    pmt.push_back(0xC1);
    pmt.push_back(0x00);
    pmt.push_back(0x00);
    pmt.push_back(static_cast<uint8_t>(0xE0 | ((kPidVideo >> 8) & 0x1F))); // PCR PID
    pmt.push_back(static_cast<uint8_t>(kPidVideo & 0xFF));
    pmt.push_back(0xF0); pmt.push_back(0x00); // program_info_length

    // video stream
    pmt.push_back(kStreamTypeH264);
    pmt.push_back(static_cast<uint8_t>(0xE0 | ((kPidVideo >> 8) & 0x1F)));
    pmt.push_back(static_cast<uint8_t>(kPidVideo & 0xFF));
    pmt.push_back(0xF0); pmt.push_back(0x00);

    // audio stream
    pmt.push_back(kStreamTypeAac);
    pmt.push_back(static_cast<uint8_t>(0xE0 | ((kPidAudio >> 8) & 0x1F)));
    pmt.push_back(static_cast<uint8_t>(kPidAudio & 0xFF));
    pmt.push_back(0xF0); pmt.push_back(0x00);

    const uint32_t pmtCrc = crc32Mpeg(pmt, 0, pmt.size());
    pmt.push_back(static_cast<uint8_t>((pmtCrc >> 24) & 0xFF));
    pmt.push_back(static_cast<uint8_t>((pmtCrc >> 16) & 0xFF));
    pmt.push_back(static_cast<uint8_t>((pmtCrc >> 8) & 0xFF));
    pmt.push_back(static_cast<uint8_t>(pmtCrc & 0xFF));
    pushSectionAsTsPackets(kPidPmt, pmt);
    if (gPatPmtLogCount == 0) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "first PAT/PMT emitted");
        gPatPmtLogCount = 1;
    }
}

std::array<uint8_t, 5> encodePts90k(uint64_t pts90k) {
    std::array<uint8_t, 5> pts{};
    pts[0] = static_cast<uint8_t>(0x20 | (((pts90k >> 30) & 0x07) << 1) | 0x01);
    pts[1] = static_cast<uint8_t>((pts90k >> 22) & 0xFF);
    pts[2] = static_cast<uint8_t>((((pts90k >> 15) & 0x7F) << 1) | 0x01);
    pts[3] = static_cast<uint8_t>((pts90k >> 7) & 0xFF);
    pts[4] = static_cast<uint8_t>(((pts90k & 0x7F) << 1) | 0x01);
    return pts;
}

void pushPesAsTsPackets(
        uint16_t pid,
        uint8_t streamId,
        const uint8_t* payload,
        size_t payloadLen,
        int64_t ptsUs) {
    const uint64_t pts90k = static_cast<uint64_t>(ptsUs < 0 ? 0 : ptsUs) * 90ULL / 1000ULL;
    const auto pts = encodePts90k(pts90k);

    std::vector<uint8_t> pes;
    pes.reserve(payloadLen + 64);
    pes.push_back(0x00);
    pes.push_back(0x00);
    pes.push_back(0x01);
    pes.push_back(streamId);
    pes.push_back(0x00);
    pes.push_back(0x00); // unbounded PES length
    pes.push_back(0x80);
    pes.push_back(0x80); // PTS only
    pes.push_back(0x05); // PTS size
    pes.insert(pes.end(), pts.begin(), pts.end());
    pes.insert(pes.end(), payload, payload + payloadLen);

    if (pid == kPidVideo && !gFirstVideoPesLogged) {
        gFirstVideoPesLogged = true;
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "first video PES streamId=0x%02X payload=%zu ptsUs=%lld", streamId, payloadLen, static_cast<long long>(ptsUs));
    }
    if (pid == kPidAudio && !gFirstAudioPesLogged) {
        gFirstAudioPesLogged = true;
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "first audio PES streamId=0x%02X payload=%zu ptsUs=%lld", streamId, payloadLen, static_cast<long long>(ptsUs));
    }

    size_t offset = 0;
    bool first = true;
    while (offset < pes.size()) {
        auto& cc = gState.continuityCounter[pid];
        const size_t chunkLen = std::min(static_cast<size_t>(184), pes.size() - offset);
        auto packet = buildTsPacket(pid, first, pes.data() + offset, chunkLen, cc++);
        gState.packetQueue.push_back(std::move(packet));
        logPidPacket(pid);
        offset += chunkLen;
        first = false;
    }
}

jboolean ingestCommon(JNIEnv* env, jbyteArray payloadArray, jlong ptsUs, jint trackIndex) {
    if (!gState.started) {
        gState.lastError = "mux not started";
        return JNI_FALSE;
    }

    if (payloadArray == nullptr) {
        gState.lastError = "payload missing";
        return JNI_FALSE;
    }

    const auto payloadLen = static_cast<size_t>(env->GetArrayLength(payloadArray));
    if (payloadLen == 0) {
        return JNI_TRUE;
    }

    std::vector<uint8_t> payload(payloadLen);
    env->GetByteArrayRegion(payloadArray, 0, static_cast<jsize>(payloadLen),
                            reinterpret_cast<jbyte*>(payload.data()));

    if (trackIndex == 1) {
        pushPesAsTsPackets(kPidAudio, 0xC0, payload.data(), payload.size(), ptsUs);
    } else {
        pushPesAsTsPackets(kPidVideo, 0xE0, payload.data(), payload.size(), ptsUs);
    }

    return JNI_TRUE;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativePrepare(
        JNIEnv*,
        jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    gState = MuxRuntimeState{};
    gState.prepared = true;
    gState.lastError.clear();
    gPatPmtLogCount = 0;
    gFirstVideoPesLogged = false;
    gFirstAudioPesLogged = false;
    gPidPacketCount.clear();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativeStart(
        JNIEnv*,
        jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gState.prepared) {
        gState.lastError = "mux not prepared";
        return JNI_FALSE;
    }
    gState.started = true;
    emitPatAndPmt();
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativeStop(
        JNIEnv*,
        jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    gState.started = false;
    gState.packetQueue.clear();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativeIngestVideo(
        JNIEnv* env,
        jobject,
        jbyteArray payload,
        jlong ptsUs,
        jint,
        jint trackIndex) {
    std::lock_guard<std::mutex> lock(gMutex);
    return ingestCommon(env, payload, ptsUs, trackIndex == 1 ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativeIngestAudio(
        JNIEnv* env,
        jobject,
        jbyteArray payload,
        jlong ptsUs,
        jint,
        jint trackIndex) {
    std::lock_guard<std::mutex> lock(gMutex);
    return ingestCommon(env, payload, ptsUs, trackIndex == 0 ? 0 : 1);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativeDrainPacket(
        JNIEnv* env,
        jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gState.packetQueue.empty()) {
        return env->NewByteArray(0);
    }

    auto packet = std::move(gState.packetQueue.front());
    gState.packetQueue.pop_front();

    jbyteArray output = env->NewByteArray(static_cast<jsize>(packet.size()));
    if (output == nullptr) {
        gState.lastError = "jni allocation failed";
        return env->NewByteArray(0);
    }
    env->SetByteArrayRegion(output, 0, static_cast<jsize>(packet.size()),
                            reinterpret_cast<const jbyte*>(packet.data()));
    return output;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_TsMuxNativeBridge_nativeLastError(
        JNIEnv* env,
        jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    return env->NewStringUTF(gState.lastError.c_str());
}
