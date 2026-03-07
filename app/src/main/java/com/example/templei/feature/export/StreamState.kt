package com.example.templei.feature.export

/**
 * Streaming pipeline state model for future mux/transport orchestration.
 */
enum class StreamState {
    Idle,
    CaptureReady,
    EncodersReady,
    MuxReady,
    TransportReady,
    Streaming,
    Reconnecting,
    Faulted,
}
