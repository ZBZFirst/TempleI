package com.example.templei.feature.export

/**
 * JNI bridge for PR E FFmpeg runtime cutover readiness.
 *
 * Current scope is incremental AV ingest wiring and diagnostics while native
 * mux/send implementation is still iterative.
 */
object FfmpegNativeBridge {
    external fun nativeProbeRuntime(): Boolean

    external fun nativePrepare(
        host: String,
        port: Int,
        latencyMs: Int,
        mode: String,
        videoEnabled: Boolean,
        audioEnabled: Boolean,
    ): Boolean

    external fun nativeStart(): Boolean

    external fun nativePushVideoAccessUnit(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean

    external fun nativePushAudioAccessUnit(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean

    external fun nativeStop()

    external fun nativeLastError(): String

    external fun nativeRuntimeInfo(): String

    external fun nativeStatsSnapshot(): String
}
