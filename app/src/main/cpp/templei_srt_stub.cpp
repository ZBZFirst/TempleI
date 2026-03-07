#include <jni.h>

#include <arpa/inet.h>
#include <dlfcn.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

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
};

struct SenderState {
    SrtApi api;
    SrtSocket socket = -1;
    bool connected = false;
    bool sending = false;
    std::string lastError;
    std::string runtimeInfo;
};

std::mutex gMutex;
SenderState gState;

constexpr const char* kRuntimeExpectedPath = "app/src/main/jniLibs/<abi>/libsrt.so";

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
    setError("libsrt shared library not found");
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
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeConnect(
        JNIEnv* env,
        jobject,
        jstring host,
        jint port,
        jint,
        jstring) {
    std::lock_guard<std::mutex> lock(gMutex);

    closeSocketIfOpen();
    gState.lastError.clear();

    if (!loadApi()) {
        return JNI_FALSE;
    }

    if (gState.api.startup() != 0) {
        setError("srt_startup failed");
        return JNI_FALSE;
    }

    std::string hostValue = jStringToStdString(env, host);
    if (hostValue.empty()) {
        setError("host missing");
        return JNI_FALSE;
    }

    gState.socket = gState.api.create_socket();
    if (gState.socket < 0) {
        setError("srt_create_socket failed");
        return JNI_FALSE;
    }

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, hostValue.c_str(), &address.sin_addr) != 1) {
        setError("invalid host ip");
        closeSocketIfOpen();
        return JNI_FALSE;
    }

    if (gState.api.connect(gState.socket, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        setError("srt_connect failed");
        closeSocketIfOpen();
        return JNI_FALSE;
    }

    gState.connected = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_templei_feature_export_SrtNativeBridge_nativeStartSending(
        JNIEnv*,
        jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gState.connected || gState.socket < 0) {
        setError("transport not connected");
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
    if (!gState.sending || gState.socket < 0) {
        setError("transport not sending");
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

    const int sent = gState.api.send(gState.socket, buffer.data(), length);
    if (sent < 0) {
        setError("srt_send failed");
        return JNI_FALSE;
    }

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
