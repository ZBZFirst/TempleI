#include <jni.h>

#include <arpa/inet.h>
#include <dlfcn.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace {

using SrtSocket = int;

struct SrtApi {
    void* handle = nullptr;
    int (*startup)() = nullptr;
    int (*cleanup)() = nullptr;
    SrtSocket (*create_socket)() = nullptr;
    int (*connect)(SrtSocket, const sockaddr*, int) = nullptr;
    int (*send)(SrtSocket, const char*, int) = nullptr;
    int (*close)(SrtSocket) = nullptr;
    int (*setsockopt)(SrtSocket, int, const void*, int) = nullptr;
    int (*getsockstate)(SrtSocket) = nullptr;
    const char* (*lasterror_str)() = nullptr;
};

struct SenderState {
    SrtApi api;
    SrtSocket socket = -1;
    bool connected = false;
    bool sending = false;
    std::string lastError;
    std::string runtimeInfo;
    int lastSendCode = 0;
    int lastConnectCode = 0;
    long long packetsSent = 0;
    long long bytesSent = 0;
    std::string socketState = "SRTS_NONEXIST";
};

std::mutex gMutex;
SenderState gState;

constexpr const char* kRuntimeExpectedPath = "app/src/main/jniLibs/<abi>/libsrt.so";
constexpr int kSrtSuccess = 0;
constexpr int kSrtError = -1;
constexpr int kSockoptSndLatency = 13;
constexpr int kSockoptRcvLatency = 14;
constexpr int kSockoptConnTimeout = 36;

const char* currentAbi() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__arm__)
    return "armeabi-v7a";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

std::string stateName(int state) {
    switch (state) {
        case 1: return "SRTS_INIT";
        case 2: return "SRTS_OPENED";
        case 3: return "SRTS_LISTENING";
        case 4: return "SRTS_CONNECTING";
        case 5: return "SRTS_CONNECTED";
        case 6: return "SRTS_BROKEN";
        case 7: return "SRTS_CLOSING";
        case 8: return "SRTS_CLOSED";
        case 9: return "SRTS_NONEXIST";
        default: return "SRTS_UNKNOWN(" + std::to_string(state) + ")";
    }
}

void refreshSocketState() {
    if (gState.socket < 0 || gState.api.getsockstate == nullptr) {
        gState.socketState = "SRTS_NONEXIST";
        return;
    }
    gState.socketState = stateName(gState.api.getsockstate(gState.socket));
}

std::string lastSrtErrorMessage() {
    if (gState.api.lasterror_str == nullptr) {
        return "srt error unavailable";
    }
    const char* detail = gState.api.lasterror_str();
    return detail != nullptr ? detail : "srt error unavailable";
}

void setError(const std::string& message) {
    gState.lastError = message;
}

void setRuntimeInfo(const std::string& message) {
    gState.runtimeInfo = message;
}

bool loadApi() {
    if (gState.api.handle != nullptr) {
        setRuntimeInfo("libsrt loaded; abi=" + std::string(currentAbi()));
        return true;
    }

    const char* candidates[] = {"libsrt.so", "libsrt.so.1"};
    std::vector<std::string> loadErrors;

    for (const char* candidate : candidates) {
        void* handle = dlopen(candidate, RTLD_NOW);
        if (handle == nullptr) {
            const char* detail = dlerror();
            loadErrors.emplace_back(std::string(candidate) + " -> " + (detail ? detail : "unknown dlopen error"));
            continue;
        }

        gState.api.handle = handle;
        gState.api.startup = reinterpret_cast<int (*)()>(dlsym(handle, "srt_startup"));
        gState.api.cleanup = reinterpret_cast<int (*)()>(dlsym(handle, "srt_cleanup"));
        gState.api.create_socket = reinterpret_cast<SrtSocket (*)()>(dlsym(handle, "srt_create_socket"));
        gState.api.connect = reinterpret_cast<int (*)(SrtSocket, const sockaddr*, int)>(dlsym(handle, "srt_connect"));
        gState.api.send = reinterpret_cast<int (*)(SrtSocket, const char*, int)>(dlsym(handle, "srt_send"));
        gState.api.close = reinterpret_cast<int (*)(SrtSocket)>(dlsym(handle, "srt_close"));
        gState.api.setsockopt = reinterpret_cast<int (*)(SrtSocket, int, const void*, int)>(dlsym(handle, "srt_setsockopt"));
        gState.api.getsockstate = reinterpret_cast<int (*)(SrtSocket)>(dlsym(handle, "srt_getsockstate"));
        gState.api.lasterror_str = reinterpret_cast<const char* (*)()>(dlsym(handle, "srt_getlasterror_str"));

        if (gState.api.startup != nullptr &&
            gState.api.cleanup != nullptr &&
            gState.api.create_socket != nullptr &&
            gState.api.connect != nullptr &&
            gState.api.send != nullptr &&
            gState.api.close != nullptr) {
            setRuntimeInfo("libsrt loaded from " + std::string(candidate) + "; abi=" + std::string(currentAbi()));
            return true;
        }

        loadErrors.emplace_back(std::string(candidate) + " -> missing srt_* symbols");
        dlclose(handle);
        gState.api = SrtApi{};
    }

    std::ostringstream summary;
    summary << "libsrt missing; abi=" << currentAbi()
            << "; expected=" << kRuntimeExpectedPath
            << "; attempts=";
    for (size_t i = 0; i < loadErrors.size(); ++i) {
        if (i > 0) {
            summary << " | ";
        }
        summary << loadErrors[i];
    }

    setRuntimeInfo(summary.str());
    setError("libsrt shared library not found (build FFmpeg with --enable-libsrt and package libsrt.so)");
    return false;
}

