package com.example.templei.feature.export

internal fun deriveMediaIngressStatus(
    config: ExportFeature.ObsStreamConfig,
    videoStats: VideoEncoderNode.RuntimeStats,
    audioStats: AudioEncoderNode.RuntimeStats,
): String {
    return when (config.streamMode) {
        CaptureCoordinator.StreamPathMode.ConnectionOnly -> "n/a"
        CaptureCoordinator.StreamPathMode.VideoOnly -> if (videoStats.encodedAccessUnitCount > 0) "flowing" else "stalled"
        CaptureCoordinator.StreamPathMode.AudioOnly -> if (audioStats.encodedAccessUnitCount > 0) "flowing" else "stalled"
        CaptureCoordinator.StreamPathMode.FullAv -> if (videoStats.encodedAccessUnitCount > 0 && audioStats.encodedAccessUnitCount > 0) "flowing" else "stalled"
    }
}

internal fun derivePacketWriteStatus(backendDiagnostics: String): String {
    val packets = parsePacketCount(backendDiagnostics)
    val consecutiveWriteFailures = Regex("""consecutiveWriteFailures=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val outputOpened = parseBooleanField(backendDiagnostics, "outputOpened")
    val headerWritten = parseBooleanField(backendDiagnostics, "headerWritten")
    return when {
        packets > 0L && consecutiveWriteFailures == 0L && outputOpened && headerWritten -> "active"
        packets == 0L && consecutiveWriteFailures > 0L -> "faulted"
        else -> "pending"
    }
}

internal fun deriveIngressMismatch(
    streamMode: CaptureCoordinator.StreamPathMode,
    videoEncodedAu: Long,
    audioEncodedAu: Long,
    videoIngressCalls: Long,
    audioIngressCalls: Long,
): String {
    return when (streamMode) {
        CaptureCoordinator.StreamPathMode.ConnectionOnly -> "none"
        CaptureCoordinator.StreamPathMode.VideoOnly -> if (videoEncodedAu > 0L && videoIngressCalls == 0L) "video-unmapped" else "none"
        CaptureCoordinator.StreamPathMode.AudioOnly -> if (audioEncodedAu > 0L && audioIngressCalls == 0L) "audio-unmapped" else "none"
        CaptureCoordinator.StreamPathMode.FullAv -> when {
            videoEncodedAu > 0L && videoIngressCalls == 0L -> "video-unmapped"
            audioEncodedAu > 0L && audioIngressCalls == 0L -> "audio-unmapped"
            else -> "none"
        }
    }
}

internal fun deriveQueuePressure(
    cameraQueueDepth: Int,
    encoderQueueDepth: Int,
    cameraDropCount: Long,
    encoderDropCount: Long,
): String {
    return when {
        cameraDropCount > 0L || encoderDropCount > 0L -> "drop"
        cameraQueueDepth >= 2 || encoderQueueDepth >= 16 -> "backlog"
        else -> "none"
    }
}

internal fun derivePacketWriteWarning(
    muxVideoIngest: Long,
    muxAudioIngest: Long,
    packetCount: Long,
    warnThreshold: Long,
): String {
    val ingressTotal = muxVideoIngest + muxAudioIngest
    if (packetCount > 0L) return "none"
    return if (ingressTotal >= warnThreshold) "ingress-active-without-packets" else "warming-up"
}

internal fun parsePacketCount(backendDiagnostics: String): Long {
    return Regex("""writePacketsSucceeded=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull()
        ?: Regex("""packetsWritten=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull()
        ?: Regex("""packets=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull()
        ?: 0L
}

internal fun parseLongField(backendDiagnostics: String, field: String): Long {
    return Regex("""$field=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
}

internal fun parseBooleanField(backendDiagnostics: String, field: String): Boolean {
    return Regex("""$field=(true|false)""").find(backendDiagnostics)?.groupValues?.get(1)?.toBoolean() ?: false
}

internal fun deriveConnectionState(backendDiagnostics: String): String {
    val match = Regex("""connState=([a-zA-Z]+)""").find(backendDiagnostics)
    return match?.groupValues?.getOrNull(1)?.lowercase() ?: "unknown"
}

internal fun parseRuntimeHealthSnapshot(backendDiagnostics: String): ExportFeature.RuntimeHealthSnapshot {
    val runtimeMode = Regex("""runtime=\{[^}]*runtimeMode=([a-zA-Z_]+)""").find(backendDiagnostics)?.groupValues?.getOrNull(1)?.lowercase() ?: "unknown"
    val connectionState = deriveConnectionState(backendDiagnostics)
    val packetsWritten = parsePacketCount(backendDiagnostics)
    val lastNativeError = Regex("""lastErr=\{([^}]*)\}""").find(backendDiagnostics)?.groupValues?.getOrNull(1)?.ifBlank { "none" } ?: "none"
    val runtimeActive = runtimeMode != "unknown" && runtimeMode != "stub"
    return ExportFeature.RuntimeHealthSnapshot(runtimeMode, connectionState, packetsWritten, lastNativeError, runtimeActive)
}

internal fun deriveInteropIssue(backendDiagnostics: String): String {
    val healthHint = Regex("""healthHint=\{([^}]*)\}""").find(backendDiagnostics)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    return if (healthHint.isNotEmpty()) healthHint else "none"
}
