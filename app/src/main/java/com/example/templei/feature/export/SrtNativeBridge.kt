package com.example.templei.feature.export

/**
 * JNI bridge surface for native SRT sender integration.
 *
 * Native implementation currently uses a minimal stub while runtime integration lands.
 */
internal object SrtNativeBridge {
    external fun nativeConnect(host: String, port: Int, latencyMs: Int, mode: String): Boolean
    external fun nativeStartSending(): Boolean
    external fun nativeSendPacket(packet: ByteArray): Boolean
    external fun nativeStopSending()
    external fun nativeLastError(): String
    external fun nativeRuntimeInfo(): String
}