std::string jStringToStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return std::string();
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return std::string();
    }
    std::string output(chars);
    env->ReleaseStringUTFChars(value, chars);
    return output;
}

void closeSocketIfOpen() {
    if (gState.socket >= 0 && gState.api.close != nullptr) {
        gState.api.close(gState.socket);
    }
    gState.socket = -1;
    gState.connected = false;
    gState.sending = false;
    gState.socketState = "SRTS_NONEXIST";
}

void configureSocketOption(SrtSocket sock, int opt, int value) {
    if (gState.api.setsockopt == nullptr) {
        return;
    }
    gState.api.setsockopt(sock, opt, &value, sizeof(value));
}

std::string buildStatsSnapshot() {
    std::ostringstream stats;
    stats << "state=" << gState.socketState
          << ",connected=" << (gState.connected ? "1" : "0")
          << ",sending=" << (gState.sending ? "1" : "0")
          << ",packets=" << gState.packetsSent
          << ",bytes=" << gState.bytesSent
          << ",last_connect_code=" << gState.lastConnectCode
          << ",last_send_code=" << gState.lastSendCode;
    return stats.str();
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeConnect(
        JNIEnv* env,
        jobject,
        jstring host,
        jint port,
        jint latencyMs,
        jstring,
        jint timeoutUs) {
    std::lock_guard<std::mutex> lock(gMutex);

    closeSocketIfOpen();
    gState.lastError.clear();
    gState.lastSendCode = 0;
    gState.lastConnectCode = 0;
    gState.packetsSent = 0;
    gState.bytesSent = 0;

    if (!loadApi()) {
        return JNI_FALSE;
    }

    if (gState.api.startup() != kSrtSuccess) {
        setError("srt_startup failed: " + lastSrtErrorMessage());
        return JNI_FALSE;
    }

    std::string hostValue = jStringToStdString(env, host);
    if (hostValue.empty()) {
        setError("host missing");
        return JNI_FALSE;
    }

    gState.socket = gState.api.create_socket();
    if (gState.socket < 0) {
        setError("srt_create_socket failed: " + lastSrtErrorMessage());
        return JNI_FALSE;
    }

    configureSocketOption(gState.socket, kSockoptSndLatency, latencyMs);
    configureSocketOption(gState.socket, kSockoptRcvLatency, latencyMs);
    configureSocketOption(gState.socket, kSockoptConnTimeout, timeoutUs);

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, hostValue.c_str(), &address.sin_addr) != 1) {
        setError("invalid host ip");
        closeSocketIfOpen();
        return JNI_FALSE;
    }

    gState.lastConnectCode = gState.api.connect(gState.socket, reinterpret_cast<sockaddr*>(&address), sizeof(address));
    refreshSocketState();
    if (gState.lastConnectCode != kSrtSuccess) {
        setError("srt_connect failed (code=" + std::to_string(gState.lastConnectCode) +
                 ", state=" + gState.socketState + ", detail=" + lastSrtErrorMessage() + ")");
        closeSocketIfOpen();
        return JNI_FALSE;
    }

    gState.connected = true;
    refreshSocketState();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeStartSending(
        JNIEnv*,
        jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    refreshSocketState();
    if (!gState.connected || gState.socket < 0) {
        setError("transport not connected (state=" + gState.socketState + ")");
        return JNI_FALSE;
    }
    gState.sending = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeSendPacket(
        JNIEnv* env,
        jobject,
        jbyteArray packet) {
    std::lock_guard<std::mutex> lock(gMutex);
    refreshSocketState();
    if (!gState.sending || gState.socket < 0) {
        setError("transport not sending (state=" + gState.socketState + ")");
        return JNI_FALSE;
    }
    if (packet == nullptr) {
        setError("packet missing");
        return JNI_FALSE;
    }

    const auto length = static_cast<int>(env->GetArrayLength(packet));
    if (length <= 0) {
        return JNI_TRUE;
    }

    std::string buffer;
    buffer.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(packet, 0, length, reinterpret_cast<jbyte*>(buffer.data()));

    gState.lastSendCode = gState.api.send(gState.socket, buffer.data(), length);
    refreshSocketState();
    if (gState.lastSendCode == kSrtError) {
        setError("srt_send failed (state=" + gState.socketState + ", detail=" + lastSrtErrorMessage() + ")");
        return JNI_FALSE;
    }

    gState.packetsSent += 1;
    gState.bytesSent += gState.lastSendCode;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeStopSending(
        JNIEnv*,
        jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    closeSocketIfOpen();
    if (gState.api.cleanup != nullptr) {
        gState.api.cleanup();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeLastError(
        JNIEnv* env,
        jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    return env->NewStringUTF(gState.lastError.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeRuntimeInfo(
        JNIEnv* env,
        jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    return env->NewStringUTF(gState.runtimeInfo.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeStatsSnapshot(
        JNIEnv* env,
        jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    refreshSocketState();
    const std::string snapshot = buildStatsSnapshot();
    return env->NewStringUTF(snapshot.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeSocketState(
        JNIEnv* env,
        jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    refreshSocketState();
    return env->NewStringUTF(gState.socketState.c_str());
}
