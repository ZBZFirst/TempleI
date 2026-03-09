package com.example.templei.feature.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFeatureInteropStageGateTest {
    @Test
    fun `stage gate reports capture first when video mode has no camera frames`() {
        val gate = deriveInteropStageGate(
            baseInputs(
                streamMode = CaptureCoordinator.StreamPathMode.VideoOnly,
            ),
        )

        assertEquals("capture", gate.firstFailedStage)
        assertEquals("CaptureIdle", gate.reasonCode)
    }

    @Test
    fun `stage gate reports native ingress for audio only when encoder is flowing but ingress is not`() {
        val gate = deriveInteropStageGate(
            baseInputs(
                streamMode = CaptureCoordinator.StreamPathMode.AudioOnly,
                cameraFramesEnqueued = 0,
                videoEncodedAu = 0,
                audioEncodedAu = 24,
                videoIngressCalls = 0,
                audioIngressCalls = 0,
            ),
        )

        assertEquals("nativeIngress", gate.firstFailedStage)
        assertEquals("IngressIdle", gate.reasonCode)
    }

    @Test
    fun `stage gate prioritizes stub runtime reason over downstream stage labels`() {
        val gate = deriveInteropStageGate(
            baseInputs(
                streamMode = CaptureCoordinator.StreamPathMode.VideoOnly,
                cameraFramesEnqueued = 10,
                videoEncodedAu = 10,
                videoIngressCalls = 10,
                packetCount = 0,
                packetWriteStatus = "pending",
                interopIssue = "JNI runtime is stubbed so OBS will not receive MPEG-TS/SRT payload yet",
            ),
        )

        assertEquals("muxWrite", gate.firstFailedStage)
        assertEquals("StubRuntime", gate.reasonCode)
    }

    @Test
    fun `stage gate surfaces queue pressure reason when drops are present`() {
        val gate = deriveInteropStageGate(
            baseInputs(
                streamMode = CaptureCoordinator.StreamPathMode.VideoOnly,
                cameraFramesEnqueued = 10,
                videoEncodedAu = 10,
                videoIngressCalls = 10,
                queuePressure = "drop",
            ),
        )

        assertEquals("muxWrite", gate.firstFailedStage)
        assertEquals("QueueDrop", gate.reasonCode)
    }

    @Test
    fun `stage gate is healthy when all stages are flowing`() {
        val gate = deriveInteropStageGate(
            baseInputs(
                streamMode = CaptureCoordinator.StreamPathMode.FullAv,
                cameraFramesEnqueued = 120,
                videoEncodedAu = 120,
                audioEncodedAu = 120,
                videoIngressCalls = 120,
                audioIngressCalls = 120,
                muxVideoIngest = 120,
                muxAudioIngest = 120,
                packetCount = 400,
                packetWriteStatus = "active",
            ),
        )

        assertEquals("none", gate.firstFailedStage)
        assertEquals("None", gate.reasonCode)
        assertTrue(gate.summary.contains("transport=ok"))
    }

    private fun baseInputs(
        streamMode: CaptureCoordinator.StreamPathMode,
        cameraFramesEnqueued: Long = 0,
        videoEncodedAu: Long = 0,
        audioEncodedAu: Long = 0,
        videoIngressCalls: Long = 0,
        audioIngressCalls: Long = 0,
        muxVideoIngest: Long = 0,
        muxAudioIngest: Long = 0,
        packetCount: Long = 0,
        connectionState: String = "connected",
        packetWriteStatus: String = "pending",
        interopIssue: String = "none",
        queuePressure: String = "none",
    ): InteropStageInputs {
        return InteropStageInputs(
            streamMode = streamMode,
            cameraFramesEnqueued = cameraFramesEnqueued,
            videoEncodedAu = videoEncodedAu,
            audioEncodedAu = audioEncodedAu,
            videoIngressCalls = videoIngressCalls,
            audioIngressCalls = audioIngressCalls,
            muxVideoIngest = muxVideoIngest,
            muxAudioIngest = muxAudioIngest,
            packetCount = packetCount,
            connectionState = connectionState,
            packetWriteStatus = packetWriteStatus,
            interopIssue = interopIssue,
            queuePressure = queuePressure,
        )
    }
}
