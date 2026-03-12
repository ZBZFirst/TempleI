package com.example.templei.feature.export

internal data class InteropStageInputs(
    val streamMode: CaptureCoordinator.StreamPathMode,
    val cameraFramesEnqueued: Long,
    val videoEncodedAu: Long,
    val audioEncodedAu: Long,
    val videoIngressCalls: Long,
    val audioIngressCalls: Long,
    val muxVideoIngest: Long,
    val muxAudioIngest: Long,
    val packetCount: Long,
    val connectionState: String,
    val packetWriteStatus: String,
    val interopIssue: String,
    val queuePressure: String,
)

internal data class InteropStageGate(
    val summary: String,
    val firstFailedStage: String,
    val reasonCode: String,
)

internal fun deriveInteropStageGate(inputs: InteropStageInputs): InteropStageGate {
    val captureOk =
        inputs.streamMode == CaptureCoordinator.StreamPathMode.AudioOnly ||
            inputs.streamMode == CaptureCoordinator.StreamPathMode.ConnectionOnly ||
            inputs.cameraFramesEnqueued > 0
    val videoEncodeOk =
        inputs.streamMode == CaptureCoordinator.StreamPathMode.AudioOnly ||
            inputs.streamMode == CaptureCoordinator.StreamPathMode.ConnectionOnly ||
            inputs.videoEncodedAu > 0
    val audioEncodeOk =
        inputs.streamMode == CaptureCoordinator.StreamPathMode.VideoOnly ||
            inputs.streamMode == CaptureCoordinator.StreamPathMode.ConnectionOnly ||
            inputs.audioEncodedAu > 0
    val nativeIngressOk = when (inputs.streamMode) {
        CaptureCoordinator.StreamPathMode.ConnectionOnly -> true
        CaptureCoordinator.StreamPathMode.VideoOnly -> inputs.videoIngressCalls > 0
        CaptureCoordinator.StreamPathMode.AudioOnly -> inputs.audioIngressCalls > 0
        CaptureCoordinator.StreamPathMode.FullAv -> inputs.videoIngressCalls > 0 && inputs.audioIngressCalls > 0
    }
    val muxWriteOk = inputs.packetCount > 0
    val transportOk = inputs.connectionState == "connected" && inputs.packetWriteStatus == "active"

    val firstFailedStage = when {
        !captureOk -> "capture"
        !videoEncodeOk -> "videoEncode"
        !audioEncodeOk -> "audioEncode"
        !nativeIngressOk -> "nativeIngress"
        !muxWriteOk -> "muxWrite"
        !transportOk -> "transport"
        else -> "none"
    }

    val reasonCode = when {
        inputs.interopIssue.contains("stubbed", ignoreCase = true) -> "StubRuntime"
        inputs.queuePressure == "drop" -> "QueueDrop"
        inputs.queuePressure == "backlog" -> "QueueBacklog"
        inputs.packetWriteStatus == "faulted" -> "NativeWriteFault"
        firstFailedStage == "capture" -> "CaptureIdle"
        firstFailedStage == "videoEncode" -> "VideoEncoderIdle"
        firstFailedStage == "audioEncode" -> "AudioEncoderIdle"
        firstFailedStage == "nativeIngress" -> "IngressIdle"
        firstFailedStage == "muxWrite" -> "MuxWritePending"
        firstFailedStage == "transport" -> "TransportNotConnected"
        else -> "None"
    }

    val summary =
        "capture=${if (captureOk) "ok" else "pending"}," +
            "videoEncode=${if (videoEncodeOk) "ok" else "pending"}," +
            "audioEncode=${if (audioEncodeOk) "ok" else "pending"}," +
            "nativeIngress=${if (nativeIngressOk) "ok" else "pending"}," +
            "muxWrite=${if (muxWriteOk) "ok" else "pending"}," +
            "transport=${if (transportOk) "ok" else "pending"}"

    return InteropStageGate(
        summary = summary,
        firstFailedStage = firstFailedStage,
        reasonCode = reasonCode,
    )
}
