package com.example.templei.feature.export

/**
 * JNI bridge surface for native MPEG-TS mux integration.
 *
 * Native implementation currently uses a minimal stub while runtime integration lands.
 */
internal object TsMuxNativeBridge {
    external fun nativePrepare(): Boolean
    external fun nativeStart(): Boolean
    external fun nativeStop()

    external fun nativeIngestVideo(
        data: ByteArray,
        presentationTimeUs: Long,
        flags: Int,
        trackIndex: Int,
    ): Boolean

    external fun nativeIngestAudio(
        data: ByteArray,
        presentationTimeUs: Long,
        flags: Int,
        trackIndex: Int,
    ): Boolean

    external fun nativeDrainPacket(): ByteArray
    external fun nativeLastError(): String
}
